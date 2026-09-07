package com.sixoffive.androidmcp.server

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.LocationManager
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
                putJsonArray("tools") { Capabilities.REGISTRY.forEach { add(toolDef(it)) } }
            })
            "tools/call" -> toolsCall(ctx, id, root, client)
            else -> error(id, -32601, "Method not found: $method")
        }
    }

    private fun toolDef(cap: CapabilityMeta): JsonObject = buildJsonObject {
        put("name", cap.id)
        val status = if (ConfigStore.isEnabled(cap.id)) "enabled" else "disabled"
        put("description", "${cap.title}. Why: ${cap.why.joinToString("; ")}. " +
            "Exposes: ${cap.dataExposed}. [currently $status]")
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
                if (cap.id == "read_sms" || cap.id == "read_call_log") {
                    putJsonObject("limit") { put("type", "integer") }
                }
                if (cap.id == "record_audio") {
                    putJsonObject("seconds") { put("type", "integer") }
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
        val b64 = android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP)
        return listOf(
            textBlk("Captured ${opts.outWidth}x${opts.outHeight} JPEG from the $facing camera (${jpeg.size} bytes)."),
            imageBlk(b64, "image/jpeg"),
        )
    }

    private suspend fun recordAudio(ctx: Context, args: JsonObject): List<JsonObject> {
        val secs = (args["seconds"]?.jsonPrimitive?.intOrNull ?: 5).coerceIn(1, 30)
        val bytes = AudioCapture.record(ctx, secs)
            ?: return listOf(textBlk("Audio capture failed — the mic may be in use, or the app is backgrounded (open androidmcp and retry)."))
        runCatching {
            val dir = java.io.File(ctx.filesDir, "audio").apply { mkdirs() }
            java.io.File(dir, "last.m4a").writeBytes(bytes)
        }
        val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        return listOf(
            textBlk("Recorded ${secs}s of audio (${bytes.size} bytes, AAC/MP4)."),
            audioBlk(b64, "audio/mp4"),
        )
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
        val b64 = android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP)
        return listOf(
            textBlk("Captured screen ${opts.outWidth}x${opts.outHeight} (${jpeg.size} bytes)."),
            imageBlk(b64, "image/jpeg"),
        )
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
}
