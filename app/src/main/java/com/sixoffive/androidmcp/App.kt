package com.sixoffive.androidmcp

import android.app.Application
import com.sixoffive.androidmcp.core.ConfigStore
import com.sixoffive.androidmcp.core.TokenStore
import com.sixoffive.androidmcp.server.McpService

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        ConfigStore.init(this)
        TokenStore.init(this)
        McpService.ensureChannel(this)
        purgeLegacyCaptures()
    }

    /**
     * Delete captures left behind by earlier builds.
     *
     * Every `take_photo` / `record_audio` / `capture_screenshot` used to also write the bytes to
     * `filesDir/{photos,audio,screens}/last.*`, which nothing ever read. The last camera frame and
     * mic clip therefore outlived turning the capability off and revoking the OS permission. The
     * writes are gone; this clears what existing installs already have on disk.
     */
    private fun purgeLegacyCaptures() {
        runCatching {
            listOf("photos", "audio", "screens").forEach { java.io.File(filesDir, it).deleteRecursively() }
        }
    }
}
