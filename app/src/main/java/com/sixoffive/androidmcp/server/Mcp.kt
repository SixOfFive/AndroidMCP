package com.sixoffive.androidmcp.server

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.LocationManager
import android.provider.CalendarContract.Instances
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import com.sixoffive.androidmcp.core.AuditLog
import com.sixoffive.androidmcp.core.Capabilities
import com.sixoffive.androidmcp.core.CapabilityMeta
import com.sixoffive.androidmcp.core.ConfigStore
import com.sixoffive.androidmcp.core.GateEngine
import com.sixoffive.androidmcp.core.Elevated
import com.sixoffive.androidmcp.core.GateResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Hand-rolled MCP JSON-RPC handler over Streamable HTTP. Returns a JSON string, or null for notifications. */
object Mcp {
    private const val PROTOCOL = "2025-06-18"
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun handle(ctx: Context, body: String, client: String): String? {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull()
            ?: return error(JsonNull, -32700, "Parse error")
        if (root !is JsonObject) return error(JsonNull, -32600, "Invalid Request")
        val id = root["id"] ?: JsonNull
        return when (val method = root["method"]?.jsonPrimitive?.contentOrNull) {
            "initialize" -> result(id, buildJsonObject {
                put("protocolVersion", PROTOCOL)
                putJsonObject("capabilities") { putJsonObject("tools") {} }
                putJsonObject("serverInfo") { put("name", "androidmcp"); put("version", "0.1.0") }
            })
            "notifications/initialized", "notifications/cancelled" -> null
            "ping" -> result(id, buildJsonObject {})
            "tools/list" -> result(id, buildJsonObject {
                putJsonArray("tools") { Capabilities.REGISTRY.forEach { add(toolDef(ctx, it)) } }
            })
            "tools/call" -> toolsCall(ctx, id, root, client)
            else -> error(id, -32601, "Method not found: $method")
        }
    }

