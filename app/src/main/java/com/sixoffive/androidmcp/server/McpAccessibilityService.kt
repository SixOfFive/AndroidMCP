package com.sixoffive.androidmcp.server

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Accessibility service — the non-root path to reading on-screen content and driving the UI.
 *
 * It deliberately does NOT react to events (the server polls on demand), so [onAccessibilityEvent]
 * is empty. Enabling it in Settings is a special access, gated exactly like the notification
 * listener; and every tool that uses it is additionally high-impact (per-call approval), so the
 * grant on its own neither reads nor does anything.
 */
class McpAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }
    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        var instance: McpAccessibilityService? = null

        fun isConnected(): Boolean = instance != null

        /** Perform a device-wide navigation action. Throws with guidance on a bad action or refusal. */
        fun globalAction(action: String): String {
            val svc = instance
                ?: throw ToolExecError("accessibility service isn't connected — grant it in Android Settings (Accessibility → androidmcp), then retry")
            val code = when (action) {
                "back" -> GLOBAL_ACTION_BACK
                "home" -> GLOBAL_ACTION_HOME
                "recents" -> GLOBAL_ACTION_RECENTS
                "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
                "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
                "power_dialog" -> GLOBAL_ACTION_POWER_DIALOG
                "lock_screen" ->
                    if (Build.VERSION.SDK_INT >= 28) GLOBAL_ACTION_LOCK_SCREEN
                    else throw ToolExecError("lock_screen needs Android 9+ (API 28)")
                "screenshot" ->
                    if (Build.VERSION.SDK_INT >= 30) GLOBAL_ACTION_TAKE_SCREENSHOT
                    else throw ToolExecError("screenshot needs Android 11+ (API 30)")
                else -> throw ToolArgError(
                    "unknown action '$action' — use back, home, recents, notifications, quick_settings, " +
                        "lock_screen, screenshot, or power_dialog",
                )
            }
            if (!svc.performGlobalAction(code)) throw ToolExecError("the system refused the '$action' action")
            return "performed global action: $action"
        }

        /**
         * Dump the active window's accessibility node tree as indented text. Each emitted node shows
         * its class, any text / content-description, interaction flags, and its on-screen centre
         * `[x,y]` — the coordinate a later `tap` can target. Nodes with nothing useful are skipped
         * but still traversed, so structure is preserved.
         */
        fun readScreen(maxNodes: Int): String {
            val svc = instance
                ?: throw ToolExecError("accessibility service isn't connected — grant it in Android Settings (Accessibility → androidmcp), then retry")
            val root = svc.rootInActiveWindow
                ?: return "no active window content available (the screen may be secure, or the service just gained focus — retry)"
            val sb = StringBuilder("foreground package: ${root.packageName ?: "?"}\n")
            var count = 0

            fun visit(node: AccessibilityNodeInfo?, depth: Int) {
                if (node == null || count >= maxNodes) return
                val text = node.text?.toString()?.replace("\n", " ")?.trim()?.take(120)
                val desc = node.contentDescription?.toString()?.replace("\n", " ")?.trim()?.take(120)
                val cls = node.className?.toString()?.substringAfterLast('.').orEmpty()
                val flags = buildList {
                    if (node.isClickable) add("clickable")
                    if (node.isEditable) add("editable")
                    if (node.isCheckable) add("checkable=${node.isChecked}")
                    if (node.isScrollable) add("scrollable")
                }
                if (!text.isNullOrBlank() || !desc.isNullOrBlank() || flags.isNotEmpty()) {
                    val r = Rect().also { node.getBoundsInScreen(it) }
                    val parts = listOfNotNull(
                        cls.ifBlank { null },
                        text?.takeIf { it.isNotBlank() }?.let { "\"$it\"" },
                        desc?.takeIf { it.isNotBlank() }?.let { "desc=\"$it\"" },
                        flags.joinToString(",").ifBlank { null },
                        "[${r.centerX()},${r.centerY()}]",
                    )
                    sb.append("  ".repeat(depth)).append(parts.joinToString(" ")).append('\n')
                    count++
                }
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i)
                    visit(child, depth + 1)
                    @Suppress("DEPRECATION") runCatching { child?.recycle() }
                }
            }

            visit(root, 0)
            @Suppress("DEPRECATION") runCatching { root.recycle() }
            if (count >= maxNodes) sb.append("… (truncated at $maxNodes nodes)\n")
            return sb.toString().trim()
        }
    }
}
