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
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
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
                    module { installRoutes { b, c, v -> Mcp.handle(applicationContext, b, c, v) } }
                }
                embeddedServer(io.ktor.server.netty.Netty, env).start(wait = false)
            } else {
                embeddedServer(CIO, host = host, port = port) {
                    installRoutes { b, c, v -> Mcp.handle(applicationContext, b, c, v) }
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
    handle: suspend (body: String, client: String, protocolHeader: String?) -> Mcp.Reply,
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
            val body = call.receiveText()
            if (body.length > MAX_BODY_BYTES) { // chunked bodies declare no length
                call.respondText("request body too large", status = HttpStatusCode.PayloadTooLarge)
                return@post
            }

            when (val resp = handle(body, client, askedVersion)) {
                // A notification gets 202 with no body — it MUST NOT be answered.
                is Mcp.Reply.None -> call.respondText("", status = HttpStatusCode.Accepted)
                is Mcp.Reply.Body -> call.respondText(resp.json, ContentType.Application.Json)
                // A body that never parsed into a request is an HTTP-layer failure, not a result.
                is Mcp.Reply.Rejected -> call.respondText(
                    resp.json, ContentType.Application.Json, HttpStatusCode.fromValue(resp.status),
                )
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
            val id = call.parameters["id"] ?: return@get call.respondText("not found", status = HttpStatusCode.NotFound)
            val nonce = call.request.queryParameters["k"] ?: ""
            val e = MediaStore.take(id, nonce)
                ?: return@get call.respondText("not found or expired", status = HttpStatusCode.NotFound)
            // Capability URLs must not be cached by anything between here and the client, and the
            // bytes must not be sniffed into an executable type.
            call.response.headers.append("Cache-Control", "no-store")
            call.response.headers.append("X-Content-Type-Options", "nosniff")
            call.respondBytes(e.bytes, ContentType.parse(e.mime))
        }
    }
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
    val host = call.request.local.remoteHost
    val r = AccessControl.recordRejection(host, detail)
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
