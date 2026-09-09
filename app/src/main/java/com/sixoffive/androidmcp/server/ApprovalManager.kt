package com.sixoffive.androidmcp.server

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.sixoffive.androidmcp.R
import com.sixoffive.androidmcp.core.CapabilityMeta
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runtime consent for high-impact tools. The in-app toggle says a capability MAY be
 * used; this makes the human approve each actual use (or arm it for a window), so a
 * prompt-injected client can't silently drive the camera/mic/location/etc.
 */
object ApprovalManager {
    const val CHANNEL = "androidmcp_approvals"
    const val ACTION = "com.sixoffive.androidmcp.APPROVE"
    /**
     * How long to wait for the human before refusing.
     *
     * Deliberately BELOW the 60 s default request timeout of the reference MCP SDKs. At exactly
     * 60 s the client's clock starts first, so the ordinary "phone in a pocket" case surfaced as
     * an opaque transport timeout instead of the structured `approvalRefusal` this app builds —
     * the client gave up a beat before the server could explain itself. Shortening the window
     * makes the *explained* deny path fire more often, not fewer approvals succeed.
     */
    const val TIMEOUT_MS = 25_000L

    private val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    /**
     * (client, request id) -> internal approval id, so `notifications/cancelled` can cancel one.
     *
     * Keyed by CLIENT as well as id: several named client tokens is a designed feature, JSON-RPC
     * ids are per-connection, and `notifications/cancelled` is neither authenticated against the
     * pending request nor rate-limited. Keyed on the bare id, client B could spray cancellations
     * over ids 1..100 and deny every other client's pending approvals.
     */
    private val byRpcId = ConcurrentHashMap<String, String>()

    /** Type-tagged so numeric `7` and string `"7"` are different requests, as JSON-RPC intends. */
    fun rpcKey(client: String, rpcId: String, numeric: Boolean): String =
        "$client\u0000${if (numeric) "n" else "s"}:$rpcId"
    private val armedUntil = ConcurrentHashMap<String, Long>()
    private val counter = AtomicInteger(1000)

    fun isArmed(capId: String): Boolean = (armedUntil[capId] ?: 0L) > System.currentTimeMillis()
    fun arm(capId: String, minutes: Int) { armedUntil[capId] = System.currentTimeMillis() + minutes * 60_000L }
    fun disarm(capId: String) { armedUntil.remove(capId) }

    /** True if the call may proceed. Blocks on the human for high-impact, un-armed tools. */
    suspend fun require(ctx: Context, cap: CapabilityMeta, client: String, rpcKey: String? = null): Boolean {
        if (!cap.highImpact) return true
        if (isArmed(cap.id)) return true
        val id = "req-${counter.incrementAndGet()}"
        val deferred = CompletableDeferred<Boolean>()
        pending[id] = deferred
        // putIfAbsent, not put: a duplicate id from the same client must not silently steal the
        // mapping of an approval that is still pending.
        rpcKey?.let { byRpcId.putIfAbsent(it, id) }
        postPrompt(ctx, id, cap, client)
        return try {
            withTimeoutOrNull(TIMEOUT_MS) { deferred.await() } ?: false
        } finally {
            // `finally`, because the cleanup used to be skipped whenever the coroutine was
            // cancelled — leaving a live consent prompt orphaned in the notification shade for a
            // request nobody is waiting on any more.
            pending.remove(id)
            // Two-argument remove: only drop the mapping if it is still OURS.
            rpcKey?.let { byRpcId.remove(it, id) }
            nm(ctx).cancel(notifId(id))
        }
    }

    fun resolve(id: String, allow: Boolean) { pending.remove(id)?.complete(allow) }

    /**
     * The client withdrew the request (`notifications/cancelled`). Deny the pending approval so
     * the tool never runs — otherwise a late "Allow" tap could still fire the camera for a call
     * the client abandoned minutes ago.
     */
    fun cancelByRpcId(rpcKey: String): Boolean {
        val internal = byRpcId.remove(rpcKey) ?: return false
        pending.remove(internal)?.complete(false)
        return true
    }

    private fun nm(ctx: Context) = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private fun notifId(id: String) = id.hashCode()

    private fun postPrompt(ctx: Context, id: String, cap: CapabilityMeta, client: String) {
        val nm = nm(ctx)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Approval prompts", NotificationManager.IMPORTANCE_HIGH))
        fun action(allow: Boolean, rc: Int): PendingIntent {
            val i = Intent(ctx, ApprovalReceiver::class.java)
                .setAction(ACTION).putExtra("req", id).putExtra("allow", allow)
            return PendingIntent.getBroadcast(ctx, rc, i,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_mcp)
            .setContentTitle("Allow ${cap.title}?")
            .setContentText("Client '$client' wants ${cap.title}. Exposes: ${cap.dataExposed}")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .addAction(0, "Allow", action(true, notifId(id) * 2))
            .addAction(0, "Deny", action(false, notifId(id) * 2 + 1))
            .build()
        nm.notify(notifId(id), n)
        Log.i("androidmcp", "approval pending id=$id cap=${cap.id} client=$client")
    }
}
