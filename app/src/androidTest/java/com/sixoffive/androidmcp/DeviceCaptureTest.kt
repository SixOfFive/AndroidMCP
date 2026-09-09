package com.sixoffive.androidmcp

import android.Manifest
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.sixoffive.androidmcp.server.AudioCapture
import com.sixoffive.androidmcp.server.CameraCapture
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Both capture paths, called TWICE. A smoke test — and, importantly, **not** a guard on the
 * camera-release fix, which is what it was originally written to be.
 *
 * ## What was measured, and why the original claim was dropped
 *
 * The v3 roadmap listed this as "`take_photo` twice in a row (guarding the camera-release fix)".
 * `take_photo` used to work exactly once per process: the `CameraDevice` was never closed, so the
 * next `openCamera` blocked until the 8 s timeout and returned null (fixed in 0709889).
 *
 * That claim did not survive checking. Run on the K70 against the **actual pre-fix code**
 * (`git show 0709889^`), this test PASSES — both captures return a JPEG, and the pair completes in
 * ~2.4 s rather than hanging. The audio equivalent passes too with `rec.release()` deleted
 * outright. Neither leak is observable from an instrumented process on this device: the reader and
 * handler thread are still torn down, the leaked object is unreferenced immediately, and the camera
 * service hands the same process a fresh open.
 *
 * The original failure was seen through the long-lived foreground service, which is a different
 * environment from a test process — different lifetime, different GC pressure, a real client
 * driving it. So this is a case of the environment being the bug's precondition, and an
 * instrumented test not being able to recreate it.
 *
 * ## What it therefore does guard
 *
 * That both capture paths return well-formed data on a repeat call, that the permission plumbing
 * works, and that neither hangs. That is worth six seconds, but it is a smoke test: a passing run
 * says nothing about whether the camera or the recorder was released.
 *
 * A second ceiling, unchanged: an instrumented process has foreground importance, so this cannot
 * reproduce the background-restriction failures the tools' own error strings describe.
 *
 * Not wired into `check` — `./gradlew :app:check` must keep working with no device attached.
 *
 *   ./gradlew :app:connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class DeviceCaptureTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
    )

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * Comfortably under `CameraCapture`'s 8 s internal timeout and well over the ~1.2 s a capture
     * actually takes here. This is the assertion that would catch the historical SYMPTOM — a
     * second call that hangs — if it ever does reproduce in this environment. It did not.
     */
    private val hangMs = 5_000L

    @Test
    fun takePhotoTwiceInARow() {
        assumeTrue(
            "no camera on this device",
            ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY),
        )
        val first = runBlocking { CameraCapture.capture(ctx, "back") }
        assertNotNull(first, "the FIRST capture failed — this test says nothing about the second")
        assertTrue(first.isJpeg(), "the first capture returned ${first.size} bytes that are not a JPEG")

        val startedAt = System.nanoTime()
        val second = runBlocking { CameraCapture.capture(ctx, "back") }
        val tookMs = (System.nanoTime() - startedAt) / 1_000_000

        assertNotNull(second, "the second capture returned null")
        assertTrue(second.isJpeg(), "the second capture returned ${second.size} bytes that are not a JPEG")
        assertTrue(tookMs < hangMs, "the second capture took $tookMs ms — it is hanging on the camera")
    }

    @Test
    fun recordAudioTwiceInARow() {
        assumeTrue(
            "no microphone on this device",
            ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE),
        )
        val first = runBlocking { AudioCapture.record(ctx, 1) }
        assertNotNull(first, "the FIRST recording failed — this test says nothing about the second")
        assertTrue(first.isNotEmpty(), "the first recording produced no bytes")

        val second = runBlocking { AudioCapture.record(ctx, 1) }
        assertNotNull(second, "the second recording returned null")
        assertTrue(second.isNotEmpty(), "the second recording produced no bytes")
    }

    /** JPEG SOI marker. A non-null ByteArray that is not an image would still be a failure. */
    private fun ByteArray.isJpeg(): Boolean =
        size > 2 && this[0] == 0xFF.toByte() && this[1] == 0xD8.toByte()
}
