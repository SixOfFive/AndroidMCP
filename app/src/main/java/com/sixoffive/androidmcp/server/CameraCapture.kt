package com.sixoffive.androidmcp.server

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** Headless single-still JPEG capture via Camera2 (no preview surface). */
object CameraCapture {

    @SuppressLint("MissingPermission") // the gate guarantees CAMERA is granted before we get here
    suspend fun capture(ctx: Context, facing: String): ByteArray? {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val camId = pickCamera(cm, facing) ?: return null
        val chars = cm.getCameraCharacteristics(camId)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val size = pickSize(map.getOutputSizes(ImageFormat.JPEG))

        val thread = HandlerThread("androidmcp-cam").apply { start() }
        val handler = Handler(thread.looper)
        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2)
        val result = CompletableDeferred<ByteArray?>()

        reader.setOnImageAvailableListener({ r ->
            val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val buf = img.planes[0].buffer
                val bytes = ByteArray(buf.remaining()); buf.get(bytes)
                if (!result.isCompleted) result.complete(bytes)
            } finally { img.close() }
        }, handler)

        // Hold device/session in atomics: they're assigned on the camera handler thread but
        // released in `finally` on the coroutine thread, and on the timeout path there is no other
        // happens-before. Failing to release the CameraDevice is exactly what makes the NEXT
        // capture block on an already-in-use camera.
        val deviceRef = java.util.concurrent.atomic.AtomicReference<CameraDevice?>()
        val sessionRef = java.util.concurrent.atomic.AtomicReference<CameraCaptureSession?>()
        try {
            cm.openCamera(camId, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    deviceRef.set(d)
                    @Suppress("DEPRECATION")
                    d.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) {
                            sessionRef.set(s)
                            val req = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                addTarget(reader.surface)
                                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                set(CaptureRequest.JPEG_ORIENTATION, chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0)
                            }.build()
                            runCatching { s.capture(req, null, handler) }
                                .onFailure { if (!result.isCompleted) result.complete(null) }
                        }
                        override fun onConfigureFailed(s: CameraCaptureSession) {
                            if (!result.isCompleted) result.complete(null)
                        }
                    }, handler)
                }
                override fun onDisconnected(d: CameraDevice) { deviceRef.set(d); if (!result.isCompleted) result.complete(null) }
                override fun onError(d: CameraDevice, error: Int) { deviceRef.set(d); if (!result.isCompleted) result.complete(null) }
            }, handler)

            return withTimeoutOrNull(8000) { result.await() }
        } catch (t: Throwable) {
            return null
        } finally {
            // Always release, in order: session -> device -> reader -> handler thread.
            runCatching { sessionRef.getAndSet(null)?.close() }
            runCatching { deviceRef.getAndSet(null)?.close() }
            runCatching { reader.close() }
            thread.quitSafely()
        }
    }

    private fun pickCamera(cm: CameraManager, facing: String): String? {
        val want = if (facing.equals("front", true))
            CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        return cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == want
        } ?: cm.cameraIdList.firstOrNull()
    }

    /** Prefer the largest JPEG size with width <= 1600 to keep memory and payloads sane. */
    private fun pickSize(sizes: Array<Size>?): Size {
        if (sizes.isNullOrEmpty()) return Size(1280, 960)
        return sizes.filter { it.width <= 1600 }.maxByOrNull { it.width.toLong() * it.height }
            ?: sizes.minByOrNull { it.width.toLong() * it.height }!!
    }
}
