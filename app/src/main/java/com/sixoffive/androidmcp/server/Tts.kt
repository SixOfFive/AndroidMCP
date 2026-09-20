package com.sixoffive.androidmcp.server

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * One-shot text-to-speech. Speaks [text] through the default TTS engine and blocks until the
 * utterance finishes (or [timeoutMs] elapses), then tears the engine down.
 *
 * A fresh engine per call keeps this stateless — no long-lived TextToSpeech to leak or to keep
 * an audio-focus grab alive between calls. Two latches serialise the two async hops the API
 * forces on us: engine init (its OnInitListener fires on the main thread) and utterance
 * completion (via UtteranceProgressListener). Called from Dispatchers.IO, so blocking is fine.
 */
object Tts {

    /** null on success; otherwise a short reason suitable for a ToolExecError message. */
    fun speak(ctx: Context, text: String, timeoutMs: Long = 20_000): String? {
        val initLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(1)
        var initStatus = TextToSpeech.ERROR
        var tts: TextToSpeech? = null
        tts = TextToSpeech(ctx.applicationContext) { status ->
            initStatus = status
            initLatch.countDown()
        }
        try {
            if (!initLatch.await(8, TimeUnit.SECONDS)) return "the text-to-speech engine did not initialise in time"
            if (initStatus != TextToSpeech.SUCCESS) return "no usable text-to-speech engine is installed on this device"
            val engine = tts ?: return "text-to-speech engine unavailable"
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { doneLatch.countDown() }
                @Deprecated("kept for API < 21 source compatibility", ReplaceWith(""))
                override fun onError(utteranceId: String?) { doneLatch.countDown() }
                override fun onError(utteranceId: String?, errorCode: Int) { doneLatch.countDown() }
            })
            val res = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "androidmcp-speak")
            if (res != TextToSpeech.SUCCESS) return "the text-to-speech engine refused the utterance"
            doneLatch.await(timeoutMs, TimeUnit.MILLISECONDS)
            return null
        } finally {
            runCatching { tts?.stop() }
            runCatching { tts?.shutdown() }
        }
    }
}
