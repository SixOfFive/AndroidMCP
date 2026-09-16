package com.sixoffive.androidmcp.ui

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.sixoffive.androidmcp.core.AuditLog
import com.sixoffive.androidmcp.core.Capabilities
import com.sixoffive.androidmcp.core.ConfigStore
import com.sixoffive.androidmcp.core.TokenStore
import com.sixoffive.androidmcp.server.McpService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppTheme { ServerScreen() } }
    }
}

/** Scope choice for a newly generated client token. */
private enum class TokenScopeMode(val label: String) {
    FULL("Full access"), READ_ONLY("Read-only"), CUSTOM("Custom"),
}

/** Short human summary of a token's scope, for the token list. */
private fun scopeLabel(scope: Set<String>?): String = when {
    scope == null -> "full access"
    scope.isEmpty() -> "no capabilities"
    scope == Capabilities.lowRiskPresetIds() -> "read-only"
    else -> "${scope.size} " + if (scope.size == 1) "capability" else "capabilities"
}

/** Expiry choices offered when generating a token. */
private enum class TokenTtl(val label: String, val ms: Long?) {
    NEVER("Never", null),
    HOUR("1h", 3_600_000L),
    DAY("24h", 86_400_000L),
    WEEK("7d", 7L * 86_400_000L),
    MONTH("30d", 30L * 86_400_000L),
}

private fun tokenTitle(t: com.sixoffive.androidmcp.core.ClientToken): String {
    val id = if (t.label.isNotBlank()) "${t.label} (${t.name})" else t.name
    return "$id  ·  ${scopeLabel(t.scope)}"
}

private fun tokenSubtitle(t: com.sixoffive.androidmcp.core.ClientToken, lastUsedMs: Long?): String {
    val now = System.currentTimeMillis()
    val created = if (t.createdAt == 0L) "created ?" else "created ${relPast(now - t.createdAt)}"
    val expiry = when {
        t.expiresAt == null -> "no expiry"
        now >= t.expiresAt -> "EXPIRED"
        else -> "expires in ${relFuture(t.expiresAt - now)}"
    }
    val used = if (lastUsedMs == null) "never used" else "used ${relPast(now - lastUsedMs)}"
    return "$created · $expiry · $used"
}

private fun relPast(deltaMs: Long): String = when {
    deltaMs < 60_000 -> "just now"
    deltaMs < 3_600_000 -> "${deltaMs / 60_000}m ago"
    deltaMs < 86_400_000 -> "${deltaMs / 3_600_000}h ago"
    else -> "${deltaMs / 86_400_000}d ago"
}

private fun relFuture(deltaMs: Long): String = when {
    deltaMs < 60_000 -> "<1m"
    deltaMs < 3_600_000 -> "${deltaMs / 60_000}m"
    deltaMs < 86_400_000 -> "${deltaMs / 3_600_000}h"
    else -> "${deltaMs / 86_400_000}d"
}

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}

