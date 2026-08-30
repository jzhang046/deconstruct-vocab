package com.mettyoung.deconstructchinese

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mettyoung.deconstructchinese.audio.AppContext
import com.mettyoung.deconstructchinese.model.TranslationState
import com.mettyoung.deconstructchinese.model.VocabularyItem
import com.mettyoung.deconstructchinese.network.createTranslationService
import com.mettyoung.deconstructchinese.storage.AppSettings
import com.mettyoung.deconstructchinese.ui.components.ErrorCard
import com.mettyoung.deconstructchinese.ui.components.TranslationResultCard
import com.mettyoung.deconstructchinese.ui.theme.BluePrimary
import com.mettyoung.deconstructchinese.ui.theme.DeconstructTheme
import com.mettyoung.deconstructchinese.ui.theme.TextPrimary
import com.mettyoung.deconstructchinese.ui.theme.TextSecondary
import com.mettyoung.deconstructchinese.viewmodel.TranslatorPopupViewModel

class TranslatePopupActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppContext.set(applicationContext)

        val selected = intent
            ?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
            ?.toString()
            ?.trim()
            .orEmpty()

        if (selected.isEmpty()) {
            finish()
            return
        }

        setContent {
            DeconstructTheme {
                PopupContent(
                    selectedText = selected,
                    onOpenInApp = { openInMainApp(selected); finish() },
                    onDismiss = { finish() }
                )
            }
        }
    }

    private fun openInMainApp(text: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_PROCESS_TEXT
            type = "text/plain"
            putExtra(Intent.EXTRA_PROCESS_TEXT, text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(intent)
    }
}

@Composable
private fun PopupContent(
    selectedText: String,
    onOpenInApp: () -> Unit,
    onDismiss: () -> Unit
) {
    val viewModel: TranslatorPopupViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                // Any configured provider counts as "has a key" for the popup's
                // early-exit check — AutoSwitchingTranslationService picks which
                // one actually serves the request.
                val apiKey = AppSettings.apiKey("qwen").ifBlank { AppSettings.apiKey("gemini") }
                TranslatorPopupViewModel(
                    translationService = createTranslationService(),
                    apiKey = apiKey,
                    useSimplified = AppSettings.useSimplified
                )
            }
        }
    )
    val state by viewModel.translationState.collectAsStateWithLifecycle()

    androidx.compose.runtime.LaunchedEffect(selectedText) {
        viewModel.translate(selectedText)
    }

    Surface(
        modifier = Modifier
            .widthIn(max = 340.dp)
            .heightIn(max = 560.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp)
        ) {
            PopupHeader(
                title = selectedText,
                onOpenInApp = onOpenInApp
            )

            Spacer(Modifier.height(8.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 8.dp)
            ) {
                when (val s = state) {
                    TranslationState.Idle, TranslationState.Loading -> LoadingBody(selectedText)
                    is TranslationState.Success -> TranslationResultCard(
                        result = s.result,
                        toEnglish = true,
                        isPlaying = false,
                        savedVocab = emptyList(),
                        useSimplified = AppSettings.useSimplified,
                        vocabLoading = s.vocabLoading,
                        onSpeak = onOpenInApp,
                        onStop = {},
                        onSpeakWord = { onOpenInApp() },
                        onSaveWord = { onOpenInApp() },
                        onRemoveWord = { _: VocabularyItem -> onOpenInApp() }
                    )
                    is TranslationState.Error -> ErrorBody(
                        message = s.message,
                        onOpenInApp = onOpenInApp,
                        onDismiss = onDismiss
                    )
                }
            }
        }
    }
}

@Composable
private fun PopupHeader(title: String, onOpenInApp: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Box {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = "More options",
                    tint = TextSecondary
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Open in Deconstruct Chinese") },
                    leadingIcon = { Icon(Icons.Default.OpenInNew, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onOpenInApp()
                    }
                )
            }
        }
    }
}

@Composable
private fun LoadingBody(text: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        CircularProgressIndicator(
            color = BluePrimary,
            strokeWidth = 3.dp,
            modifier = Modifier.size(36.dp)
        )
        Text(
            text = "Translating…",
            fontSize = 13.sp,
            color = TextSecondary
        )
    }
}

@Composable
private fun ErrorBody(
    message: String,
    onOpenInApp: () -> Unit,
    onDismiss: () -> Unit
) {
    val isMissingKey = message == TranslatorPopupViewModel.MISSING_API_KEY_MESSAGE
    val isNotChinese = message == TranslatorPopupViewModel.NOT_CHINESE_MESSAGE
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ErrorCard(message = message)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
        ) {
            OutlinedButton(onClick = onDismiss) {
                Text("Close")
            }
            if (!isNotChinese) {
                Button(
                    onClick = onOpenInApp,
                    colors = ButtonDefaults.buttonColors(containerColor = BluePrimary)
                ) {
                    Text(if (isMissingKey) "Set API key" else "Open in app")
                }
            }
        }
    }
}
