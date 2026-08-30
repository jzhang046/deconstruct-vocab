package com.mettyoung.deconstructchinese.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mettyoung.deconstructchinese.audio.AudioPlayer
import com.mettyoung.deconstructchinese.model.Language
import com.mettyoung.deconstructchinese.model.LanguagePair
import com.mettyoung.deconstructchinese.model.RecordingPhase
import com.mettyoung.deconstructchinese.model.TranslationProvider
import com.mettyoung.deconstructchinese.model.TranslationResult
import com.mettyoung.deconstructchinese.model.TranslationState
import com.mettyoung.deconstructchinese.model.VocabularyItem
import com.mettyoung.deconstructchinese.network.TranslationService
import com.mettyoung.deconstructchinese.network.createTranslationService
import com.mettyoung.deconstructchinese.speech.SpeechRecognizer
import com.mettyoung.deconstructchinese.speech.SpeechResult
import com.mettyoung.deconstructchinese.storage.AppSettings
import com.mettyoung.deconstructchinese.storage.VocabularyStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TranslatorViewModel(
    private var translationService: TranslationService
) : ViewModel() {

    private val audioPlayer = AudioPlayer()
    private val speechRecognizer = SpeechRecognizer()

    private val _translationState =
        MutableStateFlow<TranslationState>(TranslationState.Idle)
    val translationState: StateFlow<TranslationState> =
        _translationState.asStateFlow()

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _recordingPhase = MutableStateFlow(RecordingPhase.Idle)
    val recordingPhase: StateFlow<RecordingPhase> = _recordingPhase.asStateFlow()


    private val _snackbarMessage = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val snackbarMessage: SharedFlow<String> = _snackbarMessage.asSharedFlow()

    private val _languagePair = MutableStateFlow(AppSettings.languagePair)
    val languagePair: StateFlow<LanguagePair> = _languagePair.asStateFlow()

    // Vocab is tagged by the pair it was saved under, so switching pairs never
    // loses saved words — it just changes which ones are currently shown.
    val savedVocabulary: StateFlow<List<VocabularyItem>> = combine(
        VocabularyStore.savedVocabulary, _languagePair
    ) { list, pair -> list.filter { it.languagePairId == pair.id } }
        .stateIn(
            viewModelScope,
            kotlinx.coroutines.flow.SharingStarted.Eagerly,
            VocabularyStore.savedVocabulary.value.filter { it.languagePairId == _languagePair.value.id }
        )

    private val _toEnglish = MutableStateFlow(false)
    val toEnglish: StateFlow<Boolean> = _toEnglish.asStateFlow()

    private val _useSimplified = MutableStateFlow(AppSettings.useSimplified)
    val useSimplified: StateFlow<Boolean> = _useSimplified.asStateFlow()

    private val _selectedProvider = MutableStateFlow(AppSettings.selectedProvider)
    val selectedProvider: StateFlow<TranslationProvider?> = _selectedProvider.asStateFlow()

    // Raw user-entered override per provider (blank = using the bundled default key/model).
    private val _apiKeyOverrides = MutableStateFlow(
        TranslationProvider.entries.associateWith { AppSettings.apiKeyOverride(it.id) }
    )
    val apiKeyOverrides: StateFlow<Map<TranslationProvider, String>> = _apiKeyOverrides.asStateFlow()

    private val _modelOverrides = MutableStateFlow(
        TranslationProvider.entries.associateWith { AppSettings.model(it.id) }
    )
    val modelOverrides: StateFlow<Map<TranslationProvider, String>> = _modelOverrides.asStateFlow()

    private var translateJob: Job? = null

    init {
        viewModelScope.launch {
            speechRecognizer.results.collect { result ->
                when (result) {
                    is SpeechResult.Ready -> {
                        if (_recordingPhase.value != RecordingPhase.Idle) {
                            _recordingPhase.value = RecordingPhase.Armed
                        }
                    }
                    is SpeechResult.SpeechStarted -> {
                        if (_recordingPhase.value != RecordingPhase.Idle) {
                            _recordingPhase.value = RecordingPhase.Listening
                        }
                    }
                    is SpeechResult.Partial -> {
                        // Stream recognized words live without scheduling a translate.
                        if (_recordingPhase.value != RecordingPhase.Idle) {
                            _inputText.value = result.text
                        }
                    }
                    is SpeechResult.Final -> {
                        _recordingPhase.value = RecordingPhase.Idle
                        onInputTextChange(result.text)
                    }
                    is SpeechResult.Cancelled -> {
                        _recordingPhase.value = RecordingPhase.Idle
                    }
                    is SpeechResult.Error -> {
                        _recordingPhase.value = RecordingPhase.Idle
                        _snackbarMessage.tryEmit(result.message)
                    }
                }
            }
        }
    }

    fun onSharedText(text: String) {
        // Auto-detect direction from script only when the pair has one that's
        // distinguishable from English (Han characters). Latin-script foreign
        // languages (e.g. Malay) can't be told apart from English this way.
        if (_languagePair.value.hasScriptVariants) {
            val hasHan = text.any { it.code in 0x4E00..0x9FFF }
            _toEnglish.value = hasHan
        }
        onInputTextChange(text)
    }

    fun setLanguagePair(pair: LanguagePair) {
        if (_languagePair.value == pair) return
        translateJob?.cancel()
        stopAudio()
        speechRecognizer.stopListening()
        _recordingPhase.value = RecordingPhase.Idle
        AppSettings.languagePair = pair
        _languagePair.value = pair
        _inputText.value = ""
        _toEnglish.value = false
        _translationState.value = TranslationState.Idle
    }

    fun onInputTextChange(newText: String) {
        _inputText.value = newText
        if (_translationState.value is TranslationState.Success) {
            _translationState.value = TranslationState.Idle
        }
        // No auto-translate on typing — each call costs real API tokens, and
        // firing one on every debounce pause wastes them on incomplete
        // sentences. Translation now only happens via the explicit Translate
        // button / translate() call.
        translateJob?.cancel()
    }

    fun swapDirection() {
        translateJob?.cancel()
        _toEnglish.value = !_toEnglish.value
        _inputText.value = ""
        _translationState.value = TranslationState.Idle
        stopAudio()
    }

    fun translate() {
        val text = _inputText.value.trim()
        if (text.isEmpty()) return

        translateJob?.cancel()
        translateJob = viewModelScope.launch {
            _translationState.value = TranslationState.Loading
            val toEng = _toEnglish.value
            val simp = _useSimplified.value
            val pair = _languagePair.value
            val foreignLang = foreignLanguage(pair, simp)

            try {
                // Stage 1: stream translation + sentence pronunciation guide for fast first paint.
                translationService.translateStream(text, pair, toEng, simp).collect { partial ->
                    _translationState.value = TranslationState.Success(
                        result = partialResult(text, partial.translation, partial.pinyin, toEng, foreignLang),
                        vocabLoading = true
                    )
                }

                // Stage 2: full breakdown (vocabulary, pronunciation) replaces the partial.
                val full = fetchVocabularyBreakdown(text, pair, toEng, simp)
                _translationState.value = TranslationState.Success(full, vocabLoading = false)
            } catch (e: Exception) {
                // Keep a streamed translation if we already have one — only the
                // breakdown failed, offer a retry. Otherwise surface the error.
                val current = _translationState.value
                if (current is TranslationState.Success) {
                    _translationState.value = current.copy(vocabLoading = false, vocabError = true)
                } else {
                    _translationState.value = TranslationState.Error(mapError(e))
                }
            }
        }
    }

    /** Re-attempts just the stage-2 vocabulary breakdown after it previously failed. */
    fun retryVocabulary() {
        val current = _translationState.value
        if (current !is TranslationState.Success) return
        val text = _inputText.value.trim()
        if (text.isEmpty()) return

        translateJob?.cancel()
        translateJob = viewModelScope.launch {
            _translationState.value = current.copy(vocabLoading = true, vocabError = false)
            val pair = _languagePair.value
            try {
                val full = fetchVocabularyBreakdown(text, pair, _toEnglish.value, _useSimplified.value)
                _translationState.value = TranslationState.Success(full, vocabLoading = false)
            } catch (e: Exception) {
                val stillSuccess = _translationState.value
                if (stillSuccess is TranslationState.Success) {
                    _translationState.value = stillSuccess.copy(vocabLoading = false, vocabError = true)
                }
            }
        }
    }

    private suspend fun fetchVocabularyBreakdown(
        text: String,
        pair: LanguagePair,
        toEng: Boolean,
        simp: Boolean
    ): TranslationResult {
        val full = translationService.translate(text, pair, toEng, simp)
        full.vocabulary.forEach { item ->
            if (VocabularyStore.isSaved(item.word, pair.id)) {
                VocabularyStore.bumpFrequency(item)
            }
        }
        return full
    }

    private fun partialResult(
        original: String,
        translated: String,
        phonetic: String,
        toEnglish: Boolean,
        foreignLang: Language
    ) = TranslationResult(
        originalText = original,
        translatedText = translated,
        foreignText = if (toEnglish) original else translated,
        phoneticText = phonetic,
        vocabulary = emptyList(),
        grammarNote = "",
        sourceLanguage = if (toEnglish) foreignLang else Language.ENGLISH,
        targetLanguage = if (toEnglish) Language.ENGLISH else foreignLang
    )

    private fun foreignLanguage(pair: LanguagePair, useSimplified: Boolean): Language =
        if (pair.hasScriptVariants) {
            if (useSimplified) Language.CHINESE_SIMPLIFIED else Language.CHINESE_TRADITIONAL
        } else pair.language

    /** TTS/speech locale for the foreign side, honoring the script toggle for Chinese. */
    private fun foreignLocale(pair: LanguagePair, useSimplified: Boolean): String =
        if (pair.hasScriptVariants) {
            if (useSimplified) "zh-CN" else "zh-TW"
        } else pair.speechLocale

    private fun mapError(e: Exception): String = when {
        e.message?.contains("401") == true ->
            "Invalid API key. Please check your API key."
        e.message?.contains("429") == true ->
            "Rate limit reached. Wait a moment and try again."
        e.message?.contains("connect") == true ->
            "Network error. Check your internet connection."
        else -> "Translation failed: ${e.message}"
    }

    fun startRecording() {
        translateJob?.cancel()
        _inputText.value = ""
        _translationState.value = TranslationState.Idle
        _recordingPhase.value = RecordingPhase.Armed
        // Cue the user the moment "Speak now" shows (phase-driven UI flips here).
        audioPlayer.playListenCue()
        // Source side of the toggle: toEnglish == translating the foreign language -> English.
        val locale = if (_toEnglish.value) {
            foreignLocale(_languagePair.value, _useSimplified.value)
        } else {
            "en-US"
        }
        speechRecognizer.startListening(locale)
    }

    fun stopRecording() {
        if (_recordingPhase.value != RecordingPhase.Idle) {
            _recordingPhase.value = RecordingPhase.Idle
        }
        speechRecognizer.stopListening()
    }

    fun speakTranslation() {
        val state = _translationState.value
        if (state is TranslationState.Success) {
            _isPlaying.value = true
            audioPlayer.speak(state.result.foreignText, foreignLocale(_languagePair.value, _useSimplified.value))
            viewModelScope.launch {
                delay(3000)
                _isPlaying.value = false
            }
        }
    }

    fun speakWord(word: String) {
        audioPlayer.speak(word, foreignLocale(_languagePair.value, _useSimplified.value))
    }

    fun stopAudio() {
        audioPlayer.stop()
        _isPlaying.value = false
    }

    fun clearAll() {
        translateJob?.cancel()
        stopAudio()
        _inputText.value = ""
        _translationState.value = TranslationState.Idle
    }

    fun saveWord(item: VocabularyItem) {
        VocabularyStore.saveWord(item)
    }

    fun removeWord(item: VocabularyItem) {
        VocabularyStore.removeWord(item)
    }

    fun isSaved(word: String): Boolean {
        return VocabularyStore.isSaved(word, _languagePair.value.id)
    }

    fun setUseSimplified(value: Boolean) {
        AppSettings.useSimplified = value
        _useSimplified.value = value
        _translationState.value = TranslationState.Idle
    }

    fun setSelectedProvider(provider: TranslationProvider?) {
        AppSettings.selectedProvider = provider
        _selectedProvider.value = provider
        translationService = createTranslationService()
    }

    fun setApiKeyOverride(provider: TranslationProvider, value: String) {
        AppSettings.setApiKey(provider.id, value)
        _apiKeyOverrides.value = _apiKeyOverrides.value + (provider to value)
        translationService = createTranslationService()
    }

    fun setModelOverride(provider: TranslationProvider, value: String) {
        AppSettings.setModel(provider.id, value)
        _modelOverrides.value = _modelOverrides.value + (provider to value)
        translationService = createTranslationService()
    }

    override fun onCleared() {
        super.onCleared()
        audioPlayer.release()
        speechRecognizer.release()
    }
}