@Composable
private fun ServerScreen() {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val config by ConfigStore.state.collectAsState()
    val running by McpService.running.collectAsState()
    val bound by McpService.boundInfo.collectAsState()
    val tokens by TokenStore.tokens.collectAsState()
    val minted by TokenStore.freshlyMinted.collectAsState()
    val lastUsed by TokenStore.lastUsed.collectAsState()
    val audit by AuditLog.entries.collectAsState()
    var permRefresh by remember { mutableIntStateOf(0) }
    var showBootWarning by remember { mutableStateOf(false) }
    var resumedOnce by remember { mutableStateOf(false) }

    // Resume-on-launch: if the user Saved an armed start-on-boot config with the server enabled,
    // bring the server back up when the app is opened (mirrors the boot receiver). Runs once.
    LaunchedEffect(config.bootArmed, config.masterOn) {
        if (!resumedOnce && config.bootArmed && config.masterOn && !running) {
            resumedOnce = true
            McpService.start(ctx)
        }
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permRefresh++ }

    // Per-call approval prompts (and post_notification) are useless if the app can't post
    // notifications. On Android 13+ POST_NOTIFICATIONS is a runtime permission — without it
    // approvals never appear and every high-impact call silently times out. Ask once on launch.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, "android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED
        ) {
            permLauncher.launch(arrayOf("android.permission.POST_NOTIFICATIONS"))
        }
    }

    val mpm = remember {
        ctx.getSystemService(android.content.Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
    }
    val projActive by com.sixoffive.androidmcp.server.ProjectionHolder.active.collectAsState()
    val projLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == android.app.Activity.RESULT_OK && res.data != null) {
            com.sixoffive.androidmcp.server.McpService.startProjection(ctx, res.resultCode, res.data!!)
        }
    }

    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ConfigStore.addFolder(uri.toString())
        }
    }

    fun granted(perms: List<String>): Boolean {
        permRefresh // read so recomposition re-checks after a grant
        if (perms.isEmpty()) return true
        return if (perms.contains("android.permission.ACCESS_COARSE_LOCATION")) {
            ContextCompat.checkSelfPermission(ctx, "android.permission.ACCESS_FINE_LOCATION") == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(ctx, "android.permission.ACCESS_COARSE_LOCATION") == PackageManager.PERMISSION_GRANTED
        } else perms.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }
    }

    // Refresh elevated-tier state the moment Shizuku grants (or denies) permission.
    androidx.compose.runtime.DisposableEffect(Unit) {
        val l = rikka.shizuku.Shizuku.OnRequestPermissionResultListener { _, _ -> permRefresh++ }
        runCatching { rikka.shizuku.Shizuku.addRequestPermissionResultListener(l) }
        onDispose { runCatching { rikka.shizuku.Shizuku.removeRequestPermissionResultListener(l) } }
    }

    if (showBootWarning) {
        AlertDialog(
            onDismissRequest = { showBootWarning = false },
            title = { Text("Start the server on boot?") },
            text = {
                Text(
                    "If you enable this and press Save, the MCP server — and every capability you've turned on — " +
                        "will start automatically whenever the device boots or you open the app, reachable on the " +
                        "'${config.bind}' interface by anyone holding a valid token, without you opening the app first. " +
                        "Nothing starts on boot until you press Save."
                )
            },
            confirmButton = { TextButton(onClick = { ConfigStore.setStartOnBoot(true); showBootWarning = false }) { Text("Enable") } },
            dismissButton = { TextButton(onClick = { showBootWarning = false }) { Text("Cancel") } },
        )
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("androidmcp", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "On-device MCP server — default-deny, gated per capability.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Nothing here is required to be on. The server and every capability stay OFF until you turn them on — enable only the features you want a connected client to have. Each one states exactly what it exposes and the risk, right where you toggle it.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            // ---- quick bulk enable / disable ----
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { ConfigStore.enableCaps(Capabilities.noInterventionIds()) },
                            modifier = Modifier.weight(1f),
                        ) { Text("Enable all (no prompts)") }
                        OutlinedButton(
                            onClick = {
                                ConfigStore.disableAllCaps()
                                ConfigStore.setMaster(false)
                                McpService.stop(ctx)
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("Disable all & stop") }
                    }
                    Text(
                        "'Enable all' turns on only capabilities that need no permission, per-call approval, " +
                            "or Shizuku/root — everything usable with zero further taps. 'Disable all' turns every " +
                            "capability off and stops and disables the server.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ---- server: run state + bind ----
            item {
                SectionCard("Server") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(if (running) "Running" else "Stopped", style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (running) "listening on $bound  (bind: ${config.bind})"
                                else "bind: ${config.bind} · port ${config.port}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = running, onCheckedChange = { on ->
                            ConfigStore.setMaster(on)
                            if (on) McpService.start(ctx) else McpService.stop(ctx)
                        })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("loopback", "lan", "tailscale").forEach { mode ->
                            OutlinedButton(
                                onClick = { ConfigStore.setBind(mode) },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                            ) { Text(if (config.bind == mode) "● $mode" else mode, style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                    if (running) Text(
                        "Restart the server to apply a bind change.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ---- connection & transport ----
            item {
                SectionCard("Connection & transport") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Allow browser dashboard", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "OFF by default. When ON, the server accepts requests from a web page (adds CORS and permits browser Origins) so the local dashboard can reach it. A token is still required, so a random website cannot act — enable this only while you use the dashboard.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = config.allowBrowser, onCheckedChange = { ConfigStore.setAllowBrowser(it) })
                    }
                    // Origin allowlist. Turning the dashboard on used to admit ANY browser origin
                    // (CORS simply echoed whatever the caller sent), so the DNS-rebinding guard
                    // went silent exactly when it was in use.
                    if (config.allowBrowser) {
                        Text(
                            "Allowed origins — pages on localhost / 127.0.0.1 and a local file:// " +
                                "dashboard (Origin \"null\") are always accepted. Add any other page " +
                                "you want to allow; everything else is refused with 403.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        var newOrigin by remember { mutableStateOf("") }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = newOrigin,
                                onValueChange = { newOrigin = it },
                                singleLine = true,
                                label = { Text("https://example.com") },
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(8.dp))
                            TextButton(
                                enabled = newOrigin.isNotBlank(),
                                onClick = {
                                    ConfigStore.addAllowedOrigin(newOrigin.trim().trimEnd('/'))
                                    newOrigin = ""
                                },
                            ) { Text("Add") }
                        }
                        config.allowedOrigins.sorted().forEach { o ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(o, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                TextButton(onClick = { ConfigStore.removeAllowedOrigin(o) }) { Text("Remove") }
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Media as links", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Return photos / audio / screenshots as a short-lived fetchable URL (resource_link) instead of inline base64 — much smaller replies. The link carries an unguessable one-time key (not your token) and expires in 10 minutes.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = config.mediaAsLinks, onCheckedChange = { ConfigStore.setMediaAsLinks(it) })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("HTTPS (self-signed TLS)", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Serve over HTTPS with a self-signed certificate. Clients must trust it (or skip verification). Redundant on Tailscale, which is already encrypted. Restart the server to apply.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = config.tls, onCheckedChange = { ConfigStore.setTls(it) })
                    }
                    run {
                        val fp = remember(config.tls, running) {
                            com.sixoffive.androidmcp.server.TlsKeystore.fingerprintSha256(ctx)
                        }
                        if (fp != null) Text(
                            "cert SHA-256 (stable — pin this in your client):\n$fp",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // ---- startup ----
            item {
                SectionCard("Startup") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Start on boot", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "OFF by default. Auto-starts the server on device boot and when the app opens — exposing your enabled capabilities without you opening the app. Enabling shows a warning, and it only takes effect after you press Save.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = config.startOnBoot,
                            onCheckedChange = { on -> if (on) showBootWarning = true else ConfigStore.setStartOnBoot(false) },
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            when {
                                config.startOnBoot != config.bootArmed -> "Unsaved — press Save to apply start-on-boot"
                                config.bootArmed -> "Saved — server auto-starts on boot / app launch"
                                else -> "Not armed — start the server manually each time"
                            },
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.labelSmall,
                            color = when {
                                config.startOnBoot != config.bootArmed -> MaterialTheme.colorScheme.error
                                config.bootArmed -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        OutlinedButton(
                            onClick = { ConfigStore.saveStartup() },
                            enabled = config.startOnBoot != config.bootArmed,
                        ) { Text("Save") }
                    }
                }
            }

            // ---- setup & reliability ----
            item {
                SectionCard("Setup & reliability") {
                    Text(
                        "For a server that stays reachable and for the special-access capabilities:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = {
                        runCatching {
                            ctx.startActivity(
                                android.content.Intent(
                                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    android.net.Uri.parse("package:" + ctx.packageName),
                                ),
                            )
                        }
                    }) { Text("Allow unrestricted battery") }
                    OutlinedButton(onClick = {
                        runCatching {
                            ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        }
                    }) { Text("Notification access (Read notifications)") }
                    run {
                        permRefresh // re-check after a grant
                        if (android.os.Build.VERSION.SDK_INT >= 33 &&
                            ContextCompat.checkSelfPermission(ctx, "android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED
                        ) {
                            OutlinedButton(onClick = { permLauncher.launch(arrayOf("android.permission.POST_NOTIFICATIONS")) }) {
                                Text("Enable notifications (approval prompts need this)")
                            }
                        }
                    }
                    run {
                        permRefresh // re-check Shizuku/root state after a grant
                        when {
                            com.sixoffive.androidmcp.core.Elevated.shizukuReady() -> Text(
                                "Elevated tier: ON (via ${com.sixoffive.androidmcp.core.Elevated.source()}) — the silent-screenshot and elevated-shell capabilities can now be enabled below.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            com.sixoffive.androidmcp.core.Elevated.shizukuNeedsGrant() -> OutlinedButton(onClick = {
                                com.sixoffive.androidmcp.core.Elevated.requestShizuku(4001)
                            }) { Text("Grant Shizuku (elevated tier)") }
                            else -> Text(
                                "Elevated tier (silent screenshot, elevated shell) is optional and OFF. It needs Shizuku — a non-destructive uid-2000 shell you start once over ADB (no root, no unlock, no wipe) — or Magisk root. Start Shizuku and a Grant button appears here.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Text(
                        "Sideloaded: if a special-access toggle is greyed out, open App info → ⋮ → 'Allow restricted settings' first. On Samsung, also exclude androidmcp from Device Care → Sleeping apps.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ---- screen sharing (for capture_screenshot) ----
            item {
                SectionCard("Screen sharing") {
                    Text(
                        if (projActive) "Active — capture_screenshot can grab the screen."
                        else "Off. Required for capture_screenshot — Android needs a one-time consent. (Server must be running.)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (projActive) {
                        TextButton(onClick = { com.sixoffive.androidmcp.server.ProjectionHolder.stop() }) { Text("Stop sharing") }
                    } else {
                        OutlinedButton(onClick = { projLauncher.launch(mpm.createScreenCaptureIntent()) }) { Text("Start screen sharing") }
                    }
                }
            }

            // ---- shared folders (for list_files) ----
            item {
                SectionCard("Shared folders") {
                    Text(
                        "Folders you grant here are the only files list_files can read.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = { folderLauncher.launch(null) }) { Text("Add folder") }
                    config.folders.forEach { f ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                android.net.Uri.parse(f).lastPathSegment ?: f,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            TextButton(onClick = {
                                // Release the OS grant too, not just our record of it. Dropping the
                                // config entry is what stops list_files reaching the folder, but the
                                // persistable permission taken at Add time survives indefinitely
                                // otherwise: the app keeps accumulating grants (Android caps these
                                // per app), an owner auditing what androidmcp can touch still sees
                                // one the app's own UI says was removed, and re-adding the folder
                                // silently reuses a grant they believed was gone.
                                runCatching {
                                    ctx.contentResolver.releasePersistableUriPermission(
                                        android.net.Uri.parse(f),
                                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                                    )
                                }
                                ConfigStore.removeFolder(f)
                            }) { Text("Remove") }
                        }
                    }
                }
            }

            // ---- tokens ----
            item {
                SectionCard("Client tokens") {
                    var scopeMode by remember { mutableStateOf(TokenScopeMode.FULL) }
                    // capId -> checked, for the Custom picker; pre-seeded to the low-risk preset so
                    // a custom token starts safe and the owner widens or narrows from there.
                    val custom = remember {
                        androidx.compose.runtime.mutableStateMapOf<String, Boolean>().apply {
                            val low = Capabilities.lowRiskPresetIds()
                            Capabilities.REGISTRY.forEach { put(it.id, it.id in low) }
                        }
                    }
                    var label by remember { mutableStateOf("") }
                    var ttl by remember { mutableStateOf(TokenTtl.NEVER) }

                    Text("A new token can call:", style = MaterialTheme.typography.labelMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TokenScopeMode.entries.forEach { m ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 4.dp)) {
                                androidx.compose.material3.RadioButton(
                                    selected = scopeMode == m,
                                    onClick = { scopeMode = m },
                                )
                                Text(m.label, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    Text(
                        when (scopeMode) {
                            TokenScopeMode.FULL -> "Any capability the owner has enabled."
                            TokenScopeMode.READ_ONLY -> "Only low-risk tools that need no per-call approval (no camera/mic/SMS/contacts/shell, no state changes beyond trivial ones)."
                            TokenScopeMode.CUSTOM -> "Exactly the tools you tick below. list_capabilities is always allowed."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (scopeMode == TokenScopeMode.CUSTOM) {
                        Capabilities.REGISTRY.forEach { c ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                androidx.compose.material3.Checkbox(
                                    checked = custom[c.id] ?: false,
                                    onCheckedChange = { custom[c.id] = it },
                                )
                                Text(
                                    "${c.title}  ·  ${c.id}",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = label,
                        onValueChange = { label = it },
                        label = { Text("Label (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("Expires:", style = MaterialTheme.typography.labelMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TokenTtl.entries.forEach { opt ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 4.dp)) {
                                androidx.compose.material3.RadioButton(selected = ttl == opt, onClick = { ttl = opt })
                                Text(opt.label, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    OutlinedButton(onClick = {
                        val allowed: Set<String>? = when (scopeMode) {
                            TokenScopeMode.FULL -> null
                            TokenScopeMode.READ_ONLY -> Capabilities.lowRiskPresetIds()
                            TokenScopeMode.CUSTOM -> custom.filterValues { it }.keys.toSet()
                        }
                        TokenStore.generate(TokenStore.nextClientName(tokens), allowed, label.trim(), ttl.ms)
                        label = ""
                    }) {
                        Text("Generate token")
                    }
                    if (minted.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        minted.forEach { (name, raw) ->
                            val cmd = "claude mcp add --transport http phone http://$bound/mcp " +
                                "--header \"Authorization: Bearer $raw\""
                            Text("$name (new — shown once):", style = MaterialTheme.typography.labelMedium)
                            Mono(cmd)
                            TextButton(onClick = { clipboard.setText(AnnotatedString(cmd)) }) { Text("Copy connect command") }
                            HorizontalDivider()
                        }
                    }
                    tokens.forEach { t ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(tokenTitle(t), style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    tokenSubtitle(t, lastUsed[t.name]),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { TokenStore.revoke(t.name) }) { Text("Revoke") }
                        }
                    }
                }
            }

            // ---- capabilities ----
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Capabilities", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { ConfigStore.setExpandedCategories(Capabilities.CATEGORIES.map { it.id }.toSet()) }) { Text("Expand all") }
                    TextButton(onClick = { ConfigStore.setExpandedCategories(emptySet()) }) { Text("Collapse all") }
                }
            }
            Capabilities.CATEGORIES.forEach { catg ->
              val caps = Capabilities.inCategory(catg.id)
              if (caps.isEmpty()) return@forEach
              val expanded = config.expandedCategories.contains(catg.id)
              val onCount = caps.count { config.enabled.contains(it.id) || it.id == "list_capabilities" }
              item(key = "cat_${catg.id}") {
                  Row(
                      Modifier.fillMaxWidth()
                          .clickable { ConfigStore.setCategoryExpanded(catg.id, !expanded) }
                          .padding(top = 10.dp, bottom = 4.dp),
                      verticalAlignment = Alignment.CenterVertically,
                  ) {
                      Text(
                          (if (expanded) "▾  " else "▸  ") + catg.label,
                          Modifier.weight(1f),
                          style = MaterialTheme.typography.titleSmall,
                          color = MaterialTheme.colorScheme.primary,
                      )
                      Text(
                          if (onCount > 0) "$onCount / ${caps.size} on" else "0 / ${caps.size}",
                          style = MaterialTheme.typography.labelSmall,
                          color = if (onCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                      )
                  }
              }
              if (expanded) {
              items(caps, key = { it.id }) { cap ->
                val enabled = config.enabled.contains(cap.id) || cap.id == "list_capabilities"
                val permsOk = granted(cap.permissions)
                val hwMissing = com.sixoffive.androidmcp.core.HardwareCheck.missing(ctx, cap.id)
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(cap.title, style = MaterialTheme.typography.titleSmall)
                                Text(cap.why.joinToString("; "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("Exposes: ${cap.dataExposed}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = enabled && hwMissing == null,
                                enabled = cap.id != "list_capabilities" && hwMissing == null,
                                onCheckedChange = { ConfigStore.setEnabled(cap.id, it) },
                            )
                        }
                        if (hwMissing != null) {
                            Spacer(Modifier.height(6.dp))
                            Text("Not available on this device — $hwMissing.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error)
                        }
                        if (enabled && hwMissing == null && cap.permissions.isNotEmpty() && !permsOk) {
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = { permLauncher.launch(cap.permissions.toTypedArray()) }) {
                                Text("Grant Android permission")
                            }
                        }
                        if (enabled && hwMissing == null && cap.highImpact) {
                            permRefresh // recompute armed state after a tap
                            val armed = com.sixoffive.androidmcp.server.ApprovalManager.isArmed(cap.id)
                            Spacer(Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (armed) "Armed — approvals skipped for a window" else "Per-call approval required",
                                    Modifier.weight(1f),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (armed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (armed) {
                                    TextButton(onClick = { com.sixoffive.androidmcp.server.ApprovalManager.disarm(cap.id); permRefresh++ }) { Text("Disarm") }
                                } else {
                                    TextButton(onClick = { com.sixoffive.androidmcp.server.ApprovalManager.arm(cap.id, 10); permRefresh++ }) { Text("Arm 10 min") }
                                }
                            }
                        }
                    }
                }
            }
            }
            }

            // ---- audit ----
            item { Text("Recent tool calls", style = MaterialTheme.typography.titleMedium) }
            if (audit.isEmpty()) {
                item { Text("— none yet —", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(audit) { e ->
                Text(
                    "${if (e.allowed) "✓" else "✗"} ${e.tool} · ${e.client} · ${e.detail}",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (e.allowed) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun Mono(text: String) {
    Text(text, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}
