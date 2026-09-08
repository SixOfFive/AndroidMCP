package com.sixoffive.androidmcp.server

import java.util.concurrent.ConcurrentHashMap

/**
 * Origin policy and connection throttling — the two pieces of the HTTP guard that are worth
 * testing on their own, kept free of Ktor and Android so they can be.
 */
internal object AccessControl {

    // ---- Origin policy (DNS-rebinding guard) ----

    /**
     * May a request carrying this `Origin` proceed?
     *
     * Browsers send `Origin`; native MCP clients do not, so a null origin here means "not a
     * browser" and is judged only by the bearer token. For browser origins the old rule was a
     * single global boolean — with the dashboard opted in, `applyCors` echoed *whatever* origin
     * the caller sent, which reduced the spec's independent Origin check to a no-op exactly when
     * the feature it guards is in use. Measured against the live server before this change:
     * `Origin: https://evil.example` sailed past the guard and failed only at auth.
     *
     * Now: with the dashboard off, every browser origin is refused. With it on, only the
     * built-in local defaults plus any origin the owner added in the app.
     */
    fun originAllowed(origin: String?, allowBrowser: Boolean, allowed: Set<String>): Boolean {
        if (origin == null) return true          // not a browser; the bearer token still applies
        if (!allowBrowser) return false
        if (origin in allowed) return true
        return isLocalOrigin(origin)
    }

    /**
     * Origins that are safe by construction: a page the owner opened locally.
     *
     * `"null"` is what a `file://` page sends — which is how the repo's own `dashboard/index.html`
     * is opened. It is accepted deliberately, but note it is *also* what a sandboxed iframe and a
     * `data:` document send, so it is only as safe as the bearer token behind it.
     */
    private fun isLocalOrigin(origin: String): Boolean {
        if (origin == "null") return true
        val hostPort = origin.substringAfter("://", "").ifEmpty { return false }
        // IPv6 authorities are bracketed ("[::1]:9000"), so splitting on the first ':' would
        // otherwise yield "[" and quietly refuse a legitimate loopback page.
        val host = if (hostPort.startsWith("[")) {
            hostPort.drop(1).substringBefore(']')   // [::1]:9000 -> ::1
        } else {
            hostPort.substringBefore(':')           // localhost:3000 -> localhost
        }.lowercase()
        return host == "localhost" || host == "127.0.0.1" || host == "::1"
    }

    // ---- rejected-connection throttling ----

    /**
     * A per-remote-host token bucket over *failures only*.
     *
     * Counting successes too would let a busy legitimate client throttle itself. Token guessing is
     * already infeasible (24 CSPRNG bytes, constant-time hash compare); this exists so a `lan`
     * bind cannot be probed indefinitely at no cost, and so the audit log is not flooded.
     */
    private const val BURST = 20
    private const val REFILL_PER_MINUTE = 10.0
    private const val IDLE_EVICT_MS = 10 * 60 * 1000L

    /** At most one audit line per host per this interval, however fast the rejections arrive. */
    private const val LOG_COALESCE_MS = 60_000L

    private class Bucket(
        var tokens: Double,
        var lastRefillMs: Long,
        // Nullable, not 0L: a zero sentinel makes "now - lastLogged" tiny on a real clock only
        // for the epoch, but tiny in tests — and either way it must never suppress the FIRST
        // rejection from a host, which is the one most worth seeing.
        var lastLoggedMs: Long? = null,
        var suppressed: Int = 0,
    )

    private val buckets = ConcurrentHashMap<String, Bucket>()

    /**
     * What to do about one rejected request.
     *
     * @param throttle answer 429 instead of the ordinary 401/403.
     * @param logLine an audit line, or null when this rejection is folded into a later one.
     */
    data class Rejection(val throttle: Boolean, val logLine: String?)

    /**
     * Record one rejected request from [host].
     *
     * Logging is coalesced to one line per host per minute, carrying the count it stands for.
     * Logging every non-throttled rejection was not enough: the bucket refills at
     * [REFILL_PER_MINUTE], so a patient prober still earns ~10 audit lines a minute and walks the
     * 200-entry ring clean in about twenty. The audit log is the UI's trust record — an attacker
     * must not be able to scroll a real event out of it.
     */
    fun recordRejection(host: String, detail: String, nowMs: Long = System.currentTimeMillis()): Rejection {
        evictIdle(nowMs)
        val b = buckets.computeIfAbsent(host) { Bucket(BURST.toDouble(), nowMs) }
        synchronized(b) {
            val elapsedMin = (nowMs - b.lastRefillMs).coerceAtLeast(0L) / 60_000.0
            b.tokens = (b.tokens + elapsedMin * REFILL_PER_MINUTE).coerceAtMost(BURST.toDouble())
            b.lastRefillMs = nowMs
            val throttle = b.tokens < 1.0
            if (!throttle) b.tokens -= 1.0

            b.suppressed++
            val last = b.lastLoggedMs
            if (last != null && nowMs - last < LOG_COALESCE_MS) return Rejection(throttle, null)
            val n = b.suppressed
            b.suppressed = 0
            b.lastLoggedMs = nowMs
            return Rejection(throttle, if (n > 1) "$detail (+${n - 1} more in the last minute)" else detail)
        }
    }

    /** How many rejections this host has left before it is throttled (diagnostics/tests). */
    fun remaining(host: String): Int = buckets[host]?.let { synchronized(it) { it.tokens.toInt() } } ?: BURST

    private fun evictIdle(nowMs: Long) {
        if (buckets.size < 256) return
        buckets.entries.removeIf { synchronized(it.value) { nowMs - it.value.lastRefillMs > IDLE_EVICT_MS } }
    }

    /** Test hook — the buckets are process-global. */
    fun reset() = buckets.clear()
}
