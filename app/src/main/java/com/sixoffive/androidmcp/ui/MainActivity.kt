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
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
    val audit by AuditLog.entries.collectAsState()
    var permRefresh by remember { mutableIntStateOf(0) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permRefresh++ }

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

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text("androidmcp", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "On-device MCP server — default-deny, gated per capability.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ---- server ----
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
                            TextButton(onClick = { ConfigStore.removeFolder(f) }) { Text("Remove") }
                        }
                    }
                }
            }

            // ---- tokens ----
            item {
                SectionCard("Client tokens") {
                    OutlinedButton(onClick = { TokenStore.generate("client-${tokens.size + 1}") }) {
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
                            Text(t.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = { TokenStore.revoke(t.name) }) { Text("Revoke") }
                        }
                    }
                }
            }

            // ---- capabilities ----
            item { Text("Capabilities", style = MaterialTheme.typography.titleMedium) }
            items(Capabilities.REGISTRY) { cap ->
                val enabled = config.enabled.contains(cap.id) || cap.id == "list_capabilities"
                val permsOk = granted(cap.permissions)
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
                                checked = enabled,
                                enabled = cap.id != "list_capabilities",
                                onCheckedChange = { ConfigStore.setEnabled(cap.id, it) },
                            )
                        }
                        if (enabled && cap.permissions.isNotEmpty() && !permsOk) {
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = { permLauncher.launch(cap.permissions.toTypedArray()) }) {
                                Text("Grant Android permission")
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
