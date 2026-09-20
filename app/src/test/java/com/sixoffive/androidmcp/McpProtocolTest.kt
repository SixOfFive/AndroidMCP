package com.sixoffive.androidmcp

import android.content.Context
import com.sixoffive.androidmcp.server.Mcp
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import org.mockito.Mockito
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * JSON-RPC / MCP envelope conformance.
 *
 * These exercise only the paths that never touch Android: the envelope, dispatch, version
 * negotiation, and the unknown-tool branch. Anything that reaches a real capability needs a
 * device and stays in the manual on-device checks.
 *
 * The mocked [Context] is never dereferenced on these paths — if a change starts touching it,
 * these tests fail loudly rather than silently exercising a stub.
 */
class McpProtocolTest {

    private val ctx: Context = Mockito.mock(Context::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    private fun call(body: String, header: String? = null): Mcp.Reply =
        runBlocking { Mcp.handle(ctx, body, "test-client", header) }

    private fun bodyOf(reply: Mcp.Reply): JsonObject = when (reply) {
        is Mcp.Reply.Body -> json.parseToJsonElement(reply.json).jsonObject
        is Mcp.Reply.Rejected -> json.parseToJsonElement(reply.json).jsonObject
        Mcp.Reply.None -> error("expected a body, got a notification")
        is Mcp.Reply.Streamed -> error("expected a body, got a stream")
    }

    private fun errorCode(reply: Mcp.Reply): Int =
        bodyOf(reply)["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt()

    // ---- notifications (JSON-RPC 2.0 §4.1: a notification MUST NOT be answered) ----

    @Test
    fun `known notification is not answered`() {
        assertIs<Mcp.Reply.None>(call("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""))
    }

    @Test
    fun `unknown notification is not answered`() {
        // The regression that motivated this: every roots-capable client sends
        // notifications/roots/list_changed right after initialize. It used to come back as a
        // -32601 error carrying "id": null — a response to a message that forbids one.
        assertIs<Mcp.Reply.None>(call("""{"jsonrpc":"2.0","method":"notifications/roots/list_changed"}"""))
        assertIs<Mcp.Reply.None>(call("""{"jsonrpc":"2.0","method":"notifications/progress","params":{"progressToken":1}}"""))
        assertIs<Mcp.Reply.None>(call("""{"jsonrpc":"2.0","method":"totally/unknown"}"""))
    }

    @Test
    fun `a request with an id IS answered`() {
        assertIs<Mcp.Reply.Body>(call("""{"jsonrpc":"2.0","id":1,"method":"ping"}"""))
    }

    // ---- malformed envelopes reject at the HTTP layer, and never invent an id ----

    @Test
    fun `unparseable body is a 400 parse error with no id key`() {
        val r = call("""{"broken""")
        assertIs<Mcp.Reply.Rejected>(r)
        assertEquals(400, r.status)
        assertEquals(-32700, errorCode(r))
        assertFalse("id" in bodyOf(r), "id must be omitted, not null: RequestId is string | number")
    }

    @Test
    fun `top-level array is rejected — 2025-06-18 removed batching`() {
        val r = call("""[{"jsonrpc":"2.0","id":1,"method":"ping"}]""")
        assertIs<Mcp.Reply.Rejected>(r)
        assertEquals(400, r.status)
        assertEquals(-32600, errorCode(r))
        assertFalse("id" in bodyOf(r))
    }

    @Test
    fun `an id of the wrong JSON type is rejected`() {
        val r = call("""{"jsonrpc":"2.0","id":{"nested":true},"method":"ping"}""")
        assertIs<Mcp.Reply.Rejected>(r)
        assertEquals(-32600, errorCode(r))
    }

    @Test
    fun `a non-string method does not become an HTTP 500`() {
        // `.jsonPrimitive` on an object throws; that used to escape as a 500 with no id to
        // correlate. It must be a JSON-RPC error instead.
        val r = call("""{"jsonrpc":"2.0","id":1,"method":{"a":1}}""")
        assertEquals(-32600, errorCode(r))
    }

    // ---- ids are echoed with their original JSON type ----

    @Test
    fun `id type is preserved`() {
        assertEquals(
            JsonPrimitive(7),
            bodyOf(call("""{"jsonrpc":"2.0","id":7,"method":"ping"}"""))["id"],
        )
        val strId = bodyOf(call("""{"jsonrpc":"2.0","id":"abc","method":"ping"}"""))["id"]
        assertEquals("abc", strId!!.jsonPrimitive.content)
        assertTrue(strId.jsonPrimitive.isString, "a string id must stay a string")
    }

    // ---- initialize: real version negotiation ----

    @Test
    fun `initialize echoes a supported version the client asked for`() {
        val r = bodyOf(call(
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}"""
        ))
        assertEquals("2025-06-18", r["result"]!!.jsonObject["protocolVersion"]!!.jsonPrimitive.content)
    }

    @Test
    fun `initialize answers with its own version when the client asks for one it cannot speak`() {
        // The old code hardcoded its reply, so this looked identical — but for the wrong reason:
        // it would also have "agreed" to a version it does not implement.
        val r = bodyOf(call(
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05"}}"""
        ))
        val agreed = r["result"]!!.jsonObject["protocolVersion"]!!.jsonPrimitive.content
        assertEquals(Mcp.PROTOCOL, agreed)
        assertTrue(agreed in Mcp.SUPPORTED)
    }

    @Test
    fun `initialize declares only what is actually implemented`() {
        val result = bodyOf(call("""{"jsonrpc":"2.0","id":1,"method":"initialize"}"""))["result"]!!.jsonObject
        val caps = result["capabilities"]!!.jsonObject
        assertTrue("tools" in caps)
        // `resources` is declared now that resources/read serves the resource_link media.
        assertTrue("resources" in caps)
        // No SSE stream exists (GET /mcp is a 405), so listChanged would be a false promise on
        // either, and there is still no prompts/logging implementation to advertise.
        assertFalse("listChanged" in caps["tools"]!!.jsonObject)
        assertFalse("listChanged" in caps["resources"]!!.jsonObject)
        assertFalse("prompts" in caps)
        assertFalse("logging" in caps)
        val info = result["serverInfo"]!!.jsonObject
        assertEquals("androidmcp", info["name"]!!.jsonPrimitive.content)
        assertNotNull(info["title"])
        // serverInfo.version must track the built app version, never a hardcoded literal — a
        // stale "0.1.0" once shipped in a 0.3.0 build. Pinned to BuildConfig so a regression fails.
        assertEquals(BuildConfig.VERSION_NAME, info["version"]!!.jsonPrimitive.content)
        // Clients surface `instructions` to the model; it carries the default-deny contract.
        assertTrue(result["instructions"]!!.jsonPrimitive.content.contains("default-deny", ignoreCase = true))
    }

    // ---- method dispatch ----

    @Test
    fun `unknown method with an id is method-not-found`() {
        // resources/read is implemented now, so pick a method this server genuinely lacks.
        assertEquals(-32601, errorCode(call("""{"jsonrpc":"2.0","id":1,"method":"prompts/list"}""")))
        assertEquals(-32601, errorCode(call("""{"jsonrpc":"2.0","id":1,"method":"completion/complete"}""")))
    }

    @Test
    fun `ping returns an empty result object`() {
        val r = bodyOf(call("""{"jsonrpc":"2.0","id":1,"method":"ping"}"""))
        assertEquals(0, r["result"]!!.jsonObject.size)
    }

    // ---- tools/call argument validation ----

    @Test
    fun `unknown tool is a protocol error, not a tool result`() {
        // The tool never ran, so there is no execution outcome to report as isError. A result
        // envelope here also let a hallucinated tool name look like a normal refusal.
        val r = call("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"no_such_tool"}}""")
        val b = bodyOf(r)
        assertEquals(-32602, errorCode(r))
        assertNull(b["result"])
    }

    @Test
    fun `tools_call with a non-string name is invalid params`() {
        assertEquals(-32602, errorCode(
            call("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":{"a":1}}}""")
        ))
    }

    @Test
    fun `tools_call with no params is invalid params`() {
        assertEquals(-32602, errorCode(call("""{"jsonrpc":"2.0","id":1,"method":"tools/call"}""")))
    }

    @Test
    fun `tools_call with non-object arguments is rejected, not silently defaulted`() {
        // Previously `arguments` that was not an object was replaced with {} — every argument
        // vanished and the tool ran on defaults, burning a user approval on the wrong action.
        assertEquals(-32602, errorCode(call(
            """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"torch","arguments":"on"}}"""
        )))
    }

    // ---- resources (so resource_link media is dereferenceable through the protocol) ----

    @Test
    fun `the resources capability is declared`() {
        val caps = bodyOf(call("""{"jsonrpc":"2.0","id":1,"method":"initialize"}"""))["result"]!!
            .jsonObject["capabilities"]!!.jsonObject
        assertTrue("resources" in caps, "resource_link is emitted, so resources must be declared")
        // Still no listChanged on either: GET /mcp is a 405, so there is no channel to push it on.
        assertFalse("listChanged" in caps["resources"]!!.jsonObject)
    }

    @Test
    fun `resources_list is empty — media is transient, not enumerable`() {
        val r = bodyOf(call("""{"jsonrpc":"2.0","id":1,"method":"resources/list"}"""))
        assertEquals(0, r["result"]!!.jsonObject["resources"]!!.jsonArray.size)
    }

    @Test
    fun `resources_read rejects a URI this server did not mint`() {
        for (uri in listOf("file:///etc/passwd", "http://evil.example/x", "http://h/media/abc")) {
            val r = call("""{"jsonrpc":"2.0","id":1,"method":"resources/read","params":{"uri":"$uri"}}""")
            assertEquals(-32602, errorCode(r), "should refuse $uri")
        }
    }

    @Test
    fun `resources_read on an expired or already-fetched link is -32002`() {
        // -32002 is the spec's "resource not found"; single-use consumption and TTL expiry both
        // land here, and the message says which so a client does not retry forever.
        val r = call("""{"jsonrpc":"2.0","id":1,"method":"resources/read","params":{"uri":"http://h:1/media/deadbeef?k=nope"}}""")
        assertEquals(-32002, errorCode(r))
    }

    @Test
    fun `resources_read returns the blob for a live link, exactly once`() {
        val (mid, nonce) = com.sixoffive.androidmcp.server.MediaStore.put("PHOTOBYTES".toByteArray(), "image/jpeg")
        val uri = "http://192.168.1.5:8765/media/$mid?k=$nonce"
        val ok = bodyOf(call("""{"jsonrpc":"2.0","id":1,"method":"resources/read","params":{"uri":"$uri"}}"""))
        val c = ok["result"]!!.jsonObject["contents"]!!.jsonArray[0].jsonObject
        assertEquals(uri, c["uri"]!!.jsonPrimitive.content)
        assertEquals("image/jpeg", c["mimeType"]!!.jsonPrimitive.content)
        assertEquals("PHOTOBYTES", String(java.util.Base64.getDecoder().decode(c["blob"]!!.jsonPrimitive.content)))
        // Single-use: reading through the protocol consumes the same entry the HTTP route serves.
        assertEquals(-32002, errorCode(call("""{"jsonrpc":"2.0","id":2,"method":"resources/read","params":{"uri":"$uri"}}""")))
    }

    @Test
    fun `media URI parsing accepts only this server's link shape`() {
        val P = com.sixoffive.androidmcp.server.Mcp::parseMediaUri
        assertNotNull(P("http://h:8765/media/abc?k=xyz"))
        assertEquals("abc" to "xyz", P("https://1.2.3.4:8765/media/abc?k=xyz"))
        assertNull(P("http://h:8765/media/abc"))            // no nonce
        assertNull(P("http://h:8765/media/abc?j=xyz"))      // wrong param
        assertNull(P("http://h:8765/other/abc?k=xyz"))      // wrong path
        assertNull(P("http://h:8765/media/a/b?k=xyz"))      // id must be one segment
    }

    // ---- cancellation ----

    @Test
    fun `notifications_cancelled is still never answered`() {
        // It now has a side effect (withdrawing a pending approval), which must not turn it into
        // something the server replies to.
        assertIs<Mcp.Reply.None>(call("""{"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":"7"}}"""))
        assertIs<Mcp.Reply.None>(call("""{"jsonrpc":"2.0","method":"notifications/cancelled"}"""))
    }

    // ---- MCP-Protocol-Version header ----

    @Test
    fun `an unsupported version header rejects an ordinary request`() {
        val r = call("""{"jsonrpc":"2.0","id":1,"method":"ping"}""", header = "2099-01-01")
        assertIs<Mcp.Reply.Rejected>(r)
        assertEquals(400, r.status)
        assertTrue(Mcp.PROTOCOL in r.json)
    }

    @Test
    fun `an unsupported version header does NOT block initialize`() {
        // initialize is the one request whose job is to resolve a version mismatch — the
        // negotiation is in the body, so refusing it at the HTTP layer breaks the very mechanism
        // designed to fix this. Observed on the wire, Claude Code 2.1.251 sends no header on
        // initialize; a client that did would otherwise have been locked out entirely.
        val r = call(
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25"}}""",
            header = "2026-07-28",
        )
        assertIs<Mcp.Reply.Body>(r)
        assertEquals(
            Mcp.PROTOCOL,
            bodyOf(r)["result"]!!.jsonObject["protocolVersion"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `a future-era discover probe is refused so a dual-era client falls back`() {
        // Claude Code 2.1.251 probes `server/discover` with a future version header before trying
        // initialize, and relies on a 4xx to fall back. Answering 200 with a JSON-RPC error, or a
        // 404, would change how that fallback behaves — this pins the observed-good shape.
        val r = call(
            """{"jsonrpc":"2.0","id":"server-discover-probe-1","method":"server/discover"}""",
            header = "2026-07-28",
        )
        assertIs<Mcp.Reply.Rejected>(r)
        assertEquals(400, r.status)
    }

    @Test
    fun `a supported version header is accepted, and an absent one is fine`() {
        assertIs<Mcp.Reply.Body>(call("""{"jsonrpc":"2.0","id":1,"method":"ping"}""", header = Mcp.PROTOCOL))
        assertIs<Mcp.Reply.Body>(call("""{"jsonrpc":"2.0","id":1,"method":"ping"}"""))
    }

    @Test
    fun `an approval key is scoped to the client and the id's JSON type`() {
        val AM = com.sixoffive.androidmcp.server.ApprovalManager
        // notifications/cancelled is neither authenticated against the pending request nor
        // rate-limited, so keyed on the bare id one client could spray cancellations over ids
        // 1..100 and deny every other client's pending approvals.
        assertTrue(AM.rpcKey("laptop", "7", numeric = true) != AM.rpcKey("phone", "7", numeric = true))
        // ...and JSON-RPC treats numeric 7 and string "7" as different ids.
        assertTrue(AM.rpcKey("laptop", "7", numeric = true) != AM.rpcKey("laptop", "7", numeric = false))
        assertEquals(AM.rpcKey("laptop", "7", numeric = true), AM.rpcKey("laptop", "7", numeric = true))
        // A client name cannot be crafted to collide with another client's key.
        assertTrue(AM.rpcKey("a", "b", numeric = false) != AM.rpcKey("a\u0000s:b", "", numeric = false))
    }

    @Test
    fun `cancelling an id nobody is waiting on is a no-op`() {
        val AM = com.sixoffive.androidmcp.server.ApprovalManager
        assertFalse(AM.cancelByRpcId(AM.rpcKey("nobody", "999", numeric = true)))
    }

    @Test
    fun `unsupported protocol version rejection names what is supported`() {
        val b = json.parseToJsonElement(Mcp.unsupportedProtocolVersion("2099-01-01")).jsonObject
        assertFalse("id" in b)
        val err = b["error"]!!.jsonObject
        assertEquals(-32600, err["code"]!!.jsonPrimitive.content.toInt())
        assertTrue(err["data"]!!.jsonObject["supported"].toString().contains(Mcp.PROTOCOL))
    }
}
