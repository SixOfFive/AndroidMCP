package com.sixoffive.androidmcp.server

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * The model-facing contract for every tool: what it does, what arguments it takes, and what a
 * host may assume about calling it.
 *
 * Split out of [Mcp] because the schemas used to be a 100-line chain of `if (cap.id == …)` blocks
 * inside `toolDef` that emitted bare `{"type":"string"}` properties — no `required` anywhere, no
 * property descriptions, no bounds, and no annotations. A model had to discover argument
 * semantics by making failed calls, and for the 19 high-impact tools every wrong guess burns a
 * real Allow/Deny prompt on the device.
 *
 * The bounds here MIRROR the `coerceIn` calls in the handlers — when you change one, change both.
 */
internal object ToolSchemas {

    /** One argument of one tool. */
    data class Prop(
        val name: String,
        val type: String,                    // JSON Schema type: string | integer | boolean
        val description: String,
        val required: Boolean = false,
        val enum: List<String>? = null,
        val min: Int? = null,
        val max: Int? = null,
        val default: JsonElement? = null,
    )

    /**
     * Per-tool metadata.
     *
     * [readOnly], [destructive] and [openWorld] map to the MCP `annotations` object. They are
     * DISPLAY HINTS ONLY — a host may use them to decide what to auto-approve on its side. They
     * have no bearing on this device's gate, which re-checks the toggle, the OS permission and
     * the per-call approval regardless of what any client believes.
     */
    data class Spec(
        val usage: String,
        val args: List<Prop> = emptyList(),
        val readOnly: Boolean = false,
        val destructive: Boolean = false,
        val openWorld: Boolean = false,
    )

    private fun int(
        name: String, description: String, min: Int? = null, max: Int? = null,
        default: Int? = null, required: Boolean = false,
    ) = Prop(name, "integer", description, required, null, min, max, default?.let(::JsonPrimitive))

    private fun str(
        name: String, description: String, required: Boolean = false,
        enum: List<String>? = null, default: String? = null,
    ) = Prop(name, "string", description, required, enum, null, null, default?.let(::JsonPrimitive))

    private fun bool(name: String, description: String, default: Boolean? = null, required: Boolean = false) =
        Prop(name, "boolean", description, required, null, null, null, default?.let(::JsonPrimitive))

    /** Tools with no arguments and no side effects — the common case, so name it once. */
    private fun read(usage: String) = Spec(usage, readOnly = true)

