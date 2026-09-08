package com.sixoffive.androidmcp.core

/**
 * Root (Magisk `su`) detection and execution. On a non-rooted device `su` isn't found,
 * so isAvailable() is false and root capabilities gate to NOT_SUPPORTED_WITHOUT_ROOT.
 * The first successful su call triggers Magisk's one-time superuser grant for the app.
 */
object Root {
    @Volatile private var cached: Boolean? = null

    fun isAvailable(): Boolean {
        cached?.let { return it }
        val ok = runCatching {
            val p = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
            // Bounded: on a device where Magisk prompts and the user never answers, an unbounded
            // wait here would hang the very first elevated call forever.
            Elevated.drain(p, timeoutMs = 10_000L).text().contains("uid=0")
        }.getOrDefault(false)
        cached = ok
        return ok
    }

    /** Run a command as root, returning combined stdout/stderr (or an error string). */
    fun exec(cmd: String): String = runCatching {
        // redirectErrorStream merges stderr into stdout, so one drain cannot deadlock on the other.
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        Elevated.drain(p).text()
    }.getOrElse { "root exec failed: ${it.message}" }

    /** Run a command as root, returning raw stdout bytes (e.g. for `screencap -p`). */
    fun execBytes(cmd: String): ByteArray? = runCatching {
        // Binary output: stderr stays separate so it cannot corrupt the PNG/JPEG bytes.
        val p = ProcessBuilder("su", "-c", cmd).start()
        val r = Elevated.drain(p)
        if (r.timedOut) null else r.bytes
    }.getOrNull()
}
