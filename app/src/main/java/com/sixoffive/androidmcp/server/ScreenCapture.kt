package com.sixoffive.androidmcp.server

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream

/** Captures one frame from the live MediaProjection and returns a JPEG. */
object ScreenCapture {

    suspend fun capture(ctx: Context): ByteArray? {
        val proj = ProjectionHolder.projection ?: return null
        val dm = ctx.resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        val dpi = dm.densityDpi

        val thread = HandlerThread("androidmcp-scr").apply { start() }
        val handler = Handler(thread.looper)
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        val result = CompletableDeferred<ByteArray?>()

        reader.setOnImageAvailableListener({ r ->
            val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val plane = img.planes[0]
                val rowStride = plane.rowStride
                val pixelStride = plane.pixelStride
                val padded = w + (rowStride - pixelStride * w) / pixelStride
                val bmp = Bitmap.createBitmap(padded, h, Bitmap.Config.ARGB_8888)
                bmp.copyPixelsFromBuffer(plane.buffer)
                val cropped = if (padded != w) Bitmap.createBitmap(bmp, 0, 0, w, h) else bmp
                val bos = ByteArrayOutputStream()
                cropped.compress(Bitmap.CompressFormat.JPEG, 85, bos)
                if (!result.isCompleted) result.complete(bos.toByteArray())
            } catch (t: Throwable) {
                if (!result.isCompleted) result.complete(null)
            } finally { img.close() }
        }, handler)

        // no-op callback (required on newer versions before creating a display)
        runCatching { proj.registerCallback(object : android.media.projection.MediaProjection.Callback() {}, handler) }

        val vd = runCatching {
            proj.createVirtualDisplay(
                "androidmcp-cap", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, handler,
            )
        }.getOrNull()
        if (vd == null) {
            reader.close(); thread.quitSafely(); return null
        }

        val bytes = withTimeoutOrNull(4000) { result.await() }
        runCatching { vd.release() }
        runCatching { reader.close() }
        thread.quitSafely()
        return bytes
    }
}
