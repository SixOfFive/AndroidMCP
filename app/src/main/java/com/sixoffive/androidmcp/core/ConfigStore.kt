package com.sixoffive.androidmcp.core

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val Context.configDataStore by preferencesDataStore(name = "androidmcp_config")

data class AppConfig(
    val masterOn: Boolean = false,
    val enabled: Set<String> = setOf("list_capabilities"),
    val port: Int = 8765,
    val bind: String = "loopback", // loopback | lan | tailscale
    val folders: Set<String> = emptySet(), // persisted SAF tree URIs
    val allowBrowser: Boolean = false, // when true, accept browser Origins + emit CORS (token still required)
    val mediaAsLinks: Boolean = false, // return media as resource_link (fetchable URL) instead of inline base64
    val tls: Boolean = false, // serve HTTPS with a self-signed cert instead of plain HTTP
)

/** Single observable source of truth for toggles + server settings. */
object ConfigStore {
    private lateinit var app: Context
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val KEY_MASTER = booleanPreferencesKey("master_on")
    private val KEY_ENABLED = stringSetPreferencesKey("enabled_caps")
    private val KEY_PORT = intPreferencesKey("port")
    private val KEY_BIND = stringPreferencesKey("bind_mode")
    private val KEY_FOLDERS = stringSetPreferencesKey("folders")
    private val KEY_BROWSER = booleanPreferencesKey("allow_browser")
    private val KEY_MEDIA_LINKS = booleanPreferencesKey("media_as_links")
    private val KEY_TLS = booleanPreferencesKey("tls")

    val state = MutableStateFlow(AppConfig())

    val flow: Flow<AppConfig> get() = app.configDataStore.data.map { p ->
        AppConfig(
            masterOn = p[KEY_MASTER] ?: false,
            enabled = (p[KEY_ENABLED] ?: emptySet()) + "list_capabilities",
            port = p[KEY_PORT] ?: 8765,
            bind = p[KEY_BIND] ?: "loopback",
            folders = p[KEY_FOLDERS] ?: emptySet(),
            allowBrowser = p[KEY_BROWSER] ?: false,
            mediaAsLinks = p[KEY_MEDIA_LINKS] ?: false,
            tls = p[KEY_TLS] ?: false,
        )
    }

    fun init(context: Context) {
        app = context.applicationContext
        scope.launch { flow.collect { state.value = it } }
    }

    val current: AppConfig get() = state.value

    fun isEnabled(id: String): Boolean = id == "list_capabilities" || current.enabled.contains(id)

    fun setMaster(on: Boolean) = scope.launch {
        app.configDataStore.edit { it[KEY_MASTER] = on }
    }

    fun setEnabled(id: String, on: Boolean) = scope.launch {
        app.configDataStore.edit { p ->
            val set = (p[KEY_ENABLED] ?: emptySet()).toMutableSet()
            if (on) set.add(id) else set.remove(id)
            p[KEY_ENABLED] = set
        }
    }

    fun setBind(mode: String) = scope.launch { app.configDataStore.edit { it[KEY_BIND] = mode } }
    fun setPort(port: Int) = scope.launch { app.configDataStore.edit { it[KEY_PORT] = port } }
    fun setAllowBrowser(on: Boolean) = scope.launch { app.configDataStore.edit { it[KEY_BROWSER] = on } }
    fun setMediaAsLinks(on: Boolean) = scope.launch { app.configDataStore.edit { it[KEY_MEDIA_LINKS] = on } }
    fun setTls(on: Boolean) = scope.launch { app.configDataStore.edit { it[KEY_TLS] = on } }

    fun addFolder(uri: String) = scope.launch {
        app.configDataStore.edit { p -> p[KEY_FOLDERS] = (p[KEY_FOLDERS] ?: emptySet()) + uri }
    }

    fun removeFolder(uri: String) = scope.launch {
        app.configDataStore.edit { p -> p[KEY_FOLDERS] = (p[KEY_FOLDERS] ?: emptySet()) - uri }
    }
}
