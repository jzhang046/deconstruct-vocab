package com.mettyoung.deconstructchinese.audio

import platform.AVFAudio.AVSpeechSynthesizer
import platform.AVFAudio.AVSpeechUtterance
import platform.AVFAudio.AVSpeechSynthesisVoice
import platform.AVFAudio.AVSpeechBoundary
import platform.AudioToolbox.AudioServicesPlaySystemSound

actual class AudioPlayer actual constructor() {

    private val synthesizer = AVSpeechSynthesizer()

    actual fun speak(text: String, language: String) {
        synthesizer.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
        val utterance = AVSpeechUtterance(string = text)
        // Falls back to the system default voice if none exists for `language`.
        AVSpeechSynthesisVoice.voiceWithLanguage(language)?.let { utterance.voice = it }
        utterance.rate = 0.45f
        synthesizer.speakUtterance(utterance)
    }

    actual fun stop() {
        synthesizer.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
    }

    actual fun playListenCue() {
        // 1113 = the system "begin record" tone.
        AudioServicesPlaySystemSound(1113u)
    }

    actual fun release() {
        stop()
    }
}