package com.sixoffive.androidmcp.core

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Which capabilities the current hardware can actually service. A capability whose hardware
 * is absent is refused with HARDWARE_UNAVAILABLE and shown as unavailable in the UI, instead
 * of listing a tool that can only ever fail (e.g. `dial` on a Wi-Fi tablet, `vibrate` with no
 * motor). Keeps the registry honest per device without maintaining a separate list per model.
 */
object HardwareCheck {

    /** null = available; otherwise a short reason naming the missing hardware. */
    fun missing(ctx: Context, id: String): String? {
        val pm = ctx.packageManager
        fun feat(f: String) = pm.hasSystemFeature(f)
        return when (id) {
            "take_photo" -> if (feat(PackageManager.FEATURE_CAMERA_ANY)) null else "no camera"
            "torch" -> if (feat(PackageManager.FEATURE_CAMERA_FLASH)) null else "no camera flash"
            "record_audio" -> if (feat(PackageManager.FEATURE_MICROPHONE)) null else "no microphone"
            "vibrate" -> if (hasVibrator(ctx)) null else "no vibration motor"
            "wifi_info" -> if (feat(PackageManager.FEATURE_WIFI)) null else "no Wi-Fi radio"
            "get_location" -> if (feat(PackageManager.FEATURE_LOCATION)) null else "no location hardware"
            "dial", "read_sms", "read_call_log", "telephony_info" ->
                if (feat(PackageManager.FEATURE_TELEPHONY)) null else "no telephony (Wi-Fi-only device)"
            "bluetooth_info" -> if (feat(PackageManager.FEATURE_BLUETOOTH)) null else "no Bluetooth radio"
            else -> null
        }
    }

    fun available(ctx: Context, id: String): Boolean = missing(ctx, id) == null

    private fun hasVibrator(ctx: Context): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= 31) {
            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager)
                ?.defaultVibrator?.hasVibrator() == true
        } else {
            @Suppress("DEPRECATION")
            (ctx.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator)?.hasVibrator() == true
        }
    }.getOrDefault(false)
}
