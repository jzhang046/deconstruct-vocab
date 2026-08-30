package com.mettyoung.deconstructchinese.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.speech.tts.TextToSpeech
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin

actual class AudioPlayer actual constructor() {

    private var tts: TextToSpeech? = null
    private var isReady = false

    init {
        val ctx = AppContext.get()
        tts = TextToSpeech(ctx) { status ->
            if (status == TextToSpeech.SUCCESS) {
                isReady = true
            }
        }
    }

    actual fun speak(text: String, language: String) {
        if (!isReady) return
        tts?.setLanguage(Locale.forLanguageTag(language))
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts_${System.currentTimeMillis()}")
    }

    actual fun stop() {
        tts?.stop()
    }

    actual fun playListenCue() {
        // Single low "pop" — soft percussive blip like a voice-assistant /
        // Google Translate mic tap. Low sine with a fast exponential decay.
        Thread {
            runCatching {
                val sampleRate = 44_100
                val freq = 200.0                          // low, soft
                val durMs = 75
                val totalSamples = sampleRate * durMs / 1000
                val attack = 60                           // ~1.4ms click-free attack
                val buf = ShortArray(totalSamples)
                val tail = 200                            // ramp to silence, no end click
                for (n in 0 until totalSamples) {
                    val t = n.toDouble() / sampleRate
                    val attackEnv = if (n < attack) n.toDouble() / attack else 1.0
                    val tailEnv =
                        if (n > totalSamples - tail) (totalSamples - n).toDouble() / tail else 1.0
                    val decayEnv = kotlin.math.exp(-t * 38.0)   // fast pop decay
                    val amp = Short.MAX_VALUE * 0.5 * attackEnv * decayEnv * tailEnv
                    buf[n] = (amp * sin(2.0 * PI * freq * t)).toInt().toShort()
                }
                val track = AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    buf.size * 2,
                    AudioTrack.MODE_STATIC
                )
                track.write(buf, 0, buf.size)
                track.play()
                Thread.sleep((durMs + 60).toLong())
                track.stop()
                track.release()
            }
        }.start()
    }

    actual fun release() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isReady = false
    }
}

// Singleton that holds Android's Context
// needed because AudioPlayer is created from shared code
// which has no access to Android APIs directly
object AppContext {
    private var context: Context? = null

    fun set(ctx: Context) {
        context = ctx.applicationContext
    }

    fun get(): Context = context ?: throw IllegalStateException(
        "AppContext not initialized! Call AppContext.set(context) in MainActivity."
    )
}