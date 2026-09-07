package com.sixoffive.androidmcp.core

/** The capability registry — the single source of truth for tools, gating, and UI. */
object Capabilities {
    val REGISTRY: List<CapabilityMeta> = listOf(
        CapabilityMeta(
            id = "list_capabilities", title = "List capabilities",
            why = listOf(
                "Let a client see which capabilities are on or off",
                "Show exactly what to enable to unblock a tool",
            ),
            permissions = emptyList(),
            dataExposed = "Only capability on/off + permission status — no personal data",
            risk = "None",
            defaultOn = true, phase = Phase.MVP, highImpact = false,
        ),
        CapabilityMeta(
            id = "device_info", title = "Device info",
            why = listOf(
                "Report model, Android version, RAM and uptime",
                "Help a client tailor actions to this device",
            ),
            permissions = emptyList(),
            dataExposed = "Device model, OS version, memory, uptime (no IMEI or serial)",
            risk = "Low — non-identifying device facts",
            defaultOn = false, phase = Phase.MVP, highImpact = false,
        ),
        CapabilityMeta(
            id = "battery_status", title = "Battery status",
            why = listOf(
                "Report charge level and charging state",
                "Let a client warn before the device dies",
            ),
            permissions = emptyList(),
            dataExposed = "Battery %, charging state, health, temperature",
            risk = "Low",
            defaultOn = false, phase = Phase.MVP, highImpact = false,
        ),
        CapabilityMeta(
            id = "read_sensors", title = "Read sensors",
            why = listOf(
                "Read motion and environment sensors (accelerometer, light, proximity…)",
                "Answer questions about the device's physical state",
            ),
            permissions = emptyList(),
            dataExposed = "Live sensor readings",
            risk = "Low–medium — motion can imply activity",
            defaultOn = false, phase = Phase.MVP, highImpact = false,
        ),
        CapabilityMeta(
            id = "get_location", title = "Location",
            why = listOf(
                "Report the device's current or last-known location",
                "Enable location-aware answers",
            ),
            permissions = listOf("android.permission.ACCESS_COARSE_LOCATION"),
            dataExposed = "Geographic location of the device",
            risk = "High — reveals where you are",
            defaultOn = false, phase = Phase.MVP, highImpact = true,
        ),
        CapabilityMeta(
            id = "post_notification", title = "Post notification",
            why = listOf("Show a notification on the device on request"),
            permissions = listOf("android.permission.POST_NOTIFICATIONS"),
            dataExposed = "Nothing is read; posts content you or the model provide",
            risk = "Low",
            defaultOn = false, phase = Phase.MVP, highImpact = false,
        ),
        CapabilityMeta(
            id = "read_notifications", title = "Read notifications",
            why = listOf(
                "List the device's active notifications",
                "Optionally dismiss a notification",
            ),
            permissions = emptyList(), // special access (Notification Listener), handled by the gate
            dataExposed = "The contents of your notifications",
            risk = "High — notifications carry messages, 2FA codes, and alerts",
            defaultOn = false, phase = Phase.MVP, highImpact = true,
        ),
        CapabilityMeta(
            id = "list_files", title = "List / read files",
            why = listOf("Browse and read files inside folders you explicitly grant"),
            permissions = emptyList(), // SAF grants, handled by the gate
            dataExposed = "Files inside granted folders only",
            risk = "Medium–high depending on the folder granted",
            defaultOn = false, phase = Phase.MVP, highImpact = true,
        ),
        CapabilityMeta(
            id = "take_photo", title = "Take photo",
            why = listOf(
                "Capture a still photo from the front or rear camera on request",
                "Let a client see what the camera sees (scan a document, QR code, or surroundings)",
            ),
            permissions = listOf("android.permission.CAMERA"),
            dataExposed = "A photo of whatever the camera is pointed at",
            risk = "High — anything or anyone in view can be photographed on command",
            defaultOn = false, phase = Phase.V1_1, highImpact = true,
            requiresForegroundNote = true,
        ),
    )

    fun byId(id: String): CapabilityMeta? = REGISTRY.firstOrNull { it.id == id }
}