    private fun toolDef(ctx: Context, cap: CapabilityMeta): JsonObject = buildJsonObject {
        put("name", cap.id)
        val status = if (ConfigStore.isEnabled(cap.id)) "enabled" else "disabled"
        val hw = com.sixoffive.androidmcp.core.HardwareCheck.missing(ctx, cap.id)
        put("description", "${cap.title}. Why: ${cap.why.joinToString("; ")}. " +
            "Exposes: ${cap.dataExposed}. [currently $status]" +
            (if (hw != null) " [unavailable on this device: $hw]" else ""))
        put("inputSchema", buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                if (cap.id == "post_notification") {
                    putJsonObject("title") { put("type", "string") }
                    putJsonObject("text") { put("type", "string") }
                }
                if (cap.id == "take_photo") {
                    putJsonObject("camera") { put("type", "string"); putJsonArray("enum") { add("back"); add("front") } }
                }
                if (cap.id == "write_clipboard") {
                    putJsonObject("text") { put("type", "string") }
                }
                if (cap.id == "read_sms" || cap.id == "read_call_log" || cap.id == "read_notifications") {
                    putJsonObject("limit") { put("type", "integer") }
                }
                if (cap.id == "list_files") {
                    putJsonObject("uri") { put("type", "string") }
                }
                if (cap.id == "run_shortcut") {
                    putJsonObject("package") { put("type", "string") }
                }
                if (cap.id == "root_shell") {
                    putJsonObject("command") { put("type", "string") }
                }
                if (cap.id == "record_audio") {
                    putJsonObject("seconds") { put("type", "integer") }
                }
                if (cap.id == "torch") {
                    putJsonObject("on") { put("type", "boolean") }
                }
                if (cap.id == "vibrate") {
                    putJsonObject("milliseconds") { put("type", "integer") }
                }
                if (cap.id == "list_packages") {
                    putJsonObject("filter") { put("type", "string") }
                    putJsonObject("include_system") { put("type", "boolean") }
                    putJsonObject("limit") { put("type", "integer") }
                }
                if (cap.id == "launch_url") {
                    putJsonObject("url") { put("type", "string") }
                }
                if (cap.id == "dial") {
                    putJsonObject("number") { put("type", "string") }
                }
                if (cap.id == "get_contacts") {
                    putJsonObject("query") { put("type", "string") }
                    putJsonObject("limit") { put("type", "integer") }
                }
                if (cap.id == "read_calendar") {
                    putJsonObject("days_ahead") { put("type", "integer") }
                    putJsonObject("limit") { put("type", "integer") }
                }
                if (cap.id == "elevated_input") {
                    putJsonObject("action") { put("type", "string"); putJsonArray("enum") { add("tap"); add("swipe"); add("text"); add("key"); } }
                    putJsonObject("x") { put("type", "integer") }
                    putJsonObject("y") { put("type", "integer") }
                    putJsonObject("x2") { put("type", "integer") }
                    putJsonObject("y2") { put("type", "integer") }
                    putJsonObject("text") { put("type", "string") }
                    putJsonObject("keycode") { put("type", "string") }
                }
                if (cap.id == "set_volume") {
                    putJsonObject("stream") { put("type", "string"); putJsonArray("enum") { add("music"); add("ring"); add("alarm"); add("notification"); add("system"); } }
                    putJsonObject("level") { put("type", "integer") }
                    putJsonObject("show_ui") { put("type", "boolean") }
                }
                if (cap.id == "media_control") {
                    putJsonObject("action") { put("type", "string"); putJsonArray("enum") { add("play"); add("pause"); add("playpause"); add("next"); add("previous"); add("stop"); } }
                }
                if (cap.id == "toast") {
                    putJsonObject("text") { put("type", "string") }
                    putJsonObject("long") { put("type", "boolean") }
                }
                if (cap.id == "share_text") {
                    putJsonObject("text") { put("type", "string") }
                    putJsonObject("subject") { put("type", "string") }
                }
                if (cap.id == "open_settings") {
                    putJsonObject("screen") { put("type", "string"); putJsonArray("enum") { add("wifi"); add("bluetooth"); add("location"); add("display"); add("sound"); add("apps"); add("app_details"); add("battery"); add("date"); add("security"); add("home"); } }
                }
                if (cap.id == "create_calendar_event") {
                    putJsonObject("title") { put("type", "string") }
                    putJsonObject("start_epoch_ms") { put("type", "integer") }
                    putJsonObject("duration_minutes") { put("type", "integer") }
                    putJsonObject("location") { put("type", "string") }
                    putJsonObject("calendar_id") { put("type", "integer") }
                }
                if (cap.id == "elevated_settings") {
                    putJsonObject("action") { put("type", "string"); putJsonArray("enum") { add("get"); add("put"); } }
                    putJsonObject("namespace") { put("type", "string"); putJsonArray("enum") { add("system"); add("secure"); add("global"); } }
                    putJsonObject("key") { put("type", "string") }
                    putJsonObject("value") { put("type", "string") }
                }
            }
        })
    }

    private suspend fun toolsCall(ctx: Context, id: JsonElement, root: JsonObject, client: String): String {
        val params = root["params"] as? JsonObject
        val name = params?.get("name")?.jsonPrimitive?.contentOrNull
        val args = params?.get("arguments") as? JsonObject ?: JsonObject(emptyMap())
        val cap = name?.let { Capabilities.byId(it) }
            ?: return result(id, errorResult("Unknown tool: $name"))

        return when (val gate = GateEngine.evaluate(ctx, cap)) {
            is GateResult.Denied -> {
                AuditLog.record(cap.id, client, false, gate.reason.name)
                result(id, refusalResult(cap, gate))
            }
            GateResult.Allowed -> {
                if (!ApprovalManager.require(ctx, cap, client)) {
                    AuditLog.record(cap.id, client, false, "REQUIRES_USER_APPROVAL")
                    return result(id, approvalRefusal(cap))
                }
                val content = runCatching { withContext(Dispatchers.IO) { execute(ctx, cap, args) } }
                    .getOrElse { listOf(textBlk("error: ${it.message}")) }
                AuditLog.record(cap.id, client, true, "ok")
                result(id, successResult(content))
            }
        }
    }

    // ---- result envelopes ----

    private fun result(id: JsonElement, res: JsonElement): String = buildJsonObject {
        put("jsonrpc", "2.0"); put("id", id); put("result", res)
    }.toString()

    private fun error(id: JsonElement, code: Int, message: String): String = buildJsonObject {
        put("jsonrpc", "2.0"); put("id", id)
        putJsonObject("error") { put("code", code); put("message", message) }
    }.toString()

    private fun textBlk(s: String): JsonObject = buildJsonObject { put("type", "text"); put("text", s) }
    private fun imageBlk(b64: String, mime: String): JsonObject = buildJsonObject {
        put("type", "image"); put("data", b64); put("mimeType", mime)
    }
    private fun audioBlk(b64: String, mime: String): JsonObject = buildJsonObject {
        put("type", "audio"); put("data", b64); put("mimeType", mime)
    }
    private fun resourceLinkBlk(uri: String, name: String, mime: String): JsonObject = buildJsonObject {
        put("type", "resource_link"); put("uri", uri); put("name", name); put("mimeType", mime)
    }

    /** Inline base64 (default) or a fetchable resource_link (per the mediaAsLinks toggle). */
    private fun mediaBlocks(bytes: ByteArray, mime: String, name: String, isImage: Boolean): List<JsonObject> =
        if (ConfigStore.current.mediaAsLinks) {
            val (mid, nonce) = MediaStore.put(bytes, mime)
            val scheme = if (ConfigStore.current.tls) "https" else "http"
            // reachableHost, not bindHost: LAN binds 0.0.0.0 but the fetchable address is the LAN IP.
            val host = Net.reachableHost(ConfigStore.current.bind)
            listOf(resourceLinkBlk("$scheme://$host:${ConfigStore.current.port}/media/$mid?k=$nonce", name, mime))
        } else {
            val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            listOf(if (isImage) imageBlk(b64, mime) else audioBlk(b64, mime))
        }

    private fun successResult(content: List<JsonObject>): JsonObject = buildJsonObject {
        putJsonArray("content") { content.forEach { add(it) } }
        put("isError", false)
    }

    private fun errorResult(text: String): JsonObject = buildJsonObject {
        putJsonArray("content") { add(buildJsonObject { put("type", "text"); put("text", text) }) }
        put("isError", true)
    }

    private fun refusalResult(cap: CapabilityMeta, d: GateResult.Denied): JsonObject = buildJsonObject {
        putJsonArray("content") {
            add(buildJsonObject { put("type", "text"); put("text", "${cap.title} is not available. ${d.remediation}") })
        }
        putJsonObject("structuredContent") {
            put("status", "capability_disabled")
            put("capability", cap.id)
            put("reason_code", d.reason.name)
            put("gate_failed", gateFailed(d))
            put("app_toggle", cap.toggleLabel)
            put("app_toggle_enabled", d.toggleEnabled)
            put("os_permission", cap.permissions.joinToString())
            put("os_permission_granted", d.permissionGranted)
            putJsonArray("why_required") { cap.why.forEach { add(it) } }
            put("data_exposed", cap.dataExposed)
            put("remediation", d.remediation)
            put("retriable", d.retriable)
        }
        put("isError", true)
    }

    private fun approvalRefusal(cap: CapabilityMeta): JsonObject = buildJsonObject {
        putJsonArray("content") {
            add(buildJsonObject {
                put("type", "text")
                put("text", "${cap.title} needs your approval on the device. Approve the prompt (or arm the capability in the app), then retry.")
            })
        }
        putJsonObject("structuredContent") {
            put("status", "requires_user_approval")
            put("capability", cap.id)
            put("reason_code", "REQUIRES_USER_APPROVAL")
            put("gate_failed", "user_approval")
            putJsonArray("why_required") { cap.why.forEach { add(it) } }
            put("data_exposed", cap.dataExposed)
            put("remediation", "High-impact tool: approve the on-device notification prompt, or arm this capability, then retry.")
            put("retriable", true)
        }
        put("isError", true)
    }

    private fun gateFailed(d: GateResult.Denied): String = when {
        !d.toggleEnabled -> "app_toggle"
        !d.permissionGranted -> "os_permission"
        else -> "special_access"
    }

    // ---- capability runners ----

    private suspend fun execute(ctx: Context, cap: CapabilityMeta, args: JsonObject): List<JsonObject> = when (cap.id) {
        "list_capabilities" -> listOf(textBlk(listCapabilities(ctx)))
        "device_info" -> listOf(textBlk(deviceInfo(ctx)))
        "battery_status" -> listOf(textBlk(batteryStatus(ctx)))
        "read_sensors" -> listOf(textBlk(sensorSnapshot(ctx)))
        "get_location" -> listOf(textBlk(location(ctx)))
        "post_notification" -> listOf(textBlk(postNotification(ctx, args)))
        "take_photo" -> takePhoto(ctx, args)
        "record_audio" -> recordAudio(ctx, args)
        "capture_screenshot" -> screenshot(ctx)
        "read_sms" -> listOf(textBlk(smsRead(ctx, args)))
        "read_call_log" -> listOf(textBlk(callLog(ctx, args)))
        "read_clipboard" -> listOf(textBlk(clipboardRead(ctx)))
        "write_clipboard" -> listOf(textBlk(clipboardWrite(ctx, args)))
        "read_notifications" -> listOf(textBlk(readNotifications(args)))
        "list_files" -> listOf(textBlk(filesRunner(ctx, args)))
        "run_shortcut" -> listOf(textBlk(runShortcut(ctx, args)))
        "wifi_info" -> listOf(textBlk(wifiInfo(ctx)))
        "network_info" -> listOf(textBlk(networkInfo(ctx)))
        "storage_info" -> listOf(textBlk(storageInfo(ctx)))
        "thermal_status" -> listOf(textBlk(thermalStatus(ctx)))
        "screen_info" -> listOf(textBlk(screenInfo(ctx)))
        "volume_info" -> listOf(textBlk(volumeInfo(ctx)))
        "torch" -> listOf(textBlk(torch(ctx, args)))
        "vibrate" -> listOf(textBlk(vibrate(ctx, args)))
        "list_packages" -> listOf(textBlk(listPackages(ctx, args)))
        "launch_url" -> listOf(textBlk(launchUrl(ctx, args)))
        "dial" -> listOf(textBlk(dial(ctx, args)))
        "get_contacts" -> listOf(textBlk(contactsRead(ctx, args)))
        "read_calendar" -> listOf(textBlk(calendarRead(ctx, args)))
        "elevated_input" -> listOf(textBlk(elevatedInput(ctx, args)))
        "set_volume" -> listOf(textBlk(setVolume(ctx, args)))
        "media_control" -> listOf(textBlk(mediaControl(ctx, args)))
        "toast" -> listOf(textBlk(toast(ctx, args)))
        "share_text" -> listOf(textBlk(shareText(ctx, args)))
        "open_settings" -> listOf(textBlk(openSettings(ctx, args)))
        "create_calendar_event" -> listOf(textBlk(createCalendarEvent(ctx, args)))
        "elevated_current_app" -> listOf(textBlk(elevatedCurrentApp(ctx)))
        "elevated_settings" -> listOf(textBlk(elevatedSettings(ctx, args)))
        "root_screenshot" -> rootScreenshot()
        "root_shell" -> listOf(textBlk(rootShell(args)))
        else -> listOf(textBlk("not implemented: ${cap.id}"))
    }

    private fun listCapabilities(ctx: Context): String = Capabilities.REGISTRY.joinToString("\n") { c ->
        val on = ConfigStore.isEnabled(c.id)
        "${c.id}: ${if (on) "enabled" else "disabled"} — ${c.title} (phase ${c.phase})"
    }

    private fun deviceInfo(ctx: Context): String {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        fun mb(b: Long) = "%.0f MB".format(b / 1024.0 / 1024.0)
        return buildString {
            appendLine("manufacturer: ${Build.MANUFACTURER}")
            appendLine("model: ${Build.MODEL}")
            appendLine("device: ${Build.DEVICE}")
            appendLine("android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("total RAM: ${mb(mem.totalMem)}")
            appendLine("available RAM: ${mb(mem.availMem)}")
            appendLine("low memory: ${mem.lowMemory}")
            append("uptime: ${SystemClock.elapsedRealtime() / 1000} s")
        }
    }

    private fun batteryStatus(ctx: Context): String {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return "battery info unavailable"
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val status = when (i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
            BatteryManager.BATTERY_STATUS_FULL -> "full"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not charging"
            else -> "unknown"
        }
        val temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) / 10.0
        return "level: $pct%\nstatus: $status\ntemperature: $temp C"
    }

    private fun sensorSnapshot(ctx: Context): String {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val wanted = listOf(
            Sensor.TYPE_ACCELEROMETER to "accelerometer",
            Sensor.TYPE_LIGHT to "light",
            Sensor.TYPE_PROXIMITY to "proximity",
            Sensor.TYPE_MAGNETIC_FIELD to "magnetometer",
        )
        val results = java.util.Collections.synchronizedMap(LinkedHashMap<String, String>())
        val present = wanted.filter { sm.getDefaultSensor(it.first) != null }
        val latch = CountDownLatch(present.size)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val label = wanted.firstOrNull { it.first == e.sensor.type }?.second ?: return
                synchronized(results) {
                    if (!results.containsKey(label)) {
                        results[label] = e.values.joinToString(", ") { "%.3f".format(it) }
                        latch.countDown()
                        sm.unregisterListener(this, e.sensor)
                    }
                }
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        present.forEach { (t, _) -> sm.getDefaultSensor(t)?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_FASTEST) } }
        latch.await(800, TimeUnit.MILLISECONDS)
        sm.unregisterListener(listener)
        wanted.forEach { (t, label) -> if (sm.getDefaultSensor(t) == null) results.putIfAbsent(label, "unavailable") }
        return synchronized(results) { results.entries.joinToString("\n") { "${it.key}: ${it.value}" } }
    }

    private fun location(ctx: Context): String {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        val best = providers.mapNotNull { p ->
            runCatching { if (lm.isProviderEnabled(p)) lm.getLastKnownLocation(p) else null }.getOrNull()
        }.maxByOrNull { it.time } ?: return "no last-known location available (try again after a location fix)"
        return "lat: ${best.latitude}\nlon: ${best.longitude}\naccuracy: ${best.accuracy} m\nprovider: ${best.provider}\nage: ${(System.currentTimeMillis() - best.time) / 1000} s"
    }

    private fun postNotification(ctx: Context, args: JsonObject): String {
        val title = args["title"]?.jsonPrimitive?.contentOrNull ?: "androidmcp"
        val text = args["text"]?.jsonPrimitive?.contentOrNull ?: "(no text)"
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val chId = "androidmcp_posted"
        nm.createNotificationChannel(NotificationChannel(chId, "Posted by MCP", NotificationManager.IMPORTANCE_DEFAULT))
        val n = NotificationCompat.Builder(ctx, chId)
            .setSmallIcon(com.sixoffive.androidmcp.R.drawable.ic_stat_mcp)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        nm.notify((System.currentTimeMillis() % 100000).toInt(), n)
        return "posted notification: \"$title\""
    }

    private suspend fun takePhoto(ctx: Context, args: JsonObject): List<JsonObject> {
        val facing = args["camera"]?.jsonPrimitive?.contentOrNull ?: "back"
        val jpeg = CameraCapture.capture(ctx, facing)
            ?: return listOf(textBlk("Camera capture failed or timed out — another app may hold the camera, or the app is backgrounded (open androidmcp and retry)."))
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
        runCatching {
            val dir = java.io.File(ctx.filesDir, "photos").apply { mkdirs() }
            java.io.File(dir, "last.jpg").writeBytes(jpeg)
        }
        return listOf(textBlk("Captured ${opts.outWidth}x${opts.outHeight} JPEG from the $facing camera (${jpeg.size} bytes).")) +
            mediaBlocks(jpeg, "image/jpeg", "photo-$facing.jpg", isImage = true)
    }

    private suspend fun recordAudio(ctx: Context, args: JsonObject): List<JsonObject> {
        val secs = (args["seconds"]?.jsonPrimitive?.intOrNull ?: 5).coerceIn(1, 30)
        val bytes = AudioCapture.record(ctx, secs)
            ?: return listOf(textBlk("Audio capture failed — the mic may be in use, or the app is backgrounded (open androidmcp and retry)."))
        runCatching {
            val dir = java.io.File(ctx.filesDir, "audio").apply { mkdirs() }
            java.io.File(dir, "last.m4a").writeBytes(bytes)
        }
        return listOf(textBlk("Recorded ${secs}s of audio (${bytes.size} bytes, AAC/MP4).")) +
            mediaBlocks(bytes, "audio/mp4", "audio.m4a", isImage = false)
    }

    private suspend fun screenshot(ctx: Context): List<JsonObject> {
        if (ProjectionHolder.projection == null) {
            return listOf(textBlk("Screen capture isn't active. Open androidmcp and tap 'Start screen sharing' (Android requires a one-time on-device consent), then retry."))
        }
        val jpeg = ScreenCapture.capture(ctx)
            ?: return listOf(textBlk("Screen capture failed — the projection may have been revoked. Re-start screen sharing in androidmcp."))
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
        runCatching {
            val dir = java.io.File(ctx.filesDir, "screens").apply { mkdirs() }
            java.io.File(dir, "last.jpg").writeBytes(jpeg)
        }
        return listOf(textBlk("Captured screen ${opts.outWidth}x${opts.outHeight} (${jpeg.size} bytes).")) +
            mediaBlocks(jpeg, "image/jpeg", "screen.jpg", isImage = true)
    }

    private fun smsRead(ctx: Context, args: JsonObject): String {
        val limit = args["limit"]?.jsonPrimitive?.intOrNull ?: 20
        val sb = StringBuilder(); var n = 0
        val cur = ctx.contentResolver.query(
            android.net.Uri.parse("content://sms/inbox"),
            arrayOf("address", "body", "date"), null, null, "date DESC",
        ) ?: return "SMS provider not accessible"
        cur.use { c ->
            val ai = c.getColumnIndex("address"); val bi = c.getColumnIndex("body"); val di = c.getColumnIndex("date")
            val fmt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US)
            while (c.moveToNext() && n < limit) {
                val date = fmt.format(java.util.Date(c.getLong(di)))
                val body = c.getString(bi)?.replace("\n", " ")?.take(200)
                sb.append("[$date] ${c.getString(ai)}: $body\n"); n++
            }
        }
        return if (n == 0) "no messages" else sb.toString().trim()
    }

    private fun callLog(ctx: Context, args: JsonObject): String {
        val limit = args["limit"]?.jsonPrimitive?.intOrNull ?: 20
        val sb = StringBuilder(); var n = 0
        val cur = ctx.contentResolver.query(
            android.provider.CallLog.Calls.CONTENT_URI,
            arrayOf(
                android.provider.CallLog.Calls.NUMBER,
                android.provider.CallLog.Calls.TYPE,
                android.provider.CallLog.Calls.DATE,
                android.provider.CallLog.Calls.DURATION,
            ), null, null, android.provider.CallLog.Calls.DATE + " DESC",
        ) ?: return "call log not accessible"
        cur.use { c ->
            val fmt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US)
            while (c.moveToNext() && n < limit) {
                val type = when (c.getInt(1)) { 1 -> "in"; 2 -> "out"; 3 -> "missed"; else -> "other" }
                val date = fmt.format(java.util.Date(c.getLong(2)))
                sb.append("[$date] $type ${c.getString(0)} (${c.getLong(3)}s)\n"); n++
            }
        }
        return if (n == 0) "no calls" else sb.toString().trim()
    }

    private fun clipboardRead(ctx: Context): String {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = cm.primaryClip
        if (clip == null || clip.itemCount == 0)
            return "clipboard is empty, or not readable from the background — Android 10+ only lets the foreground app read the clipboard, so open androidmcp and retry"
        return clip.getItemAt(0).coerceToText(ctx).toString()
    }

    private fun clipboardWrite(ctx: Context, args: JsonObject): String {
        val text = args["text"]?.jsonPrimitive?.contentOrNull ?: return "no 'text' argument provided"
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("androidmcp", text))
        return "clipboard set to ${text.length} chars (background writes may be silently restricted on some Android versions)"
    }

    private fun readNotifications(args: JsonObject): String {
        val limit = args["limit"]?.jsonPrimitive?.intOrNull ?: 20
        return McpNotificationListener.readActive(limit)
            ?: "notification listener isn't connected yet — toggle Notification access off/on for androidmcp, then retry"
    }

    private fun filesRunner(ctx: Context, args: JsonObject): String {
        val uri = args["uri"]?.jsonPrimitive?.contentOrNull
        return if (uri.isNullOrBlank()) FilesAccess.listAll(ctx) else FilesAccess.read(ctx, uri)
    }

    // ---- wifi_info ----
    @Suppress("DEPRECATION")
    private fun wifiInfo(ctx: android.content.Context): String {
        val wm = ctx.applicationContext.getSystemService(android.content.Context.WIFI_SERVICE)
            as? android.net.wifi.WifiManager
            ?: return "Wi-Fi service is unavailable on this device."
        // getConnectionInfo() is deprecated at API 31 but is the only synchronous way to read the
        // current network at targetSdk 33 (the replacement reads WifiInfo via an async network
        // callback, which doesn't fit a one-shot runner). Every field read below exists at <= API 26.
        val info = try {
            wm.connectionInfo
        } catch (t: Throwable) {
            return "Couldn't read Wi-Fi connection info: ${t.message ?: t.javaClass.simpleName}"
        } ?: return "No Wi-Fi connection info available (Wi-Fi may be off, or nothing is connected)."
        val rssi = info.rssi
        // A disconnected radio reports networkId -1 and the invalid-RSSI floor (WifiInfo.INVALID_RSSI = -127).
        // BOTH are required so a connected-but-location-redacted network (networkId -1 yet a real RSSI)
        // is not misreported as disconnected.
        if (info.networkId == -1 && rssi <= -127) {
            return "Not connected to any Wi-Fi network."
        }
        // Static 5-level overload: deprecated at API 30, present since API 1, yields a 0-4 bucket.
        val level = try {
            android.net.wifi.WifiManager.calculateSignalLevel(rssi, 5).coerceIn(0, 4)
        } catch (t: Throwable) { -1 }
        val rawSsid: String? = info.ssid
        val ssid = when {
            rawSsid.isNullOrEmpty() -> "<unknown ssid>"
            rawSsid == "<unknown ssid>" -> "<unknown ssid> (redacted — needs location permission)"
            else -> rawSsid.trim().removeSurrounding("\"")
        }
        val rawBssid: String? = info.bssid
        val bssidStr = when {
            rawBssid.isNullOrEmpty() -> "<unknown>"
            rawBssid == "02:00:00:00:00:00" -> "<redacted — needs location permission>"
            else -> rawBssid
        }
        val speed = info.linkSpeed // Mbps, -1 if unknown
        val freq = info.frequency  // MHz, WifiInfo.getFrequency() (API 21+)
        return buildString {
            appendLine("ssid: $ssid")
            appendLine("bssid: $bssidStr")
            appendLine("rssi: $rssi dBm")
            appendLine("signal level: ${if (level in 0..4) "$level/4" else "unknown"}")
            appendLine("link speed: ${if (speed >= 0) "$speed Mbps" else "unknown"}")
            append("frequency: ${if (freq > 0) "$freq MHz" else "unknown"}")
        }
    }

    // ---- network_info ----
    private fun networkInfo(ctx: android.content.Context): String {
        val cm = ctx.applicationContext.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
            as? android.net.ConnectivityManager
            ?: return "Connectivity service is unavailable on this device."
        val tm = ctx.applicationContext.getSystemService(android.content.Context.TELEPHONY_SERVICE)
            as? android.telephony.TelephonyManager
        val carrier = (try { tm?.networkOperatorName } catch (t: Throwable) { null })
            ?.takeIf { it.isNotBlank() } ?: "unknown"
        val net = try { cm.activeNetwork } catch (t: Throwable) { null } // API 23+
        val caps = net?.let { try { cm.getNetworkCapabilities(it) } catch (t: Throwable) { null } } // API 21+
        if (net == null || caps == null) {
            return buildString {
                appendLine("transport: none")
                appendLine("connected: false")
                appendLine("validated: false")
                appendLine("metered: unknown")
                append("carrier: $carrier")
            }
        }
        // VPN is reported alongside its underlying transport, so collect all present ones.
        val transports = buildList {
            if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) add("vpn")
            if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) add("wifi")
            if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)) add("cellular")
            if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)) add("ethernet")
        }
        val transport = if (transports.isEmpty()) "unknown" else transports.joinToString("+")
        val hasInternet = caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val validated = caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val metered = !caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        return buildString {
            appendLine("transport: $transport")
            appendLine("connected: $hasInternet")
            appendLine("validated: $validated")
            appendLine("metered: $metered")
            append("carrier: $carrier")
        }
    }

    // ---- storage_info ----
    private fun storageInfo(ctx: android.content.Context): String {
        fun gb(bytes: Long) = "%.2f GB".format(bytes / 1073741824.0)
        fun statLine(label: String, path: String): String = try {
            val sf = android.os.StatFs(path)
            val total = sf.totalBytes
            val free = sf.availableBytes
            val used = total - free
            val pct = if (total > 0) used * 100.0 / total else 0.0
            "$label ($path)\n  total ${gb(total)} | used ${gb(used)} (${"%.0f".format(pct)}%) | free ${gb(free)}"
        } catch (t: Throwable) {
            "$label ($path): unavailable (${t.message})"
        }
        return buildString {
            appendLine("== Internal storage (app data partition) ==")
            appendLine(statLine("data", ctx.dataDir.absolutePath))
            appendLine()
            appendLine("== External storage ==")
            val dirs = ctx.getExternalFilesDirs(null)?.filterNotNull() ?: emptyList()
            if (dirs.isEmpty()) {
                append("no external storage volumes currently available")
            } else {
                append(dirs.mapIndexed { i, f ->
                    statLine(if (i == 0) "external (primary)" else "external (volume ${i + 1})", f.absolutePath)
                }.joinToString("\n"))
            }
        }.trim()
    }

    // ---- thermal_status ----
    private fun thermalStatus(ctx: android.content.Context): String {
        val pm = ctx.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
            ?: return "thermal status unavailable (PowerManager not present)"
        return buildString {
            if (Build.VERSION.SDK_INT >= 29) {
                val label = when (pm.currentThermalStatus) {
                    android.os.PowerManager.THERMAL_STATUS_NONE -> "none"
                    android.os.PowerManager.THERMAL_STATUS_LIGHT -> "light"
                    android.os.PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
                    android.os.PowerManager.THERMAL_STATUS_SEVERE -> "severe"
                    android.os.PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
                    android.os.PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
                    android.os.PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
                    else -> "unknown"
                }
                appendLine("thermal status: $label")
            } else {
                appendLine("thermal status: unavailable (needs Android 10/11+)")
            }
            if (Build.VERSION.SDK_INT >= 30) {
                val hr = try { pm.getThermalHeadroom(0) } catch (t: Throwable) { Float.NaN }
                if (hr.isNaN()) {
                    append("thermal headroom: unavailable (device reported no value)")
                } else {
                    append("thermal headroom: ${"%.2f".format(hr)}  (0.0 cool -> 1.0 = throttling threshold)")
                }
            } else {
                append("thermal headroom: unavailable (needs Android 10/11+)")
            }
        }.trim()
    }

    // ---- screen_info ----
    private fun screenInfo(ctx: android.content.Context): String {
        val dm = ctx.resources.displayMetrics
        val pm = ctx.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
        var display: android.view.Display? = null
        if (Build.VERSION.SDK_INT >= 30) {
            // Context.getDisplay() (API 30) throws UnsupportedOperationException when the Context is
            // not display-associated. This runner is handed the Application context, so on API 30+
            // this always throws and the fallback below is what actually runs — hence guard + catch.
            display = try { ctx.display } catch (t: Throwable) { null }
        }
        if (display == null) {
            // DisplayManager is context-independent (API 17+) and NOT deprecated, unlike
            // WindowManager.defaultDisplay which is discouraged/unreliable from a non-visual
            // (Application/Service) context on API 30+. DEFAULT_DISPLAY (0) always exists.
            display = try {
                (ctx.getSystemService(android.content.Context.DISPLAY_SERVICE) as? android.hardware.display.DisplayManager)
                    ?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
            } catch (t: Throwable) { null }
        }
        val refresh = display?.refreshRate
        val rotation = when (display?.rotation) {
            android.view.Surface.ROTATION_0 -> "0 deg (natural)"
            android.view.Surface.ROTATION_90 -> "90 deg"
            android.view.Surface.ROTATION_180 -> "180 deg"
            android.view.Surface.ROTATION_270 -> "270 deg"
            null -> "unavailable (no display resolved for this context)"
            else -> "unknown"
        }
        val timeoutMs = try {
            android.provider.Settings.System.getInt(
                ctx.contentResolver, android.provider.Settings.System.SCREEN_OFF_TIMEOUT, -1)
        } catch (t: Throwable) { -1 }
        return buildString {
            appendLine("resolution: ${dm.widthPixels} x ${dm.heightPixels} px")
            appendLine("density: ${dm.densityDpi} dpi (scale x${"%.2f".format(dm.density)})")
            appendLine("refresh rate: ${if (refresh != null) "%.1f Hz".format(refresh) else "unavailable"}")
            appendLine("rotation: $rotation")
            appendLine("interactive (awake): ${pm?.isInteractive ?: "unknown"}")
            append("screen-off timeout: " + (if (timeoutMs >= 0) "${timeoutMs / 1000}s ($timeoutMs ms)" else "unavailable"))
        }.trim()
    }

    // ---- volume_info ----
    private fun volumeInfo(ctx: Context): String {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
            ?: return "audio service unavailable"
        val streams = listOf(
            "music" to android.media.AudioManager.STREAM_MUSIC,
            "ring" to android.media.AudioManager.STREAM_RING,
            "alarm" to android.media.AudioManager.STREAM_ALARM,
            "notification" to android.media.AudioManager.STREAM_NOTIFICATION,
            "voice_call" to android.media.AudioManager.STREAM_VOICE_CALL,
            "system" to android.media.AudioManager.STREAM_SYSTEM,
        )
        val sb = StringBuilder()
        for ((label, s) in streams) {
            val cur = runCatching { am.getStreamVolume(s) }.getOrDefault(-1)
            val max = runCatching { am.getStreamMaxVolume(s) }.getOrDefault(-1)
            val min = if (Build.VERSION.SDK_INT >= 28) runCatching { am.getStreamMinVolume(s) }.getOrDefault(0) else 0
            sb.append("$label: $cur/$max")
            if (min > 0) sb.append(" (min $min)")
            sb.append("\n")
        }
        val ringer = when (am.ringerMode) {
            android.media.AudioManager.RINGER_MODE_NORMAL -> "normal"
            android.media.AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
            android.media.AudioManager.RINGER_MODE_SILENT -> "silent"
            else -> "unknown"
        }
        sb.append("ringerMode: $ringer")
        return sb.toString()
    }

    // ---- torch ----
    private fun torch(ctx: Context, args: JsonObject): String {
        val on = args["on"]?.jsonPrimitive?.booleanOrNull ?: false
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as? android.hardware.camera2.CameraManager
            ?: return "camera service unavailable"
        val flashId = runCatching {
            cm.cameraIdList.firstOrNull { id ->
                cm.getCameraCharacteristics(id)
                    .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        }.getOrNull()
            ?: return "no camera flash available on this device"
        return try {
            cm.setTorchMode(flashId, on)
            "torch ${if (on) "on" else "off"} (cameraId $flashId)"
        } catch (t: Throwable) {
            "could not set torch: ${t.message} (the camera may be in use by another app)"
        }
    }

    // ---- vibrate ----
    private fun vibrate(ctx: Context, args: JsonObject): String {
        val ms = (args["milliseconds"]?.jsonPrimitive?.intOrNull ?: 300).coerceIn(1, 5000).toLong()
        val vib: android.os.Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            (ctx.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator)
        }
        if (vib == null || !vib.hasVibrator()) return "no vibrator on this device"
        return try {
            val effect = android.os.VibrationEffect.createOneShot(ms, android.os.VibrationEffect.DEFAULT_AMPLITUDE)
            if (Build.VERSION.SDK_INT >= 33) {
                val attrs = android.os.VibrationAttributes.Builder()
                    .setUsage(android.os.VibrationAttributes.USAGE_ALARM)
                    .build()
                vib.vibrate(effect, attrs)
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(effect)
            }
            "vibrated for $ms ms (if nothing was felt, the phone's master \"Vibration & haptics\" setting may be off)"
        } catch (t: Throwable) {
            "could not vibrate: ${t.message}"
        }
    }

    // ---- list_packages ----
    private fun listPackages(ctx: android.content.Context, args: kotlinx.serialization.json.JsonObject): String {
        val filter = args["filter"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val includeSystem = args["include_system"]?.jsonPrimitive?.booleanOrNull ?: false
        val limit = (args["limit"]?.jsonPrimitive?.intOrNull ?: 100).coerceIn(1, 2000)
        val pm = ctx.packageManager
        val apps = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledApplications(
                    android.content.pm.PackageManager.ApplicationInfoFlags.of(0L)
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledApplications(0)
            }
        } catch (t: Throwable) {
            return "could not list installed apps: ${t.message}"
        }
        val systemMask = android.content.pm.ApplicationInfo.FLAG_SYSTEM or
            android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
        val rows = apps.asSequence()
            .map { ai ->
                val isSystem = (ai.flags and systemMask) != 0
                val label = runCatching { pm.getApplicationLabel(ai).toString() }.getOrNull()
                    ?.takeIf { it.isNotBlank() } ?: ai.packageName
                Triple(label, ai.packageName, isSystem)
            }
            .filter { (label, pkg, isSystem) ->
                (includeSystem || !isSystem) &&
                    (filter == null || label.lowercase().contains(filter) || pkg.lowercase().contains(filter))
            }
            .sortedBy { it.first.lowercase() }
            .toList()
        if (rows.isEmpty()) return "no matching apps"
        val shown = rows.take(limit)
        val sb = StringBuilder()
        sb.appendLine(
            "installed apps: ${rows.size} match${if (rows.size == 1) "" else "es"}" +
                (if (rows.size > shown.size) " (showing first ${shown.size})" else "")
        )
        shown.forEach { (label, pkg, isSystem) ->
            sb.appendLine("$label — $pkg${if (isSystem) " [system]" else ""}")
        }
        return sb.toString().trim()
    }

    // ---- launch_url ----
    private fun launchUrl(ctx: android.content.Context, args: kotlinx.serialization.json.JsonObject): String {
        val raw = args["url"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: return "provide a 'url' to open"
        if (raw.isEmpty()) return "provide a non-empty 'url' to open"
        // Prepend https:// only when there is no leading URI scheme (scheme://…).
        val hasScheme = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(raw)
        val normalized = if (hasScheme) raw else "https://$raw"
        val uri = try {
            android.net.Uri.parse(normalized)
        } catch (t: Throwable) {
            return "invalid url: ${t.message}"
        }
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(intent)
            "opened $normalized (note: Android may block launching activities while androidmcp is in the background)"
        } catch (t: android.content.ActivityNotFoundException) {
            "no app can handle $normalized"
        } catch (t: Throwable) {
            "could not open $normalized: ${t.message}"
        }
    }

    // ---- dial ----
    private fun dial(ctx: android.content.Context, args: kotlinx.serialization.json.JsonObject): String {
        val number = args["number"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: return "provide a 'number' to dial"
        if (number.isEmpty()) return "provide a non-empty 'number' to dial"
        val uri = android.net.Uri.fromParts("tel", number, null)
        val intent = Intent(Intent.ACTION_DIAL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(intent)
            "opened dialer pre-filled with $number (the call is NOT placed — you must press call)"
        } catch (t: android.content.ActivityNotFoundException) {
            "no dialer app available for $number (this device may have no phone app — e.g. a tablet)"
        } catch (t: Throwable) {
            "could not open dialer for $number: ${t.message}"
        }
    }

    // ---- get_contacts ----
    private fun contactsRead(ctx: android.content.Context, args: kotlinx.serialization.json.JsonObject): String {
        val query = args["query"]?.jsonPrimitive?.contentOrNull?.trim()?.takeUnless { it.isBlank() }
        val limit = (args["limit"]?.jsonPrimitive?.intOrNull ?: 30).coerceIn(1, 500)
        // Phone is imported by simple name (see extraImports) — a Java class cannot be bound to a Kotlin `val`.
        val cols = arrayOf(
            Phone.CONTACT_ID, Phone.DISPLAY_NAME, Phone.NUMBER, Phone.TYPE, Phone.LABEL,
        )
        val selection = if (query != null) "${Phone.DISPLAY_NAME} LIKE ?" else null
        val selectionArgs = if (query != null) arrayOf("%$query%") else null
        val sort = "${Phone.DISPLAY_NAME} COLLATE NOCASE ASC"
        val cur = runCatching {
            ctx.contentResolver.query(Phone.CONTENT_URI, cols, selection, selectionArgs, sort)
        }.getOrNull() ?: return "contacts provider not accessible"
        // contactId (or name fallback) -> (display name, distinct numbers)
        val grouped = LinkedHashMap<String, Pair<String, MutableList<String>>>()
        cur.use { c ->
            val idIx = c.getColumnIndex(Phone.CONTACT_ID)
            val nameIx = c.getColumnIndex(Phone.DISPLAY_NAME)
            val numIx = c.getColumnIndex(Phone.NUMBER)
            val typeIx = c.getColumnIndex(Phone.TYPE)
            val labelIx = c.getColumnIndex(Phone.LABEL)
            while (c.moveToNext()) {
                val number = (if (numIx >= 0) c.getString(numIx) else null)?.trim()
                if (number.isNullOrBlank()) continue
                val name = (if (nameIx >= 0) c.getString(nameIx) else null)?.trim()
                    ?.takeUnless { it.isBlank() } ?: "(no name)"
                val cid = (if (idIx >= 0) c.getString(idIx) else null)?.takeUnless { it.isBlank() }
                val key = cid ?: "name:$name"
                if (!grouped.containsKey(key) && grouped.size >= limit) continue
                val typeLabel = runCatching {
                    val t = if (typeIx >= 0) c.getInt(typeIx) else 0
                    val custom = if (labelIx >= 0) c.getString(labelIx) else null
                    Phone.getTypeLabel(ctx.resources, t, custom ?: "").toString().trim()
                }.getOrDefault("")
                val display = if (typeLabel.isBlank()) number else "$number ($typeLabel)"
                val entry = grouped.getOrPut(key) { name to mutableListOf() }
                if (entry.second.none { it == display }) entry.second.add(display)
            }
        }
        if (grouped.isEmpty())
            return if (query != null) "no contacts match \"$query\"" else "no contacts found"
        val sb = StringBuilder()
        sb.append("${grouped.size} contact(s)")
        if (query != null) sb.append(" matching \"$query\"")
        sb.append(":\n")
        grouped.values.forEach { (name, numbers) ->
            sb.append("• $name: ${numbers.joinToString("; ")}\n")
        }
        return sb.toString().trim()
    }

    // ---- read_calendar ----
    private fun calendarRead(ctx: android.content.Context, args: kotlinx.serialization.json.JsonObject): String {
        val days = (args["days_ahead"]?.jsonPrimitive?.intOrNull ?: 7).coerceIn(1, 365)
        val limit = (args["limit"]?.jsonPrimitive?.intOrNull ?: 30).coerceIn(1, 500)
        val now = System.currentTimeMillis()
        val end = now + days.toLong() * 24L * 60L * 60L * 1000L
        // Instances is imported by simple name (see extraImports) — a Java class cannot be bound to a Kotlin `val`.
        // Instances requires the time window appended to CONTENT_URI as two path ids (begin, end).
        val builder = Instances.CONTENT_URI.buildUpon()
        android.content.ContentUris.appendId(builder, now)
        android.content.ContentUris.appendId(builder, end)
        val cols = arrayOf(
            Instances.TITLE, Instances.BEGIN, Instances.END,
            Instances.EVENT_LOCATION, Instances.ALL_DAY, Instances.CALENDAR_DISPLAY_NAME,
        )
        val cur = runCatching {
            ctx.contentResolver.query(builder.build(), cols, null, null, "${Instances.BEGIN} ASC")
        }.getOrNull() ?: return "calendar provider not accessible"
        val dtFmt = java.text.SimpleDateFormat("EEE MMM d, HH:mm", java.util.Locale.US)
        val timeFmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
        val dayFmt = java.text.SimpleDateFormat("EEE MMM d", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") } // all-day BEGIN is UTC midnight
        val sb = StringBuilder()
        var n = 0
        cur.use { c ->
            val titleIx = c.getColumnIndex(Instances.TITLE)
            val beginIx = c.getColumnIndex(Instances.BEGIN)
            val endIx = c.getColumnIndex(Instances.END)
            val locIx = c.getColumnIndex(Instances.EVENT_LOCATION)
            val allDayIx = c.getColumnIndex(Instances.ALL_DAY)
            val calIx = c.getColumnIndex(Instances.CALENDAR_DISPLAY_NAME)
            while (c.moveToNext() && n < limit) {
                val title = (if (titleIx >= 0) c.getString(titleIx) else null)?.trim()
                    ?.takeUnless { it.isBlank() } ?: "(no title)"
                val begin = if (beginIx >= 0) c.getLong(beginIx) else 0L
                val endMs = if (endIx >= 0) c.getLong(endIx) else 0L
                val allDay = allDayIx >= 0 && c.getInt(allDayIx) == 1
                val loc = (if (locIx >= 0) c.getString(locIx) else null)?.trim()
                val cal = (if (calIx >= 0) c.getString(calIx) else null)?.trim()
                val whenStr = if (allDay) {
                    dayFmt.format(java.util.Date(begin)) + " (all day)"
                } else {
                    val tail = if (endMs > begin) " – ${timeFmt.format(java.util.Date(endMs))}" else ""
                    dtFmt.format(java.util.Date(begin)) + tail
                }
                sb.append("• $title — $whenStr")
                if (!loc.isNullOrBlank()) sb.append(" @ $loc")
                if (!cal.isNullOrBlank()) sb.append(" [$cal]")
                sb.append("\n")
                n++
            }
        }
        return if (n == 0) "no upcoming events in the next $days day(s)"
        else "$n event(s) in the next $days day(s):\n${sb.toString().trim()}"
    }

    // ---- elevated_input ----
    private fun elevatedInput(ctx: android.content.Context, args: kotlinx.serialization.json.JsonObject): String {
        val action = args["action"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
            ?: return "provide 'action': tap, swipe, text, or key"
        val x = args["x"]?.jsonPrimitive?.intOrNull
        val y = args["y"]?.jsonPrimitive?.intOrNull
        val x2 = args["x2"]?.jsonPrimitive?.intOrNull
        val y2 = args["y2"]?.jsonPrimitive?.intOrNull

        // Elevated.exec wraps the whole string in `sh -c`, so single-quote any token that
        // carries user data to pass it verbatim — no subshells, no word-splitting.
        fun sq(s: String): String = "'" + s.replace("'", "'\\''") + "'"

        val cmd: String = when (action) {
            "tap" -> {
                if (x == null || y == null) return "tap needs integer 'x' and 'y'"
                "input tap $x $y"
            }
            "swipe" -> {
                if (x == null || y == null || x2 == null || y2 == null)
                    return "swipe needs integer 'x', 'y', 'x2', and 'y2'"
                "input swipe $x $y $x2 $y2 300"
            }
            "text" -> {
                val t = args["text"]?.jsonPrimitive?.contentOrNull
                    ?: return "text action needs a 'text' argument"
                if (t.isEmpty()) return "text action needs a non-empty 'text' argument"
                // 'input text' word-splits on spaces and maps the literal %s back to a space,
                // so encode spaces as %s, then single-quote so every other shell metacharacter
                // ($, `, ;, &, |, (), quotes) is passed literally and cannot spawn a subshell.
                "input text ${sq(t.replace(" ", "%s"))}"
            }
            "key" -> {
                val raw = args["keycode"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?: return "key action needs a 'keycode' (a number like 4, or a name like KEYCODE_BACK)"
                val key = raw.uppercase()
                val valid = key.matches(Regex("^\\d+$")) || key.matches(Regex("^KEYCODE_[A-Z0-9_]+$"))
                if (!valid) return "invalid keycode '$raw' — use a numeric code (e.g. 4) or a KEYCODE_ name (e.g. KEYCODE_BACK)"
                "input keyevent $key"
            }
            else -> return "unknown action '$action' — use tap, swipe, text, or key"
        }

        // 'input' is SILENT on success and prints failures (bad keycode, off-screen coords,
        // display errors) only to stderr — which the Shizuku backend (shizukuExec) does NOT
        // capture, so a failed injection would otherwise look like a silent success. Merge
        // stderr into stdout with 2>&1 and echo the exit code so failures are visible. The
        // root backend already merges stderr (redirectErrorStream), so this is compatible with both.
        val raw = Elevated.exec("$cmd 2>&1; echo __rc=\$?")
        val rcMatch = Regex("__rc=(-?\\d+)").findAll(raw).lastOrNull()
        val rc = rcMatch?.groupValues?.get(1)?.toIntOrNull()
        val out = (if (rcMatch != null) raw.substring(0, rcMatch.range.first) else raw).trim()

        return buildString {
            appendLine("ran (via ${Elevated.source()}): $cmd")
            when {
                rc == null -> append("result: ${if (out.isEmpty()) "(no output)" else out.take(4000)}")
                rc == 0 && out.isEmpty() -> append("result: ok (exit 0 — 'input' is silent on success)")
                rc == 0 -> append("result: exit 0\n${out.take(4000)}")
                else -> append("result: FAILED (exit $rc)\n${if (out.isEmpty()) "(no error text captured)" else out.take(4000)}")
            }
        }
    }

    // ---- set_volume ----
    private fun setVolume(ctx: android.content.Context, args: kotlinx.serialization.json.JsonObject): String {
        val am = ctx.getSystemService(android.content.Context.AUDIO_SERVICE) as? android.media.AudioManager
            ?: return "audio service unavailable"
        val streamName = args["stream"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase() ?: "music"
        val streamConst = when (streamName) {
            "music" -> android.media.AudioManager.STREAM_MUSIC
            "ring" -> android.media.AudioManager.STREAM_RING
            "alarm" -> android.media.AudioManager.STREAM_ALARM
            "notification" -> android.media.AudioManager.STREAM_NOTIFICATION
            "system" -> android.media.AudioManager.STREAM_SYSTEM
            else -> return "unknown stream '$streamName' — use music, ring, alarm, notification, or system"
        }
        val level = args["level"]?.jsonPrimitive?.intOrNull
            ?: return "provide an integer 'level'"
        val showUi = args["show_ui"]?.jsonPrimitive?.booleanOrNull ?: false
        val max = runCatching { am.getStreamMaxVolume(streamConst) }.getOrDefault(-1)
        if (max < 0) return "could not read the max volume for the $streamName stream"
        val old = runCatching { am.getStreamVolume(streamConst) }.getOrDefault(-1)
        val target = level.coerceIn(0, max)
        val flags = if (showUi) android.media.AudioManager.FLAG_SHOW_UI else 0
        return try {
            am.setStreamVolume(streamConst, target, flags)
            val now = runCatching { am.getStreamVolume(streamConst) }.getOrDefault(target)
            buildString {
                append("$streamName volume: ${if (old < 0) "?" else old.toString()} -> $now (max $max)")
                if (target != level) append("; requested $level clamped to 0..$max")
                if (now != target) append("; system settled on $now (a DND/ringer policy, ring/notification coupling, or a fixed-volume output such as some Bluetooth/HDMI sinks can override the request)")
            }
        } catch (t: SecurityException) {
            "could not set $streamName volume: ${t.message ?: "Notification Policy access required"} — this usually means Do Not Disturb is active; changing ring/notification volume (or dropping it to 0) while DND is on needs Notification Policy (DND) access"
        } catch (t: Throwable) {
            "could not set $streamName volume: ${t.message ?: t.javaClass.simpleName}"
        }
    }

    // ---- media_control ----
    private fun mediaControl(ctx: android.content.Context, args: kotlinx.serialization.json.JsonObject): String {
        val am = ctx.getSystemService(android.content.Context.AUDIO_SERVICE) as? android.media.AudioManager
            ?: return "audio service unavailable"
        val action = args["action"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
            ?: return "provide an 'action': play, pause, playpause, next, previous, or stop"
        val keyCode = when (action) {
            "play" -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> android.view.KeyEvent.KEYCODE_MEDIA_PAUSE
            "playpause" -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "next" -> android.view.KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop" -> android.view.KeyEvent.KEYCODE_MEDIA_STOP
            else -> return "unknown action '$action' — use play, pause, playpause, next, previous, or stop"
        }
        // dispatchMediaKeyEvent returns void and never reports whether an app consumed the key,
        // so isMusicActive is the only cheap hint about whether a media session is likely present.
        val musicActive = runCatching { am.isMusicActive }.getOrDefault(false)
        // A media key needs a DOWN then an UP; some receivers ignore zero-timestamp events, so use
        // the 5-arg KeyEvent constructor and stamp downTime/eventTime from SystemClock.uptimeMillis().
        val ts = android.os.SystemClock.uptimeMillis()
        return try {
            am.dispatchMediaKeyEvent(android.view.KeyEvent(ts, ts, android.view.KeyEvent.ACTION_DOWN, keyCode, 0))
            am.dispatchMediaKeyEvent(android.view.KeyEvent(ts, ts, android.view.KeyEvent.ACTION_UP, keyCode, 0))
            buildString {
                appendLine("sent media key: $action (${android.view.KeyEvent.keyCodeToString(keyCode)})")
                append(
                    if (musicActive)
                        "a media session appears active (audio is playing) — the key should have reached it"
                    else
                        "no active audio detected — if no app holds a media session, the key may go nowhere (dispatch is fire-and-forget and reports no target)"
                )
            }
        } catch (t: Throwable) {
            "could not dispatch media key '$action': ${t.message ?: t.javaClass.simpleName}"
        }
    }

    // ---- toast ----
    private fun toast(ctx: android.content.Context, args: kotlinx.serialization.json.JsonObject): String {
        val text = args["text"]?.jsonPrimitive?.contentOrNull
            ?: return "provide a 'text' to show"
        if (text.isEmpty()) return "provide a non-empty 'text' to show"
        val long = args["long"]?.jsonPrimitive?.booleanOrNull ?: false
        // Toast.makeText()/show() must run on a thread with a Looper; this runner is on the IO
        // thread, so post to the main looper. Use applicationContext so the toast survives the
        // runner returning. Text toasts are still allowed from the background on API 30+ (only
        // custom-view toasts are blocked), so this works whether or not androidmcp is foreground.
        // runCatching INSIDE the posted runnable: this runner returns BEFORE the runnable runs, so
        // an exception here would land uncaught on the MAIN thread and crash the whole app; swallow
        // it instead (the confirmation string is already best-effort, see gotchas).
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            runCatching {
                android.widget.Toast.makeText(
                    ctx.applicationContext,
                    text,
                    if (long) android.widget.Toast.LENGTH_LONG else android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
        }
        return "showed a ${if (long) "long" else "short"} toast: \"${text.take(200)}\""
    }

    // ---- share_text ----
    private fun shareText(ctx: android.content.Context, args: kotlinx.serialization.json.JsonObject): String {
        val text = args["text"]?.jsonPrimitive?.contentOrNull
            ?: return "provide 'text' to share"
        if (text.isEmpty()) return "provide non-empty 'text' to share"
        val subject = args["subject"]?.jsonPrimitive?.contentOrNull?.trim()?.takeUnless { it.isBlank() }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            if (subject != null) putExtra(Intent.EXTRA_SUBJECT, subject)
        }
        // createChooser is resolved by the system, so it works without QUERY_ALL_PACKAGES
        // package-visibility even on API 30+. NEW_TASK is required to start an activity from a
        // non-activity (Service/Application) context.
        val chooser = Intent.createChooser(send, subject ?: "Share")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(chooser)
            buildString {
                append("opened the share sheet with ${text.length} char(s) of text")
                if (subject != null) append(" (subject: \"$subject\")")
                append(" — note: Android may block launching the chooser while androidmcp is in the background")
            }
        } catch (t: android.content.ActivityNotFoundException) {
            "no app can handle sharing text on this device"
        } catch (t: Throwable) {
            "could not open the share sheet: ${t.message}"
        }
    }

    // ---- open_settings ----
    private fun openSettings(ctx: android.content.Context, args: kotlinx.serialization.json.JsonObject): String {
        val screen = args["screen"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
            ?.takeUnless { it.isBlank() } ?: "apps"
        // app_details needs the target package as intent data; everything else is action-only.
        var data: android.net.Uri? = null
        val action = when (screen) {
            "wifi" -> android.provider.Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> android.provider.Settings.ACTION_BLUETOOTH_SETTINGS
            "location" -> android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "display" -> android.provider.Settings.ACTION_DISPLAY_SETTINGS
            "sound" -> android.provider.Settings.ACTION_SOUND_SETTINGS
            "apps" -> android.provider.Settings.ACTION_APPLICATION_SETTINGS
            "app_details" -> {
                data = android.net.Uri.fromParts("package", ctx.packageName, null)
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
            }
            "battery" -> android.provider.Settings.ACTION_BATTERY_SAVER_SETTINGS
            "date" -> android.provider.Settings.ACTION_DATE_SETTINGS
            "security" -> android.provider.Settings.ACTION_SECURITY_SETTINGS
            "home" -> android.provider.Settings.ACTION_HOME_SETTINGS
            else -> return "unknown screen '$screen' — use one of: wifi, bluetooth, location, " +
                "display, sound, apps, app_details, battery, date, security, home"
        }
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (data != null) intent.data = data
        return try {
            ctx.startActivity(intent)
            "opened the '$screen' settings screen (note: Android may block launching activities while androidmcp is in the background)"
        } catch (t: android.content.ActivityNotFoundException) {
            // Some OEMs/tablets lack a dedicated screen for a given action — fall back to the top-level Settings app.
            try {
                ctx.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "no dedicated '$screen' screen on this device — opened the main Settings app instead"
            } catch (t2: Throwable) {
                "could not open settings: ${t2.message}"
            }
        } catch (t: Throwable) {
            "could not open the '$screen' settings screen: ${t.message}"
        }
    }

    // ---- create_calendar_event ----
    private fun createCalendarEvent(ctx: android.content.Context, args: kotlinx.serialization.json.JsonObject): String {
        val title = args["title"]?.jsonPrimitive?.contentOrNull?.trim()?.takeUnless { it.isBlank() }
            ?: return "provide a non-empty 'title' for the event"
        // Epoch millis (~1.7e12) overflow a 32-bit Int, so read start as Long even though the schema
        // type is "integer" (MCP has no separate long type). intOrNull would silently return null here.
        val start = args["start_epoch_ms"]?.jsonPrimitive?.longOrNull
            ?: (System.currentTimeMillis() + 60L * 60L * 1000L) // default: now + 1h
        val durationMin = (args["duration_minutes"]?.jsonPrimitive?.intOrNull ?: 60).coerceIn(1, 60 * 24 * 30)
        val end = start + durationMin.toLong() * 60L * 1000L
        val location = args["location"]?.jsonPrimitive?.contentOrNull?.trim()?.takeUnless { it.isBlank() }
        val requestedCalId = args["calendar_id"]?.jsonPrimitive?.longOrNull

        // Resolve the target calendar (and, for the auto-pick, its display name). With no explicit
        // calendar_id, pick the lowest-id calendar the app may write to (CALENDAR_ACCESS_LEVEL >=
        // CAL_ACCESS_CONTRIBUTOR). Those access constants are compile-time ints, so inlining them into
        // the selection carries no injection risk and sidesteps SQLite text/integer affinity on a bound
        // arg. Enumerating Calendars is a READ on the provider, so this tool declares READ_CALENDAR too.
        var calName: String? = null
        val calendarId: Long = if (requestedCalId != null) {
            requestedCalId
        } else {
            val projection = arrayOf(
                android.provider.CalendarContract.Calendars._ID,
                android.provider.CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            )
            val sel = "${android.provider.CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= " +
                "${android.provider.CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}"
            val order = "${android.provider.CalendarContract.Calendars._ID} ASC"
            val cur = runCatching {
                ctx.contentResolver.query(
                    android.provider.CalendarContract.Calendars.CONTENT_URI, projection, sel, null, order)
            }.getOrNull()
                ?: return "could not enumerate calendars - this needs READ_CALENDAR granted, or pass an explicit 'calendar_id'"
            var found = -1L
            cur.use { c ->
                val idIx = c.getColumnIndex(android.provider.CalendarContract.Calendars._ID)
                val nameIx = c.getColumnIndex(android.provider.CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
                if (idIx >= 0 && c.moveToFirst()) {
                    found = c.getLong(idIx)
                    if (nameIx >= 0 && !c.isNull(nameIx)) calName = c.getString(nameIx)
                }
            }
            if (found < 0L)
                return "no writable calendar found on this device - add an account with a writable calendar, or pass an explicit 'calendar_id'"
            found
        }

        val tz = java.util.TimeZone.getDefault().id
        val values = android.content.ContentValues().apply {
            put(android.provider.CalendarContract.Events.DTSTART, start)
            put(android.provider.CalendarContract.Events.DTEND, end)
            put(android.provider.CalendarContract.Events.TITLE, title)
            if (location != null) put(android.provider.CalendarContract.Events.EVENT_LOCATION, location)
            put(android.provider.CalendarContract.Events.CALENDAR_ID, calendarId)
            put(android.provider.CalendarContract.Events.EVENT_TIMEZONE, tz)
        }

        val uri = try {
            ctx.contentResolver.insert(android.provider.CalendarContract.Events.CONTENT_URI, values)
        } catch (t: Throwable) {
            return "could not create event: ${t.message} (calendar_id $calendarId may not be writable, or WRITE_CALENDAR is not granted)"
        } ?: return "insert returned no URI - calendar_id $calendarId may be invalid or not writable"

        val newId = uri.lastPathSegment ?: "?"
        val fmt = java.text.SimpleDateFormat("EEE MMM d yyyy, HH:mm", java.util.Locale.US)
        return buildString {
            appendLine("created event #$newId")
            appendLine("title: $title")
            appendLine("when: ${fmt.format(java.util.Date(start))} - ${fmt.format(java.util.Date(end))} ($durationMin min, tz $tz)")
            if (location != null) appendLine("location: $location")
            append("calendar_id: $calendarId")
            if (calName != null) append(" ($calName)")
        }.trim()
    }

    // ---- elevated_current_app ----
    private fun elevatedCurrentApp(ctx: android.content.Context): String {
        // Matches a `package/activity` component in dumpsys output. Package is a dotted id
        // (needs at least one dot, so bare `foo/bar` tokens and file paths do not match);
        // activity may be shorthand (".Foo", relative to the package) or fully-qualified and may
        // contain `$` for nested classes. The `$` is written as \$ so Kotlin does not treat it as
        // a string template.
        val comp = Regex("([a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z0-9_]+)+)/(\\.?[a-zA-Z0-9_.\$]+)")

        fun parse(raw: String): Triple<String, String, String>? {
            for (line in raw.split("\n").map { it.trim() }.filter { it.isNotEmpty() }) {
                val m = comp.find(line) ?: continue
                val pkg = m.groupValues[1]
                var act = m.groupValues[2]
                if (act.startsWith(".")) act = pkg + act
                return Triple(pkg, act, line)
            }
            return null
        }

        // 1) The activity manager's resumed activity is the authoritative "foreground app".
        //    stderr -> /dev/null so a dumpsys warning (which the root backend folds into stdout
        //    via redirectErrorStream) cannot leak into the grep input or the raw fallback.
        var used = "dumpsys activity activities 2>/dev/null | grep -E 'mResumedActivity|topResumedActivity|ResumedActivity'"
        var raw = Elevated.exec(used)
        var parsed = parse(raw)

        // 2) Fall back to the window manager's focused window/app if that yielded nothing.
        if (parsed == null) {
            used = "dumpsys window 2>/dev/null | grep -E 'mCurrentFocus|mFocusedApp'"
            raw = Elevated.exec(used)
            parsed = parse(raw)
        }

        return buildString {
            appendLine("source: ${Elevated.source()}")
            appendLine("command: $used")
            val p = parsed
            if (p != null) {
                appendLine("package: ${p.first}")
                appendLine("activity: ${p.second}")
                append("matched line: ${p.third.take(500)}")
            } else {
                appendLine("package: (could not parse)")
                appendLine("activity: (could not parse)")
                val trimmed = raw.trim()
                append("raw output: " + if (trimmed.isEmpty())
                    "(no output — screen may be locked/off, no foreground app, or the elevated backend blocked dumpsys)"
                    else trimmed.take(1500))
            }
        }
    }

    // ---- elevated_settings ----
    private fun elevatedSettings(ctx: android.content.Context, args: kotlinx.serialization.json.JsonObject): String {
        val action = args["action"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
            ?: return "provide 'action': get or put"
        val ns = args["namespace"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
            ?: return "provide 'namespace': system, secure, or global"
        if (ns != "system" && ns != "secure" && ns != "global")
            return "invalid namespace '$ns' — use system, secure, or global"
        val key = args["key"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: return "provide a 'key'"
        if (key.isEmpty()) return "provide a non-empty 'key'"

        // Elevated.exec wraps the whole string in `sh -c`, so single-quote every token that
        // carries user data to pass it verbatim (no word-splitting, no subshells).
        fun sq(s: String): String = "'" + s.replace("'", "'\\''") + "'"

        // `settings` is silent on a successful put and reports failures (unknown table, bad value,
        // denied write) only on stderr — which the Shizuku backend does NOT capture, so a failed
        // write would otherwise look like a silent success. Merge stderr into stdout and echo the
        // exit code, then split them off. lastOrNull() picks the appended marker even if the value
        // itself contains "__rc=". The root backend already merges stderr, so this works on both.
        fun runRc(cmd: String): Pair<Int?, String> {
            val raw = Elevated.exec("$cmd 2>&1; echo __rc=\$?")
            val m = Regex("__rc=(-?\\d+)").findAll(raw).lastOrNull()
            val rc = m?.groupValues?.get(1)?.toIntOrNull()
            val out = (if (m != null) raw.substring(0, m.range.first) else raw).trim()
            return rc to out
        }

        return when (action) {
            "get" -> {
                val cmd = "settings get $ns ${sq(key)}"
                val (rc, out) = runRc(cmd)
                buildString {
                    appendLine("source: ${Elevated.source()}")
                    appendLine("ran: $cmd")
                    when {
                        rc != null && rc != 0 ->
                            append("error: 'settings get' exited $rc\n" +
                                (if (out.isEmpty()) "(no error text captured)" else out.take(4000)))
                        out.isEmpty() -> append("value: (empty)")
                        out == "null" -> append("value: null  (unset, or the literal string \"null\" — 'settings get' cannot distinguish)")
                        else -> append("value: ${out.take(4000)}")
                    }
                }
            }
            "put" -> {
                val value = args["value"]?.jsonPrimitive?.contentOrNull
                    ?: return "put needs a 'value'"
                val cmd = "settings put $ns ${sq(key)} ${sq(value)}"
                val (rc, out) = runRc(cmd)
                // Read the value back so the caller sees the effective, stored result.
                val after = runRc("settings get $ns ${sq(key)}").second
                buildString {
                    appendLine("source: ${Elevated.source()}")
                    appendLine("ran: $cmd")
                    if (rc != null && rc != 0)
                        appendLine("write exited $rc (non-zero — the put may have been rejected)")
                    if (out.isNotEmpty()) appendLine("output: ${out.take(2000)}")
                    appendLine("read-back ($ns/$key): " + when {
                        after.isEmpty() -> "(empty)"
                        after == "null" -> "null (unset, or the literal string \"null\")"
                        else -> after.take(2000)
                    })
                    append(when {
                        after == value.trim() ->
                            "confirmed: setting now equals the requested value"
                        after == "null" ->
                            "note: read-back is 'null' — the write did not take effect (unknown key/table, denied, or coerced)"
                        else ->
                            "note: read-back \"$after\" does not equal the requested \"$value\" — the write may have been coerced, rejected, or stored differently"
                    })
                }
            }
            else -> "unknown action '$action' — use get or put"
        }
    }

    private fun rootScreenshot(): List<JsonObject> {
        val png = Elevated.execBytes("screencap -p")
        if (png == null || png.isEmpty()) return listOf(textBlk("silent screencap failed or returned no data"))
        return listOf(textBlk("silent screenshot (${png.size} bytes, via ${Elevated.source()})")) +
            mediaBlocks(png, "image/png", "screen.png", isImage = true)
    }

    private fun rootShell(args: JsonObject): String {
        val cmd = args["command"]?.jsonPrimitive?.contentOrNull ?: return "provide a 'command' to run"
        return Elevated.exec(cmd).ifBlank { "(no output)" }.take(20000)
    }

    private fun runShortcut(ctx: Context, args: JsonObject): String {
        val pkg = args["package"]?.jsonPrimitive?.contentOrNull
            ?: return "provide a 'package' to launch (e.g. com.android.settings)"
        val intent = ctx.packageManager.getLaunchIntentForPackage(pkg)
            ?: return "app not installed or not launchable: $pkg"
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(intent)
            "launched $pkg (note: Android may block launching apps while androidmcp is in the background)"
        } catch (t: Throwable) {
            "could not launch $pkg: ${t.message}"
        }
    }
}
