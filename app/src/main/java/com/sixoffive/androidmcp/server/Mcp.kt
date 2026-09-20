package com.sixoffive.androidmcp.server

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.LocationManager
import android.provider.CalendarContract.Instances
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import com.sixoffive.androidmcp.core.AuditLog
import com.sixoffive.androidmcp.core.Capabilities
import com.sixoffive.androidmcp.core.CapabilityMeta
import com.sixoffive.androidmcp.core.ConfigStore
import com.sixoffive.androidmcp.core.GateEngine
import com.sixoffive.androidmcp.core.Elevated
import com.sixoffive.androidmcp.core.GateResult
import com.sixoffive.androidmcp.core.TokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A tool was called with missing or unusable arguments.
 *
 * Handlers used to `return` these as ordinary strings, which became a **successful** tool result
 * whose text happened to read like an error — so a model calling `torch {}` got back
 * `isError: false` with the body "provide 'on': …". Found by driving the server with the real
 * MCP Python SDK; no unit test caught it because the string was the documented return value.
 */
internal class ToolArgError(message: String) : Exception(message)

/**
 * A tool ran but could not do its job — the app is not installed, DND blocks the volume change,
 * the calendar is not writable.
 *
 * Distinct from [ToolArgError] (the caller is at fault) and from an unexpected throwable (the code
 * is at fault). Like ToolArgError, these were `return`ed as ordinary strings, so a failure reached
 * the client as `isError:false` and was written to the audit log as "ok".
 */
internal class ToolExecError(message: String) : Exception(message)

/** Hand-rolled MCP JSON-RPC handler over Streamable HTTP. */
object Mcp {
    /** The one protocol revision this server implements. `initialize` negotiates against this set. */
    const val PROTOCOL = "2025-06-18"
    val SUPPORTED = setOf(PROTOCOL)

    /** A client that sends no `MCP-Protocol-Version` header is assumed to predate it (spec back-compat). */
    const val ASSUMED_WHEN_HEADER_ABSENT = "2025-03-26"

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Outcome of one JSON-RPC message.
     *
     * [Rejected] exists because a malformed *envelope* is an HTTP-layer failure, not a JSON-RPC
     * result: the spec wants 4xx for a body that never parsed into a request, and a JSON-RPC error
     * for one that did. [None] is a notification — it MUST NOT be answered (JSON-RPC 2.0 §4.1).
     */
    sealed interface Reply {
        data class Body(val json: String) : Reply
        data class Rejected(val status: Int, val json: String) : Reply
        data object None : Reply

        /**
         * A response the client asked to receive as a stream.
         *
         * 22 of the 40 tools block on a human tapping "Allow" for up to
         * [ApprovalManager.TIMEOUT_MS], during which a plain POST is an open socket saying
         * nothing — indistinguishable from a hung server. When the client supplies a
         * `_meta.progressToken` AND accepts `text/event-stream`, the same POST response carries
         * `notifications/progress` while the work runs and the ordinary JSON-RPC response last.
         *
         * [produce] is handed an emitter for notification JSON and returns the final response
         * JSON. The transport frames both as SSE; nothing here knows about SSE, so the framing
         * stays testable and this stays the protocol layer.
         */
        class Streamed(
            val produce: suspend (emit: suspend (String) -> Unit) -> String,
        ) : Reply
    }

    /**
     * How often to emit progress while a tool runs.
     *
     * Well under the 25 s approval window so a human tap is never the first thing the client
     * hears, and well over the cost of a write so an armed (instant) tool emits nothing at all.
     */
    internal const val PROGRESS_TICK_MS = 2_000L

