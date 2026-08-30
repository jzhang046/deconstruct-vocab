package com.mettyoung.deconstructchinese.network

import com.mettyoung.deconstructchinese.config.defaultApiKeys
import com.mettyoung.deconstructchinese.model.LanguagePair
import com.mettyoung.deconstructchinese.model.TranslationProvider
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
        languagePair: LanguagePair,
        toEnglish: Boolean,
        useSimplified: Boolean,
        includeGrammarNote: Boolean
    ): TranslationResult = attempt { it.translate(text, languagePair, toEnglish, useSimplified, includeGrammarNote) }

    override fun translateStream(
        text: String,
        languagePair: LanguagePair,
        toEnglish: Boolean,
        useSimplified: Boolean
    ): Flow<PartialTranslation> = flow {
        var lastError: Exception? = null
        for (candidate in orderedCandidates()) {
            try {
                candidate.service.translateStream(text, languagePair, toEnglish, useSimplified).collect { emit(it) }
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
 * The service used everywhere in the app.
 *
 * `AppSettings.selectedProvider == null` ("Auto" in the settings UI): tries
 * every provider, Qwen first, falling back to Gemini — using the bundled
 * default key and default model for each, ignoring any per-provider
 * key/model override the user may have entered while a specific provider was
 * selected. (Auto's exact behavior is still being decided; for now it's
 * simply "use the built-in defaults.")
 *
 * `selectedProvider` set to a specific provider: uses only that provider,
 * with its saved API key override (falling back to the bundled key if blank)
 * and saved model override (falling back to `provider.defaultModel`) — no
 * fallback to the other provider, since the user explicitly chose this one.
 *
 * This is the only place "Qwen"/"Gemini" mean anything as concrete services —
 * AppSettings/Secrets below just hold keys/models by opaque provider id, and
 * [TranslationProvider] is just an id+label+models tuple for the UI.
 */
fun createTranslationService(): TranslationService {
    fun buildService(provider: TranslationProvider, apiKey: String, model: String): TranslationService =
        when (provider) {
            TranslationProvider.QWEN -> QwenService(apiKey, model)
            TranslationProvider.GEMINI -> GeminiService(apiKey, model)
        }

    val selected = AppSettings.selectedProvider
    val candidates = if (selected != null) {
        val apiKey = AppSettings.apiKey(selected.id)
        val model = AppSettings.model(selected.id).ifBlank { selected.defaultModel }
        listOf(AutoSwitchingTranslationService.Candidate(selected.label, apiKey, buildService(selected, apiKey, model)))
    } else {
        TranslationProvider.entries.map { provider ->
            val apiKey = defaultApiKeys[provider.id].orEmpty()
            AutoSwitchingTranslationService.Candidate(provider.label, apiKey, buildService(provider, apiKey, provider.defaultModel))
        }
    }
    return AutoSwitchingTranslationService(candidates)
}
