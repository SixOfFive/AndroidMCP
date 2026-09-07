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

        var device: CameraDevice? = null
        try {
            cm.openCamera(camId, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    device = d
                    @Suppress("DEPRECATION")
                    d.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            val req = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                addTarget(reader.surface)
                                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                set(CaptureRequest.JPEG_ORIENTATION, chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0)
                            }.build()
                            session.capture(req, null, handler)
                        }
                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            if (!result.isCompleted) result.complete(null)
                        }
                    }, handler)
                }
                override fun onDisconnected(d: CameraDevice) { d.close(); if (!result.isCompleted) result.complete(null) }
                override fun onError(d: CameraDevice, error: Int) { d.close(); if (!result.isCompleted) result.complete(null) }
            }, handler)
        } catch (t: Throwable) {
            if (!result.isCompleted) result.complete(null)
        }

        val bytes = withTimeoutOrNull(8000) { result.await() }
        runCatching { device?.close() }
        runCatching { reader.close() }
        thread.quitSafely()
        return bytes
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
