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
        val buf = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(16 * 1024)
        var truncated = false
        val deadline = System.currentTimeMillis() + timeoutMs
        var timedOut = false
        try {
            val ins = p.inputStream
            while (true) {
                if (System.currentTimeMillis() > deadline) { timedOut = true; break }
                val r = try { ins.read(chunk) } catch (_: Throwable) { -1 }
                if (r < 0) break
                if (buf.size() + r > cap) {
                    buf.write(chunk, 0, (cap - buf.size()).coerceAtLeast(0))
                    truncated = true
                    break
                }
                buf.write(chunk, 0, r)
            }
            if (!timedOut) {
                val left = (deadline - System.currentTimeMillis()).coerceAtLeast(0)
                if (!p.waitFor(left, java.util.concurrent.TimeUnit.MILLISECONDS)) timedOut = true
            }
        } finally {
            // Truncation and timeout both leave the child alive with a full pipe; kill it rather
            // than leaking a process that can never make progress.
            if (timedOut || truncated) runCatching { p.destroyForcibly() }
        }
        val rc = runCatching { if (timedOut) null else p.exitValue() }.getOrNull()
        return DrainResult(buf.toByteArray(), truncated, timedOut, rc)
    }

    internal data class DrainResult(
        val bytes: ByteArray,
        val truncated: Boolean,
        val timedOut: Boolean,
        val exitCode: Int?,
    ) {
        /** Text plus an explicit note when output was cut short, so a caller is never silently lied to. */
        fun text(): String = String(bytes) + when {
            timedOut -> "\n… (killed after ${EXEC_TIMEOUT_MS / 1000}s — the command did not finish; " +
                "long-running commands like 'logcat' need a bounded form such as 'logcat -d')"
            truncated -> "\n… (truncated at ${MAX_OUTPUT_BYTES / 1024} KB)"
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
        if (r.timedOut) null else r.bytes
    }.getOrNull()

    fun requestShizuku(code: Int) {
        runCatching { Shizuku.requestPermission(code) }
    }
}
