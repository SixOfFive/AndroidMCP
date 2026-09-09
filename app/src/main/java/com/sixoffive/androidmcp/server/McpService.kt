package com.sixoffive.androidmcp.server

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.app.Notification
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.sixoffive.androidmcp.R
import com.sixoffive.androidmcp.core.AuditLog
import com.sixoffive.androidmcp.core.ConfigStore
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.applicationEngineEnvironment
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.request.contentLength
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.options
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.flow.MutableStateFlow

class McpService : Service() {

    private var engine: ApplicationEngine? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundCompat(buildNotification("starting…"), mediaProjection = false)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PROJECT) {
            handleProjection(intent)
            return START_STICKY
        }
        if (engine == null) startServer()
        return START_STICKY
    }

    private fun startForegroundCompat(n: Notification, mediaProjection: Boolean) {
        if (Build.VERSION.SDK_INT >= 29) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            if (mediaProjection) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            startForeground(NOTIF_ID, n, type)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun handleProjection(intent: Intent) {
        // Android requires a mediaProjection-typed foreground service to be running
        // BEFORE getMediaProjection() — so (re)assert foreground with that type here.
        startForegroundCompat(buildNotification("screen sharing active"), mediaProjection = true)
        val code = intent.getIntExtra("code", Int.MIN_VALUE)
        @Suppress("DEPRECATION")
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra("data", Intent::class.java)
        else intent.getParcelableExtra("data")
        if (code == Int.MIN_VALUE || data == null) return
        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val proj = mpm.getMediaProjection(code, data)
            proj.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { ProjectionHolder.set(null) }
            }, Handler(Looper.getMainLooper()))
            ProjectionHolder.set(proj)
        } catch (t: Throwable) {
            Log.e("androidmcp", "getMediaProjection (service) failed", t)
            ProjectionHolder.set(null)
        }
    }

    private fun startServer() {
        // Read the SAVED config synchronously — at boot the async StateFlow (ConfigStore.current)
        // hasn't populated yet, so it would bind default options (loopback) instead of the user's.
        val cfg = runCatching { ConfigStore.currentBlocking() }.getOrDefault(ConfigStore.current)
        val host = Net.bindHost(cfg.bind)
        val port = cfg.port
        try {
            engine = if (cfg.tls) {
                // Self-signed HTTPS. Cert is generated fresh each start (no keystore on disk);
                // clients must trust it or skip verification. TLS uses the Netty engine because
                // CIO does not support HTTPS at all (it throws asynchronously). Plain HTTP keeps
                // the simple, proven CIO bootstrap below.
                val alias = TlsKeystore.ALIAS
                val pass = TlsKeystore.PW
                val ks = TlsKeystore.loadOrCreate(applicationContext)
                val env = applicationEngineEnvironment {
                    sslConnector(ks, alias, { pass }, { pass }) {
                        this.host = host
                        this.port = port
                    }
                    module { installRoutes { b, c, v, sse -> Mcp.handle(applicationContext, b, c, v, sse) } }
                }
                embeddedServer(io.ktor.server.netty.Netty, env).start(wait = false)
            } else {
                embeddedServer(CIO, host = host, port = port) {
                    installRoutes { b, c, v, sse -> Mcp.handle(applicationContext, b, c, v, sse) }
                }.start(wait = false)
            }
            running.value = true
            val scheme = if (cfg.tls) "https" else "http"
            boundInfo.value = "${Net.reachableHost(cfg.bind)}:$port"
            // Snapshot what the listener ACTUALLY bound. Media links used to be built from
            // ConfigStore.current at mint time, but the bind/TLS toggles can be changed without
            // restarting the server — so flipping to "lan" without a restart produced a LAN URL
            // that the still-loopback listener would refuse.
            boundBase.value = "$scheme://${Net.reachableHost(cfg.bind)}:$port"
            AuditLog.record("server", "local", true, "started $scheme on $host:$port")
            notify("listening on $scheme://${boundInfo.value}")
        } catch (t: Throwable) {
            running.value = false
            boundInfo.value = "error: ${t.message}"
            AuditLog.record("server", "local", false, "start failed: ${t.message}")
        }
    }

    override fun onDestroy() {
        engine?.stop(100, 500)
        engine = null
        running.value = false
        boundInfo.value = ""
        boundBase.value = ""
        super.onDestroy()
    }

    private fun buildNotification(text: String) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_stat_mcp)
        .setContentTitle("androidmcp server")
        .setContentText(text)
        .setOngoing(true)
        .build()

    private fun notify(text: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, buildNotification(text))
    }

    companion object {
        const val CHANNEL = "androidmcp_server"
        const val NOTIF_ID = 1
        const val ACTION_PROJECT = "com.sixoffive.androidmcp.START_PROJECTION"

        val running = MutableStateFlow(false)
        val boundInfo = MutableStateFlow("")

        /** Scheme://host:port the listener actually bound, for building fetchable media URLs. */
        val boundBase = MutableStateFlow("")

        fun ensureChannel(ctx: Context) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "MCP server", NotificationManager.IMPORTANCE_LOW)
            )
        }

        fun start(ctx: Context) {
            ensureChannel(ctx)
            ContextCompat.startForegroundService(ctx, Intent(ctx, McpService::class.java))
        }

        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, McpService::class.java))

        fun startProjection(ctx: Context, code: Int, data: Intent) {
            val i = Intent(ctx, McpService::class.java)
                .setAction(ACTION_PROJECT).putExtra("code", code).putExtra("data", data)
            ContextCompat.startForegroundService(ctx, i)
        }
    }
}

