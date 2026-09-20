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
            return notes.take(limit).joinToString("\n\n") { sbn ->
                val e = sbn.notification.extras
                val title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
                val text = e.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.replace("\n", " ")?.take(150) ?: ""
                val head = "[key: ${sbn.key}] ${sbn.packageName}: $title — $text"
                val actions = sbn.notification.actions
                if (actions.isNullOrEmpty()) head
                else head + "\n" + actions.mapIndexed { i, a ->
                    val reply = !a.remoteInputs.isNullOrEmpty()
                    "    action $i: ${a.title}${if (reply) " (accepts text reply)" else ""}"
                }.joinToString("\n")
            }
        }

        /**
         * Reply to, or fire an action button on, a live notification. No new permission — the bound
         * listener may send a notification's own action PendingIntents (and fill a RemoteInput). The
         * capability gate has already confirmed the listener is connected, but re-check defensively
         * (it can disconnect between the gate and here).
         */
        fun sendAction(key: String, index: Int, text: String?): String {
            val svc = instance
                ?: throw ToolExecError("notification listener isn't connected — toggle Notification access off/on for androidmcp, then retry")
            val sbn = runCatching { svc.activeNotifications }.getOrNull()?.firstOrNull { it.key == key }
                ?: throw ToolArgError("no active notification with key '$key' — call read_notifications again (keys change as notifications come and go)")
            val actions = sbn.notification.actions
            if (actions.isNullOrEmpty()) throw ToolArgError("that notification has no action buttons")
            if (index < 0 || index >= actions.size)
                throw ToolArgError("action_index $index is out of range — this notification has ${actions.size} action(s), so use 0..${actions.size - 1}")
            val action = actions[index]
            val remoteInputs = action.remoteInputs
            try {
                if (!remoteInputs.isNullOrEmpty()) {
                    val reply = text?.takeUnless { it.isBlank() }
                        ?: throw ToolArgError("action $index ('${action.title}') is a reply action — provide 'text'")
                    val intent = android.content.Intent()
                    val results = android.os.Bundle()
                    for (ri in remoteInputs) results.putCharSequence(ri.resultKey, reply)
                    android.app.RemoteInput.addResultsToIntent(remoteInputs, intent, results)
                    action.actionIntent.send(svc, 0, intent)
                    return "sent reply to ${sbn.packageName} via '${action.title}': \"${reply.take(120)}\""
                }
                action.actionIntent.send()
                return "fired action '${action.title}' on ${sbn.packageName}"
            } catch (e: android.app.PendingIntent.CanceledException) {
                throw ToolExecError("that notification's action was already cancelled (it may be stale) — call read_notifications again")
            }
        }
    }
}
