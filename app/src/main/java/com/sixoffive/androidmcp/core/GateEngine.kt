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
            "read_notifications" -> {
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
        }

        // Gate 2 — OS runtime permission (re-checked live)
        val granted = if (cap.id == "get_location") {
            hasPerm(ctx, "android.permission.ACCESS_FINE_LOCATION") ||
                hasPerm(ctx, "android.permission.ACCESS_COARSE_LOCATION")
        } else {
            cap.permissions.all { hasPerm(ctx, it) }
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
}