/** Largest MCP request body accepted. A token holder should not be able to OOM the foreground service. */
internal const val MAX_BODY_BYTES = 512 * 1024L

/**
 * MCP endpoint: bearer-auth + Origin (DNS-rebinding) check, then hand the body to [handle].
 *
 * Takes the handler as a lambda rather than a [Context] so the whole HTTP layer — auth, the
 * rebinding guard, CORS, the media nonce — is exercisable from `testApplication` with no device.
 */
internal fun Application.installRoutes(
    handle: suspend (
        body: String, client: String, protocolHeader: String?, acceptsSse: Boolean,
    ) -> Mcp.Reply,
) {
    routing {
        // CORS preflight — only honoured when the browser dashboard is opted in.
        options("/mcp") {
            val origin = call.request.headers["Origin"]
            val cfg = ConfigStore.current
            if (origin != null && AccessControl.originAllowed(origin, cfg.allowBrowser, cfg.allowedOrigins)) {
                applyCors(call, origin)
                call.respondText("", status = HttpStatusCode.NoContent)
            } else {
                if (rejected(call, "origin", "forbidden preflight origin: $origin")) return@options
                call.respondText("", status = HttpStatusCode.Forbidden)
            }
        }
        post("/mcp") {
            val origin = call.request.headers["Origin"]
            val cfg = ConfigStore.current
            // Browsers send Origin; native MCP clients don't. DNS-rebinding guard: an origin must
            // be on the allowlist (or a local default) even when the dashboard is opted in — the
            // old rule was a single global boolean plus reflect-anything CORS, which made the
            // spec's Origin check a no-op exactly when the feature it guards was in use.
            if (!AccessControl.originAllowed(origin, cfg.allowBrowser, cfg.allowedOrigins)) {
                if (rejected(call, "origin", "forbidden origin: $origin")) return@post
                call.respondText("forbidden origin", status = HttpStatusCode.Forbidden); return@post
            }
            if (origin != null) applyCors(call, origin)
            val client = authenticate(call)
            if (client == null) {
                if (rejected(call, "auth", "unauthorized")) return@post
                // Name the scheme so a client knows *how* to authenticate. Deliberately no
                // `resource_metadata`: there is no authorization server, and advertising one
                // sends clients down an OAuth discovery path that leads nowhere.
                call.response.headers.append("WWW-Authenticate", "Bearer realm=\"androidmcp\", error=\"invalid_token\"")
                call.respondText("{\"error\":\"unauthorized\"}", ContentType.Application.Json, HttpStatusCode.Unauthorized)
                return@post
            }

            // The MCP-Protocol-Version header is validated inside Mcp.handle, which has already
            // parsed the method — the check must not apply to `initialize`, whose body carries the
            // negotiation that resolves a version mismatch.
            val askedVersion = call.request.headers["MCP-Protocol-Version"]

            val declared = call.request.contentLength()
            if (declared != null && declared > MAX_BODY_BYTES) {
                call.respondText("request body too large", status = HttpStatusCode.PayloadTooLarge)
                return@post
            }
            // Read through a bounded copy rather than calling receiveText() and measuring after.
            // A `Transfer-Encoding: chunked` body declares no length, so the check above never
            // fires for one — and receiveText() buffers the WHOLE stream into a String first, so
            // the cap was enforced only once the damage was done. Ktor 2.3.12 has no request-size
            // plugin, so the bound has to be applied while reading.
            val body = call.receiveBoundedText(MAX_BODY_BYTES)
            if (body == null) {
                call.respondText("request body too large", status = HttpStatusCode.PayloadTooLarge)
                return@post
            }

            // Streaming is offered only to a client that says it can read one. Anything else —
            // including a client that asked for progress but did not advertise SSE — gets the
            // single-JSON response it has always got.
            val acceptsSse = call.request.headers["Accept"]
                ?.contains("text/event-stream", ignoreCase = true) == true

            when (val resp = handle(body, client, askedVersion, acceptsSse)) {
                // A notification gets 202 with no body — it MUST NOT be answered.
                is Mcp.Reply.None -> call.respondText("", status = HttpStatusCode.Accepted)
                is Mcp.Reply.Body -> call.respondText(resp.json, ContentType.Application.Json)
                // A body that never parsed into a request is an HTTP-layer failure, not a result.
                is Mcp.Reply.Rejected -> call.respondText(
                    resp.json, ContentType.Application.Json, HttpStatusCode.fromValue(resp.status),
                )
                // Progress notifications, then the response, on this POST's own body. Verified by
                // StreamingFlushTest that both engines actually put each write on the wire when
                // it is made — without that this would be a silent stall wearing a new MIME type.
                is Mcp.Reply.Streamed -> {
                    call.response.headers.append("Cache-Control", "no-store")
                    call.respondTextWriter(ContentType.Text.EventStream) {
                        val last = resp.produce { line -> writeEvent(line) }
                        writeEvent(last)
                    }
                }
            }
        }
        get("/mcp") {
            // The spec makes the server-to-client SSE stream a MAY; 405 is the conformant way to
            // decline it. Nothing here needs to push, and `tools.listChanged` is not declared.
            call.respondText("server-to-client SSE stream not offered", status = HttpStatusCode.MethodNotAllowed)
        }
        // Transient media for resource_link replies. Auth is the unguessable ?k= nonce tied to
        // this id (a capability URL) — no bearer token in the URL, single-use, TTL-expiring.
        get("/media/{id}") {
            val origin = call.request.headers["Origin"]
            val cfg = ConfigStore.current
            if (!AccessControl.originAllowed(origin, cfg.allowBrowser, cfg.allowedOrigins)) {
                if (rejected(call, "origin", "forbidden origin on /media: $origin")) return@get
                call.respondText("forbidden origin", status = HttpStatusCode.Forbidden); return@get
            }
            if (origin != null) applyCors(call, origin)
            val id = call.parameters["id"]
            val nonce = call.request.queryParameters["k"] ?: ""
            val e = id?.let { MediaStore.take(it, nonce) }
            if (e == null) {
                // Metered and audited like every other refusal. /media carries no bearer token by
                // design, so without this it was the one unauthenticated surface an attacker could
                // probe indefinitely at no cost and leaving no trace.
                if (rejected(call, "media", "no such media id, or wrong/expired key")) return@get
                call.respondText("not found or expired", status = HttpStatusCode.NotFound)
                return@get
            }
            // Capability URLs must not be cached by anything between here and the client, and the
            // bytes must not be sniffed into an executable type.
            call.response.headers.append("Cache-Control", "no-store")
            call.response.headers.append("X-Content-Type-Options", "nosniff")
            call.respondBytes(e.bytes, ContentType.parse(e.mime))
        }
    }
}

