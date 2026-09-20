package com.sixoffive.androidmcp.server

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import kotlinx.coroutines.delay
import java.io.File

/**
 * Records the live MediaProjection to a short MP4 and returns the bytes. Video only — no audio,
 * which would need the separate AudioPlaybackCapture consent. Reuses the same [ProjectionHolder]
 * consent that [ScreenCapture] uses, so it needs no permission of its own.
 */
object ScreenRecorder {

    /** Record for [durationSec] seconds. Returns the MP4 bytes, or null if no projection is active. */
    suspend fun record(ctx: Context, durationSec: Int): ByteArray? {
        val proj = ProjectionHolder.projection ?: return null
        val dm = ctx.resources.displayMetrics
        val dpi = dm.densityDpi
        // Cap the longer side to 1280 to bound file size and stay within common encoder limits,
        // preserving aspect ratio; H264 wants even dimensions.
        val scale = minOf(1.0, 1280.0 / maxOf(dm.widthPixels, dm.heightPixels))
        val w = ((dm.widthPixels * scale).toInt()) and 0xFFFFFFFE.toInt()
        val h = ((dm.heightPixels * scale).toInt()) and 0xFFFFFFFE.toInt()

        val file = File(ctx.cacheDir, "rec-${System.currentTimeMillis()}.mp4")
        val thread = HandlerThread("androidmcp-rec").apply { start() }
        val handler = Handler(thread.looper)

        @Suppress("DEPRECATION")
        val recorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else MediaRecorder()
        var vd: android.hardware.display.VirtualDisplay? = null
        var started = false
        try {
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            recorder.setVideoSize(w, h)
            recorder.setVideoFrameRate(30)
            recorder.setVideoEncodingBitRate(6_000_000)
            recorder.setOutputFile(file.absolutePath)
            recorder.prepare()

            // A no-op callback is required before creating a display on newer versions.
            runCatching { proj.registerCallback(object : android.media.projection.MediaProjection.Callback() {}, handler) }
            vd = proj.createVirtualDisplay(
                "androidmcp-rec", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                recorder.surface, null, handler,
            ) ?: return null

            recorder.start()
            started = true
            delay(durationSec.coerceIn(1, 30) * 1000L)
        } catch (t: Throwable) {
            return null
        } finally {
            if (started) runCatching { recorder.stop() }
            runCatching { recorder.reset() }
            runCatching { recorder.release() }
            runCatching { vd?.release() }
            thread.quitSafely()
        }

        return runCatching { file.readBytes() }.getOrNull().also { runCatching { file.delete() } }
    }
}
