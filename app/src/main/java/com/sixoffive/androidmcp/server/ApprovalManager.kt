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
    const val TIMEOUT_MS = 60_000L

    private val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val armedUntil = ConcurrentHashMap<String, Long>()
    private val counter = AtomicInteger(1000)

    fun isArmed(capId: String): Boolean = (armedUntil[capId] ?: 0L) > System.currentTimeMillis()
    fun arm(capId: String, minutes: Int) { armedUntil[capId] = System.currentTimeMillis() + minutes * 60_000L }
    fun disarm(capId: String) { armedUntil.remove(capId) }

    /** True if the call may proceed. Blocks on the human for high-impact, un-armed tools. */
    suspend fun require(ctx: Context, cap: CapabilityMeta, client: String): Boolean {
        if (!cap.highImpact) return true
        if (isArmed(cap.id)) return true
        val id = "req-${counter.incrementAndGet()}"
        val deferred = CompletableDeferred<Boolean>()
        pending[id] = deferred
        postPrompt(ctx, id, cap, client)
        val ok = withTimeoutOrNull(TIMEOUT_MS) { deferred.await() } ?: false
        pending.remove(id)
        nm(ctx).cancel(notifId(id))
        return ok
    }

    fun resolve(id: String, allow: Boolean) { pending.remove(id)?.complete(allow) }

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
