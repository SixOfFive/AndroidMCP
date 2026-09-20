package com.sixoffive.androidmcp.server

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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

        /** A single tap at absolute screen pixels, dispatched as a short gesture. */
        fun tap(x: Int, y: Int): String {
            val svc = requireSvc()
            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 50)).build()
            dispatch(svc, gesture)
            return "tapped ($x, $y)"
        }

        /** A swipe from (x1,y1) to (x2,y2) over [durationMs]. */
        fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): String {
            val svc = requireSvc()
            val path = Path().apply { moveTo(x1.toFloat(), y1.toFloat()); lineTo(x2.toFloat(), y2.toFloat()) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.toLong())).build()
            dispatch(svc, gesture)
            return "swiped ($x1, $y1) → ($x2, $y2) over ${durationMs}ms"
        }

        /**
         * Set the text of an editable field. Unlike a bare `ACTION_SET_TEXT` on whatever happens to
         * hold input focus, this call locates and focuses the target node itself — a synthetic `tap`
         * does not reliably focus Jetpack Compose text fields, so `type_text` must not depend on one
         * having landed.
         *
         * With [x]/[y] (a centre from `read_screen`) it targets the editable field under that point.
         * Without them it uses the already-focused input if there is one, else the sole editable
         * field on screen; if several are editable and none is focused it asks for x,y so the choice
         * is the caller's, not a guess.
         */
        fun typeText(text: String, x: Int? = null, y: Int? = null): String {
            val svc = requireSvc()
            val root = svc.rootInActiveWindow
                ?: throw ToolExecError("no active window (the screen may be secure) — retry")
            try {
                val target: AccessibilityNodeInfo = when {
                    x != null && y != null ->
                        editableAt(root, x, y)
                            ?: throw ToolExecError("no editable field at ($x, $y) — check the coordinate against read_screen")
                    else -> {
                        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
                        focused ?: run {
                            val editable = collectEditable(root)
                            when (editable.size) {
                                0 -> throw ToolExecError("no editable field on screen — open one first, or pass x,y from read_screen")
                                1 -> editable.single()
                                else -> throw ToolExecError(
                                    "${editable.size} editable fields are on screen and none is focused — " +
                                        "pass x,y (a field's centre from read_screen) to choose one")
                            }
                        }
                    }
                }
                try {
                    if (!target.isEditable) throw ToolExecError("the targeted element isn't a text field")
                    // Focus the field via node actions, which work where a synthetic tap does not
                    // (notably Compose). Best-effort: ACTION_SET_TEXT is what must succeed.
                    runCatching { target.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }
                    runCatching { target.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
                    val args = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                    }
                    if (!target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                        throw ToolExecError("the field refused the text (it may not support programmatic input)")
                    }
                    return "set the field to ${text.length} character(s)"
                } finally {
                    if (target !== root) @Suppress("DEPRECATION") runCatching { target.recycle() }
                }
            } finally {
                @Suppress("DEPRECATION") runCatching { root.recycle() }
            }
        }

        /** The smallest editable, on-screen node whose bounds contain (x,y). Caller owns the result. */
        private fun editableAt(root: AccessibilityNodeInfo, x: Int, y: Int): AccessibilityNodeInfo? {
            var best: AccessibilityNodeInfo? = null
            var bestArea = Int.MAX_VALUE
            val rect = Rect()
            fun visit(node: AccessibilityNodeInfo?) {
                if (node == null) return
                if (node.isEditable && node.isVisibleToUser) {
                    node.getBoundsInScreen(rect)
                    if (rect.contains(x, y)) {
                        val area = rect.width() * rect.height()
                        if (area < bestArea) { bestArea = area; best = node }
                    }
                }
                for (i in 0 until node.childCount) visit(node.getChild(i))
            }
            visit(root)
            return best
        }

        /** Every editable, on-screen node in the tree. Caller owns the results. */
        private fun collectEditable(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
            val out = ArrayList<AccessibilityNodeInfo>()
            fun visit(node: AccessibilityNodeInfo?) {
                if (node == null) return
                if (node.isEditable && node.isVisibleToUser) out.add(node)
                for (i in 0 until node.childCount) visit(node.getChild(i))
            }
            visit(root)
            return out
        }

        private fun requireSvc(): McpAccessibilityService = instance
            ?: throw ToolExecError("accessibility service isn't connected — grant it in Android Settings (Accessibility → androidmcp), then retry")

        /** Dispatch a gesture and block until the system reports it done (or times out). */
        private fun dispatch(svc: McpAccessibilityService, gesture: GestureDescription) {
            val latch = CountDownLatch(1)
            var cancelled = false
            val cb = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) { latch.countDown() }
                override fun onCancelled(g: GestureDescription?) { cancelled = true; latch.countDown() }
            }
            // handler=null → the callback runs on the service's main thread; we block Dispatchers.IO.
            if (!svc.dispatchGesture(gesture, cb, null)) {
                throw ToolExecError("the system rejected the gesture (dispatchGesture returned false)")
            }
            if (!latch.await(10, TimeUnit.SECONDS)) throw ToolExecError("the gesture did not complete in time")
            if (cancelled) throw ToolExecError("the gesture was cancelled by the system")
        }
    }
}