/**
 * One SSE event carrying one JSON-RPC message, flushed immediately.
 *
 * The flush is the entire point: buffered, a progress notification arrives with the result it was
 * meant to precede. `data:` lines are per the SSE grammar, and the JSON is emitted by
 * kotlinx.serialization so it never contains a raw newline that would split the frame.
 */
private suspend fun java.io.Writer.writeEvent(json: String) {
    write("data: ")
    write(json)
    write("\n\n")
    flush()
}

/**
 * Record a rejected request and decide whether to throttle the caller.
 *
 * Neither the 401 nor the Origin 403 used to write anything: `AuditLog.record` appeared only on
 * the tool path, so a `lan` bind could be probed indefinitely leaving no trace in the log the UI
 * presents as the trust record. Returns true when the caller has been answered with a 429 and the
 * route should stop.
 */
private suspend fun rejected(call: ApplicationCall, kind: String, detail: String): Boolean {
    // `remoteHost` (not `remoteAddress`) bottoms out in InetSocketAddress.getHostName(), a reverse
    // DNS lookup — on the UNAUTHENTICATED reject path. With a slow or blackholed resolver every
    // rejected probe would pin an IO thread for the resolver timeout, the throttle key would become
    // a peer-controlled hostname (so changing it resets your own bucket), and that name would be
    // written into the audit log the UI presents as the trust record.
    val host = normaliseHost(call.request.local.remoteAddress)
    val r = AccessControl.recordRejection(host, detail, kind = kind)
    // One line per host per minute, carrying the count it stands for, so a patient prober cannot
    // scroll a real event out of the 200-entry ring.
    r.logLine?.let { AuditLog.record("rejected:$kind", host, false, it) }
    if (r.throttle) {
        call.response.headers.append("Retry-After", "60")
        call.respondText("too many rejected requests", status = HttpStatusCode.TooManyRequests)
    }
    return r.throttle
}

