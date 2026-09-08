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
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            out.contains("uid=0")
        }.getOrDefault(false)
        cached = ok
        return ok
    }

    /** Run a command as root, returning combined stdout/stderr (or an error string). */
    fun exec(cmd: String): String = runCatching {
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        val out = p.inputStream.readBytes()
        p.waitFor()
        String(out)
    }.getOrElse { "root exec failed: ${it.message}" }

    /** Run a command as root, returning raw stdout bytes (e.g. for `screencap -p`). */
    fun execBytes(cmd: String): ByteArray? = runCatching {
        val p = ProcessBuilder("su", "-c", cmd).start()
        val bytes = p.inputStream.readBytes()
        p.waitFor()
        bytes
    }.getOrNull()
}
