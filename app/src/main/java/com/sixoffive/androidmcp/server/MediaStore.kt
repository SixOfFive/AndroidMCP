package com.sixoffive.androidmcp.server

import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Short-lived in-memory store for media returned as `resource_link` instead of inline base64.
 * Each blob is addressed by an opaque id plus an unguessable nonce (a capability URL): only a
 * client that received the link can fetch it, once, within the TTL. Nothing is written to disk
 * here and nothing survives a process restart — it exists only to avoid shipping multi-MB
 * base64 inside a JSON-RPC reply.
 */
object MediaStore {
    class Entry(val bytes: ByteArray, val mime: String, val nonce: String, val expiresAt: Long)

    private const val TTL_MS = 10 * 60 * 1000L
    private const val MAX_ENTRIES = 24

    private val map = ConcurrentHashMap<String, Entry>()
    private val counter = AtomicLong(0)
    private val rng = SecureRandom()

    /** Store bytes; returns (id, nonce) for building the fetch URL. */
    fun put(bytes: ByteArray, mime: String): Pair<String, String> {
        prune()
        val id = "m" + counter.incrementAndGet().toString(36)
        val nonce = randomToken()
        map[id] = Entry(bytes, mime, nonce, System.currentTimeMillis() + TTL_MS)
        return id to nonce
    }

    /** Fetch bytes for id iff the nonce matches and it hasn't expired (single-use safe). */
    fun get(id: String, nonce: String): Entry? {
        val e = map[id] ?: return null
        if (e.expiresAt < System.currentTimeMillis() || e.nonce != nonce) {
            map.remove(id); return null
        }
        return e
    }

    private fun prune() {
        val now = System.currentTimeMillis()
        map.entries.removeIf { it.value.expiresAt < now }
        if (map.size >= MAX_ENTRIES) {
            // evict the oldest (smallest id counter) until under the cap
            map.keys.sortedBy { it.drop(1).toLongOrNull(36) ?: Long.MAX_VALUE }
                .take((map.size - MAX_ENTRIES) + 1)
                .forEach { map.remove(it) }
        }
    }

    private fun randomToken(): String {
        val b = ByteArray(18); rng.nextBytes(b)
        return android.util.Base64.encodeToString(b, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
    }
}