    suspend fun handle(
        ctx: Context,
        body: String,
        client: String,
        /** The request's `MCP-Protocol-Version` header, if it sent one. */
        protocolHeader: String? = null,
        /**
         * Did the request's `Accept` header include `text/event-stream`?
         *
         * Streaming is offered only when the client both asked for progress and said it can read
         * an SSE body. A client that sends a progressToken but not the Accept header gets exactly
         * the old single-JSON response — asking for progress must never break a working client.
         */
        acceptsSse: Boolean = false,
    ): Reply {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull()
            ?: return Reply.Rejected(400, errorNoId(-32700, "Parse error"))
        // 2025-06-18 removed JSON-RPC batching, so a top-level array is not a valid request here.
        if (root !is JsonObject) return Reply.Rejected(400, errorNoId(-32600, "Invalid Request: expected a JSON object"))

        // `method` must be a string; a client sending an object/array here would otherwise blow up
        // `.jsonPrimitive` and surface as an HTTP 500 instead of a JSON-RPC error.
        val method = (root["method"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

        // A message with no `id` is a notification: never answer it, whatever the method is. The
        // old code only special-cased two names, so `notifications/roots/list_changed` — which every
        // roots-capable client sends right after `initialize` — got back a -32601 with `"id": null`.
        if (!root.containsKey("id")) {
            // The one notification with an effect: withdraw a pending approval so a late "Allow"
            // tap cannot fire the camera for a call the client already abandoned.
            if (method == "notifications/cancelled") {
                val raw = (root["params"] as? JsonObject)?.get("requestId") as? JsonPrimitive
                val cancelled = raw?.contentOrNull
                // Scoped to the CLIENT that sent the cancellation, and type-tagged, so one client
                // cannot withdraw another's pending approvals by guessing ids.
                if (cancelled != null &&
                    ApprovalManager.cancelByRpcId(ApprovalManager.rpcKey(client, cancelled, !raw.isString))
                ) {
                    AuditLog.record("approval", client, false, "CANCELLED_BY_CLIENT: request $cancelled")
                }
            }
            return Reply.None
        }

        val id = root["id"]!!
        if (id !is JsonPrimitive || (!id.isString && id.longOrNull == null && id.doubleOrNull == null)) {
            return Reply.Rejected(400, errorNoId(-32600, "Invalid Request: id must be a string or a number"))
        }
        if (method == null) return Reply.Body(error(id, -32600, "Invalid Request: missing method"))

        // Reject an unsupported MCP-Protocol-Version header — but NEVER on `initialize`, which is
        // the one request whose whole job is to resolve a version mismatch. The negotiation lives
        // in the body (`params.protocolVersion`), so refusing at the HTTP layer first would break
        // exactly the mechanism designed to fix this. Observed on the wire: Claude Code 2.1.251
        // sends no header on initialize, but probes `server/discover` with a future one first and
        // relies on the 4xx to fall back — so the check must stay for every other method.
        if (protocolHeader != null && protocolHeader !in SUPPORTED && method != "initialize") {
            return Reply.Rejected(400, unsupportedProtocolVersion(protocolHeader))
        }

        return runCatching {
            when (method) {
                "initialize" -> Reply.Body(result(id, initialize(root)))
                "ping" -> Reply.Body(result(id, buildJsonObject {}))
                "tools/list" -> Reply.Body(result(id, buildJsonObject {
                    putJsonArray("tools") { Capabilities.REGISTRY.forEach { add(toolDef(ctx, it)) } }
                }))
                "tools/call" -> toolsCallReply(ctx, id, root, client, acceptsSse)
                // Media is transient, single-use and minted per call, so there is nothing static
                // to enumerate. An empty list is legal and honest; the links themselves arrive as
                // resource_link content blocks on the tool result that produced them.
                "resources/list" -> Reply.Body(result(id, buildJsonObject { putJsonArray("resources") {} }))
                "resources/templates/list" -> Reply.Body(result(id, buildJsonObject { putJsonArray("resourceTemplates") {} }))
                "resources/read" -> Reply.Body(resourcesRead(id, root, client))
                else -> Reply.Body(error(id, -32601, "Method not found: $method"))
            }
        }.getOrElse { t ->
            // Nothing below should throw, but an escapee must not become a bare HTTP 500 —
            // that gives the client no id to correlate and no reason.
            Reply.Body(error(id, -32603, "Internal error: ${t::class.java.simpleName}: ${t.message}"))
        }
    }

    /**
     * Version negotiation. The spec requires the server to echo the client's requested version when
     * it supports it, and to answer with a version it *does* support otherwise — the old code
     * hardcoded its own version either way, which silently mismatches an older client.
     */
    private fun initialize(root: JsonObject): JsonObject {
        val params = root["params"] as? JsonObject
        val asked = ((params?.get("protocolVersion")) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        val agreed = if (asked != null && asked in SUPPORTED) asked else PROTOCOL
        return buildJsonObject {
            put("protocolVersion", agreed)
            // `tools` and `resources`. No `listChanged` on either: there is no SSE stream to push
            // it on (GET /mcp is a 405), so claiming it would be a false promise. `resources`
            // exists so a client can dereference the `resource_link` blocks that photo / audio /
            // screenshot replies return — without it a conformant host sees a reference it has no
            // protocol-level way to resolve.
            putJsonObject("capabilities") {
                putJsonObject("tools") {}
                putJsonObject("resources") {}
            }
            putJsonObject("serverInfo") {
                put("name", "androidmcp")
                put("title", "Android MCP")
                put("version", "0.1.0")
            }
            put("instructions", INSTRUCTIONS)
        }
    }

    private val INSTRUCTIONS = """
        This server exposes an Android device's own capabilities as tools. Everything is DEFAULT-DENY:
        each tool must be enabled by the device's owner in the app, and high-impact tools additionally
        raise an Allow/Deny prompt on the device for every call.

        Every tool is always listed, even when it is off, so you can discover it and explain the fix.
        A blocked call is not a protocol error — it returns isError:true plus a structuredContent
        object naming the exact gate that failed (reason_code, app_toggle, os_permission, remediation,
        retriable). Read remediation and tell the user what to turn on; retry only when retriable is true.

        Call list_capabilities first to see what is currently enabled — tool descriptions are static
        and do not reflect live on/off state.
    """.trimIndent()

    private fun toolDef(ctx: Context, cap: CapabilityMeta): JsonObject = buildJsonObject {
        val spec = ToolSchemas.specFor(cap.id)
        put("name", cap.id)
        put("title", cap.title)
        val hw = com.sixoffive.androidmcp.core.HardwareCheck.missing(ctx, cap.id)
        // Lead with what the tool DOES. The description used to be pure privacy boilerplate
        // ("Why: …. Exposes: ….") that never named an argument — and it carried a
        // "[currently enabled/disabled]" suffix computed at list time. Clients cache tools/list at
        // connect and there is no listChanged channel to correct it, so that suffix went stale the
        // moment the owner flipped a toggle. Live state comes from `list_capabilities` and from the
        // structuredContent refusal on the call itself; `why`/`dataExposed` are re-sent there too.
        put("description", buildString {
            append(spec.usage.ifEmpty { cap.title })
            append(" Exposes: ${cap.dataExposed}.")
            if (cap.highImpact) append(" Requires the device owner to approve each call on the device.")
            if (hw != null) append(" [unavailable on this device: $hw]")
        })
        put("inputSchema", buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                spec.args.forEach { a ->
                    putJsonObject(a.name) {
                        put("type", a.type)
                        put("description", a.description)
                        a.enum?.let { e -> putJsonArray("enum") { e.forEach { add(it) } } }
                        a.min?.let { put("minimum", it) }
                        a.max?.let { put("maximum", it) }
                        a.default?.let { put("default", it) }
                    }
                }
            }
            val required = spec.args.filter { it.required }.map { it.name }
            if (required.isNotEmpty()) putJsonArray("required") { required.forEach { add(it) } }
            // No undeclared arguments: the handlers ignore extras, and saying so stops a model
            // inventing parameters that silently do nothing.
            put("additionalProperties", false)
        })
        // Display hints for the host only. The device-side gate, OS permission check and per-call
        // approval run regardless of what a client concludes from these.
        putJsonObject("annotations") {
            put("title", cap.title)
            put("readOnlyHint", spec.readOnly)
            if (!spec.readOnly) put("destructiveHint", spec.destructive)
            put("openWorldHint", spec.openWorld)
        }
    }

    /**
     * Dispatch a `tools/call`, streaming progress only when the client asked for it both ways.
     *
     * The heartbeat runs BESIDE the work rather than being threaded through the 40 tool handlers:
     * every tool becomes observable without any of them knowing progress exists, and a tool that
     * finishes inside one tick emits nothing at all.
     */
    private suspend fun toolsCallReply(
        ctx: Context,
        id: JsonElement,
        root: JsonObject,
        client: String,
        acceptsSse: Boolean,
    ): Reply {
        val token = progressToken(root)
        if (!acceptsSse || token == null) return Reply.Body(toolsCall(ctx, id, root, client))

        // Same key ApprovalManager files the pending prompt under, so the heartbeat can say which
        // of the two very different stalls this is: a human who hasn't looked at their phone, or
        // a tool genuinely taking its time.
        val rpcKey = (id as? JsonPrimitive)?.contentOrNull
            ?.let { ApprovalManager.rpcKey(client, it, !id.isString) }

        return Reply.Streamed { emit ->
            streamWithProgress(
                token = token,
                emit = emit,
                approvalPending = { rpcKey != null && ApprovalManager.isPendingFor(rpcKey) },
            ) { toolsCall(ctx, id, root, client) }
        }
    }

    /**
     * Run [work], emitting a progress notification every [tickMs] until it finishes.
     *
     * Separate from the dispatch above, and taking its inputs rather than reaching for them, so
     * the timing behaviour is testable with no [Context] and no real tool: the properties that
     * matter here are that a fast call emits NOTHING, that a slow one emits a notification that
     * cannot arrive behind its own result, and that progress never goes backwards.
     */
    internal suspend fun streamWithProgress(
        token: JsonPrimitive,
        emit: suspend (String) -> Unit,
        approvalPending: () -> Boolean,
        tickMs: Long = PROGRESS_TICK_MS,
        work: suspend () -> String,
    ): String = coroutineScope {
        val job = async { work() }
        val startedAt = System.nanoTime()
        while (!job.isCompleted) {
            delay(tickMs)
            // Re-check AFTER the delay: a tool that finished during it must not emit a progress
            // notification that arrives behind the result it was supposed to precede.
            if (job.isCompleted) break
            val elapsed = (System.nanoTime() - startedAt) / 1_000_000_000.0
            val message = if (approvalPending()) "waiting for approval on the device" else "working"
            // A write to a client that has gone away throws here, cancelling this scope and with
            // it the work — which is what should happen. ApprovalManager.require cancels its
            // notification in a `finally`, so no orphaned prompt is left in the shade for a
            // request nobody is waiting on.
            emit(progressNotification(token, elapsed, message))
        }
        job.await()
    }

    /**
     * The client's `_meta.progressToken`, or null if it did not ask for progress.
     *
     * Spec: a progress token is a string or an integer. [JsonNull] is a [JsonPrimitive], so an
     * explicit `"progressToken": null` has to be excluded by hand or it would be echoed back as
     * the token of a stream nothing can correlate.
     */
    private fun progressToken(root: JsonObject): JsonPrimitive? {
        val t = ((root["params"] as? JsonObject)?.get("_meta") as? JsonObject)
            ?.get("progressToken") as? JsonPrimitive ?: return null
        if (t is JsonNull) return null
        return if (t.isString || t.longOrNull != null) t else null
    }

    internal fun progressNotification(token: JsonPrimitive, seconds: Double, message: String): String =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "notifications/progress")
            putJsonObject("params") {
                put("progressToken", token)
                // Spec: MUST increase with each notification for the same token. Elapsed seconds
                // does by construction. No `total`: the wait is genuinely indeterminate — the tool
                // may be armed and instant, or blocked on a human — and a total that the progress
                // then sails past is worse than none.
                put("progress", seconds)
                put("message", message)
            }
        }.toString()

    private suspend fun toolsCall(ctx: Context, id: JsonElement, root: JsonObject, client: String): String {
        val params = root["params"] as? JsonObject
            ?: return error(id, -32602, "Invalid params: 'params' must be an object")
        val name = (params["name"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            ?: return error(id, -32602, "Invalid params: 'name' must be a string naming a tool")
        // A non-object `arguments` used to be silently swapped for {} — every argument vanished and
        // the tool ran on its defaults, which for a high-impact tool means burning a user approval
        // on the wrong action.
        if (params.containsKey("arguments") && params["arguments"] !is JsonObject) {
            return error(id, -32602, "Invalid params: 'arguments' must be an object")
        }
        val args = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())
        // An unknown tool is a protocol error (-32602), not a tool result: the tool never ran, so
        // there is no execution outcome to report. This branch also used to write no audit entry.
        val cap = Capabilities.byId(name) ?: run {
            AuditLog.record(name, client, false, "UNKNOWN_TOOL")
            return error(id, -32602, "Unknown tool: $name")
        }

        // Per-token scope. Checked before the capability gate: whether the *owner* enabled a tool is
        // a separate question from whether *this token* is allowed to call it at all. A scoped token
        // asking for a tool outside its scope is refused here, so it cannot even learn (via the gate
        // refusal's remediation) how to unblock a capability it was never granted.
        if (!TokenStore.allows(client, cap.id)) {
            AuditLog.record(cap.id, client, false, "OUT_OF_TOKEN_SCOPE")
            return result(id, scopeRefusal(cap))
        }

        // On Dispatchers.IO, not the request coroutine: the gate is mostly cheap permission
        // lookups, but for a `rootRequired` capability it spawns `su -c id` to detect elevated
        // access. On a Magisk device whose superuser prompt goes unanswered that probe runs to its
        // 10 s bound, and it happened before the approval prompt — so it blocked a Ktor coroutine
        // for a call the owner had not yet consented to. (`Root.isAvailable` memoises the result,
        // so this is once per process, but once is enough to matter.)
        return when (val gate = withContext(Dispatchers.IO) { GateEngine.evaluate(ctx, cap) }) {
            is GateResult.Denied -> {
                AuditLog.record(cap.id, client, false, gate.reason.name)
                result(id, refusalResult(cap, gate))
            }
            GateResult.Allowed -> {
                // Pass the (client, id) key so notifications/cancelled can withdraw this approval.
                val rpcKey = (id as? JsonPrimitive)?.contentOrNull
                    ?.let { ApprovalManager.rpcKey(client, it, !(id as JsonPrimitive).isString) }
                if (!ApprovalManager.require(ctx, cap, client, rpcKey)) {
                    AuditLog.record(cap.id, client, false, "REQUIRES_USER_APPROVAL")
                    return result(id, approvalRefusal(cap))
                }
                // A thrown tool used to be reported to the client as isError:false and to the audit
                // log as "ok" — a camera crash looked like a successful capture in the trust record.
                runCatching { withContext(Dispatchers.IO) { execute(ctx, cap, args) } }.fold(
                    onSuccess = { content ->
                        AuditLog.record(cap.id, client, true, "ok")
                        result(id, successResult(content))
                    },
                    onFailure = { t ->
                        if (t is ToolArgError) {
                            // A bad call, not a broken device: report the handler's own guidance
                            // verbatim, but as isError so the model cannot read it as success.
                            AuditLog.record(cap.id, client, false, "INVALID_ARGUMENT: ${t.message}")
                            result(id, errorResult(t.message ?: "invalid arguments"))
                        } else if (t is ToolExecError) {
                            // The tool ran and failed for a device-state reason it can explain.
                            // Pass the message through verbatim — it already names the fix.
                            AuditLog.record(cap.id, client, false, "EXECUTION_ERROR: ${t.message}")
                            result(id, errorResult(t.message ?: "the tool could not complete"))
                        } else {
                            val why = "${t::class.java.simpleName}: ${t.message}"
                            AuditLog.record(cap.id, client, false, "EXECUTION_ERROR: $why")
                            result(id, errorResult("${cap.title} failed: $why"))
                        }
                    },
                )
            }
        }
    }

    /**
     * Serve a `resource_link` through the protocol instead of an out-of-band HTTP fetch.
     *
     * The URI is the one the link carried — `http(s)://host:port/media/<id>?k=<nonce>` — so a
     * client can either dereference it here or GET it directly, whichever it prefers. Both paths
     * consume the same single-use entry.
     */
    private fun resourcesRead(id: JsonElement, root: JsonObject, client: String): String {
        val params = root["params"] as? JsonObject
            ?: return error(id, -32602, "Invalid params: 'params' must be an object")
        val uri = (params["uri"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            ?: return error(id, -32602, "Invalid params: 'uri' must be a string")
        val parsed = parseMediaUri(uri)
            ?: return error(id, -32602, "Unknown resource: $uri (this server serves only the " +
                "/media/<id>?k=<nonce> links returned by capture tools)")
        val entry = MediaStore.take(parsed.first, parsed.second)
            // -32002 is the spec's "resource not found". Expiry and single-use consumption both
            // land here, so say which so a client does not retry forever.
            ?: return error(id, -32002, "Resource not found: the link has expired (10-minute TTL) " +
                "or was already fetched — media links are single-use")
        AuditLog.record("resources/read", client, true, "served ${entry.bytes.size}B ${entry.mime}")
        return result(id, buildJsonObject {
            putJsonArray("contents") {
                add(buildJsonObject {
                    put("uri", uri)
                    put("mimeType", entry.mime)
                    put("blob", java.util.Base64.getEncoder().encodeToString(entry.bytes))
                })
            }
        })
    }

    /** Pull (id, nonce) out of a media link this server minted; null for anything else. */
    internal fun parseMediaUri(uri: String): Pair<String, String>? {
        val path = uri.substringBefore('?')
        val id = path.substringAfterLast("/media/", "").takeIf { it.isNotEmpty() && '/' !in it } ?: return null
        val query = uri.substringAfter('?', "")
        val nonce = query.split('&').firstOrNull { it.startsWith("k=") }?.removePrefix("k=") ?: return null
        if (nonce.isEmpty()) return null
        return id to nonce
    }

    // ---- result envelopes ----

    private fun result(id: JsonElement, res: JsonElement): String = buildJsonObject {
        put("jsonrpc", "2.0"); put("id", id); put("result", res)
    }.toString()

    private fun error(id: JsonElement, code: Int, message: String): String = buildJsonObject {
        put("jsonrpc", "2.0"); put("id", id)
        putJsonObject("error") { put("code", code); put("message", message) }
    }.toString()

    /**
     * An error for a message whose id could not be recovered. JSON-RPC's `RequestId` is
     * `string | number`, so `"id": null` is off-schema — omit the key entirely instead.
     */
    private fun errorNoId(code: Int, message: String, data: JsonObject? = null): String = buildJsonObject {
        put("jsonrpc", "2.0")
        putJsonObject("error") {
            put("code", code); put("message", message)
            if (data != null) put("data", data)
        }
    }.toString()

    /** Rejection for an unsupported `MCP-Protocol-Version` header, built here so the list stays in one place. */
    fun unsupportedProtocolVersion(asked: String): String = errorNoId(
        -32600, "Unsupported MCP-Protocol-Version: $asked",
        buildJsonObject { putJsonArray("supported") { SUPPORTED.forEach { add(it) } } },
    )

    private fun textBlk(s: String): JsonObject = buildJsonObject { put("type", "text"); put("text", s) }
    private fun imageBlk(b64: String, mime: String): JsonObject = buildJsonObject {
        put("type", "image"); put("data", b64); put("mimeType", mime)
    }
    private fun audioBlk(b64: String, mime: String): JsonObject = buildJsonObject {
        put("type", "audio"); put("data", b64); put("mimeType", mime)
    }
    private fun resourceLinkBlk(uri: String, name: String, mime: String): JsonObject = buildJsonObject {
        put("type", "resource_link"); put("uri", uri); put("name", name); put("mimeType", mime)
    }

    // Very large media is auto-returned as a resource_link even with the toggle off — a multi-MB
    // base64 blob inside one JSON-RPC reply is a memory/'too-large-response' hazard for many clients.
    private const val LARGE_MEDIA_BYTES = 4_000_000

    /** Inline base64, or a fetchable resource_link when "Media as links" is on OR the blob is very large. */
    private fun mediaBlocks(bytes: ByteArray, mime: String, name: String, isImage: Boolean): List<JsonObject> =
        if (ConfigStore.current.mediaAsLinks || bytes.size > LARGE_MEDIA_BYTES) {
            val (mid, nonce) = MediaStore.put(bytes, mime)
            // The base the listener ACTUALLY bound, not live config: bind/TLS can be toggled
            // without restarting the server, and a link built from the new setting points at an
            // address the running listener never bound. Falls back to live config only if the
            // service has not published a base yet.
            val base = McpService.boundBase.value.ifEmpty {
                val scheme = if (ConfigStore.current.tls) "https" else "http"
                // reachableHost, not bindHost: LAN binds 0.0.0.0 but the fetchable address is the LAN IP.
                "$scheme://${Net.reachableHost(ConfigStore.current.bind)}:${ConfigStore.current.port}"
            }
            val link = resourceLinkBlk("$base/media/$mid?k=$nonce", name, mime)
            if (!ConfigStore.current.mediaAsLinks) // auto-linked purely due to size — say why
                listOf(textBlk("(${bytes.size / 1_000_000}+ MB — returned as a link instead of inline base64)"), link)
            else listOf(link)
        } else {
            // java.util.Base64: same standard alphabet, padded, unwrapped — byte-identical to
            // android.util.Base64 with NO_WRAP, but usable from a plain-JVM unit test.
            val b64 = java.util.Base64.getEncoder().encodeToString(bytes)
            listOf(if (isImage) imageBlk(b64, mime) else audioBlk(b64, mime))
        }

    private fun successResult(content: List<JsonObject>): JsonObject = buildJsonObject {
        putJsonArray("content") { content.forEach { add(it) } }
        put("isError", false)
    }

    private fun errorResult(text: String): JsonObject = buildJsonObject {
        putJsonArray("content") { add(buildJsonObject { put("type", "text"); put("text", text) }) }
        put("isError", true)
    }

    /**
     * A result carrying both prose and machine-readable structure.
     *
     * The spec: "For backwards compatibility, a tool that returns structured content SHOULD also
     * return the serialized JSON in a TextContent block." Both refusal envelopes previously
     * returned `structuredContent` with only a human sentence beside it, so a client that reads
     * only `content` — which is every client predating structured content — saw the remediation
     * but none of the machine-readable gate detail.
     */
    private fun structuredResult(prose: String, structured: JsonObject, isError: Boolean): JsonObject =
        buildJsonObject {
            putJsonArray("content") {
                add(textBlk(prose))
                add(textBlk(structured.toString()))
            }
            put("structuredContent", structured)
            put("isError", isError)
        }

    private fun refusalResult(cap: CapabilityMeta, d: GateResult.Denied): JsonObject = structuredResult(
        "${cap.title} is not available. ${d.remediation}",
        buildJsonObject {
            put("status", "capability_disabled")
            put("capability", cap.id)
            put("reason_code", d.reason.name)
            put("gate_failed", gateFailed(d))
            put("app_toggle", cap.toggleLabel)
            put("app_toggle_enabled", d.toggleEnabled)
            put("os_permission", cap.permissions.joinToString())
            put("os_permission_granted", d.permissionGranted)
            putJsonArray("why_required") { cap.why.forEach { add(it) } }
            put("data_exposed", cap.dataExposed)
            put("remediation", d.remediation)
            put("retriable", d.retriable)
        },
        isError = true,
    )

    private fun approvalRefusal(cap: CapabilityMeta): JsonObject = structuredResult(
        "${cap.title} needs your approval on the device. Approve the prompt (or arm the capability in the app), then retry.",
        buildJsonObject {
            put("status", "requires_user_approval")
            put("capability", cap.id)
            put("reason_code", "REQUIRES_USER_APPROVAL")
            put("gate_failed", "user_approval")
            putJsonArray("why_required") { cap.why.forEach { add(it) } }
            put("data_exposed", cap.dataExposed)
            put("remediation", "High-impact tool: approve the on-device notification prompt, or arm this capability, then retry.")
            put("retriable", true)
        },
        isError = true,
    )

    private fun scopeRefusal(cap: CapabilityMeta): JsonObject = structuredResult(
        "${cap.title} is not permitted for this client token — it is scoped to a subset of capabilities that does not include it.",
        buildJsonObject {
            put("status", "capability_out_of_scope")
            put("capability", cap.id)
            put("reason_code", "OUT_OF_TOKEN_SCOPE")
            put("gate_failed", "token_scope")
            put("remediation", "This token cannot call '${cap.id}'. Use a token whose scope includes it, or widen this token's scope in the androidmcp app.")
            // Not retriable with the same token: no on-device action unblocks it, unlike the toggle
            // or permission gates. The owner must re-scope or mint a different token.
            put("retriable", false)
        },
        isError = true,
    )

    /**
     * Which gate actually failed, as a machine-readable token.
     *
     * This used to be inferred purely from the toggle/permission booleans, ignoring the reason
     * code — so a capability refused because the hardware is absent reported
     * `gate_failed: "app_toggle"` (the toggle is off, but that is not why it failed). A model
     * reading that tells the user to flip a switch that cannot help; the companion `retriable`
     * was already false, so the two fields contradicted each other.
     */
    private fun gateFailed(d: GateResult.Denied): String = when (d.reason) {
        com.sixoffive.androidmcp.core.ReasonCode.HARDWARE_UNAVAILABLE -> "hardware"
        com.sixoffive.androidmcp.core.ReasonCode.NOT_SUPPORTED_WITHOUT_ROOT -> "elevated_access"
        com.sixoffive.androidmcp.core.ReasonCode.SPECIAL_ACCESS_NOT_ENABLED -> "special_access"
        com.sixoffive.androidmcp.core.ReasonCode.FEATURE_DISABLED_IN_APP -> "app_toggle"
        com.sixoffive.androidmcp.core.ReasonCode.OS_PERMISSION_NOT_GRANTED,
        com.sixoffive.androidmcp.core.ReasonCode.OS_PERMISSION_PERMANENTLY_DENIED -> "os_permission"
        else -> when {
            !d.toggleEnabled -> "app_toggle"
            !d.permissionGranted -> "os_permission"
            else -> "special_access"
        }
    }

    // ---- capability runners ----

    private suspend fun execute(ctx: Context, cap: CapabilityMeta, args: JsonObject): List<JsonObject> = when (cap.id) {
        "list_capabilities" -> listOf(textBlk(listCapabilities(ctx)))
        "device_info" -> listOf(textBlk(deviceInfo(ctx)))
        "battery_status" -> listOf(textBlk(batteryStatus(ctx)))
        "read_sensors" -> listOf(textBlk(sensorSnapshot(ctx)))
        "get_location" -> listOf(textBlk(location(ctx, args)))
        "telephony_info" -> listOf(textBlk(telephonyInfo(ctx)))
        "bluetooth_info" -> listOf(textBlk(bluetoothInfo(ctx)))
        "locale_info" -> listOf(textBlk(localeInfo(ctx)))
        "dnd_status" -> listOf(textBlk(dndStatus(ctx)))
        "speak" -> listOf(textBlk(speak(ctx, args)))
        "post_notification" -> listOf(textBlk(postNotification(ctx, args)))
        "take_photo" -> takePhoto(ctx, args)
        "record_audio" -> recordAudio(ctx, args)
        "capture_screenshot" -> screenshot(ctx)
        "read_sms" -> listOf(textBlk(smsRead(ctx, args)))
        "read_call_log" -> listOf(textBlk(callLog(ctx, args)))
        "read_clipboard" -> listOf(textBlk(clipboardRead(ctx)))
        "write_clipboard" -> listOf(textBlk(clipboardWrite(ctx, args)))
        "read_notifications" -> listOf(textBlk(readNotifications(args)))
        "notification_action" -> listOf(textBlk(notificationAction(args)))
        "list_files" -> listOf(textBlk(filesRunner(ctx, args)))
        "run_shortcut" -> listOf(textBlk(runShortcut(ctx, args)))
        "wifi_info" -> listOf(textBlk(wifiInfo(ctx)))
        "network_info" -> listOf(textBlk(networkInfo(ctx)))
        "storage_info" -> listOf(textBlk(storageInfo(ctx)))
        "thermal_status" -> listOf(textBlk(thermalStatus(ctx)))
        "screen_info" -> listOf(textBlk(screenInfo(ctx)))
        "volume_info" -> listOf(textBlk(volumeInfo(ctx)))
        "torch" -> listOf(textBlk(torch(ctx, args)))
        "vibrate" -> listOf(textBlk(vibrate(ctx, args)))
        "list_packages" -> listOf(textBlk(listPackages(ctx, args)))
        "launch_url" -> listOf(textBlk(launchUrl(ctx, args)))
        "dial" -> listOf(textBlk(dial(ctx, args)))
        "get_contacts" -> listOf(textBlk(contactsRead(ctx, args)))
        "read_calendar" -> listOf(textBlk(calendarRead(ctx, args)))
        "elevated_input" -> listOf(textBlk(elevatedInput(ctx, args)))
        "set_volume" -> listOf(textBlk(setVolume(ctx, args)))
        "media_control" -> listOf(textBlk(mediaControl(ctx, args)))
        "toast" -> listOf(textBlk(toast(ctx, args)))
        "share_text" -> listOf(textBlk(shareText(ctx, args)))
        "open_settings" -> listOf(textBlk(openSettings(ctx, args)))
        "create_calendar_event" -> listOf(textBlk(createCalendarEvent(ctx, args)))
        "elevated_current_app" -> listOf(textBlk(elevatedCurrentApp(ctx)))
        "elevated_settings" -> listOf(textBlk(elevatedSettings(ctx, args)))
        "root_screenshot" -> rootScreenshot()
        "root_shell" -> listOf(textBlk(rootShell(args)))
        else -> listOf(textBlk("not implemented: ${cap.id}"))
    }

    private fun listCapabilities(ctx: Context): String = Capabilities.REGISTRY.joinToString("\n") { c ->
        val on = ConfigStore.isEnabled(c.id)
        "${c.id}: ${if (on) "enabled" else "disabled"} — ${c.title} (phase ${c.phase})"
    }

    private fun deviceInfo(ctx: Context): String {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        fun mb(b: Long) = "%.0f MB".format(b / 1024.0 / 1024.0)
        return buildString {
            appendLine("manufacturer: ${Build.MANUFACTURER}")
            appendLine("model: ${Build.MODEL}")
            appendLine("device: ${Build.DEVICE}")
            appendLine("android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("total RAM: ${mb(mem.totalMem)}")
            appendLine("available RAM: ${mb(mem.availMem)}")
            appendLine("low memory: ${mem.lowMemory}")
            append("uptime: ${SystemClock.elapsedRealtime() / 1000} s")
        }
    }

    private fun batteryStatus(ctx: Context): String {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return "battery info unavailable"
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val status = when (i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
            BatteryManager.BATTERY_STATUS_FULL -> "full"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not charging"
            else -> "unknown"
        }
        val temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) / 10.0
        return "level: $pct%\nstatus: $status\ntemperature: $temp C"
    }

    private fun sensorSnapshot(ctx: Context): String {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val wanted = listOf(
            Sensor.TYPE_ACCELEROMETER to "accelerometer",
            Sensor.TYPE_LIGHT to "light",
            Sensor.TYPE_PROXIMITY to "proximity",
            Sensor.TYPE_MAGNETIC_FIELD to "magnetometer",
        )
        val results = java.util.Collections.synchronizedMap(LinkedHashMap<String, String>())
        val present = wanted.filter { sm.getDefaultSensor(it.first) != null }
        val latch = CountDownLatch(present.size)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val label = wanted.firstOrNull { it.first == e.sensor.type }?.second ?: return
                synchronized(results) {
                    if (!results.containsKey(label)) {
                        results[label] = e.values.joinToString(", ") { "%.3f".format(it) }
                        latch.countDown()
                        sm.unregisterListener(this, e.sensor)
                    }
                }
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        present.forEach { (t, _) -> sm.getDefaultSensor(t)?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_FASTEST) } }
        latch.await(800, TimeUnit.MILLISECONDS)
        sm.unregisterListener(listener)
        wanted.forEach { (t, label) -> if (sm.getDefaultSensor(t) == null) results.putIfAbsent(label, "unavailable") }
        return synchronized(results) { results.entries.joinToString("\n") { "${it.key}: ${it.value}" } }
    }

    private fun location(ctx: Context, args: JsonObject): String {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        val best = providers.mapNotNull { p ->
            runCatching { if (lm.isProviderEnabled(p)) lm.getLastKnownLocation(p) else null }.getOrNull()
        }.maxByOrNull { it.time } ?: return "no last-known location available (try again after a location fix)"
        val base = "lat: ${best.latitude}\nlon: ${best.longitude}\naccuracy: ${best.accuracy} m\nprovider: ${best.provider}\nage: ${(System.currentTimeMillis() - best.time) / 1000} s"
        val wantAddress = args["address"]?.jsonPrimitive?.booleanOrNull ?: false
        if (!wantAddress) return base
        val addr = reverseGeocode(ctx, best.latitude, best.longitude)
        return if (addr != null) "$base\naddress: $addr"
        else "$base\naddress: (reverse geocoding produced no result — the geocoder backend may be missing or offline)"
    }

    /** Best-effort reverse geocode; null when no backend is present or no match is returned. */
    @Suppress("DEPRECATION") // the async getFromLocation is API 33+; the sync form still works at targetSdk 33
    private fun reverseGeocode(ctx: Context, lat: Double, lon: Double): String? = runCatching {
        if (!android.location.Geocoder.isPresent()) return null
        val g = android.location.Geocoder(ctx, java.util.Locale.getDefault())
        val a = g.getFromLocation(lat, lon, 1)?.firstOrNull() ?: return null
        (0..a.maxAddressLineIndex).joinToString(", ") { a.getAddressLine(it) }.takeUnless { it.isBlank() }
    }.getOrNull()

    private fun postNotification(ctx: Context, args: JsonObject): String {
        val title = args["title"]?.jsonPrimitive?.contentOrNull?.takeUnless { it.isBlank() }
            ?: throw ToolArgError("provide a non-empty 'title'")
        val text = args["text"]?.jsonPrimitive?.contentOrNull?.takeUnless { it.isBlank() }
            ?: throw ToolArgError("provide non-empty 'text'")
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val chId = "androidmcp_posted"
        nm.createNotificationChannel(NotificationChannel(chId, "Posted by MCP", NotificationManager.IMPORTANCE_DEFAULT))
        val n = NotificationCompat.Builder(ctx, chId)
            .setSmallIcon(com.sixoffive.androidmcp.R.drawable.ic_stat_mcp)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        nm.notify((System.currentTimeMillis() % 100000).toInt(), n)
        return "posted notification: \"$title\""
    }

    private suspend fun takePhoto(ctx: Context, args: JsonObject): List<JsonObject> {
        val facing = (args["camera"]?.jsonPrimitive?.contentOrNull ?: "back").trim().lowercase()
        if (facing != "back" && facing != "front") return listOf(textBlk("unknown camera '$facing' — use 'back' or 'front'"))
        val jpeg = CameraCapture.capture(ctx, facing)
            ?: throw ToolExecError("Camera capture failed or timed out — another app may hold the camera, or the app is backgrounded (open androidmcp and retry).")
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
        return listOf(textBlk("Captured ${opts.outWidth}x${opts.outHeight} JPEG from the $facing camera (${jpeg.size} bytes).")) +
            mediaBlocks(jpeg, "image/jpeg", "photo-$facing.jpg", isImage = true)
    }

    private suspend fun recordAudio(ctx: Context, args: JsonObject): List<JsonObject> {
        val secs = (args["seconds"]?.jsonPrimitive?.intOrNull ?: 5).coerceIn(1, 30)
        val bytes = AudioCapture.record(ctx, secs)
            ?: throw ToolExecError("Audio capture failed — the mic may be in use, or the app is backgrounded (open androidmcp and retry).")
        return listOf(textBlk("Recorded ${secs}s of audio (${bytes.size} bytes, AAC/MP4).")) +
            mediaBlocks(bytes, "audio/mp4", "audio.m4a", isImage = false)
    }

    private suspend fun screenshot(ctx: Context): List<JsonObject> {
        if (ProjectionHolder.projection == null) {
            throw ToolExecError("Screen capture isn't active. Open androidmcp and tap 'Start screen sharing' (Android requires a one-time on-device consent), then retry.")
        }
        val jpeg = ScreenCapture.capture(ctx)
            ?: throw ToolExecError("Screen capture failed — the projection may have been revoked. Re-start screen sharing in androidmcp.")
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
        return listOf(textBlk("Captured screen ${opts.outWidth}x${opts.outHeight} (${jpeg.size} bytes).")) +
            mediaBlocks(jpeg, "image/jpeg", "screen.jpg", isImage = true)
    }

    private fun smsRead(ctx: Context, args: JsonObject): String {
        val limit = (args["limit"]?.jsonPrimitive?.intOrNull ?: 20).coerceIn(1, 200)
        val sb = StringBuilder(); var n = 0
        val cur = ctx.contentResolver.query(
            android.net.Uri.parse("content://sms/inbox"),
            arrayOf("address", "body", "date"), null, null, "date DESC",
        ) ?: return "SMS provider not accessible"
        cur.use { c ->
            val ai = c.getColumnIndex("address"); val bi = c.getColumnIndex("body"); val di = c.getColumnIndex("date")
            val fmt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US)
            while (c.moveToNext() && n < limit) {
                val date = fmt.format(java.util.Date(c.getLong(di)))
                val body = c.getString(bi)?.replace("\n", " ")?.take(200)
                sb.append("[$date] ${c.getString(ai)}: $body\n"); n++
            }
        }
        return if (n == 0) "no messages" else sb.toString().trim()
    }

    private fun callLog(ctx: Context, args: JsonObject): String {
        val limit = (args["limit"]?.jsonPrimitive?.intOrNull ?: 20).coerceIn(1, 200)
        val sb = StringBuilder(); var n = 0
        val cur = ctx.contentResolver.query(
            android.provider.CallLog.Calls.CONTENT_URI,
            arrayOf(
                android.provider.CallLog.Calls.NUMBER,
                android.provider.CallLog.Calls.TYPE,
                android.provider.CallLog.Calls.DATE,
                android.provider.CallLog.Calls.DURATION,
            ), null, null, android.provider.CallLog.Calls.DATE + " DESC",
        ) ?: return "call log not accessible"
        cur.use { c ->
            val fmt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US)
            while (c.moveToNext() && n < limit) {
                val type = when (c.getInt(1)) { 1 -> "in"; 2 -> "out"; 3 -> "missed"; else -> "other" }
                val date = fmt.format(java.util.Date(c.getLong(2)))
                sb.append("[$date] $type ${c.getString(0)} (${c.getLong(3)}s)\n"); n++
            }
        }
        return if (n == 0) "no calls" else sb.toString().trim()
    }

    private fun clipboardRead(ctx: Context): String {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = cm.primaryClip
        if (clip == null || clip.itemCount == 0)
            return "clipboard is empty, or not readable from the background — Android 10+ only lets the foreground app read the clipboard, so open androidmcp and retry"
        return clip.getItemAt(0).coerceToText(ctx).toString()
    }

    private fun clipboardWrite(ctx: Context, args: JsonObject): String {
        val text = args["text"]?.jsonPrimitive?.contentOrNull ?: throw ToolArgError("no 'text' argument provided")
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("androidmcp", text))
        return "clipboard set to ${text.length} chars (background writes may be silently restricted on some Android versions)"
    }

    private fun readNotifications(args: JsonObject): String {
        val limit = (args["limit"]?.jsonPrimitive?.intOrNull ?: 20).coerceIn(1, 200)
        return McpNotificationListener.readActive(limit)
            ?: "notification listener isn't connected yet — toggle Notification access off/on for androidmcp, then retry"
    }

    private fun notificationAction(args: JsonObject): String {
        val key = args["key"]?.jsonPrimitive?.contentOrNull?.takeUnless { it.isBlank() }
            ?: throw ToolArgError("provide the notification 'key' from read_notifications")
        val index = args["action_index"]?.jsonPrimitive?.intOrNull
            ?: throw ToolArgError("provide an integer 'action_index' (the index read_notifications showed for the action)")
        val text = args["text"]?.jsonPrimitive?.contentOrNull
        return McpNotificationListener.sendAction(key, index, text)
    }

    private fun filesRunner(ctx: Context, args: JsonObject): String {
        val uri = args["uri"]?.jsonPrimitive?.contentOrNull
        return if (uri.isNullOrBlank()) FilesAccess.listAll(ctx) else FilesAccess.read(ctx, uri)
    }

    // ---- wifi_info ----
    @Suppress("DEPRECATION")
    private fun wifiInfo(ctx: android.content.Context): String {
        val wm = ctx.applicationContext.getSystemService(android.content.Context.WIFI_SERVICE)
            as? android.net.wifi.WifiManager
            ?: return "Wi-Fi service is unavailable on this device."
        // getConnectionInfo() is deprecated at API 31 but is the only synchronous way to read the
        // current network at targetSdk 33 (the replacement reads WifiInfo via an async network
        // callback, which doesn't fit a one-shot runner). Every field read below exists at <= API 26.
        val info = try {
            wm.connectionInfo
        } catch (t: Throwable) {
            return "Couldn't read Wi-Fi connection info: ${t.message ?: t.javaClass.simpleName}"
        } ?: return "No Wi-Fi connection info available (Wi-Fi may be off, or nothing is connected)."
        val rssi = info.rssi
        // A disconnected radio reports networkId -1 and the invalid-RSSI floor (WifiInfo.INVALID_RSSI = -127).
        // BOTH are required so a connected-but-location-redacted network (networkId -1 yet a real RSSI)
        // is not misreported as disconnected.
        if (info.networkId == -1 && rssi <= -127) {
            return "Not connected to any Wi-Fi network."
        }
        // Static 5-level overload: deprecated at API 30, present since API 1, yields a 0-4 bucket.
        val level = try {
            android.net.wifi.WifiManager.calculateSignalLevel(rssi, 5).coerceIn(0, 4)
        } catch (t: Throwable) { -1 }
        val rawSsid: String? = info.ssid
        val ssid = when {
            rawSsid.isNullOrEmpty() -> "<unknown ssid>"
            rawSsid == "<unknown ssid>" -> "<unknown ssid> (redacted — needs location permission)"
            else -> rawSsid.trim().removeSurrounding("\"")
        }
        val rawBssid: String? = info.bssid
        val bssidStr = when {
            rawBssid.isNullOrEmpty() -> "<unknown>"
            rawBssid == "02:00:00:00:00:00" -> "<redacted — needs location permission>"
            else -> rawBssid
        }
        val speed = info.linkSpeed // Mbps, -1 if unknown
        val freq = info.frequency  // MHz, WifiInfo.getFrequency() (API 21+)
        return buildString {
            appendLine("ssid: $ssid")
            appendLine("bssid: $bssidStr")
            appendLine("rssi: $rssi dBm")
            appendLine("signal level: ${if (level in 0..4) "$level/4" else "unknown"}")
            appendLine("link speed: ${if (speed >= 0) "$speed Mbps" else "unknown"}")
            append("frequency: ${if (freq > 0) "$freq MHz" else "unknown"}")
        }
    }

    // ---- network_info ----
    private fun networkInfo(ctx: android.content.Context): String {
        val cm = ctx.applicationContext.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
            as? android.net.ConnectivityManager
            ?: return "Connectivity service is unavailable on this device."
        val tm = ctx.applicationContext.getSystemService(android.content.Context.TELEPHONY_SERVICE)
            as? android.telephony.TelephonyManager
        val carrier = (try { tm?.networkOperatorName } catch (t: Throwable) { null })
            ?.takeIf { it.isNotBlank() } ?: "unknown"
        val net = try { cm.activeNetwork } catch (t: Throwable) { null } // API 23+
        val caps = net?.let { try { cm.getNetworkCapabilities(it) } catch (t: Throwable) { null } } // API 21+
        if (net == null || caps == null) {
            return buildString {
                appendLine("transport: none")
                appendLine("connected: false")
                appendLine("validated: false")
                appendLine("metered: unknown")
                append("carrier: $carrier")
            }
        }
        // VPN is reported alongside its underlying transport, so collect all present ones.
        val transports = buildList {
            if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) add("vpn")
            if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) add("wifi")
            if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)) add("cellular")
            if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)) add("ethernet")
        }
        val transport = if (transports.isEmpty()) "unknown" else transports.joinToString("+")
        val hasInternet = caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val validated = caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val metered = !caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        return buildString {
            appendLine("transport: $transport")
            appendLine("connected: $hasInternet")
            appendLine("validated: $validated")
            appendLine("metered: $metered")
            append("carrier: $carrier")
        }
    }

    // ---- telephony_info ----
    private fun telephonyInfo(ctx: android.content.Context): String {
        val tm = ctx.applicationContext.getSystemService(android.content.Context.TELEPHONY_SERVICE)
            as? android.telephony.TelephonyManager
            ?: return "Telephony service is unavailable on this device."
        val simState = when (tm.simState) {
            android.telephony.TelephonyManager.SIM_STATE_ABSENT -> "absent"
            android.telephony.TelephonyManager.SIM_STATE_READY -> "ready"
            android.telephony.TelephonyManager.SIM_STATE_PIN_REQUIRED -> "pin_required"
            android.telephony.TelephonyManager.SIM_STATE_PUK_REQUIRED -> "puk_required"
            android.telephony.TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "network_locked"
            android.telephony.TelephonyManager.SIM_STATE_NOT_READY -> "not_ready"
            android.telephony.TelephonyManager.SIM_STATE_PERM_DISABLED -> "permanently_disabled"
            else -> "unknown"
        }
        val phoneType = when (tm.phoneType) {
            android.telephony.TelephonyManager.PHONE_TYPE_GSM -> "gsm"
            android.telephony.TelephonyManager.PHONE_TYPE_CDMA -> "cdma"
            android.telephony.TelephonyManager.PHONE_TYPE_SIP -> "sip"
            else -> "none"
        }
        val operator = tm.networkOperatorName?.takeIf { it.isNotBlank() } ?: "unknown"
        val simOperator = tm.simOperatorName?.takeIf { it.isNotBlank() } ?: "unknown"
        val country = tm.networkCountryIso?.takeIf { it.isNotBlank() }?.uppercase() ?: "unknown"
        val roaming = runCatching { tm.isNetworkRoaming }.getOrDefault(false)
        val dataState = when (tm.dataState) {
            android.telephony.TelephonyManager.DATA_DISCONNECTED -> "disconnected"
            android.telephony.TelephonyManager.DATA_CONNECTING -> "connecting"
            android.telephony.TelephonyManager.DATA_CONNECTED -> "connected"
            android.telephony.TelephonyManager.DATA_SUSPENDED -> "suspended"
            else -> "unknown"
        }
        // getSignalStrength() is API 28+ and needs no permission; .level is a 0-4 bucket.
        val signal = runCatching {
            if (Build.VERSION.SDK_INT >= 28) tm.signalStrength?.let { "${it.level}/4" } else null
        }.getOrNull() ?: "unknown"
        return buildString {
            appendLine("phone type: $phoneType")
            appendLine("sim state: $simState")
            appendLine("network operator: $operator")
            appendLine("sim operator: $simOperator")
            appendLine("network country: $country")
            appendLine("roaming: $roaming")
            appendLine("data state: $dataState")
            append("signal level: $signal")
        }
    }

    // ---- bluetooth_info ----
    private fun bluetoothInfo(ctx: android.content.Context): String {
        val mgr = ctx.applicationContext.getSystemService(android.content.Context.BLUETOOTH_SERVICE)
            as? android.bluetooth.BluetoothManager
        val adapter = mgr?.adapter ?: return "No Bluetooth adapter on this device."
        // getState()/isEnabled() do not need BLUETOOTH_CONNECT (that governs names, scans and the
        // bonded-device list, which this tool deliberately does not read). On API < 31 the legacy,
        // auto-granted BLUETOOTH permission covers them; guard anyway for OEM quirks.
        val state = runCatching {
            when (adapter.state) {
                android.bluetooth.BluetoothAdapter.STATE_OFF -> "off"
                android.bluetooth.BluetoothAdapter.STATE_ON -> "on"
                android.bluetooth.BluetoothAdapter.STATE_TURNING_ON -> "turning_on"
                android.bluetooth.BluetoothAdapter.STATE_TURNING_OFF -> "turning_off"
                else -> "unknown"
            }
        }.getOrDefault("restricted")
        val enabled = runCatching { adapter.isEnabled }.getOrDefault(false)
        val ble = ctx.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_BLUETOOTH_LE)
        return buildString {
            appendLine("adapter: present")
            appendLine("state: $state")
            appendLine("enabled: $enabled")
            append("bluetooth low energy (BLE): ${if (ble) "supported" else "not supported"}")
        }
    }

    // ---- locale_info ----
    private fun localeInfo(ctx: android.content.Context): String {
        val locales = ctx.resources.configuration.locales
        val primary = if (locales.size() > 0) locales.get(0) else java.util.Locale.getDefault()
        val tz = java.util.TimeZone.getDefault()
        val now = java.util.Date()
        val iso = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.US).format(now)
        val offsetMin = tz.getOffset(now.time) / 60000
        val sign = if (offsetMin >= 0) "+" else "-"
        val off = "%02d:%02d".format(Math.abs(offsetMin) / 60, Math.abs(offsetMin) % 60)
        val allTags = (0 until locales.size()).joinToString(", ") { locales.get(it).toLanguageTag() }
        return buildString {
            appendLine("language: ${primary.language}")
            appendLine("country: ${primary.country.ifBlank { "unknown" }}")
            appendLine("locale: ${primary.toLanguageTag()}")
            appendLine("all locales: ${allTags.ifBlank { primary.toLanguageTag() }}")
            appendLine("timezone: ${tz.id} (${tz.getDisplayName(tz.inDaylightTime(now), java.util.TimeZone.SHORT)})")
            appendLine("utc offset: $sign$off")
            appendLine("24-hour clock: ${android.text.format.DateFormat.is24HourFormat(ctx)}")
            append("device time: $iso")
        }
    }

    // ---- dnd_status ----
    private fun dndStatus(ctx: android.content.Context): String {
        val nm = ctx.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return "Notification service is unavailable on this device."
        val filter = when (nm.currentInterruptionFilter) {
            NotificationManager.INTERRUPTION_FILTER_ALL -> "all (Do Not Disturb off)"
            NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "priority only"
            NotificationManager.INTERRUPTION_FILTER_NONE -> "total silence"
            NotificationManager.INTERRUPTION_FILTER_ALARMS -> "alarms only"
            else -> "undetermined (grant Notification Policy access for a precise read)"
        }
        val access = runCatching { nm.isNotificationPolicyAccessGranted }.getOrDefault(false)
        return buildString {
            appendLine("interruption filter: $filter")
            append("policy access (needed to change DND): ${if (access) "granted" else "not granted"}")
        }
    }

    // ---- storage_info ----
    private fun storageInfo(ctx: android.content.Context): String {
        fun gb(bytes: Long) = "%.2f GB".format(bytes / 1073741824.0)
        fun statLine(label: String, path: String): String = try {
            val sf = android.os.StatFs(path)
            val total = sf.totalBytes
            val free = sf.availableBytes
            val used = total - free
            val pct = if (total > 0) used * 100.0 / total else 0.0
            "$label ($path)\n  total ${gb(total)} | used ${gb(used)} (${"%.0f".format(pct)}%) | free ${gb(free)}"
        } catch (t: Throwable) {
            "$label ($path): unavailable (${t.message})"
        }
        return buildString {
            appendLine("== Internal storage (app data partition) ==")
            appendLine(statLine("data", ctx.dataDir.absolutePath))
            appendLine()
            appendLine("== External storage ==")
            val dirs = ctx.getExternalFilesDirs(null)?.filterNotNull() ?: emptyList()
            if (dirs.isEmpty()) {
                append("no external storage volumes currently available")
            } else {
                append(dirs.mapIndexed { i, f ->
                    statLine(if (i == 0) "external (primary)" else "external (volume ${i + 1})", f.absolutePath)
                }.joinToString("\n"))
            }
        }.trim()
    }

    // ---- thermal_status ----
    private fun thermalStatus(ctx: android.content.Context): String {
        val pm = ctx.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
            ?: return "thermal status unavailable (PowerManager not present)"
        return buildString {
            if (Build.VERSION.SDK_INT >= 29) {
                val label = when (pm.currentThermalStatus) {
                    android.os.PowerManager.THERMAL_STATUS_NONE -> "none"
                    android.os.PowerManager.THERMAL_STATUS_LIGHT -> "light"
                    android.os.PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
                    android.os.PowerManager.THERMAL_STATUS_SEVERE -> "severe"
                    android.os.PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
                    android.os.PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
                    android.os.PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
                    else -> "unknown"
                }
                appendLine("thermal status: $label")
            } else {
                appendLine("thermal status: unavailable (needs Android 10/11+)")
            }
            if (Build.VERSION.SDK_INT >= 30) {
                val hr = try { pm.getThermalHeadroom(0) } catch (t: Throwable) { Float.NaN }
                if (hr.isNaN()) {
                    append("thermal headroom: unavailable (device reported no value)")
                } else {
                    append("thermal headroom: ${"%.2f".format(hr)}  (0.0 cool -> 1.0 = throttling threshold)")
                }
            } else {
                append("thermal headroom: unavailable (needs Android 10/11+)")
            }
        }.trim()
    }

    // ---- screen_info ----
    private fun screenInfo(ctx: android.content.Context): String {
        val dm = ctx.resources.displayMetrics
        val pm = ctx.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
        var display: android.view.Display? = null
        if (Build.VERSION.SDK_INT >= 30) {
            // Context.getDisplay() (API 30) throws UnsupportedOperationException when the Context is
            // not display-associated. This runner is handed the Application context, so on API 30+
            // this always throws and the fallback below is what actually runs — hence guard + catch.
            display = try { ctx.display } catch (t: Throwable) { null }
        }
        if (display == null) {
            // DisplayManager is context-independent (API 17+) and NOT deprecated, unlike
            // WindowManager.defaultDisplay which is discouraged/unreliable from a non-visual
            // (Application/Service) context on API 30+. DEFAULT_DISPLAY (0) always exists.
            display = try {
                (ctx.getSystemService(android.content.Context.DISPLAY_SERVICE) as? android.hardware.display.DisplayManager)
                    ?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
            } catch (t: Throwable) { null }
        }
        val refresh = display?.refreshRate
        val rotation = when (display?.rotation) {
            android.view.Surface.ROTATION_0 -> "0 deg (natural)"
            android.view.Surface.ROTATION_90 -> "90 deg"
            android.view.Surface.ROTATION_180 -> "180 deg"
            android.view.Surface.ROTATION_270 -> "270 deg"
            null -> "unavailable (no display resolved for this context)"
            else -> "unknown"
        }
        val timeoutMs = try {
            android.provider.Settings.System.getInt(
                ctx.contentResolver, android.provider.Settings.System.SCREEN_OFF_TIMEOUT, -1)
        } catch (t: Throwable) { -1 }
        return buildString {
            appendLine("resolution: ${dm.widthPixels} x ${dm.heightPixels} px")
            appendLine("density: ${dm.densityDpi} dpi (scale x${"%.2f".format(dm.density)})")
            appendLine("refresh rate: ${if (refresh != null) "%.1f Hz".format(refresh) else "unavailable"}")
            appendLine("rotation: $rotation")
            appendLine("interactive (awake): ${pm?.isInteractive ?: "unknown"}")
            append("screen-off timeout: " + (if (timeoutMs >= 0) "${timeoutMs / 1000}s ($timeoutMs ms)" else "unavailable"))
        }.trim()
    }

    // ---- volume_info ----
    private fun volumeInfo(ctx: Context): String {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
            ?: return "audio service unavailable"
        val streams = listOf(
            "music" to android.media.AudioManager.STREAM_MUSIC,
            "ring" to android.media.AudioManager.STREAM_RING,
            "alarm" to android.media.AudioManager.STREAM_ALARM,
            "notification" to android.media.AudioManager.STREAM_NOTIFICATION,
            "voice_call" to android.media.AudioManager.STREAM_VOICE_CALL,
            "system" to android.media.AudioManager.STREAM_SYSTEM,
        )
        val sb = StringBuilder()
        for ((label, s) in streams) {
            val cur = runCatching { am.getStreamVolume(s) }.getOrDefault(-1)
            val max = runCatching { am.getStreamMaxVolume(s) }.getOrDefault(-1)
            val min = if (Build.VERSION.SDK_INT >= 28) runCatching { am.getStreamMinVolume(s) }.getOrDefault(0) else 0
            sb.append("$label: $cur/$max")
            if (min > 0) sb.append(" (min $min)")
            sb.append("\n")
        }
        val ringer = when (am.ringerMode) {
            android.media.AudioManager.RINGER_MODE_NORMAL -> "normal"
            android.media.AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
            android.media.AudioManager.RINGER_MODE_SILENT -> "silent"
            else -> "unknown"
        }
        sb.append("ringerMode: $ringer")
        return sb.toString()
    }

    // ---- torch ----
    private fun torch(ctx: Context, args: JsonObject): String {
        val on = args["on"]?.jsonPrimitive?.booleanOrNull
            ?: throw ToolArgError("provide 'on': true to switch the light on, false to switch it off")
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as? android.hardware.camera2.CameraManager
            ?: return "camera service unavailable"
        val flashId = runCatching {
            cm.cameraIdList.firstOrNull { id ->
                cm.getCameraCharacteristics(id)
                    .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        }.getOrNull()
            ?: return "no camera flash available on this device"
        return try {
            cm.setTorchMode(flashId, on)
            "torch ${if (on) "on" else "off"} (cameraId $flashId)"
        } catch (t: Throwable) {
            "could not set torch: ${t.message} (the camera may be in use by another app)"
        }
    }

    // ---- vibrate ----
    private fun vibrate(ctx: Context, args: JsonObject): String {
        val ms = (args["milliseconds"]?.jsonPrimitive?.intOrNull ?: 300).coerceIn(1, 5000).toLong()
        val vib: android.os.Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            (ctx.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator)
        }
        if (vib == null || !vib.hasVibrator()) return "no vibrator on this device"
        return try {
            val effect = android.os.VibrationEffect.createOneShot(ms, android.os.VibrationEffect.DEFAULT_AMPLITUDE)
            if (Build.VERSION.SDK_INT >= 33) {
                val attrs = android.os.VibrationAttributes.Builder()
                    .setUsage(android.os.VibrationAttributes.USAGE_ALARM)
                    .build()
                vib.vibrate(effect, attrs)
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(effect)
            }
            "vibrated for $ms ms (if nothing was felt, the phone's master \"Vibration & haptics\" setting may be off)"
        } catch (t: Throwable) {
            "could not vibrate: ${t.message}"
        }
    }

    // ---- list_packages ----
    private fun listPackages(ctx: android.content.Context, args: kotlinx.serialization.json.JsonObject): String {
        val filter = args["filter"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val includeSystem = args["include_system"]?.jsonPrimitive?.booleanOrNull ?: false
        val limit = (args["limit"]?.jsonPrimitive?.intOrNull ?: 100).coerceIn(1, 2000)
        val pm = ctx.packageManager
        val apps = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledApplications(
                    android.content.pm.PackageManager.ApplicationInfoFlags.of(0L)
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledApplications(0)
            }
        } catch (t: Throwable) {
            throw ToolExecError("could not list installed apps: ${t.message}")
        }
        val systemMask = android.content.pm.ApplicationInfo.FLAG_SYSTEM or
            android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
        val rows = apps.asSequence()
            .map { ai ->
                val isSystem = (ai.flags and systemMask) != 0
                val label = runCatching { pm.getApplicationLabel(ai).toString() }.getOrNull()
                    ?.takeIf { it.isNotBlank() } ?: ai.packageName
                Triple(label, ai.packageName, isSystem)
            }
            .filter { (label, pkg, isSystem) ->
                (includeSystem || !isSystem) &&
                    (filter == null || label.lowercase().contains(filter) || pkg.lowercase().contains(filter))
            }
            .sortedBy { it.first.lowercase() }
            .toList()
        if (rows.isEmpty()) return "no matching apps"
        val shown = rows.take(limit)
        val sb = StringBuilder()
        sb.appendLine(
            "installed apps: ${rows.size} match${if (rows.size == 1) "" else "es"}" +
                (if (rows.size > shown.size) " (showing first ${shown.size})" else "")
        )
        shown.forEach { (label, pkg, isSystem) ->
            sb.appendLine("$label — $pkg${if (isSystem) " [system]" else ""}")
        }
        return sb.toString().trim()
    }

    // ---- launch_url ----
    private fun launchUrl(ctx: android.content.Context, args: kotlinx.serialization.json.JsonObject): String {
        val raw = args["url"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: throw ToolArgError("provide a 'url' to open")
        if (raw.isEmpty()) throw ToolArgError("provide a non-empty 'url' to open")
        // Prepend https:// only when there is no leading URI scheme (scheme://…).
        val hasScheme = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(raw)
        val normalized = if (hasScheme) raw else "https://$raw"
        val uri = try {
            android.net.Uri.parse(normalized)
        } catch (t: Throwable) {
            throw ToolArgError("invalid url: ${t.message}")
        }
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(intent)
            "opened $normalized (note: Android may block launching activities while androidmcp is in the background)"
        } catch (t: android.content.ActivityNotFoundException) {
            throw ToolExecError("no app can handle $normalized")
        } catch (t: Throwable) {
            throw ToolExecError("could not open $normalized: ${t.message}")
        }
    }

    // ---- dial ----
    private fun dial(ctx: android.content.Context, args: kotlinx.serialization.json.JsonObject): String {
        val number = args["number"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: throw ToolArgError("provide a 'number' to dial")
        if (number.isEmpty()) throw ToolArgError("provide a non-empty 'number' to dial")
        val uri = android.net.Uri.fromParts("tel", number, null)
        val intent = Intent(Intent.ACTION_DIAL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(intent)
            "opened dialer pre-filled with $number (the call is NOT placed — you must press call)"
        } catch (t: android.content.ActivityNotFoundException) {
            "no dialer app available for $number (this device may have no phone app — e.g. a tablet)"
        } catch (t: Throwable) {
            "could not open dialer for $number: ${t.message}"
        }
    }

    // ---- get_contacts ----
    private fun contactsRead(ctx: android.content.Context, args: kotlinx.serialization.json.JsonObject): String {
        val query = args["query"]?.jsonPrimitive?.contentOrNull?.trim()?.takeUnless { it.isBlank() }
        val limit = (args["limit"]?.jsonPrimitive?.intOrNull ?: 30).coerceIn(1, 500)
        // Phone is imported by simple name (see extraImports) — a Java class cannot be bound to a Kotlin `val`.
        val cols = arrayOf(
            Phone.CONTACT_ID, Phone.DISPLAY_NAME, Phone.NUMBER, Phone.TYPE, Phone.LABEL,
        )
        val selection = if (query != null) "${Phone.DISPLAY_NAME} LIKE ?" else null
        val selectionArgs = if (query != null) arrayOf("%$query%") else null
        val sort = "${Phone.DISPLAY_NAME} COLLATE NOCASE ASC"
        val cur = runCatching {
            ctx.contentResolver.query(Phone.CONTENT_URI, cols, selection, selectionArgs, sort)
        }.getOrNull() ?: return "contacts provider not accessible"
        // contactId (or name fallback) -> (display name, distinct numbers)
        val grouped = LinkedHashMap<String, Pair<String, MutableList<String>>>()
        cur.use { c ->
            val idIx = c.getColumnIndex(Phone.CONTACT_ID)
            val nameIx = c.getColumnIndex(Phone.DISPLAY_NAME)
            val numIx = c.getColumnIndex(Phone.NUMBER)
            val typeIx = c.getColumnIndex(Phone.TYPE)
            val labelIx = c.getColumnIndex(Phone.LABEL)
            while (c.moveToNext()) {
                val number = (if (numIx >= 0) c.getString(numIx) else null)?.trim()
                if (number.isNullOrBlank()) continue
                val name = (if (nameIx >= 0) c.getString(nameIx) else null)?.trim()
                    ?.takeUnless { it.isBlank() } ?: "(no name)"
                val cid = (if (idIx >= 0) c.getString(idIx) else null)?.takeUnless { it.isBlank() }
                val key = cid ?: "name:$name"
                if (!grouped.containsKey(key) && grouped.size >= limit) continue
                val typeLabel = runCatching {
                    val t = if (typeIx >= 0) c.getInt(typeIx) else 0
                    val custom = if (labelIx >= 0) c.getString(labelIx) else null
                    Phone.getTypeLabel(ctx.resources, t, custom ?: "").toString().trim()
                }.getOrDefault("")
                val display = if (typeLabel.isBlank()) number else "$number ($typeLabel)"
                val entry = grouped.getOrPut(key) { name to mutableListOf() }
                if (entry.second.none { it == display }) entry.second.add(display)
            }
        }
        if (grouped.isEmpty())
            return if (query != null) "no contacts match \"$query\"" else "no contacts found"
        val sb = StringBuilder()
        sb.append("${grouped.size} contact(s)")
        if (query != null) sb.append(" matching \"$query\"")
        sb.append(":\n")
        grouped.values.forEach { (name, numbers) ->
            sb.append("• $name: ${numbers.joinToString("; ")}\n")
        }
        return sb.toString().trim()
    }

    // ---- read_calendar ----
    private fun calendarRead(ctx: android.content.Context, args: kotlinx.serialization.json.JsonObject): String {
        val days = (args["days_ahead"]?.jsonPrimitive?.intOrNull ?: 7).coerceIn(1, 365)
        val limit = (args["limit"]?.jsonPrimitive?.intOrNull ?: 30).coerceIn(1, 500)
        val now = System.currentTimeMillis()
        val end = now + days.toLong() * 24L * 60L * 60L * 1000L
        // Instances is imported by simple name (see extraImports) — a Java class cannot be bound to a Kotlin `val`.
        // Instances requires the time window appended to CONTENT_URI as two path ids (begin, end).
        val builder = Instances.CONTENT_URI.buildUpon()
        android.content.ContentUris.appendId(builder, now)
        android.content.ContentUris.appendId(builder, end)
        val cols = arrayOf(
            Instances.TITLE, Instances.BEGIN, Instances.END,
            Instances.EVENT_LOCATION, Instances.ALL_DAY, Instances.CALENDAR_DISPLAY_NAME,
        )
        val cur = runCatching {
            ctx.contentResolver.query(builder.build(), cols, null, null, "${Instances.BEGIN} ASC")
        }.getOrNull() ?: return "calendar provider not accessible"
        val dtFmt = java.text.SimpleDateFormat("EEE MMM d, HH:mm", java.util.Locale.US)
        val timeFmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
        val dayFmt = java.text.SimpleDateFormat("EEE MMM d", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") } // all-day BEGIN is UTC midnight
        val sb = StringBuilder()
        var n = 0
        cur.use { c ->
            val titleIx = c.getColumnIndex(Instances.TITLE)
            val beginIx = c.getColumnIndex(Instances.BEGIN)
            val endIx = c.getColumnIndex(Instances.END)
            val locIx = c.getColumnIndex(Instances.EVENT_LOCATION)
            val allDayIx = c.getColumnIndex(Instances.ALL_DAY)
            val calIx = c.getColumnIndex(Instances.CALENDAR_DISPLAY_NAME)
            while (c.moveToNext() && n < limit) {
                val title = (if (titleIx >= 0) c.getString(titleIx) else null)?.trim()
                    ?.takeUnless { it.isBlank() } ?: "(no title)"
                val begin = if (beginIx >= 0) c.getLong(beginIx) else 0L
                val endMs = if (endIx >= 0) c.getLong(endIx) else 0L
                val allDay = allDayIx >= 0 && c.getInt(allDayIx) == 1
                val loc = (if (locIx >= 0) c.getString(locIx) else null)?.trim()
                val cal = (if (calIx >= 0) c.getString(calIx) else null)?.trim()
                val whenStr = if (allDay) {
                    dayFmt.format(java.util.Date(begin)) + " (all day)"
                } else {
                    val tail = if (endMs > begin) " – ${timeFmt.format(java.util.Date(endMs))}" else ""
                    dtFmt.format(java.util.Date(begin)) + tail
                }
                sb.append("• $title — $whenStr")
                if (!loc.isNullOrBlank()) sb.append(" @ $loc")
                if (!cal.isNullOrBlank()) sb.append(" [$cal]")
                sb.append("\n")
                n++
            }
        }
        return if (n == 0) "no upcoming events in the next $days day(s)"
        else "$n event(s) in the next $days day(s):\n${sb.toString().trim()}"
    }

    // ---- elevated_input ----
    private fun elevatedInput(ctx: android.content.Context, args: kotlinx.serialization.json.JsonObject): String {
        val action = args["action"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
            ?: throw ToolArgError("provide 'action': tap, swipe, text, or key")
        val x = args["x"]?.jsonPrimitive?.intOrNull
        val y = args["y"]?.jsonPrimitive?.intOrNull
        val x2 = args["x2"]?.jsonPrimitive?.intOrNull
        val y2 = args["y2"]?.jsonPrimitive?.intOrNull

        // Elevated.exec wraps the whole string in `sh -c`, so single-quote any token that
        // carries user data to pass it verbatim — no subshells, no word-splitting.
        fun sq(s: String): String = "'" + s.replace("'", "'\\''") + "'"

        val cmd: String = when (action) {
            "tap" -> {
                if (x == null || y == null) throw ToolArgError("tap needs integer 'x' and 'y'")
                "input tap $x $y"
            }
            "swipe" -> {
                if (x == null || y == null || x2 == null || y2 == null)
                    throw ToolArgError("swipe needs integer 'x', 'y', 'x2', and 'y2'")
                "input swipe $x $y $x2 $y2 300"
            }
            "text" -> {
                val t = args["text"]?.jsonPrimitive?.contentOrNull
                    ?: throw ToolArgError("text action needs a 'text' argument")
                if (t.isEmpty()) throw ToolArgError("text action needs a non-empty 'text' argument")
                // 'input text' word-splits on spaces and maps the literal %s back to a space,
                // so encode spaces as %s, then single-quote so every other shell metacharacter
                // ($, `, ;, &, |, (), quotes) is passed literally and cannot spawn a subshell.
                "input text ${sq(t.replace(" ", "%s"))}"
            }
            "key" -> {
                val raw = args["keycode"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?: throw ToolArgError("key action needs a 'keycode' (a number like 4, or a name like KEYCODE_BACK)")
                val key = raw.uppercase()
                val valid = key.matches(Regex("^\\d+$")) || key.matches(Regex("^KEYCODE_[A-Z0-9_]+$"))
                if (!valid) throw ToolArgError("invalid keycode '$raw' — use a numeric code (e.g. 4) or a KEYCODE_ name (e.g. KEYCODE_BACK)")
                "input keyevent $key"
            }
            else -> throw ToolArgError("unknown action '$action' — use tap, swipe, text, or key")
        }

        // 'input' is SILENT on success and prints failures (bad keycode, off-screen coords,
        // display errors) only to stderr — which the Shizuku backend (shizukuExec) does NOT
        // capture, so a failed injection would otherwise look like a silent success. Merge
        // stderr into stdout with 2>&1 and echo the exit code so failures are visible. The
        // root backend already merges stderr (redirectErrorStream), so this is compatible with both.
        val raw = Elevated.exec("$cmd 2>&1; echo __rc=\$?")
        val rcMatch = Regex("__rc=(-?\\d+)").findAll(raw).lastOrNull()
        val rc = rcMatch?.groupValues?.get(1)?.toIntOrNull()
        val out = (if (rcMatch != null) raw.substring(0, rcMatch.range.first) else raw).trim()

        return buildString {
            appendLine("ran (via ${Elevated.source()}): $cmd")
            when {
                rc == null -> append("result: ${if (out.isEmpty()) "(no output)" else out.take(4000)}")
                rc == 0 && out.isEmpty() -> append("result: ok (exit 0 — 'input' is silent on success)")
                rc == 0 -> append("result: exit 0\n${out.take(4000)}")
                else -> append("result: FAILED (exit $rc)\n${if (out.isEmpty()) "(no error text captured)" else out.take(4000)}")
            }
        }
    }

    // ---- set_volume ----
    private fun setVolume(ctx: android.content.Context, args: kotlinx.serialization.json.JsonObject): String {
        val am = ctx.getSystemService(android.content.Context.AUDIO_SERVICE) as? android.media.AudioManager
            ?: return "audio service unavailable"
        val streamName = args["stream"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase() ?: "music"
        val streamConst = when (streamName) {
            "music" -> android.media.AudioManager.STREAM_MUSIC
            "ring" -> android.media.AudioManager.STREAM_RING
            "alarm" -> android.media.AudioManager.STREAM_ALARM
            "notification" -> android.media.AudioManager.STREAM_NOTIFICATION
            "voice_call" -> android.media.AudioManager.STREAM_VOICE_CALL
            "system" -> android.media.AudioManager.STREAM_SYSTEM
            else -> throw ToolArgError("unknown stream '$streamName' — use music, ring, alarm, notification, voice_call, or system")
        }
        val level = args["level"]?.jsonPrimitive?.intOrNull
            ?: throw ToolArgError("provide an integer 'level'")
        val showUi = args["show_ui"]?.jsonPrimitive?.booleanOrNull ?: false
        val max = runCatching { am.getStreamMaxVolume(streamConst) }.getOrDefault(-1)
        if (max < 0) throw ToolExecError("could not read the max volume for the $streamName stream")
        val old = runCatching { am.getStreamVolume(streamConst) }.getOrDefault(-1)
        val target = level.coerceIn(0, max)
        val flags = if (showUi) android.media.AudioManager.FLAG_SHOW_UI else 0
        return try {
            am.setStreamVolume(streamConst, target, flags)
            val now = runCatching { am.getStreamVolume(streamConst) }.getOrDefault(target)
            buildString {
                append("$streamName volume: ${if (old < 0) "?" else old.toString()} -> $now (max $max)")
                if (target != level) append("; requested $level clamped to 0..$max")
                if (now != target) append("; system settled on $now (a DND/ringer policy, ring/notification coupling, or a fixed-volume output such as some Bluetooth/HDMI sinks can override the request)")
            }
        } catch (t: SecurityException) {
            throw ToolExecError("could not set $streamName volume: ${t.message ?: "Notification Policy access required"} — this usually means Do Not Disturb is active; changing ring/notification volume (or dropping it to 0) while DND is on needs Notification Policy (DND) access")
        } catch (t: Throwable) {
            throw ToolExecError("could not set $streamName volume: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    // ---- media_control ----
    private fun mediaControl(ctx: android.content.Context, args: kotlinx.serialization.json.JsonObject): String {
        val am = ctx.getSystemService(android.content.Context.AUDIO_SERVICE) as? android.media.AudioManager
            ?: return "audio service unavailable"
        val action = args["action"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
            ?: throw ToolArgError("provide an 'action': play, pause, playpause, next, previous, or stop")
        val keyCode = when (action) {
            "play" -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> android.view.KeyEvent.KEYCODE_MEDIA_PAUSE
            "playpause" -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "next" -> android.view.KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop" -> android.view.KeyEvent.KEYCODE_MEDIA_STOP
            else -> throw ToolArgError("unknown action '$action' — use play, pause, playpause, next, previous, or stop")
        }
        // dispatchMediaKeyEvent returns void and never reports whether an app consumed the key,
        // so isMusicActive is the only cheap hint about whether a media session is likely present.
        val musicActive = runCatching { am.isMusicActive }.getOrDefault(false)
        // A media key needs a DOWN then an UP; some receivers ignore zero-timestamp events, so use
        // the 5-arg KeyEvent constructor and stamp downTime/eventTime from SystemClock.uptimeMillis().
        val ts = android.os.SystemClock.uptimeMillis()
        return try {
            am.dispatchMediaKeyEvent(android.view.KeyEvent(ts, ts, android.view.KeyEvent.ACTION_DOWN, keyCode, 0))
            am.dispatchMediaKeyEvent(android.view.KeyEvent(ts, ts, android.view.KeyEvent.ACTION_UP, keyCode, 0))
            buildString {
                appendLine("sent media key: $action (${android.view.KeyEvent.keyCodeToString(keyCode)})")
                append(
                    if (musicActive)
                        "a media session appears active (audio is playing) — the key should have reached it"
                    else
                        "no active audio detected — if no app holds a media session, the key may go nowhere (dispatch is fire-and-forget and reports no target)"
                )
            }
        } catch (t: Throwable) {
            throw ToolExecError("could not dispatch media key '$action': ${t.message ?: t.javaClass.simpleName}")
        }
    }

    // ---- toast ----
    private fun toast(ctx: android.content.Context, args: kotlinx.serialization.json.JsonObject): String {
        val text = args["text"]?.jsonPrimitive?.contentOrNull
            ?: throw ToolArgError("provide a 'text' to show")
        if (text.isEmpty()) throw ToolArgError("provide a non-empty 'text' to show")
        val long = args["long"]?.jsonPrimitive?.booleanOrNull ?: false
        // Toast.makeText()/show() must run on a thread with a Looper; this runner is on the IO
        // thread, so post to the main looper. Use applicationContext so the toast survives the
        // runner returning. Text toasts are still allowed from the background on API 30+ (only
        // custom-view toasts are blocked), so this works whether or not androidmcp is foreground.
        // runCatching INSIDE the posted runnable: this runner returns BEFORE the runnable runs, so
        // an exception here would land uncaught on the MAIN thread and crash the whole app; swallow
        // it instead (the confirmation string is already best-effort, see gotchas).
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            runCatching {
                android.widget.Toast.makeText(
                    ctx.applicationContext,
                    text,
                    if (long) android.widget.Toast.LENGTH_LONG else android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
        }
        return "showed a ${if (long) "long" else "short"} toast: \"${text.take(200)}\""
    }

    // ---- speak (text-to-speech) ----
    private fun speak(ctx: android.content.Context, args: kotlinx.serialization.json.JsonObject): String {
        val text = args["text"]?.jsonPrimitive?.contentOrNull?.takeUnless { it.isBlank() }
            ?: throw ToolArgError("provide a non-empty 'text' to speak")
        val clipped = text.take(2000)
        Tts.speak(ctx, clipped)?.let { throw ToolExecError("could not speak: $it") }
        return "spoke ${clipped.length} characters aloud through the device speaker" +
            if (clipped.length < text.length) " (truncated from ${text.length})" else ""
    }

    // ---- share_text ----
    private fun shareText(ctx: android.content.Context, args: kotlinx.serialization.json.JsonObject): String {
        val text = args["text"]?.jsonPrimitive?.contentOrNull
            ?: throw ToolArgError("provide 'text' to share")
        if (text.isEmpty()) throw ToolArgError("provide non-empty 'text' to share")
        val subject = args["subject"]?.jsonPrimitive?.contentOrNull?.trim()?.takeUnless { it.isBlank() }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            if (subject != null) putExtra(Intent.EXTRA_SUBJECT, subject)
        }
        // createChooser is resolved by the system, so it works without QUERY_ALL_PACKAGES
        // package-visibility even on API 30+. NEW_TASK is required to start an activity from a
        // non-activity (Service/Application) context.
        val chooser = Intent.createChooser(send, subject ?: "Share")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(chooser)
            buildString {
                append("opened the share sheet with ${text.length} char(s) of text")
                if (subject != null) append(" (subject: \"$subject\")")
                append(" — note: Android may block launching the chooser while androidmcp is in the background")
            }
        } catch (t: android.content.ActivityNotFoundException) {
            "no app can handle sharing text on this device"
        } catch (t: Throwable) {
            "could not open the share sheet: ${t.message}"
        }
    }

    // ---- open_settings ----
    private fun openSettings(ctx: android.content.Context, args: kotlinx.serialization.json.JsonObject): String {
        val screen = args["screen"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
            ?.takeUnless { it.isBlank() } ?: "apps"
        // app_details needs the target package as intent data; everything else is action-only.
        var data: android.net.Uri? = null
        val action = when (screen) {
            "wifi" -> android.provider.Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> android.provider.Settings.ACTION_BLUETOOTH_SETTINGS
            "location" -> android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "display" -> android.provider.Settings.ACTION_DISPLAY_SETTINGS
            "sound" -> android.provider.Settings.ACTION_SOUND_SETTINGS
            "apps" -> android.provider.Settings.ACTION_APPLICATION_SETTINGS
            "app_details" -> {
                data = android.net.Uri.fromParts("package", ctx.packageName, null)
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
            }
            "battery" -> android.provider.Settings.ACTION_BATTERY_SAVER_SETTINGS
            "date" -> android.provider.Settings.ACTION_DATE_SETTINGS
            "security" -> android.provider.Settings.ACTION_SECURITY_SETTINGS
            "home" -> android.provider.Settings.ACTION_HOME_SETTINGS
            else -> throw ToolArgError("unknown screen '$screen' — use one of: wifi, bluetooth, " +
                "location, display, sound, apps, app_details, battery, date, security, home")
        }
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (data != null) intent.data = data
        return try {
            ctx.startActivity(intent)
            "opened the '$screen' settings screen (note: Android may block launching activities while androidmcp is in the background)"
        } catch (t: android.content.ActivityNotFoundException) {
            // Some OEMs/tablets lack a dedicated screen for a given action — fall back to the top-level Settings app.
            try {
                ctx.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "no dedicated '$screen' screen on this device — opened the main Settings app instead"
            } catch (t2: Throwable) {
                throw ToolExecError("could not open settings: ${t2.message}")
            }
        } catch (t: Throwable) {
            throw ToolExecError("could not open the '$screen' settings screen: ${t.message}")
        }
    }

    // ---- create_calendar_event ----
    private fun createCalendarEvent(ctx: android.content.Context, args: kotlinx.serialization.json.JsonObject): String {
        val title = args["title"]?.jsonPrimitive?.contentOrNull?.trim()?.takeUnless { it.isBlank() }
            ?: throw ToolArgError("provide a non-empty 'title' for the event")
        // Epoch millis (~1.7e12) overflow a 32-bit Int, so read start as Long even though the schema
        // type is "integer" (MCP has no separate long type). intOrNull would silently return null here.
        val start = args["start_epoch_ms"]?.jsonPrimitive?.longOrNull
            ?: (System.currentTimeMillis() + 60L * 60L * 1000L) // default: now + 1h
        val durationMin = (args["duration_minutes"]?.jsonPrimitive?.intOrNull ?: 60).coerceIn(1, 60 * 24 * 30)
        val end = start + durationMin.toLong() * 60L * 1000L
        val location = args["location"]?.jsonPrimitive?.contentOrNull?.trim()?.takeUnless { it.isBlank() }
        val requestedCalId = args["calendar_id"]?.jsonPrimitive?.longOrNull

        // Resolve the target calendar (and, for the auto-pick, its display name). With no explicit
        // calendar_id, pick the lowest-id calendar the app may write to (CALENDAR_ACCESS_LEVEL >=
        // CAL_ACCESS_CONTRIBUTOR). Those access constants are compile-time ints, so inlining them into
        // the selection carries no injection risk and sidesteps SQLite text/integer affinity on a bound
        // arg. Enumerating Calendars is a READ on the provider, so this tool declares READ_CALENDAR too.
        var calName: String? = null
        val calendarId: Long = if (requestedCalId != null) {
            requestedCalId
        } else {
            val projection = arrayOf(
                android.provider.CalendarContract.Calendars._ID,
                android.provider.CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            )
            val sel = "${android.provider.CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= " +
                "${android.provider.CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}"
            val order = "${android.provider.CalendarContract.Calendars._ID} ASC"
            val cur = runCatching {
                ctx.contentResolver.query(
                    android.provider.CalendarContract.Calendars.CONTENT_URI, projection, sel, null, order)
            }.getOrNull()
                ?: throw ToolExecError("could not enumerate calendars - this needs READ_CALENDAR granted, or pass an explicit 'calendar_id'")
            var found = -1L
            cur.use { c ->
                val idIx = c.getColumnIndex(android.provider.CalendarContract.Calendars._ID)
                val nameIx = c.getColumnIndex(android.provider.CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
                if (idIx >= 0 && c.moveToFirst()) {
                    found = c.getLong(idIx)
                    if (nameIx >= 0 && !c.isNull(nameIx)) calName = c.getString(nameIx)
                }
            }
            if (found < 0L)
                throw ToolExecError("no writable calendar found on this device - add an account with a writable calendar, or pass an explicit 'calendar_id'")
            found
        }

        val tz = java.util.TimeZone.getDefault().id
        val values = android.content.ContentValues().apply {
            put(android.provider.CalendarContract.Events.DTSTART, start)
            put(android.provider.CalendarContract.Events.DTEND, end)
            put(android.provider.CalendarContract.Events.TITLE, title)
            if (location != null) put(android.provider.CalendarContract.Events.EVENT_LOCATION, location)
            put(android.provider.CalendarContract.Events.CALENDAR_ID, calendarId)
            put(android.provider.CalendarContract.Events.EVENT_TIMEZONE, tz)
        }

        val uri = try {
            ctx.contentResolver.insert(android.provider.CalendarContract.Events.CONTENT_URI, values)
        } catch (t: Throwable) {
            throw ToolExecError("could not create event: ${t.message} (calendar_id $calendarId may not be writable, or WRITE_CALENDAR is not granted)")
        } ?: throw ToolExecError("insert returned no URI - calendar_id $calendarId may be invalid or not writable")

        val newId = uri.lastPathSegment ?: "?"
        val fmt = java.text.SimpleDateFormat("EEE MMM d yyyy, HH:mm", java.util.Locale.US)
        return buildString {
            appendLine("created event #$newId")
            appendLine("title: $title")
            appendLine("when: ${fmt.format(java.util.Date(start))} - ${fmt.format(java.util.Date(end))} ($durationMin min, tz $tz)")
            if (location != null) appendLine("location: $location")
            append("calendar_id: $calendarId")
            if (calName != null) append(" ($calName)")
        }.trim()
    }

    // ---- elevated_current_app ----
    private fun elevatedCurrentApp(ctx: android.content.Context): String {
        // Matches a `package/activity` component in dumpsys output. Package is a dotted id
        // (needs at least one dot, so bare `foo/bar` tokens and file paths do not match);
        // activity may be shorthand (".Foo", relative to the package) or fully-qualified and may
        // contain `$` for nested classes. The `$` is written as \$ so Kotlin does not treat it as
        // a string template.
        val comp = Regex("([a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z0-9_]+)+)/(\\.?[a-zA-Z0-9_.\$]+)")

        fun parse(raw: String): Triple<String, String, String>? {
            for (line in raw.split("\n").map { it.trim() }.filter { it.isNotEmpty() }) {
                val m = comp.find(line) ?: continue
                val pkg = m.groupValues[1]
                var act = m.groupValues[2]
                if (act.startsWith(".")) act = pkg + act
                return Triple(pkg, act, line)
            }
            return null
        }

        // 1) The activity manager's resumed activity is the authoritative "foreground app".
        //    stderr -> /dev/null so a dumpsys warning (which the root backend folds into stdout
        //    via redirectErrorStream) cannot leak into the grep input or the raw fallback.
        var used = "dumpsys activity activities 2>/dev/null | grep -E 'mResumedActivity|topResumedActivity|ResumedActivity'"
        var raw = Elevated.exec(used)
        var parsed = parse(raw)

        // 2) Fall back to the window manager's focused window/app if that yielded nothing.
        if (parsed == null) {
            used = "dumpsys window 2>/dev/null | grep -E 'mCurrentFocus|mFocusedApp'"
            raw = Elevated.exec(used)
            parsed = parse(raw)
        }

        return buildString {
            appendLine("source: ${Elevated.source()}")
            appendLine("command: $used")
            val p = parsed
            if (p != null) {
                appendLine("package: ${p.first}")
                appendLine("activity: ${p.second}")
                append("matched line: ${p.third.take(500)}")
            } else {
                appendLine("package: (could not parse)")
                appendLine("activity: (could not parse)")
                val trimmed = raw.trim()
                append("raw output: " + if (trimmed.isEmpty())
                    "(no output — screen may be locked/off, no foreground app, or the elevated backend blocked dumpsys)"
                    else trimmed.take(1500))
            }
        }
    }

    // ---- elevated_settings ----
    private fun elevatedSettings(ctx: android.content.Context, args: kotlinx.serialization.json.JsonObject): String {
        val action = args["action"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
            ?: throw ToolArgError("provide 'action': get or put")
        val ns = args["namespace"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
            ?: throw ToolArgError("provide 'namespace': system, secure, or global")
        if (ns != "system" && ns != "secure" && ns != "global")
            throw ToolArgError("invalid namespace '$ns' — use system, secure, or global")
        val key = args["key"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: throw ToolArgError("provide a 'key'")
        if (key.isEmpty()) throw ToolArgError("provide a non-empty 'key'")

        // Elevated.exec wraps the whole string in `sh -c`, so single-quote every token that
        // carries user data to pass it verbatim (no word-splitting, no subshells).
        fun sq(s: String): String = "'" + s.replace("'", "'\\''") + "'"

        // `settings` is silent on a successful put and reports failures (unknown table, bad value,
        // denied write) only on stderr — which the Shizuku backend does NOT capture, so a failed
        // write would otherwise look like a silent success. Merge stderr into stdout and echo the
        // exit code, then split them off. lastOrNull() picks the appended marker even if the value
        // itself contains "__rc=". The root backend already merges stderr, so this works on both.
        fun runRc(cmd: String): Pair<Int?, String> {
            val raw = Elevated.exec("$cmd 2>&1; echo __rc=\$?")
            val m = Regex("__rc=(-?\\d+)").findAll(raw).lastOrNull()
            val rc = m?.groupValues?.get(1)?.toIntOrNull()
            val out = (if (m != null) raw.substring(0, m.range.first) else raw).trim()
            return rc to out
        }

        return when (action) {
            "get" -> {
                val cmd = "settings get $ns ${sq(key)}"
                val (rc, out) = runRc(cmd)
                buildString {
                    appendLine("source: ${Elevated.source()}")
                    appendLine("ran: $cmd")
                    when {
                        rc != null && rc != 0 ->
                            append("error: 'settings get' exited $rc\n" +
                                (if (out.isEmpty()) "(no error text captured)" else out.take(4000)))
                        out.isEmpty() -> append("value: (empty)")
                        out == "null" -> append("value: null  (unset, or the literal string \"null\" — 'settings get' cannot distinguish)")
                        else -> append("value: ${out.take(4000)}")
                    }
                }
            }
            "put" -> {
                val value = args["value"]?.jsonPrimitive?.contentOrNull
                    ?: throw ToolArgError("put needs a 'value'")
                val cmd = "settings put $ns ${sq(key)} ${sq(value)}"
                val (rc, out) = runRc(cmd)
                // Read the value back so the caller sees the effective, stored result.
                val after = runRc("settings get $ns ${sq(key)}").second
                buildString {
                    appendLine("source: ${Elevated.source()}")
                    appendLine("ran: $cmd")
                    if (rc != null && rc != 0)
                        appendLine("write exited $rc (non-zero — the put may have been rejected)")
                    if (out.isNotEmpty()) appendLine("output: ${out.take(2000)}")
                    appendLine("read-back ($ns/$key): " + when {
                        after.isEmpty() -> "(empty)"
                        after == "null" -> "null (unset, or the literal string \"null\")"
                        else -> after.take(2000)
                    })
                    append(when {
                        after == value.trim() ->
                            "confirmed: setting now equals the requested value"
                        after == "null" ->
                            "note: read-back is 'null' — the write did not take effect (unknown key/table, denied, or coerced)"
                        else ->
                            "note: read-back \"$after\" does not equal the requested \"$value\" — the write may have been coerced, rejected, or stored differently"
                    })
                }
            }
            else -> throw ToolArgError("unknown action '$action' — use get or put")
        }
    }

    private fun rootScreenshot(): List<JsonObject> {
        val png = Elevated.execBytes("screencap -p")
        // Reachable whenever execBytes gives up — including, since the drain fix, when the PNG
        // exceeded the 1 MB output cap and would otherwise have been served half-written.
        if (png == null || png.isEmpty()) throw ToolExecError("silent screencap failed or returned no data")
        return listOf(textBlk("silent screenshot (${png.size} bytes, via ${Elevated.source()})")) +
            mediaBlocks(png, "image/png", "screen.png", isImage = true)
    }

    private fun rootShell(args: JsonObject): String {
        val cmd = args["command"]?.jsonPrimitive?.contentOrNull ?: throw ToolArgError("provide a 'command' to run")
        return Elevated.exec(cmd).ifBlank { "(no output)" }.take(20000)
    }

    private fun runShortcut(ctx: Context, args: JsonObject): String {
        val pkg = args["package"]?.jsonPrimitive?.contentOrNull
            ?: throw ToolArgError("provide a 'package' to launch (e.g. com.android.settings)")
        val intent = ctx.packageManager.getLaunchIntentForPackage(pkg)
            ?: throw ToolExecError("app not installed or not launchable: $pkg")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(intent)
            "launched $pkg (note: Android may block launching apps while androidmcp is in the background)"
        } catch (t: Throwable) {
            throw ToolExecError("could not launch $pkg: ${t.message}")
        }
    }
}
