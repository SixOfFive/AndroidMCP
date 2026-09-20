package com.sixoffive.androidmcp.core

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * The single choke-point. Every tool handler calls this before doing any work.
 * Re-evaluated at call time, so a revoked toggle or permission fails the next call.
 */
object GateEngine {

    fun evaluate(ctx: Context, cap: CapabilityMeta): GateResult {
        // Gate 0 — hardware. A capability whose hardware is absent can never succeed, so refuse
        // it clearly (and the UI greys it out) rather than pretending it can be enabled.
        HardwareCheck.missing(ctx, cap.id)?.let { why ->
            return GateResult.Denied(
                ReasonCode.HARDWARE_UNAVAILABLE, cap, ConfigStore.isEnabled(cap.id), false,
                "Not available on this device ($why).", retriable = false,
            )
        }

        // Gate 1 — in-app toggle
        if (!ConfigStore.isEnabled(cap.id)) {
            return GateResult.Denied(
                reason = ReasonCode.FEATURE_DISABLED_IN_APP,
                cap = cap, toggleEnabled = false, permissionGranted = false,
                remediation = "Enable '${cap.toggleLabel}' in the androidmcp app" +
                    if (cap.permissions.isNotEmpty()) ", then grant the Android permission." else ".",
                retriable = true,
            )
        }

        // Elevated (root or Shizuku) capabilities
        if (cap.rootRequired && !Elevated.isAvailable()) {
            return GateResult.Denied(
                ReasonCode.NOT_SUPPORTED_WITHOUT_ROOT, cap, true, false,
                "This capability needs elevated access — either Magisk root, or Shizuku (a non-destructive shell / uid-2000 service you start over ADB, no wipe). Neither was detected; start/grant Shizuku (or root the device) and retry.",
                false,
            )
        }

        // Situational gates for capabilities needing special access / not yet wired
        when (cap.id) {
            "read_notifications", "notification_action" -> {
                val enabled = androidx.core.app.NotificationManagerCompat
                    .getEnabledListenerPackages(ctx).contains(ctx.packageName)
                if (!enabled) return GateResult.Denied(
                    ReasonCode.SPECIAL_ACCESS_NOT_ENABLED, cap, true, false,
                    "Grant Notification access to androidmcp in Android settings (Notifications → Notification access; sideloaded apps must first tap 'Allow restricted settings').",
                    true,
                )
            }
            "list_files" -> {
                if (ConfigStore.current.folders.isEmpty()) return GateResult.Denied(
                    ReasonCode.SPECIAL_ACCESS_NOT_ENABLED, cap, true, false,
                    "No folders are shared yet. Open androidmcp → Shared folders → Add folder to grant read access to a folder, then retry.",
                    true,
                )
            }
            "write_file", "delete_file", "rename_file" -> {
                if (ConfigStore.current.writableFolders.isEmpty()) return GateResult.Denied(
                    ReasonCode.SPECIAL_ACCESS_NOT_ENABLED, cap, true, false,
                    "No writable folders yet. Open androidmcp → Writable folders → Add folder to grant write access to a folder, then retry.",
                    true,
                )
            }
            "set_dnd" -> {
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
                val granted = runCatching { nm?.isNotificationPolicyAccessGranted == true }.getOrDefault(false)
                if (!granted) return GateResult.Denied(
                    ReasonCode.SPECIAL_ACCESS_NOT_ENABLED, cap, true, false,
                    "Grant Do Not Disturb access to androidmcp in Android settings (Notifications → Do Not Disturb access → androidmcp → Allow; sideloaded apps must first tap 'Allow restricted settings').",
                    true,
                )
            }
            "set_brightness", "set_screen_timeout" -> {
                if (!android.provider.Settings.System.canWrite(ctx)) return GateResult.Denied(
                    ReasonCode.SPECIAL_ACCESS_NOT_ENABLED, cap, true, false,
                    "Grant 'Modify system settings' to androidmcp in Android settings (Apps → androidmcp → Modify system settings → Allow), then retry.",
                    true,
                )
            }
            "read_screen", "global_action", "tap", "swipe", "type_text" -> {
                if (!com.sixoffive.androidmcp.server.McpAccessibilityService.isConnected()) return GateResult.Denied(
                    ReasonCode.SPECIAL_ACCESS_NOT_ENABLED, cap, true, false,
                    "Grant Accessibility access to androidmcp in Android settings (Accessibility → androidmcp → On; sideloaded apps must first tap 'Allow restricted settings').",
                    true,
                )
            }
            "foreground_app" -> {
                // Usage access is an appop granted in Settings, not a runtime permission — check it here.
                if (!hasUsageAccess(ctx)) return GateResult.Denied(
                    ReasonCode.SPECIAL_ACCESS_NOT_ENABLED, cap, true, false,
                    "Grant Usage access to androidmcp in Android settings (Special app access → Usage access → androidmcp → Allow; sideloaded apps must first tap 'Allow restricted settings').",
                    true,
                )
            }
        }

        // Gate 2 — OS runtime permission (re-checked live)
        val granted = when (cap.id) {
            "get_location" -> hasPerm(ctx, "android.permission.ACCESS_FINE_LOCATION") ||
                hasPerm(ctx, "android.permission.ACCESS_COARSE_LOCATION")
            // media_search names three READ_MEDIA_* perms but any ONE is enough to search that type;
            // on API <= 32 those don't exist and the OS wants the legacy READ_EXTERNAL_STORAGE. The
            // handler enforces the specific requested type and explains if that one is missing.
            "media_search" -> if (android.os.Build.VERSION.SDK_INT >= 33) {
                hasPerm(ctx, "android.permission.READ_MEDIA_IMAGES") ||
                    hasPerm(ctx, "android.permission.READ_MEDIA_VIDEO") ||
                    hasPerm(ctx, "android.permission.READ_MEDIA_AUDIO")
            } else {
                hasPerm(ctx, "android.permission.READ_EXTERNAL_STORAGE")
            }
            else -> cap.permissions.all { hasPerm(ctx, it) }
        }
        if (!granted) {
            return GateResult.Denied(
                ReasonCode.OS_PERMISSION_NOT_GRANTED, cap, true, false,
                "Grant the Android permission(s) [${cap.permissions.joinToString()}] to androidmcp, then retry.",
                true,
            )
        }

        return GateResult.Allowed
    }

    private fun hasPerm(ctx: Context, p: String): Boolean =
        ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    /** Whether the "Usage access" appop is granted to this app (for foreground_app). */
    private fun hasUsageAccess(ctx: Context): Boolean = runCatching {
        val aom = ctx.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        val uid = android.os.Process.myUid()
        val mode = if (android.os.Build.VERSION.SDK_INT >= 29) {
            aom.unsafeCheckOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, uid, ctx.packageName)
        } else {
            @Suppress("DEPRECATION")
            aom.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, uid, ctx.packageName)
        }
        mode == android.app.AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)
}
