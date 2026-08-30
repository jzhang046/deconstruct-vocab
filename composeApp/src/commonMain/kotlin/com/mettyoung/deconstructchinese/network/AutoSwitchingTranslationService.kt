package com.mettyoung.deconstructchinese.network

import com.mettyoung.deconstructchinese.model.TranslationResult
import com.mettyoung.deconstructchinese.storage.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Tries each configured candidate in order, falling through to the next only
 * when a call throws (auth/rate-limit/network failure) — not merely because
 * a result looked empty. The candidate that last succeeded is tried first on
 * the next call, so a dead or rate-limited provider isn't retried on every
 * keystroke — it's only retried once the sticky one fails again.
 *
 * Candidates with a blank API key are skipped. If none have a key, calls
 * throw immediately so the existing ViewModel error handling surfaces a
 * normal "translation failed" message instead of a construction-time crash.
 */
class AutoSwitchingTranslationService(
    private val candidates: List<Candidate>
) : TranslationService {

    data class Candidate(val label: String, val apiKey: String, val service: TranslationService)

    private var lastGoodLabel: String? = null

    override suspend fun translate(
        text: String,
        toEnglish: Boolean,
        useSimplified: Boolean,
        includeGrammarNote: Boolean
    ): TranslationResult = attempt { it.translate(text, toEnglish, useSimplified, includeGrammarNote) }

    override fun translateStream(
        text: String,
        toEnglish: Boolean,
        useSimplified: Boolean
    ): Flow<PartialTranslation> = flow {
        var lastError: Exception? = null
        for (candidate in orderedCandidates()) {
            try {
                candidate.service.translateStream(text, toEnglish, useSimplified).collect { emit(it) }
                lastGoodLabel = candidate.label
                return@flow
            } catch (e: Exception) {
                lastError = e
                println("[AutoSwitch] ${candidate.label} stream failed, trying next: ${e.message}")
            }
        }
        throw lastError ?: noProviderConfigured()
    }

    private suspend fun <T> attempt(call: suspend (TranslationService) -> T): T {
        var lastError: Exception? = null
        for (candidate in orderedCandidates()) {
            try {
                val result = call(candidate.service)
                lastGoodLabel = candidate.label
                return result
            } catch (e: Exception) {
                lastError = e
                println("[AutoSwitch] ${candidate.label} failed, trying next: ${e.message}")
            }
        }
        throw lastError ?: noProviderConfigured()
    }

    private fun orderedCandidates(): List<Candidate> {
        val configured = candidates.filter { it.apiKey.isNotBlank() }
        val sticky = configured.firstOrNull { it.label == lastGoodLabel }
        return listOfNotNull(sticky) + configured.filter { it.label != lastGoodLabel }
    }

    private fun noProviderConfigured() =
        IllegalStateException("No translation provider is configured. Add a Qwen or Gemini API key.")
}

/**
 * The service used everywhere in the app: Qwen (the bundled, developer-funded
 * key) tried first, then Gemini (your own free-tier key, if you've set one).
 * Either key may be blank — a blank one is simply skipped.
 *
 * This is the only place "Qwen"/"Gemini" mean anything as providers —
 * AppSettings/Secrets below just hold keys by opaque provider id.
 */
fun createTranslationService(): TranslationService = AutoSwitchingTranslationService(
    listOf(
        AutoSwitchingTranslationService.Candidate("Qwen", AppSettings.apiKey("qwen"), QwenService(AppSettings.apiKey("qwen"))),
        AutoSwitchingTranslationService.Candidate("Gemini", AppSettings.apiKey("gemini"), GeminiService(AppSettings.apiKey("gemini")))
    )
)