    val SPECS: Map<String, Spec> = mapOf(

        // ---- core & device (pure reads, no arguments) ----
        "list_capabilities" to read(
            "List every capability with its live on/off state, the OS permission it needs, and what " +
                "it exposes. Call this first — tool descriptions are static and do not say what is " +
                "currently enabled."
        ),
        "device_info" to read("Report device model, manufacturer, Android version, RAM and uptime. No IMEI or serial."),
        "battery_status" to read("Report battery percentage, charging state, health and temperature."),
        "read_sensors" to read("Take a one-shot reading of the accelerometer, light, proximity and magnetometer sensors."),
        "wifi_info" to read("Report the current Wi-Fi link: SSID, signal strength (RSSI and level), link speed, frequency."),
        "network_info" to read("Report the active network transport (wifi/cellular/none), whether it is connected and metered, and the carrier."),
        "storage_info" to read("Report internal and external storage totals, free and used space."),
        "thermal_status" to read("Report the device thermal status and remaining thermal headroom."),
        "screen_info" to read("Report screen resolution, density, refresh rate, rotation and screen-off timeout."),
        "volume_info" to read("Report the current and maximum volume of every audio stream, plus the ringer mode."),
        "get_location" to Spec(
            "Report the device's current location, falling back to the last known fix. Returns latitude, longitude, accuracy and age.",
            listOf(bool("address", "Also reverse-geocode the fix to a human-readable street address, when a geocoder backend is available.", default = false)),
            readOnly = true,
        ),
        "telephony_info" to read("Report the mobile network: operator, SIM state, roaming, data state, signal level and country. No phone number, IMEI or IMSI."),
        "bluetooth_info" to read("Report whether a Bluetooth adapter is present, whether it is on, and whether Bluetooth Low Energy is supported. Does not list paired devices."),
        "locale_info" to read("Report the device language, region, timezone, 24-hour clock setting and current local date-time."),
        "dnd_status" to read("Report the current Do Not Disturb / interruption-filter mode and whether the app can change it."),
        "read_clipboard" to read(
            "Read the current clipboard text. Android 10+ returns nothing unless androidmcp is the " +
                "foregrounded app, so this fails while the app is in the background."
        ),
        "elevated_current_app" to read(
            "Report the true foreground app and activity via `dumpsys`. Needs Shizuku or root — a " +
                "normal app cannot see which other app is in front."
        ),

        // ---- reads that take arguments ----
        "read_sms" to Spec(
            "List recently RECEIVED SMS messages, newest first. Does not send anything and cannot read sent messages.",
            listOf(int("limit", "How many messages to return, newest first.", 1, 200, 20)),
            readOnly = true,
        ),
        "read_call_log" to Spec(
            "List recent call history (incoming, outgoing and missed), newest first.",
            listOf(int("limit", "How many call records to return, newest first.", 1, 200, 20)),
            readOnly = true,
        ),
        "read_notifications" to Spec(
            "List the notifications currently showing in the shade. Each entry includes a key and its " +
                "action buttons (with indexes); pass those to notification_action to reply or tap one. " +
                "This tool itself only reads — it cannot dismiss a notification.",
            listOf(int("limit", "How many notifications to return.", 1, 200, 20)),
            readOnly = true,
        ),
        "notification_action" to Spec(
            "Reply to a notification or fire one of its action buttons. Call read_notifications first " +
                "to get the notification's key and its action list (each action's index, and whether " +
                "it accepts a text reply).",
            listOf(
                str("key", "The notification key from read_notifications, copied verbatim.", required = true),
                int("action_index", "Which action to invoke — the index read_notifications showed for it.", min = 0, required = true),
                str("text", "Reply text. Required for a reply action (one that accepts text); ignored for a plain button."),
            ),
            openWorld = true,
        ),
        "list_packages" to Spec(
            "List installed apps as label + package name. Use this to find the exact package name for `run_shortcut`.",
            listOf(
                str("filter", "Case-insensitive SUBSTRING matched against both the app label and the package name (not a glob or regex). Omit to list everything."),
                bool("include_system", "Include pre-installed system apps. Off by default because they dominate the list.", default = false),
                int("limit", "Maximum apps to return.", 1, 2000, 100),
            ),
            readOnly = true,
        ),
        "get_contacts" to Spec(
            "Look up contacts, returning name and phone numbers.",
            listOf(
                // The handler's selection is `DISPLAY_NAME LIKE ?` — searching for digits returns
                // a confident "no contacts match", which reads as "this person isn't in contacts".
                str("query", "Case-insensitive substring matched against the contact's DISPLAY NAME only — NOT against phone numbers. Omit to list all contacts alphabetically."),
                int("limit", "Maximum contacts to return.", 1, 500, 30),
            ),
            readOnly = true,
        ),
        "read_calendar" to Spec(
            "List upcoming calendar events, soonest first.",
            listOf(
                int("days_ahead", "How far ahead to look, in days from now.", 1, 365, 7),
                int("limit", "Maximum events to return.", 1, 500, 30),
            ),
            readOnly = true,
        ),
        "list_files" to Spec(
            "Two modes. With NO arguments: list every file in the folders the owner has shared, each " +
                "line ending in the content:// URI to read it by. With `uri`: return that one file's " +
                "text. Only folders explicitly shared in the app are reachable.",
            listOf(str(
                "uri",
                "A content:// URI copied verbatim from a previous no-argument listing. Anything " +
                    "outside a shared folder is refused, and file:// paths are never accepted. " +
                    "Omit to list instead of read. Text is truncated at 100 KB; binary files are not returned.",
            )),
            readOnly = true,
        ),
        "write_file" to Spec(
            "Create or overwrite a text file inside a folder granted for WRITING. With NO arguments, " +
                "lists the writable folders and their content:// URIs. With `folder` + `name` + " +
                "`content`, writes the file (overwriting any file of the same name in that folder). " +
                "Only folders added under 'Writable folders' in the app are reachable — the read-only " +
                "folders used by list_files are never writable.",
            listOf(
                str("folder", "A writable folder's content:// tree URI, copied verbatim from the no-argument listing. Omit to list the writable folders instead of writing."),
                str("name", "File name to create or overwrite — no path separators, e.g. report.md. Required when writing."),
                str("content", "UTF-8 text to write into the file. Required when writing."),
                str("mime", "MIME type used only when creating a new file.", default = "text/plain"),
            ),
            destructive = true,
        ),

        // ---- capture ----
        "take_photo" to Spec(
            "Take a single still photo with the device camera and return it as an image. Headless — no camera app opens.",
            listOf(str("camera", "Which lens to use.", enum = listOf("back", "front"), default = "back")),
        ),
        "record_audio" to Spec(
            "Record a short clip from the microphone and return it as audio (AAC in MP4). Blocks for the full duration.",
            listOf(int("seconds", "Recording length in seconds.", 1, 30, 5)),
        ),
        "capture_screenshot" to Spec(
            "Capture the current screen via MediaProjection. Requires the owner to have started screen " +
                "sharing in the app first, and shows the system cast indicator while active.",
            readOnly = true,
        ),
        "root_screenshot" to Spec(
            "Capture the screen silently via `screencap` — no consent dialog and no cast indicator. " +
                "Needs Shizuku or root.",
            readOnly = true,
        ),

        // ---- actions ----
        "post_notification" to Spec(
            "Post a notification to the device's notification shade.",
            listOf(
                str("title", "Notification title line.", required = true),
                str("text", "Notification body text.", required = true),
            ),
        ),
        "write_clipboard" to Spec(
            "Replace the device clipboard contents. This overwrites whatever the owner had copied.",
            listOf(str("text", "Text to place on the clipboard.", required = true)),
            destructive = true,
        ),
        "toast" to Spec(
            "Show a brief message on the device screen.",
            listOf(
                str("text", "Message to display.", required = true),
                bool("long", "Show for ~3.5s instead of ~2s.", default = false),
            ),
        ),
        "speak" to Spec(
            "Speak text aloud through the device speaker using the on-device text-to-speech engine. Blocks until the utterance finishes.",
            listOf(str("text", "The text to read aloud. Truncated at 2000 characters.", required = true)),
        ),
        "vibrate" to Spec(
            "Vibrate the device once for a set duration. A single buzz — patterns are not supported.",
            listOf(int("milliseconds", "Buzz duration in milliseconds.", 1, 5000, 300)),
        ),
        "torch" to Spec(
            "Turn the camera flash LED on or off.",
            // `on` used to default to false, so "turn on the flashlight" with no arguments turned
            // it OFF. Required now: there is no sensible default for a toggle.
            listOf(bool("on", "true turns the light on, false turns it off.", required = true)),
        ),
        "set_volume" to Spec(
            "Set one audio stream's volume. The level is clamped to the stream's own range, and DND " +
                "or a fixed-volume output can override the request — the reply states what actually happened.",
            listOf(
                str("stream", "Which audio stream to change.",
                    enum = listOf("music", "ring", "alarm", "notification", "voice_call", "system"), default = "music"),
                int("level", "Target volume as a whole number, not a percentage. The maximum differs per stream — read `volume_info` first.", 0, null, required = true),
                bool("show_ui", "Also show the system volume slider on screen.", default = false),
            ),
        ),
        "media_control" to Spec(
            "Send a media key to whatever app is currently playing.",
            listOf(str("action", "Which media key to send.", required = true,
                enum = listOf("play", "pause", "playpause", "next", "previous", "stop"))),
        ),
        "run_shortcut" to Spec(
            "Launch an installed app by package name. Use `list_packages` to find the exact name.",
            listOf(str("package", "Exact package name, e.g. com.android.settings.", required = true)),
            openWorld = true,
        ),
        "launch_url" to Spec(
            "Open a URL in the device's default handler app.",
            listOf(str("url", "Absolute http:// or https:// URL.", required = true)),
            openWorld = true,
        ),
        "dial" to Spec(
            "Open the dialer pre-filled with a number. Does NOT place the call — the owner must press dial.",
            listOf(str("number", "Phone number to pre-fill.", required = true)),
            openWorld = true,
        ),
        "share_text" to Spec(
            "Open the Android share sheet with text, letting the owner pick an app to send it to.",
            listOf(
                str("text", "Body text to share.", required = true),
                str("subject", "Optional subject line, used by apps such as email."),
            ),
            openWorld = true,
        ),
        "open_settings" to Spec(
            "Open a specific Android Settings screen.",
            // Not required: the handler falls back to "apps" rather than erroring.
            listOf(str("screen", "Which Settings screen to open. Note `app_details` opens androidmcp's own App info page, not another app's.",
                default = "apps",
                enum = listOf("wifi", "bluetooth", "location", "display", "sound", "apps",
                    "app_details", "battery", "date", "security", "home"))),
        ),
        "create_calendar_event" to Spec(
            "Insert an event into a calendar on the device. This writes to a real, possibly shared calendar.",
            listOf(
                str("title", "Event title, shown in the calendar.", required = true),
                // Omitting this silently books now+1h into a real calendar, and the unit is a
                // classic trap: seconds would land the event in 1970.
                int("start_epoch_ms", "Start time as Unix epoch MILLISECONDS (not seconds). Defaults to one hour from now if omitted."),
                int("duration_minutes", "Event length in minutes.", 1, 43200, 60),
                str("location", "Optional location text."),
                int("calendar_id", "Which calendar to write to. Omit to use the device's primary calendar."),
            ),
            destructive = true,
        ),

        // ---- accessibility (non-root, via the accessibility service) ----
        "read_screen" to Spec(
            "Read the current screen as a structured tree of on-screen elements via the accessibility " +
                "service — text, buttons and fields, each with its class, interaction flags and an " +
                "on-screen centre [x,y] you can later tap. A non-root alternative to a screenshot that " +
                "gives structure a picture cannot.",
            listOf(int("max_nodes", "Maximum elements to return, deepest-first traversal.", 1, 2000, 200)),
            readOnly = true,
        ),
        "global_action" to Spec(
            "Perform a device-wide navigation or system action via the accessibility service — no root.",
            listOf(str("action", "Which action to perform.", required = true,
                enum = listOf("back", "home", "recents", "notifications", "quick_settings",
                    "lock_screen", "screenshot", "power_dialog"))),
        ),

        // ---- elevated ----
        "elevated_input" to Spec(
            "Inject input system-wide into ANY app — something a normal Android app cannot do. Needs " +
                "Shizuku or root. Which arguments matter depends on `action`: " +
                "tap → x,y; swipe → x,y,x2,y2; text → text; key → keycode.",
            listOf(
                str("action", "What to inject.", required = true, enum = listOf("tap", "swipe", "text", "key")),
                int("x", "tap/swipe: start X in absolute screen PIXELS (not a percentage). Read `screen_info` for the resolution."),
                int("y", "tap/swipe: start Y in absolute screen pixels."),
                int("x2", "swipe only: end X in absolute screen pixels."),
                int("y2", "swipe only: end Y in absolute screen pixels. The swipe takes a fixed 300 ms."),
                str("text", "text only: the string to type into the focused field. Spaces are sent as %s, as `input text` requires."),
                str("keycode", "key only: an Android keycode, with or without the KEYCODE_ prefix, e.g. BACK, HOME, ENTER, VOLUME_UP."),
            ),
            destructive = true, openWorld = true,
        ),
        "elevated_settings" to Spec(
            "Read or write an Android system setting via `settings get`/`put`. Needs Shizuku or root. " +
                "Writes are confirmed by reading the value back.",
            listOf(
                str("action", "`get` reads a setting, `put` writes one.", required = true, enum = listOf("get", "put")),
                str("namespace", "Which settings table to use.", required = true, enum = listOf("system", "secure", "global")),
                str("key", "Setting key, e.g. screen_brightness.", required = true),
                str("value", "Required for `put`: the new value. Ignored for `get`."),
            ),
            destructive = true,
        ),
        "root_shell" to Spec(
            "Run an arbitrary shell command with elevated privilege (Shizuku's uid 2000, or root's uid 0). " +
                "Output is capped at 1 MB and the command is killed after 20 seconds, so use bounded " +
                "forms — `logcat -d`, not `logcat`.",
            listOf(str("command", "Shell command line, run through `sh -c`. stderr is merged into the output.", required = true)),
            destructive = true, openWorld = true,
        ),
    )

    /** Fallback for a capability with no entry here, so a new tool is never left undescribed. */
    fun specFor(id: String): Spec = SPECS[id] ?: Spec(usage = "")
}
