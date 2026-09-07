package com.sixoffive.androidmcp.server

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import kotlinx.coroutines.delay
import java.io.File

/** Records a short AAC/MP4 mic clip and returns the encoded bytes. */
object AudioCapture {

    @SuppressLint("MissingPermission") // the gate guarantees RECORD_AUDIO before we get here
    suspend fun record(ctx: Context, seconds: Int): ByteArray? {
        val file = File(ctx.cacheDir, "rec_${System.currentTimeMillis()}.m4a")
        @Suppress("DEPRECATION")
        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(ctx) else MediaRecorder()
        try {
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioEncodingBitRate(128_000)
            rec.setAudioSamplingRate(44_100)
            rec.setOutputFile(file.absolutePath)
            rec.prepare()
            rec.start()
            delay(seconds * 1000L)
            rec.stop()
        } catch (t: Throwable) {
            runCatching { rec.reset() }; runCatching { rec.release() }; file.delete()
            return null
        }
        runCatching { rec.release() }
        val bytes = runCatching { file.readBytes() }.getOrNull()
        file.delete()
        return bytes
    }
}
