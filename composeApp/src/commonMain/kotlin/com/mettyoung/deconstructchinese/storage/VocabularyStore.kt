package com.mettyoung.deconstructchinese.storage

import com.mettyoung.deconstructchinese.model.VocabularyItem
import com.russhwolf.settings.Settings
import com.russhwolf.settings.serialization.decodeValueOrNull
import com.russhwolf.settings.serialization.encodeValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.ExperimentalSerializationApi

@OptIn(ExperimentalSerializationApi::class)
object VocabularyStore {
    private val settings: Settings = Settings()
    private const val KEY_VOCABULARY = "saved_vocabulary"

    private val _savedVocabulary = MutableStateFlow<List<VocabularyItem>>(loadVocabulary())
    val savedVocabulary: StateFlow<List<VocabularyItem>> = _savedVocabulary.asStateFlow()

    private fun loadVocabulary(): List<VocabularyItem> {
        return (settings.decodeValueOrNull<List<VocabularyItem>>(KEY_VOCABULARY) ?: emptyList())
            .sortedByDescending { it.frequency }
    }

    private fun saveToSettings(list: List<VocabularyItem>) {
        settings.encodeValue(KEY_VOCABULARY, list)
    }

    private fun persist(list: List<VocabularyItem>) {
        val sorted = list.sortedByDescending { it.frequency }
        _savedVocabulary.value = sorted
        saveToSettings(sorted)
    }

    private fun matches(a: VocabularyItem, b: VocabularyItem) =
        a.word == b.word && a.languagePairId == b.languagePairId

    fun saveWord(item: VocabularyItem) {
        val currentList = _savedVocabulary.value
        if (currentList.none { matches(it, item) }) {
            persist(currentList + item.copy(frequency = 0))
        }
    }

    fun removeWord(item: VocabularyItem) {
        persist(_savedVocabulary.value.filterNot { matches(it, item) })
    }

    fun bumpFrequency(item: VocabularyItem) {
        val currentList = _savedVocabulary.value
        if (currentList.none { matches(it, item) }) return
        persist(currentList.map {
            if (matches(it, item))
                it.copy(frequency = it.frequency + 1, altScript = item.altScript ?: it.altScript)
            else it
        })
    }

    fun isSaved(word: String, languagePairId: String): Boolean {
        return _savedVocabulary.value.any { it.word == word && it.languagePairId == languagePairId }
    }
}
