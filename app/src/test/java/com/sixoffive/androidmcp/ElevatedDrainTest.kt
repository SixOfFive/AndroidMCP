package com.sixoffive.androidmcp

import com.sixoffive.androidmcp.core.Elevated
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `Elevated.drain` against real child processes.
 *
 * These run on the JVM with `/bin/sh`, not on the device — the point is the draining contract, not
 * Shizuku. It is the one place where a single tool call can pin a `Dispatchers.IO` worker for the
 * life of a child process, which is unrecoverable without restarting the foreground service.
 */
class ElevatedDrainTest {

    private fun sh(cmd: String): Process =
        ProcessBuilder("/bin/sh", "-c", cmd).redirectErrorStream(true).start()

    private fun shellAvailable() = java.io.File("/bin/sh").canExecute()

    @Test
    fun `ordinary output is returned whole with an exit code`() {
        assumeTrue(shellAvailable())
        val r = Elevated.drain(sh("echo hello; echo world"))
        assertEquals("hello\nworld\n", String(r.bytes))
        assertFalse(r.timedOut)
        assertFalse(r.truncated)
        assertEquals(0, r.exitCode)
    }

    @Test
    fun `a non-zero exit code is reported`() {
        assumeTrue(shellAvailable())
        assertEquals(3, Elevated.drain(sh("exit 3")).exitCode)
    }

    @Test
    fun `a silent long-running child is killed at the deadline`() {
        assumeTrue(shellAvailable())
        // THE REGRESSION THIS EXISTS FOR. `InputStream.read` blocks indefinitely on a child that
        // writes nothing and does not exit, so a loop that only checks the clock *between* reads
        // never gets to look at it again — the 20 s timeout was unenforceable and the worker was
        // pinned for the child's whole lifetime.
        //
        // Killing the child is NOT enough on its own either: `sh -c "sleep 30"` leaves a grandchild
        // holding the stdout pipe, so even after destroyForcibly the read kept blocking and this
        // measured 30 s. Hence the reader thread — the caller returns on time regardless.
        val started = System.currentTimeMillis()
        val r = Elevated.drain(sh("sleep 30"), timeoutMs = 1_500L)
        val elapsed = System.currentTimeMillis() - started
        assertTrue(r.timedOut, "must report a timeout")
        assertTrue(elapsed < 10_000, "drain took ${elapsed}ms — the deadline was not enforced")
        assertTrue(r.text().contains("killed after"), "the caller must be told output was cut short")
    }

    @Test
    fun `a child reading stdin does not hang forever`() {
        assumeTrue(shellAvailable())
        // `cat` with no redirect blocks on stdin. Closing the child's stdin gives it EOF, so this
        // finishes on its own rather than waiting out the watchdog.
        val started = System.currentTimeMillis()
        val r = Elevated.drain(sh("cat"), timeoutMs = 5_000L)
        val elapsed = System.currentTimeMillis() - started
        assertTrue(elapsed < 4_000, "cat blocked on stdin for ${elapsed}ms — stdin was not closed")
        assertFalse(r.timedOut)
    }

    @Test
    fun `output is capped and the truncation is declared`() {
        assumeTrue(shellAvailable())
        val r = Elevated.drain(sh("cat /dev/zero"), timeoutMs = 10_000L, cap = 64 * 1024)
        assertTrue(r.truncated, "an endless stream must be truncated, not buffered")
        assertEquals(64 * 1024, r.bytes.size)
        assertTrue(r.text().contains("truncated"), "the caller must be told output was cut short")
    }

    @Test
    fun `a chatty stderr does not deadlock when merged`() {
        assumeTrue(shellAvailable())
        // The original defect class: only stdout was drained, so a child filling the stderr pipe
        // blocked while the server blocked reading stdout. Callers merge via redirectErrorStream
        // or a shell `2>&1`; this proves a large stderr volume flows through.
        val r = Elevated.drain(sh("i=0; while [ \$i -lt 2000 ]; do echo err-\$i >&2; i=\$((i+1)); done"))
        assertFalse(r.timedOut, "merged stderr must not deadlock the drain")
        assertTrue(String(r.bytes).contains("err-1999"))
    }

    @Test
    fun `the child is always reaped, even on a clean fast exit`() {
        assumeTrue(shellAvailable())
        // destroyForcibly used to be guarded by (timedOut || truncated), so a child that closed
        // stdout but kept running was left behind on every ordinary call.
        val p = sh("echo done")
        Elevated.drain(p)
        assertTrue(p.waitFor(2, TimeUnit.SECONDS), "process must not still be running after drain")
        assertFalse(p.isAlive)
    }

    @Test
    fun `a child that closes stdout but keeps running is bounded by the deadline`() {
        assumeTrue(shellAvailable())
        // Note what does NOT happen here: with redirectErrorStream(true) stdout and stderr are the
        // same pipe, so closing fd 1 leaves fd 2 holding the write end open and the read never sees
        // EOF. The deadline is therefore the correct exit path — the guarantee is that we return
        // on time (~2 s), not that we notice the close.
        val started = System.currentTimeMillis()
        val p = sh("echo early; exec 1>&-; sleep 30")
        val r = Elevated.drain(p, timeoutMs = 2_000L)
        val elapsed = System.currentTimeMillis() - started
        assertTrue(String(r.bytes).startsWith("early"))
        assertTrue(r.timedOut)
        assertTrue(elapsed < 10_000, "waited ${elapsed}ms — should be bounded by the 2s deadline, not the 30s sleep")
        assertFalse(p.isAlive, "the lingering child must be reaped")
    }
}
