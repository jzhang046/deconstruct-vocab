package com.mettyoung.deconstructchinese.network

import com.mettyoung.deconstructchinese.model.LanguagePair
import com.mettyoung.deconstructchinese.model.TranslationResult
import kotlinx.coroutines.flow.Flow

interface TranslationService {
    suspend fun translate(
        text: String,
        languagePair: LanguagePair,
        toEnglish: Boolean = false,
        useSimplified: Boolean = true,
        includeGrammarNote: Boolean = true
    ): TranslationResult

    /**
     * Stage 1: stream the translation + whole-sentence pronunciation guide (no
     * per-word vocabulary) for fast first paint. Emits the accumulated state
     * as tokens arrive — `translation` fills first, then `pinyin`.
     */
    fun translateStream(
        text: String,
        languagePair: LanguagePair,
        toEnglish: Boolean = false,
        useSimplified: Boolean = true
    ): Flow<PartialTranslation>
}

/** Streamed stage-1 state: translation grows first, then pinyin. */
data class PartialTranslation(
    val translation: String,
    val pinyin: String = ""
)