/**
 * Read the request body, giving up as soon as it exceeds [max] bytes.
 *
 * Returns null when the cap is passed, having read at most `max + 1` bytes — the point is that an
 * over-cap body is never fully materialised. Counts BYTES; the old check compared `String.length`,
 * which is UTF-16 units, against a byte constant.
 */
private suspend fun ApplicationCall.receiveBoundedText(max: Long): String? {
    val channel = receiveChannel()
    val out = java.io.ByteArrayOutputStream()
    val chunk = ByteArray(16 * 1024)
    while (!channel.isClosedForRead) {
        val n = channel.readAvailable(chunk, 0, chunk.size)
        if (n <= 0) continue
        if (out.size() + n > max) return null
        out.write(chunk, 0, n)
    }
    return out.toString(Charsets.UTF_8.name())
}

/** One peer, one bucket: fold the IPv4-mapped IPv6 form (`::ffff:192.168.1.5`) onto the IPv4 one. */
private fun normaliseHost(addr: String): String =
    addr.removePrefix("::ffff:").removeSurrounding("[", "]")

/**
 * Parse `Authorization: Bearer <token>` and resolve it to a client name.
 *
 * The old `removePrefix("Bearer ")` was exact-match, which both rejected a lowercase `bearer`
 * scheme (legal per RFC 7235 — the scheme is case-insensitive) and, worse, accepted a bare
 * schemeless token, since `removePrefix` returns the string unchanged when the prefix is absent.
 */
private fun authenticate(call: ApplicationCall): String? {
    val header = call.request.headers["Authorization"] ?: return null
    val sep = header.indexOf(' ')
    if (sep < 0) return null
    if (!header.regionMatches(0, "Bearer", 0, sep, ignoreCase = true) || sep != 6) return null
    val token = header.substring(sep + 1).trim()
    if (token.isEmpty()) return null
    return com.sixoffive.androidmcp.core.TokenStore.verify(token)
}

/** Reflect the caller's Origin (no cookies are used, so echoing is safe and precise). */
private fun applyCors(call: ApplicationCall, origin: String) {
    val h = call.response.headers
    h.append("Access-Control-Allow-Origin", origin)
    h.append("Vary", "Origin")
    h.append("Access-Control-Allow-Methods", "POST, OPTIONS")
    h.append("Access-Control-Allow-Headers", "Authorization, Content-Type, mcp-protocol-version, mcp-session-id")
    h.append("Access-Control-Max-Age", "86400")
}
