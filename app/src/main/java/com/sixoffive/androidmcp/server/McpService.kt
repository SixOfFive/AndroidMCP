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
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
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
        val cfg = ConfigStore.current
        val host = Net.bindHost(cfg.bind)
        val port = cfg.port
        try {
            engine = embeddedServer(CIO, host = host, port = port) {
                installRoutes(applicationContext)
            }.start(wait = false)
            running.value = true
            boundInfo.value = "${Net.reachableHost(cfg.bind)}:$port"
            AuditLog.record("server", "local", true, "started on $host:$port")
            notify("listening on ${boundInfo.value}")
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

/** MCP endpoint: bearer-auth + Origin (DNS-rebinding) check, then hand the body to [Mcp]. */
private fun Application.installRoutes(appCtx: Context) {
    routing {
        post("/mcp") {
            // Browsers send Origin; MCP clients don't. Reject any browser origin.
            if (call.request.headers["Origin"] != null) {
                call.respondText("forbidden origin", status = HttpStatusCode.Forbidden); return@post
            }
            val bearer = call.request.headers["Authorization"]?.removePrefix("Bearer ")?.trim()
            val client = bearer?.let { com.sixoffive.androidmcp.core.TokenStore.verify(it) }
            if (client == null) {
                call.respondText("{\"error\":\"unauthorized\"}", ContentType.Application.Json, HttpStatusCode.Unauthorized)
                return@post
            }
            val body = call.receiveText()
            val resp = Mcp.handle(appCtx, body, client)
            if (resp == null) {
                call.respondText("", status = HttpStatusCode.Accepted)
            } else {
                call.respondText(resp, ContentType.Application.Json)
            }
        }
        get("/mcp") {
            call.respondText("server-to-client SSE stream not offered", status = HttpStatusCode.MethodNotAllowed)
        }
    }
}
