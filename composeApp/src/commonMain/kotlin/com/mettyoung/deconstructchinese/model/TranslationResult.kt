package com.mettyoung.deconstructchinese.model

import kotlinx.serialization.Serializable

@Serializable
enum class Language(val displayName: String, val code: String) {
    ENGLISH("English", "en"),
    CHINESE_TRADITIONAL("Traditional Chinese", "zh-TW"),
    CHINESE_SIMPLIFIED("Simplified Chinese", "zh-CN"),
    MALAY("Bahasa Malaysia", "ms-MY"),
    JAPANESE("Japanese", "ja"),
    KOREAN("Korean", "ko"),
    FRENCH("French", "fr"),
    SPANISH("Spanish", "es"),
    GERMAN("German", "de")
}

@Serializable
data class VocabularyItem(
    val word: String,
    val phonetic: String,
    val meaning: String,
    val frequency: Int = 0,
    val altScript: String? = null,
    val languagePairId: String = LanguagePair.DEFAULT.id
)

@Serializable
data class TranslationResult(
    val originalText: String,
    val translatedText: String,   // English when toEnglish, foreign language otherwise
    val foreignText: String,       // the foreign-language side, script-normalized when applicable
    val phoneticText: String,      // pronunciation guide of foreignText; empty when the pair has none
    val vocabulary: List<VocabularyItem>,
    val grammarNote: String = "",
    val sourceLanguage: Language,
    val targetLanguage: Language
)

sealed class TranslationState {
    data object Idle : TranslationState()
    data object Loading : TranslationState()
    // Stage 1 streams the translation into `result.translatedText` with
    // vocabLoading=true (empty vocabulary); stage 2 replaces it with the full
    // breakdown and vocabLoading=false. If stage 2 fails (e.g. rate limit),
    // vocabError=true and the translation from stage 1 is kept as-is —
    // retryVocabulary() re-attempts just the breakdown.
    data class Success(
        val result: TranslationResult,
        val vocabLoading: Boolean = false,
        val vocabError: Boolean = false
    ) : TranslationState()
    data class Error(val message: String) : TranslationState()
}
