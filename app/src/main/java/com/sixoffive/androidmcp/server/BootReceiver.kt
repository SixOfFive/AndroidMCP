package com.sixoffive.androidmcp.server

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sixoffive.androidmcp.core.AuditLog
import com.sixoffive.androidmcp.core.ConfigStore

/**
 * Restores the server on device boot — but ONLY when the user explicitly **Saved** a start-on-boot
 * configuration (`bootArmed`) with the server enabled (`masterOn`). Flipping the in-app toggle alone
 * does nothing here; arming boot-start is a deliberate, warned, save-gated choice.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON"
        ) return
        // App.onCreate runs before a manifest receiver's onReceive, so ConfigStore is initialized.
        val cfg = runCatching { ConfigStore.currentBlocking() }.getOrNull() ?: return
        if (cfg.bootArmed && cfg.masterOn) {
            runCatching { McpService.start(context.applicationContext) }
                .onSuccess { AuditLog.record("server", "boot", true, "auto-start on boot (armed)") }
                .onFailure { AuditLog.record("server", "boot", false, "boot auto-start failed: ${it.message}") }
        }
    }
}
