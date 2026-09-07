package com.sixoffive.androidmcp.server

import android.app.Notification
import android.service.notification.NotificationListenerService

/** Notification-access service. When connected, exposes the active notifications. */
class McpNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() { instance = this }
    override fun onListenerDisconnected() { if (instance === this) instance = null }
    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        var instance: McpNotificationListener? = null

        fun readActive(limit: Int): String? {
            val svc = instance ?: return null
            val notes = runCatching { svc.activeNotifications }.getOrNull() ?: return "no active notifications accessible"
            if (notes.isEmpty()) return "no active notifications"
            return notes.take(limit).joinToString("\n") { sbn ->
                val e = sbn.notification.extras
                val title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
                val text = e.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.replace("\n", " ")?.take(150) ?: ""
                "${sbn.packageName}: $title — $text"
            }
        }
    }
}
