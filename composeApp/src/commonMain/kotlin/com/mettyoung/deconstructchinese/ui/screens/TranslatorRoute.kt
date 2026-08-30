package com.mettyoung.deconstructchinese.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mettyoung.deconstructchinese.IncomingText
import com.mettyoung.deconstructchinese.network.createTranslationService
import com.mettyoung.deconstructchinese.storage.AppSettings
import com.mettyoung.deconstructchinese.ui.components.SettingsDialog
import com.mettyoung.deconstructchinese.ui.theme.Background
import com.mettyoung.deconstructchinese.ui.theme.BluePrimary
import com.mettyoung.deconstructchinese.ui.theme.GoldAccent
import com.mettyoung.deconstructchinese.ui.theme.Surface
import com.mettyoung.deconstructchinese.ui.theme.TextSecondary
import com.mettyoung.deconstructchinese.viewmodel.TranslatorViewModel

@Composable
fun TranslatorRoute() {
    val viewModel: TranslatorViewModel = viewModel(factory = viewModelFactory {
        initializer { TranslatorViewModel(createTranslationService()) }
    })

    val inputText by viewModel.inputText.collectAsState()
    val translationState by viewModel.translationState.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val savedVocab by viewModel.savedVocabulary.collectAsState()
    val toEnglish by viewModel.toEnglish.collectAsState()
    val useSimplified by viewModel.useSimplified.collectAsState()
    val recordingPhase by viewModel.recordingPhase.collectAsState()
    val languagePair by viewModel.languagePair.collectAsState()
    val selectedProvider by viewModel.selectedProvider.collectAsState()
    val apiKeyOverrides by viewModel.apiKeyOverrides.collectAsState()
    val modelOverrides by viewModel.modelOverrides.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.snackbarMessage.collect { snackbarHostState.showSnackbar(it) }
    }
    LaunchedEffect(Unit) {
        IncomingText.texts.collect { viewModel.onSharedText(it) }
    }


    var showSettings by remember { mutableStateOf(false) }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var needsLanguagePairChoice by remember { mutableStateOf(!AppSettings.hasSelectedLanguagePair) }

    if (needsLanguagePairChoice) {
        LanguagePairPickerScreen(onSelect = {
            viewModel.setLanguagePair(it)
            needsLanguagePairChoice = false
        })
        return
    }

    if (showSettings) {
        SettingsDialog(
            languagePair = languagePair,
            onLanguagePairChange = { viewModel.setLanguagePair(it) },
            useSimplified = useSimplified,
            onUseSimplifiedChange = { viewModel.setUseSimplified(it) },
            selectedProvider = selectedProvider,
            onSelectedProviderChange = { viewModel.setSelectedProvider(it) },
            apiKeyOverrides = apiKeyOverrides,
            onApiKeyChange = { provider, value -> viewModel.setApiKeyOverride(provider, value) },
            modelOverrides = modelOverrides,
            onModelChange = { provider, value -> viewModel.setModelOverride(provider, value) },
            onDismiss = { showSettings = false }
        )
    }

    Scaffold(
        containerColor = Background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            BottomNav(
                selectedTab = selectedTab,
                savedCount = savedVocab.size,
                onSelectTab = { selectedTab = it }
            )
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            when (selectedTab) {
                0 -> TranslateScreen(
                    inputText = inputText,
                    translationState = translationState,
                    languagePair = languagePair,
                    toEnglish = toEnglish,
                    isPlaying = isPlaying,
                    recordingPhase = recordingPhase,
                    savedVocab = savedVocab,
                    useSimplified = useSimplified,
                    onInputChange = viewModel::onInputTextChange,
                    onClear = viewModel::clearAll,
                    onTranslate = viewModel::translate,
                    onSwapDirection = viewModel::swapDirection,
                    onSpeak = viewModel::speakTranslation,
                    onStop = viewModel::stopAudio,
                    onSpeakWord = viewModel::speakWord,
                    onSaveWord = viewModel::saveWord,
                    onRemoveWord = viewModel::removeWord,
                    onRetryVocabulary = viewModel::retryVocabulary,
                    onOpenSettings = { showSettings = true },
                    onStartRecording = viewModel::startRecording,
                    onStopRecording = viewModel::stopRecording
                )
                1 -> VocabularyScreen(
                    vocabulary = savedVocab,
                    languagePair = languagePair,
                    useSimplified = useSimplified,
                    onDismiss = { selectedTab = 0 },
                    onRemove = viewModel::removeWord,
                    onSpeak = viewModel::speakWord
                )
            }
        }
    }
}

@Composable
private fun BottomNav(selectedTab: Int, savedCount: Int, onSelectTab: (Int) -> Unit) {
    NavigationBar(
        containerColor = Surface,
        tonalElevation = 8.dp,
        modifier = Modifier.shadow(16.dp)
    ) {
        NavigationBarItem(
            selected = selectedTab == 0,
            onClick = { onSelectTab(0) },
            icon = { Icon(Icons.Default.Translate, contentDescription = null) },
            label = { Text("Translate") },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = BluePrimary,
                selectedTextColor = BluePrimary,
                indicatorColor = BluePrimary.copy(alpha = 0.1f),
                unselectedIconColor = TextSecondary,
                unselectedTextColor = TextSecondary
            )
        )
        NavigationBarItem(
            selected = selectedTab == 1,
            onClick = { onSelectTab(1) },
            icon = {
                BadgedBox(
                    badge = {
                        if (savedCount > 0) {
                            Badge(containerColor = GoldAccent, contentColor = Color.White) {
                                Text("$savedCount", fontSize = 10.sp, modifier = Modifier.padding(horizontal = 4.dp))
                            }
                        }
                    }
                ) { Icon(Icons.Default.Bookmark, contentDescription = null) }
            },
            label = { Text("Saved") },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = GoldAccent,
                selectedTextColor = GoldAccent,
                indicatorColor = GoldAccent.copy(alpha = 0.1f),
                unselectedIconColor = TextSecondary,
                unselectedTextColor = TextSecondary
            )
        )
    }
}
