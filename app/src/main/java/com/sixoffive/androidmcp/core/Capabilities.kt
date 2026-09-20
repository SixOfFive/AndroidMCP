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
            id = "notification_action", title = "Act on a notification",
            why = listOf(
                "Reply to a message notification, or tap one of its action buttons",
                "Let a client answer a chat or clear an alert without opening the app",
            ),
            permissions = emptyList(), // same Notification Listener special access as read_notifications
            dataExposed = "Nothing is read; sends a reply or fires an action button on a notification you name",
            risk = "High — can send messages and trigger actions in other apps on your behalf",
            defaultOn = false, phase = Phase.V1_1, highImpact = true,
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
            id = "write_file", title = "Write files",
            why = listOf(
                "Create or overwrite a file inside a folder you grant for writing",
                "Let a client save output — a note, a report, an export — to your storage",
            ),
            permissions = emptyList(), // separate 'Writable folders' SAF read+write grant, handled by the gate
            dataExposed = "Nothing is read; writes content you or the model provide into a folder you granted for writing",
            risk = "High — creates or overwrites files in the folders you grant for writing (read-only shared folders are never touched)",
            defaultOn = false, phase = Phase.V1_1, highImpact = true,
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
        CapabilityMeta(
            id = "record_audio", title = "Record audio",
            why = listOf("Record a short microphone clip on request", "Let a client hear a sound or capture a note"),
            permissions = listOf("android.permission.RECORD_AUDIO"),
            dataExposed = "Audio from the microphone (anything within earshot)",
            risk = "High — can record conversations and ambient sound",
            defaultOn = false, phase = Phase.V1_1, highImpact = true,
            requiresForegroundNote = true,
        ),
        CapabilityMeta(
            id = "read_sms", title = "Read SMS",
            why = listOf("Read recent received text messages", "Look up a code or message a client needs"),
            permissions = listOf("android.permission.READ_SMS"),
            dataExposed = "The content and senders of your text messages",
            risk = "High — texts carry 2FA codes, private conversations, and alerts",
            defaultOn = false, phase = Phase.V1_1, highImpact = true,
        ),
        CapabilityMeta(
            id = "read_call_log", title = "Read call log",
            why = listOf("Read recent call history (numbers, in/out/missed, time)"),
            permissions = listOf("android.permission.READ_CALL_LOG"),
            dataExposed = "Who you called and who called you, and when",
            risk = "High — reveals your contacts and communication patterns",
            defaultOn = false, phase = Phase.V1_1, highImpact = true,
        ),
        CapabilityMeta(
            id = "read_clipboard", title = "Read clipboard",
            why = listOf("Read the current clipboard text on request"),
            permissions = emptyList(),
            dataExposed = "Whatever you last copied — often passwords or codes",
            risk = "High — clipboards frequently hold secrets",
            defaultOn = false, phase = Phase.V1_1, highImpact = true,
            requiresForegroundNote = true,
        ),
        CapabilityMeta(
            id = "write_clipboard", title = "Write clipboard",
            why = listOf("Set the clipboard text so you can paste it"),
            permissions = emptyList(),
            dataExposed = "Nothing is read; sets content the model provides",
            risk = "Low",
            defaultOn = false, phase = Phase.V1_1, highImpact = false,
        ),
        CapabilityMeta(
            id = "capture_screenshot", title = "Capture screenshot",
            why = listOf(
                "Capture the current screen so a client can see what's displayed",
                "Read on-screen content a client cannot otherwise reach",
            ),
            permissions = emptyList(), // MediaProjection session, not a runtime permission
            dataExposed = "An image of whatever is on your screen right now",
            risk = "High — the screen may show messages, banking, or private content",
            defaultOn = false, phase = Phase.V1_1, highImpact = true,
            requiresForegroundNote = true,
        ),
        CapabilityMeta(
            id = "run_shortcut", title = "Run shortcut",
            why = listOf("Launch an app by package name on request"),
            permissions = emptyList(),
            dataExposed = "Nothing is read; performs an action you approve",
            risk = "Medium — performs actions on your device",
            defaultOn = false, phase = Phase.V1_1, highImpact = true,
        ),
        CapabilityMeta(
            id = "wifi_info", title = "Wi-Fi info",
            why = listOf(
                "Report the current Wi-Fi signal (RSSI + 0-4 level), link speed, and frequency",
                "Help diagnose connectivity, band, or pick a better network",
            ),
            permissions = emptyList(),
            dataExposed = "Current Wi-Fi RSSI/level, link speed, frequency, and SSID/BSSID (SSID/BSSID shown as <unknown ssid> / redacted MAC when location isn't granted)",
            risk = "Low-medium - SSID/BSSID can hint at your physical location",
            defaultOn = false, phase = Phase.V1_1, highImpact = false, rootRequired = false,
        ),
        CapabilityMeta(
            id = "network_info", title = "Network info",
            why = listOf(
                "Report the active transport (wifi/cellular/ethernet/vpn/none) and whether it's connected",
                "Let a client adapt to the link - e.g. avoid large transfers on a metered connection",
            ),
            permissions = emptyList(),
            dataExposed = "Active transport type, connected/validated/metered flags, and carrier name",
            risk = "Low - non-identifying connection facts (carrier name can hint at region/network)",
            defaultOn = false, phase = Phase.V1_1, highImpact = false, rootRequired = false,
        ),
        CapabilityMeta(
            id = "telephony_info", title = "Telephony info",
            why = listOf(
                "Report the mobile network: operator, SIM state, roaming, data state, and signal level",
                "Let a client understand cellular connectivity without location or phone-number access",
            ),
            permissions = emptyList(),
            dataExposed = "Carrier/operator name, SIM state, roaming and data state, coarse signal level, network country - no phone number, IMEI, or IMSI",
            risk = "Low-medium - operator and country can hint at region; no subscriber identifiers",
            defaultOn = false, phase = Phase.V1_1, highImpact = false, rootRequired = false,
        ),
        CapabilityMeta(
            id = "bluetooth_info", title = "Bluetooth info",
            why = listOf(
                "Report whether Bluetooth is present and on, and whether BLE is supported",
                "Let a client check radio state before suggesting a Bluetooth action",
            ),
            permissions = emptyList(),
            dataExposed = "Adapter presence, on/off state, and BLE support - no device names, addresses, or bonded-device list",
            risk = "Low - adapter state only; paired devices are not enumerated (that would need BLUETOOTH_CONNECT)",
            defaultOn = false, phase = Phase.V1_1, highImpact = false, rootRequired = false,
        ),
        CapabilityMeta(
            id = "storage_info", title = "Storage info",
            why = listOf(
                "Report internal and external storage total, used and free space",
                "Let a client warn before the device runs out of space",
            ),
            permissions = emptyList(),
            dataExposed = "Free/used/total capacity of the app-data partition and external volumes — no file names or contents",
            risk = "Low — aggregate storage sizes only, nothing personal",
            defaultOn = false, phase = Phase.V1_1, highImpact = false, rootRequired = false,
        ),
        CapabilityMeta(
            id = "thermal_status", title = "Thermal status",
            why = listOf(
                "Report the OS thermal throttling level (none…shutdown)",
                "Expose thermal headroom so a client can back off heavy work before throttling",
            ),
            permissions = emptyList(),
            dataExposed = "Current thermal throttling state and a 0.0–1.0 headroom estimate — no personal data",
            risk = "Low — a coarse device-temperature signal",
            defaultOn = false, phase = Phase.V1_1, highImpact = false, rootRequired = false,
        ),
        CapabilityMeta(
            id = "screen_info", title = "Screen info",
            why = listOf(
                "Report resolution, density, refresh rate, rotation and whether the screen is awake",
                "Expose the screen-off timeout setting",
            ),
            permissions = emptyList(),
            dataExposed = "Display geometry, refresh rate, rotation, interactive state, screen-off timeout — no screen contents",
            risk = "Low — display characteristics only, no on-screen content",
            defaultOn = false, phase = Phase.V1_1, highImpact = false, rootRequired = false,
        ),
        CapabilityMeta(
            id = "volume_info", title = "Volume info",
            why = listOf(
                "Report each audio stream's current and maximum volume",
                "Tell a client whether the phone is on normal, vibrate, or silent",
            ),
            permissions = emptyList(),
            dataExposed = "Per-stream volume levels and the ringer mode — no personal data",
            risk = "Low — non-identifying audio settings",
            defaultOn = false, phase = Phase.V1_1, highImpact = false, rootRequired = false,
        ),
        CapabilityMeta(
            id = "locale_info", title = "Locale & time",
            why = listOf(
                "Report the device language, region, timezone, 24-hour setting, and current local time",
                "Let a client localise answers and reason about the user's clock",
            ),
            permissions = emptyList(),
            dataExposed = "Language, country/region, timezone, clock format, and the device's current date-time - no personal data",
            risk = "Low - locale and timezone can hint at region",
            defaultOn = false, phase = Phase.V1_1, highImpact = false, rootRequired = false,
        ),
        CapabilityMeta(
            id = "dnd_status", title = "Do Not Disturb status",
            why = listOf(
                "Report the current Do Not Disturb / interruption filter (all, priority, alarms, none)",
                "Let a client know whether notifications are currently being silenced",
            ),
            permissions = emptyList(),
            dataExposed = "The interruption-filter mode and whether the app holds DND policy access - no notification contents",
            risk = "Low - a single interruption-filter setting",
            defaultOn = false, phase = Phase.V1_1, highImpact = false, rootRequired = false,
        ),
        CapabilityMeta(
            id = "torch", title = "Torch (flashlight)",
            why = listOf(
                "Turn the camera flash LED on or off on request",
                "Give a client a controllable light source without opening the camera",
            ),
            permissions = emptyList(),
            dataExposed = "Nothing is read; toggles the flash LED",
            risk = "Low — controls the flashlight only (no image capture, no CAMERA permission)",
            defaultOn = false, phase = Phase.V1_1, highImpact = false, rootRequired = false,
        ),
        CapabilityMeta(
            id = "vibrate", title = "Vibrate",
            why = listOf(
                "Buzz the phone for a requested duration",
                "Give a client a physical, silent way to get attention",
            ),
            permissions = emptyList(),
            dataExposed = "Nothing is read; triggers the vibration motor",
            risk = "Low — brief haptic buzz only",
            defaultOn = false, phase = Phase.V1_1, highImpact = false, rootRequired = false,
        ),
        CapabilityMeta(
            id = "list_packages", title = "List installed apps",
            why = listOf(
                "Report which apps are installed (label + package name)",
                "Help a client target the right app to launch or reference",
            ),
            permissions = emptyList(),
            dataExposed = "Which apps are installed on the device (names and package ids)",
            risk = "Low–medium — the installed-app list can hint at habits, banks, or health apps",
            defaultOn = false, phase = Phase.V1_1, highImpact = false, rootRequired = false,
        ),
        CapabilityMeta(
            id = "launch_url", title = "Open URL",
            why = listOf(
                "Open a web address in the default browser or handler app on request",
                "Let a client hand the user a link to view",
            ),
            permissions = emptyList(),
            dataExposed = "Nothing is read; opens a link you or the model provide",
            risk = "Medium — launches an external activity; a crafted link could open an unexpected app or site",
            defaultOn = false, phase = Phase.V1_1, highImpact = true, rootRequired = false,
        ),
        CapabilityMeta(
            id = "dial", title = "Open dialer",
            why = listOf(
                "Open the phone dialer pre-filled with a number on request",
                "Let a client set up a call the user then confirms",
            ),
            permissions = emptyList(),
            dataExposed = "Nothing is read; pre-fills the dialer with a number you provide",
            risk = "Low — opens the dialer only; it does NOT place the call, the user must press call",
            defaultOn = false, phase = Phase.V1_1, highImpact = true, rootRequired = false,
        ),
        CapabilityMeta(
            id = "get_contacts", title = "Get contacts",
            why = listOf(
                "Look up a contact's phone number on request",
                "Answer 'what's X's number' without opening the phone",
            ),
            permissions = listOf("android.permission.READ_CONTACTS"),
            dataExposed = "Your contacts' names and phone numbers",
            risk = "High — exposes your address book (names and numbers)",
            defaultOn = false, phase = Phase.V1_1, highImpact = true, rootRequired = false,
        ),
        CapabilityMeta(
            id = "read_calendar", title = "Read calendar",
            why = listOf(
                "List upcoming calendar events on request",
                "Answer 'what's on my schedule' without opening the calendar app",
            ),
            permissions = listOf("android.permission.READ_CALENDAR"),
            dataExposed = "Your upcoming events: titles, times, and locations",
            risk = "High — reveals your schedule, meetings, and whereabouts",
            defaultOn = false, phase = Phase.V1_1, highImpact = true, rootRequired = false,
        ),
        CapabilityMeta(
            id = "elevated_input", title = "Inject input (Shizuku/root)",
            why = listOf(
                "Tap, swipe, type text, and send key events system-wide via the shell 'input' command (needs Shizuku or root)",
                "Drive the UI of any app — automate flows a normal, unprivileged app cannot touch",
            ),
            permissions = emptyList(),
            dataExposed = "Nothing is read; injects taps, swipes, text, and key events anywhere on the device",
            risk = "Critical — can operate any app and confirm irreversible actions as if you had physically tapped the screen",
            defaultOn = false, phase = Phase.V1_1, highImpact = true, rootRequired = true,
        ),
        CapabilityMeta(
            id = "set_volume", title = "Set volume",
            why = listOf(
                "Set a specific audio stream's volume level on request",
                "Let a client turn media, ring, alarm, or notification volume up or down",
            ),
            permissions = emptyList(),
            dataExposed = "Nothing is read; changes one audio stream's volume level",
            risk = "Medium — changes device state; can silence the ringer/alarm or blast media volume",
            defaultOn = false, phase = Phase.V1_1, highImpact = true, rootRequired = false,
        ),
        CapabilityMeta(
            id = "media_control", title = "Media control",
            why = listOf(
                "Send a media transport key (play, pause, next, …) to the active media app",
                "Let a client control playback without opening the player",
            ),
            permissions = emptyList(),
            dataExposed = "Nothing is read; sends a media transport key event",
            risk = "Medium — changes playback state on whatever app currently holds the media session",
            defaultOn = false, phase = Phase.V1_1, highImpact = true, rootRequired = false,
        ),
        CapabilityMeta(
            id = "toast", title = "Show toast",
            why = listOf(
                "Flash a brief on-screen message on the device on request",
                "Give a client a lightweight way to surface a note without a persistent notification",
            ),
            permissions = emptyList(),
            dataExposed = "Nothing is read; shows a brief on-screen message you or the model provide",
            risk = "Low — a transient on-screen message only, dismissed automatically",
            defaultOn = false, phase = Phase.V1_1, highImpact = false, rootRequired = false,
        ),
        CapabilityMeta(
            id = "speak", title = "Speak (text-to-speech)",
            why = listOf(
                "Read text aloud through the device speaker on request",
                "Give a client an audible output channel - locate the phone, or hear a short message",
            ),
            permissions = emptyList(),
            dataExposed = "Nothing is read; speaks aloud text you or the model provide",
            risk = "Low - plays synthesised speech through the speaker; audible to anyone nearby",
            defaultOn = false, phase = Phase.V1_1, highImpact = false, rootRequired = false,
        ),
        CapabilityMeta(
            id = "share_text", title = "Share text",
            why = listOf(
                "Open the Android share sheet with text so the user can send it to any app",
                "Hand off a snippet (a link, a note, a message) to email, messaging, or notes",
            ),
            permissions = emptyList(),
            dataExposed = "Nothing is read; hands text you provide to whichever app the user picks in the share sheet",
            risk = "Medium — content leaves androidmcp into another app the user chooses (email, messaging, notes)",
            defaultOn = false, phase = Phase.V1_1, highImpact = true, rootRequired = false,
        ),
        CapabilityMeta(
            id = "open_settings", title = "Open Settings screen",
            why = listOf(
                "Jump the user straight to a specific system Settings screen on request",
                "Save the user hunting through Settings to toggle Wi-Fi, Bluetooth, location, etc.",
            ),
            permissions = emptyList(),
            dataExposed = "Nothing is read; opens a system Settings screen (changes nothing itself)",
            risk = "Low — navigates to a Settings screen; the user makes any change themselves",
            defaultOn = false, phase = Phase.V1_1, highImpact = false, rootRequired = false,
        ),
        CapabilityMeta(
            id = "create_calendar_event", title = "Create calendar event",
            why = listOf(
                "Insert a new event into a writable calendar on request",
                "Let a client schedule a reminder or meeting without opening the calendar app",
            ),
            permissions = listOf("android.permission.READ_CALENDAR", "android.permission.WRITE_CALENDAR"),
            dataExposed = "When 'calendar_id' is omitted, reads calendar metadata (id, display name, access level) to auto-pick a writable calendar; writes an event (title, time, location) and returns the new event id and the calendar it landed in",
            risk = "High - creates a persistent entry in your calendar that others sharing it may see, and can send invites/reminders",
            defaultOn = false, phase = Phase.V1_1, highImpact = true, rootRequired = false,
        ),
        CapabilityMeta(
            id = "read_screen", title = "Read screen (accessibility)",
            why = listOf(
                "Report on-screen content as a structured tree of elements (text, buttons, fields) with tap coordinates",
                "Let a client see and reason about the current UI without root or a screenshot",
            ),
            permissions = emptyList(), // Accessibility special access, handled by the gate
            dataExposed = "The text and structure of whatever is on screen — messages, fields, labels",
            risk = "High — reveals on-screen content, including private messages and what you're typing",
            defaultOn = false, phase = Phase.V1_1, highImpact = true, rootRequired = false,
        ),
        CapabilityMeta(
            id = "global_action", title = "Navigate (accessibility)",
            why = listOf(
                "Perform a device-wide action: back, home, recents, notifications, quick settings, lock, screenshot",
                "Let a client drive system navigation without root",
            ),
            permissions = emptyList(), // Accessibility special access, handled by the gate
            dataExposed = "Nothing is read; performs a navigation or system action you approve",
            risk = "High — can navigate, open the shade/quick settings, lock the device, or trigger a screenshot",
            defaultOn = false, phase = Phase.V1_1, highImpact = true, rootRequired = false,
        ),
        CapabilityMeta(
            id = "tap", title = "Tap (accessibility)",
            why = listOf(
                "Tap a point on the screen (use read_screen to find an element's coordinates)",
                "Let a client operate the UI of any app without root",
            ),
            permissions = emptyList(), // Accessibility special access, handled by the gate
            dataExposed = "Nothing is read; injects a tap at a screen coordinate you approve",
            risk = "Critical — can operate any app and confirm irreversible actions as if you tapped the screen",
            defaultOn = false, phase = Phase.V1_1, highImpact = true, rootRequired = false,
        ),
        CapabilityMeta(
            id = "swipe", title = "Swipe (accessibility)",
            why = listOf(
                "Swipe or scroll between two points on the screen",
                "Let a client scroll lists, dismiss, or drag without root",
            ),
            permissions = emptyList(), // Accessibility special access, handled by the gate
            dataExposed = "Nothing is read; injects a swipe gesture you approve",
            risk = "High — can scroll, dismiss, and drag anywhere on screen",
            defaultOn = false, phase = Phase.V1_1, highImpact = true, rootRequired = false,
        ),
        CapabilityMeta(
            id = "type_text", title = "Type text (accessibility)",
            why = listOf(
                "Set the text of an editable field, targeted by coordinate or the focused one",
                "Let a client fill a field without root",
            ),
            permissions = emptyList(), // Accessibility special access, handled by the gate
            dataExposed = "Nothing is read; replaces the target field's text with content you provide",
            risk = "High — writes into an editable field you point it at, in any app",
            defaultOn = false, phase = Phase.V1_1, highImpact = true, rootRequired = false,
        ),
        // ---- Wave 5 ----
        CapabilityMeta(
            id = "foreground_app", title = "Foreground app & usage",
            why = listOf(
                "Report the app currently in the foreground, and optionally recent app-usage time — no root",
                "Let a client know what the user is doing to give context-aware help",
            ),
            permissions = emptyList(), // Usage-access special access (appop), handled by the gate
            dataExposed = "Which app is on screen now and, for a window, which apps were used and for how long — no screen contents",
            risk = "Medium — reveals app usage, which hints at activity and habits",
            defaultOn = false, phase = Phase.V1_1, highImpact = true, rootRequired = false,
        ),
        CapabilityMeta(
            id = "media_search", title = "Search media library",
            why = listOf(
                "Find photos, videos or audio by date, album/folder or name — metadata only",
                "Let a client locate media without granting a whole folder",
            ),
            permissions = listOf(
                "android.permission.READ_MEDIA_IMAGES",
                "android.permission.READ_MEDIA_VIDEO",
                "android.permission.READ_MEDIA_AUDIO",
            ),
            dataExposed = "Media metadata — file name, date, size, type, dimensions/duration, album/folder (not the file bytes)",
            risk = "Medium — reveals what media you have and when it was captured",
            defaultOn = false, phase = Phase.V1_1, highImpact = true, rootRequired = false,
        ),
        CapabilityMeta(
            id = "record_screen", title = "Record screen",
            why = listOf("Record the screen to a short video (needs the same one-time screen-share consent as capture_screenshot)"),
            permissions = emptyList(), // MediaProjection consent, handled in the handler
            dataExposed = "A video of whatever is on your screen for the recording window",
            risk = "High — captures everything shown on screen over time",
            defaultOn = false, phase = Phase.V1_1, highImpact = true, rootRequired = false,
        ),
        CapabilityMeta(
            id = "write_contact", title = "Add / update contact",
            why = listOf(
                "Create a new contact, or add a phone/email to an existing one",
                "Let a client save contact details on request",
            ),
            permissions = listOf("android.permission.WRITE_CONTACTS"),
            dataExposed = "Nothing is read; writes a contact (name, phone, email) into your address book",
            risk = "High — modifies your contacts",
            defaultOn = false, phase = Phase.V1_1, highImpact = true, rootRequired = false,
        ),
        // ---- Wave 6 (finish the pairs) ----
        CapabilityMeta(
            id = "set_dnd", title = "Set Do Not Disturb",
            why = listOf(
                "Change the interruption filter (all / priority / alarms only / total silence)",
                "The write side of dnd_status — silence or unsilence the device on request",
            ),
            permissions = emptyList(), // Do Not Disturb access (notification-policy), handled by the gate
            dataExposed = "Nothing is read; changes how the device interrupts you (DND mode)",
            risk = "Medium — can silence calls and alarms, or turn silencing off",
            defaultOn = false, phase = Phase.V1_1, highImpact = true, rootRequired = false,
        ),
        CapabilityMeta(
            id = "set_brightness", title = "Set screen brightness",
            why = listOf(
                "Set the screen brightness (0–100%) — the write side of screen_info",
                "Dim or brighten the display on request",
            ),
            permissions = emptyList(), // WRITE_SETTINGS special access, handled by the gate
            dataExposed = "Nothing is read; changes the display brightness (a system setting)",
            risk = "Low–medium — changes a device-wide display setting",
            defaultOn = false, phase = Phase.V1_1, highImpact = true, rootRequired = false,
        ),
        CapabilityMeta(
            id = "delete_contact", title = "Delete contact",
            why = listOf(
                "Delete a contact by display name — the inverse of write_contact",
                "Remove a contact on request",
            ),
            permissions = listOf("android.permission.WRITE_CONTACTS"),
            dataExposed = "Nothing is read; removes a contact (and its numbers/emails) from your address book",
            risk = "High — permanently deletes contacts",
            defaultOn = false, phase = Phase.V1_1, highImpact = true, rootRequired = false,
        ),
        CapabilityMeta(
            id = "delete_calendar_event", title = "Delete calendar event",
            why = listOf(
                "Delete a calendar event by id (from read_calendar) — the inverse of create_calendar_event",
                "Cancel an event on request",
            ),
            permissions = listOf("android.permission.WRITE_CALENDAR"),
            dataExposed = "Nothing is read; removes an event from your calendar",
            risk = "High — permanently deletes a calendar event",
            defaultOn = false, phase = Phase.V1_1, highImpact = true, rootRequired = false,
        ),
        CapabilityMeta(
            id = "delete_file", title = "Delete file",
            why = listOf(
                "Delete a file inside a granted writable folder — the inverse of write_file",
                "Remove a file on request, confined to the writable-folders grant",
            ),
            permissions = emptyList(), // SAF writable-folders grant, handled by the gate + containment
            dataExposed = "Nothing is read; permanently deletes a file within a writable folder",
            risk = "High — permanently deletes files (only inside folders you granted for writing)",
            defaultOn = false, phase = Phase.V1_1, highImpact = true, rootRequired = false,
        ),
        CapabilityMeta(
            id = "elevated_current_app", title = "Foreground app (Shizuku/root)",
            why = listOf(
                "Report the app/activity currently in the foreground via the shell (needs Shizuku or root)",
                "Let a client know what the user is looking at to give context-aware help",
            ),
            permissions = emptyList(),
            dataExposed = "The package and activity of the app currently on screen — no screen contents",
            risk = "Low–medium — reveals which app is in use, hinting at the user's current activity",
            defaultOn = false, phase = Phase.V1_1, highImpact = false, rootRequired = true,
        ),
        CapabilityMeta(
            id = "elevated_settings", title = "Read/write settings (Shizuku/root)",
            why = listOf(
                "Read or change a Settings.System/Secure/Global value via the shell 'settings' command (needs Shizuku or root)",
                "Toggle settings a normal app cannot write — e.g. adb-only secure or global values",
            ),
            permissions = emptyList(),
            dataExposed = "Reads any system/secure/global setting value; can change device-wide settings",
            risk = "High — writing secure/global settings can alter device behavior, security, or connectivity",
            defaultOn = false, phase = Phase.V1_1, highImpact = true, rootRequired = true,
        ),
        CapabilityMeta(
            id = "root_screenshot", title = "Silent screenshot (Shizuku/root)",
            why = listOf("Capture the screen silently — no consent prompt or cast indicator (needs Shizuku or root)"),
            permissions = emptyList(),
            dataExposed = "An image of whatever is on your screen",
            risk = "High — silent, indicator-free screen capture",
            defaultOn = false, phase = Phase.V1_1, highImpact = true, rootRequired = true,
        ),
        CapabilityMeta(
            id = "root_shell", title = "Elevated shell (Shizuku/root)",
            why = listOf("Run a shell command as root — covers input injection, any-file read, dumpsys, and more (needs Shizuku or root)"),
            permissions = emptyList(),
            dataExposed = "Full device access — anything a root shell can reach",
            risk = "Critical — unrestricted root command execution",
            defaultOn = false, phase = Phase.V1_1, highImpact = true, rootRequired = true,
        ),
    )

    fun byId(id: String): CapabilityMeta? = REGISTRY.firstOrNull { it.id == id }

    /**
     * Capabilities that can be enabled AND used with **no further input from the user**: no runtime
     * permission to grant, no per-call Allow/Deny approval, and no elevated (Shizuku/root) access.
     * The three special-access capabilities (`read_notifications` → notification-listener,
     * `list_files` → SAF folder grant, `capture_screenshot` → MediaProjection consent) are all
     * `highImpact`, so they fall out via that flag — no id needs to be named here. Metadata-only, so
     * it stays a pure unit test with no device or Context.
     *
     * Backs the "Enable all (no prompts)" button. Anything not in this set stays for the user to
     * enable deliberately, because turning it on would (or its use would) require a grant or a tap.
     */
    fun noInterventionIds(): Set<String> =
        REGISTRY.filter { it.permissions.isEmpty() && !it.highImpact && !it.rootRequired }
            .map { it.id }
            .toSet()

    /**
     * The "Read-only" preset for a scoped client token: the low-risk tools that never trigger a
     * per-call approval and need no elevation — `!highImpact && !rootRequired`. Note this is
     * "low-risk", not literally read-only: it includes trivial writes (torch, vibrate, toast,
     * write_clipboard) and excludes *sensitive* reads (read_sms, get_contacts, capture_screenshot),
     * which are high-impact by design. Use the custom per-capability picker for anything finer.
     */
    fun lowRiskPresetIds(): Set<String> =
        REGISTRY.filter { !it.highImpact && !it.rootRequired }.map { it.id }.toSet()

    /** UI grouping so the (now 40-entry) list is navigable. Ordered; headers shown in this order. */
    data class Category(val id: String, val label: String)

    val CATEGORIES: List<Category> = listOf(
        Category("core", "Core & device"),
        Category("sensors", "Sensors & battery"),
        Category("location", "Location & network"),
        Category("state", "Device state"),
        Category("messaging", "Notifications & messages"),
        Category("personal", "Contacts & calendar"),
        Category("files", "Files"),
        Category("media", "Camera · mic · screen"),
        Category("clipboard", "Clipboard"),
        Category("actions", "Actions & apps"),
        Category("accessibility", "Accessibility (read & control screen)"),
        Category("elevated", "Elevated (Shizuku / root)"),
    )

    fun categoryOf(id: String): String = when (id) {
        "list_capabilities", "device_info" -> "core"
        "battery_status", "read_sensors" -> "sensors"
        "get_location", "wifi_info", "network_info", "telephony_info", "bluetooth_info" -> "location"
        "storage_info", "thermal_status", "screen_info", "volume_info", "locale_info", "dnd_status",
        "foreground_app", "set_dnd", "set_brightness" -> "state"
        "read_notifications", "notification_action", "post_notification", "read_sms", "read_call_log" -> "messaging"
        "get_contacts", "read_calendar", "create_calendar_event", "write_contact",
        "delete_contact", "delete_calendar_event" -> "personal"
        "list_files", "write_file", "media_search", "delete_file" -> "files"
        "take_photo", "record_audio", "capture_screenshot", "record_screen" -> "media"
        "read_clipboard", "write_clipboard" -> "clipboard"
        "run_shortcut", "list_packages", "launch_url", "dial", "torch", "vibrate",
        "set_volume", "media_control", "toast", "share_text", "open_settings", "speak" -> "actions"
        "read_screen", "global_action", "tap", "swipe", "type_text" -> "accessibility"
        "root_screenshot", "root_shell", "elevated_input", "elevated_current_app", "elevated_settings" -> "elevated"
        else -> "actions"
    }

    fun inCategory(catId: String): List<CapabilityMeta> = REGISTRY.filter { categoryOf(it.id) == catId }
}
