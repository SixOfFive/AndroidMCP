package com.sixoffive.androidmcp.server

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

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
    private val rng = SecureRandom()

    /** Store bytes; returns (id, nonce) for building the fetch URL. */
    fun put(bytes: ByteArray, mime: String): Pair<String, String> {
        prune()
        // Ids used to be a sequential base36 counter ("m1", "m2", …). The /media route needs no
        // bearer token, so a guessable id let anyone on the network enumerate pending blobs.
        val id = randomToken(9)
        val nonce = randomToken(18)
        map[id] = Entry(bytes, mime, nonce, System.currentTimeMillis() + TTL_MS)
        return id to nonce
    }

    /**
     * Fetch bytes for [id] iff [nonce] matches and it hasn't expired, consuming the entry.
     *
     * Genuinely single-use: the old `get` returned the entry *without* removing it, so a link was
     * replayable for the full TTL despite four places documenting it as one-time. Conversely a
     * *wrong* nonce used to evict the entry, which — with guessable ids and no token on this route
     * — let anyone delete every pending blob.
     */
    fun take(id: String, nonce: String): Entry? {
        // sweepExpired, NOT prune: prune() also evicts for CAPACITY, and running it here destroyed
        // a live blob on the way to fetching one. put() prunes before inserting too, so the store
        // sits permanently at MAX_ENTRIES — meaning at capacity every take() evicted the oldest
        // unfetched link, the very one a client was about to dereference. Both callers then
        // reported "expired or already fetched" about a blob that was neither. And because it ran
        // before the nonce compare, it broke the documented "a bad guess must not evict" rule on a
        // route that carries no bearer token.
        sweepExpired()
        val e = map[id] ?: return null
        if (e.expiresAt < System.currentTimeMillis()) { map.remove(id); return null }
        if (!constantTimeEquals(e.nonce, nonce)) return null // a bad guess must not evict
        return if (map.remove(id, e)) e else null            // consume; lose the race → not found
    }

    /** Test/diagnostic: how many blobs are currently held. */
    fun size(): Int { sweepExpired(); return map.size }

    /** Drop entries past their TTL. Safe anywhere — it never touches a live blob. */
    private fun sweepExpired() {
        val now = System.currentTimeMillis()
        map.entries.removeIf { it.value.expiresAt < now }
    }

    /** Sweep, then make room for one more. Only [put] may do this: discarding a live blob is only
     *  acceptable when the alternative is refusing to store the new one. */
    private fun prune() {
        sweepExpired()
        if (map.size > MAX_ENTRIES - 1) {
            // Ids are random, so evict by actual age rather than by id ordering.
            map.entries.sortedBy { it.value.expiresAt }
                .take(map.size - (MAX_ENTRIES - 1))
                .forEach { map.remove(it.key) }
        }
    }

    /** Compare without leaking the matching prefix length through timing. */
    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    // java.util.Base64 (API 26+, and minSdk is 26) rather than android.util.Base64: identical
    // RFC 4648 URL-safe output, but available on a plain JVM so this class is unit-testable.
    private fun randomToken(bytes: Int): String {
        val b = ByteArray(bytes); rng.nextBytes(b)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    }
}
