package com.sixoffive.androidmcp.core

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

private val Context.tokenDataStore by preferencesDataStore(name = "androidmcp_tokens")

/** A named client token, stored only as a SHA-256 hash. */
data class ClientToken(val name: String, val hashHex: String)

/**
 * Per-client bearer tokens. Raw tokens are shown to the user exactly once (at
 * creation) and never persisted — only their hashes are stored. A stored entry is
 * 64 hex chars of hash immediately followed by the client name.
 */
object TokenStore {
    private lateinit var app: Context
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val KEY = stringSetPreferencesKey("tokens")

    /** Newly-minted raw tokens, keyed by name, kept in memory only until the app dies. */
    val freshlyMinted = MutableStateFlow<Map<String, String>>(emptyMap())
    val tokens = MutableStateFlow<List<ClientToken>>(emptyList())

    fun init(context: Context) {
        app = context.applicationContext
        scope.launch {
            app.tokenDataStore.data.map { p -> decode(p[KEY] ?: emptySet()) }.collect { tokens.value = it }
        }
    }

    private fun decode(raw: Set<String>): List<ClientToken> = raw.mapNotNull { s ->
        if (s.length > 64) ClientToken(name = s.substring(64), hashHex = s.substring(0, 64)) else null
    }

    private fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /**
     * A client name not already in use.
     *
     * `"client-${tokens.size + 1}"` reused a name after a revoke: revoke client-2 of three and the
     * next mint is client-3 again. Names are the identity the approval prompt shows, the audit log
     * records, and `notifications/cancelled` is scoped by — so two live tokens sharing one meant a
     * second holder could withdraw the first's pending approvals and their audit rows merged.
     */
    fun nextClientName(existing: List<ClientToken>): String {
        val taken = existing.map { it.name }.toSet()
        var n = existing.size + 1
        while ("client-$n" in taken) n++
        return "client-$n"
    }

    /** Generate a new token, store its hash, return the raw value (shown once). */
    fun generate(name: String): String {
        val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        // java.util.Base64 (API 26+; minSdk is 26) — same RFC 4648 URL-safe alphabet and no
        // wrapping, so token values are byte-identical, but it works on a plain JVM so token
        // minting and verification are unit-testable without Robolectric.
        val raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val hash = sha256Hex(raw)
        scope.launch {
            app.tokenDataStore.edit { p ->
                val set = (p[KEY] ?: emptySet()).toMutableSet()
                set.add(hash + name)
                p[KEY] = set
            }
        }
        freshlyMinted.value = freshlyMinted.value + (name to raw)
        return raw
    }

    fun revoke(name: String) = scope.launch {
        app.tokenDataStore.edit { p ->
            p[KEY] = (p[KEY] ?: emptySet()).filterNot { it.length > 64 && it.substring(64) == name }.toSet()
        }
        freshlyMinted.value = freshlyMinted.value - name
    }

    /** Verify a presented bearer token; returns the matching client name, or null. */
    fun verify(raw: String): String? {
        val hash = sha256Hex(raw)
        var matchName: String? = null
        for (t in tokens.value) {
            if (constantTimeEquals(t.hashHex, hash)) matchName = t.name
        }
        return matchName
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var r = 0
        for (i in a.indices) r = r or (a[i].code xor b[i].code)
        return r == 0
    }
}
