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

    private fun shizukuExec(cmd: String): String = runCatching {
        val p = shizukuProcess(cmd)
        val out = p.inputStream.readBytes(); p.waitFor(); String(out)
    }.getOrElse { "shizuku exec failed: ${it.message}" }

    private fun shizukuBytes(cmd: String): ByteArray? = runCatching {
        val p = shizukuProcess(cmd)
        val b = p.inputStream.readBytes(); p.waitFor(); b
    }.getOrNull()

    fun requestShizuku(code: Int) {
        runCatching { Shizuku.requestPermission(code) }
    }
}
