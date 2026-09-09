package com.sixoffive.androidmcp.core

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/**
 * Elevated (shell/root) execution. Prefers **Shizuku** (uid 2000, started over ADB — no
 * bootloader unlock, no wipe) and falls back to **Magisk su** if present. Non-root
 * devices with neither report NOT_SUPPORTED_WITHOUT_ROOT.
 */
object Elevated {

    fun shizukuReady(): Boolean = runCatching {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** Shizuku is running but this app hasn't been granted yet (UI can offer a grant button). */
    fun shizukuNeedsGrant(): Boolean = runCatching {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun isAvailable(): Boolean = shizukuReady() || Root.isAvailable()

    fun source(): String = when {
        shizukuReady() -> "shizuku"
        Root.isAvailable() -> "root"
        else -> "none"
    }

    fun exec(cmd: String): String =
        if (shizukuReady()) shizukuExec(cmd) else Root.exec(cmd)

    fun execBytes(cmd: String): ByteArray? =
        if (shizukuReady()) shizukuBytes(cmd) else Root.execBytes(cmd)

    /**
     * Drain a child process safely and return its bytes.
     *
     * Three separate ways the old `p.inputStream.readBytes(); p.waitFor()` could wedge a worker
     * thread on `Dispatchers.IO` for the life of the process — unrecoverable without restarting
     * the service:
     *  - **stderr deadlock.** Only stdout was read. A command writing more than the stderr pipe
     *    buffer (`find / -name x` → thousands of "Permission denied") blocks in the child while
     *    the server blocks reading stdout. Callers pass [mergeStderr] so both land in one pipe.
     *  - **no timeout.** `waitFor()` with no bound never returns for `logcat` without `-d`.
     *  - **unbounded read.** `readBytes()` on `cat /dev/urandom` exhausts the heap long before
     *    any downstream `.take(20000)` applies.
     */
    internal fun drain(p: Process, timeoutMs: Long = EXEC_TIMEOUT_MS, cap: Int = MAX_OUTPUT_BYTES): DrainResult {
        // Close the child's stdin at once. A command that reads stdin (`cat`, `grep foo`) would
        // otherwise block forever waiting for input nobody is going to send.
        runCatching { p.outputStream.close() }

        val buf = java.io.ByteArrayOutputStream()
        val truncated = java.util.concurrent.atomic.AtomicBoolean(false)

        // The read runs on its own daemon thread and the CALLER waits with a timeout, rather than
        // the caller doing the blocking read itself.
        //
        // Killing the child is not sufficient to unblock a read: `root_shell` runs `sh -c "<cmd>"`,
        // and any grandchild the command spawns inherits the stdout pipe and holds the write end
        // open. `destroyForcibly()` reaps the shell, the grandchild lives on, and `read` keeps
        // blocking — measured: a 1.5 s deadline on `sh -c "sleep 30"` still returned after 30 s.
        // So the guarantee here is about the CALLER's thread, which is the scarce resource: it is
        // released on time no matter what the child's descendants do. A leaked reader thread that
        // exits whenever the pipe finally closes is a far smaller problem than a pinned worker
        // from the request pool.
        val reader = Thread {
            val chunk = ByteArray(16 * 1024)
            val ins = p.inputStream
            while (true) {
                val r = try { ins.read(chunk) } catch (_: Throwable) { -1 }
                if (r < 0) break
                synchronized(buf) {
                    if (buf.size() + r > cap) {
                        buf.write(chunk, 0, (cap - buf.size()).coerceAtLeast(0))
                        truncated.set(true)
                    } else {
                        buf.write(chunk, 0, r)
                    }
                }
                if (truncated.get()) break
            }
        }.apply { isDaemon = true; name = "androidmcp-exec-reader"; start() }

        reader.join(timeoutMs)
        // Timed out only if the reader is still going AND it did not stop because of the cap.
        val timedOut = reader.isAlive && !truncated.get()

        // Always reap, whatever the outcome. This used to be guarded by `timedOut || truncated`,
        // so an ordinary command that closed stdout but kept running was left behind every time.
        runCatching { p.destroyForcibly() }
        if (!timedOut && !truncated.get()) runCatching { p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS) }

        val bytes = synchronized(buf) { buf.toByteArray() }
        val rc = runCatching { if (timedOut) null else p.exitValue() }.getOrNull()
        return DrainResult(bytes, truncated.get(), timedOut, rc)
    }

    internal data class DrainResult(
        val bytes: ByteArray,
        val truncated: Boolean,
        val timedOut: Boolean,
        val exitCode: Int?,
    ) {
        /** Text plus an explicit note when output was cut short, so a caller is never silently lied to. */
        fun text(): String = String(bytes) + when {
            // Truncation first: when output was capped we stopped reading deliberately, which is a
            // different thing from the command outrunning its deadline.
            truncated -> "\n… (truncated at ${MAX_OUTPUT_BYTES / 1024} KB)"
            timedOut -> "\n… (killed after ${EXEC_TIMEOUT_MS / 1000}s — the command did not finish; " +
                "long-running commands like 'logcat' need a bounded form such as 'logcat -d')"
            else -> ""
        }
    }

    /** Hard bounds on any elevated command: it runs inside one HTTP request on a shared IO pool. */
    internal const val EXEC_TIMEOUT_MS = 20_000L
    internal const val MAX_OUTPUT_BYTES = 1024 * 1024

    // Shizuku.newProcess is @hidden in the public API; reach it by reflection. The returned
    // ShizukuRemoteProcess extends java.lang.Process, so its streams behave normally.
    private val newProcess by lazy {
        Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java, Array<String>::class.java, String::class.java,
        ).apply { isAccessible = true }
    }

    private fun shizukuProcess(cmd: String): Process =
        newProcess.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process

    // `2>&1` inside the shell command: the Shizuku process's stderr is a separate pipe nobody
    // reads, so a chatty command would otherwise fill it and deadlock. (`elevated_input` and
    // `elevated_settings` already appended this by hand; `root_shell` did not.)
    private fun shizukuExec(cmd: String): String = runCatching {
        drain(shizukuProcess("$cmd 2>&1")).text()
    }.getOrElse { "shizuku exec failed: ${it.message}" }

    // Binary output (e.g. `screencap -p`) must NOT have stderr merged into the byte stream.
    private fun shizukuBytes(cmd: String): ByteArray? = runCatching {
        val r = drain(shizukuProcess(cmd))
        // Truncation matters as much as a timeout here. `timedOut` is false when the reader
        // stopped because it hit the cap, so gating on it alone handed back a PNG cut off
        // mid-IDAT as if it were a complete image.
        if (r.timedOut || r.truncated) null else r.bytes
    }.getOrNull()

    fun requestShizuku(code: Int) {
        runCatching { Shizuku.requestPermission(code) }
    }
}
