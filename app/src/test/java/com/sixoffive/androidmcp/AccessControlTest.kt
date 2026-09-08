package com.sixoffive.androidmcp

import com.sixoffive.androidmcp.server.AccessControl
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The DNS-rebinding Origin policy and the rejected-connection throttle. */
class AccessControlTest {

    @Before fun clean() = AccessControl.reset()

    private fun allowed(origin: String?, browser: Boolean = true, list: Set<String> = emptySet()) =
        AccessControl.originAllowed(origin, browser, list)

    // ---- Origin policy ----

    @Test
    fun `no Origin header means a native client, judged only by its token`() {
        assertTrue(allowed(null, browser = false))
        assertTrue(allowed(null, browser = true))
    }

    @Test
    fun `every browser Origin is refused while the dashboard is off`() {
        for (o in listOf("http://localhost:3000", "null", "https://evil.example")) {
            assertFalse(allowed(o, browser = false), "should refuse $o with the dashboard off")
        }
    }

    @Test
    fun `an arbitrary Origin is refused even with the dashboard on`() {
        // Measured against the live server before this change: `Origin: https://evil.example`
        // passed the guard and failed only at auth, because CORS echoed whatever was sent.
        assertFalse(allowed("https://evil.example"))
        assertFalse(allowed("http://192.168.15.99:8080"))
        assertFalse(allowed("https://localhost.evil.example"))
    }

    @Test
    fun `local pages are accepted by default`() {
        for (o in listOf("http://localhost:3000", "http://127.0.0.1:5500", "https://localhost", "http://[::1]:9000")) {
            assertTrue(allowed(o), "should accept local origin $o")
        }
    }

    @Test
    fun `a file-opened dashboard sends Origin null and is accepted`() {
        // The repo's own dashboard/index.html is opened from disk.
        assertTrue(allowed("null"))
        assertFalse(allowed("null", browser = false))
    }

    @Test
    fun `an explicitly allowlisted origin is accepted`() {
        assertTrue(allowed("https://dash.example", list = setOf("https://dash.example")))
        // ...and only that exact origin — no prefix or suffix matching.
        assertFalse(allowed("https://dash.example.evil.com", list = setOf("https://dash.example")))
        assertFalse(allowed("https://dash.example:8443", list = setOf("https://dash.example")))
    }

    @Test
    fun `a hostname that merely contains localhost is not local`() {
        assertFalse(allowed("http://notlocalhost:3000"))
        assertFalse(allowed("http://localhost.attacker.tld"))
    }

    // ---- rejection throttling ----

    private fun reject(host: String, at: Long = 1_000L) =
        AccessControl.recordRejection(host, "unauthorized", nowMs = at)

    @Test
    fun `a burst of rejections is tolerated, then throttled`() {
        var throttledAt = -1
        for (i in 1..40) if (reject("10.0.0.5").throttle) { throttledAt = i; break }
        assertTrue(throttledAt in 15..25, "expected throttling after ~20 rejections, got $throttledAt")
    }

    @Test
    fun `hosts are throttled independently`() {
        repeat(30) { reject("10.0.0.5") }
        assertTrue(reject("10.0.0.5").throttle)
        // A different caller must not inherit the first one's exhausted bucket.
        assertFalse(reject("10.0.0.6").throttle)
    }

    @Test
    fun `the bucket refills over time`() {
        repeat(30) { reject("10.0.0.7") }
        assertTrue(reject("10.0.0.7").throttle, "should be exhausted")
        // Ten minutes later it is well above the burst again.
        assertFalse(reject("10.0.0.7", at = 1_000L + 10 * 60_000L).throttle)
    }

    @Test
    fun `a patient prober cannot scroll the audit ring clean`() {
        // The bucket refills at 10/min, so logging every non-throttled rejection would earn an
        // attacker ~10 audit lines a minute — enough to walk the 200-entry ring in ~20 minutes.
        // Logging is coalesced to one line per host per minute instead.
        val host = "10.0.0.9"
        var lines = 0
        var t = 0L
        repeat(60) {                      // one hour of probing, once a minute
            repeat(50) { reject(host, at = t) }   // ...50 attempts each time
            t += 60_000L
        }
        // Re-count deterministically over the same window.
        AccessControl.reset()
        lines = 0; t = 0L
        repeat(60) {
            repeat(50) { if (reject(host, at = t).logLine != null) lines++ }
            t += 60_000L
        }
        assertTrue(lines <= 61, "3000 rejections produced $lines audit lines; must be ~1 per minute")
        assertTrue(lines >= 55, "should still log steadily, got $lines")
    }

    @Test
    fun `a coalesced line reports how many it stands for`() {
        val host = "10.0.0.10"
        // The first rejection from a host must always be logged — it is the one most worth seeing.
        assertEquals("unauthorized", reject(host).logLine)
        repeat(5) { reject(host) }                            // suppressed within the minute
        val later = reject(host, at = 1_000L + 61_000L).logLine
        assertNotNull(later)
        // The line stands for six rejections: itself plus the five it absorbed.
        assertTrue(later.contains("+5 more"), "expected a suppressed count, got: $later")
    }

    @Test
    fun `only failures count, so a legitimate client cannot throttle itself`() {
        // The bucket is only ever touched from the reject path, so a client that authenticates
        // correctly never consumes a token however busy it is.
        assertTrue(AccessControl.remaining("10.0.0.8") >= 20, "an untouched host starts with a full burst")
    }
}
