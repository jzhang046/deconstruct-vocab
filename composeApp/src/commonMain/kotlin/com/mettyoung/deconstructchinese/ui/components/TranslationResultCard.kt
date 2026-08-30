package com.mettyoung.deconstructchinese.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mettyoung.deconstructchinese.model.LanguagePair
import com.mettyoung.deconstructchinese.model.TranslationResult
import com.mettyoung.deconstructchinese.model.VocabularyItem
import com.mettyoung.deconstructchinese.util.ChineseScriptConverter
import com.mettyoung.deconstructchinese.ui.theme.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ForeignWithPhonetic(
    languagePair: LanguagePair,
    vocabulary: List<VocabularyItem>,
    useSimplified: Boolean,
    fallbackText: String,
    fallbackPhonetic: String,
    modifier: Modifier = Modifier
) {
    // The oversized-glyph treatment only fits scripts with a phonetic guide
    // (short CJK words/characters paired with a pinyin-style caption). Other
    // pairs' vocabulary entries can be full multi-word phrases, so they're
    // shown as normal-sized flowing text instead of giant blocks.
    val bigGlyphs = languagePair.hasPhoneticGuide
    val wordFontSize = if (bigGlyphs) 36.sp else 20.sp
    val wordLineHeight = if (bigGlyphs) 44.sp else 26.sp

    // Stage 1 (streaming): no per-word segmentation yet — show the raw foreign
    // text with the whole-sentence pronunciation guide above it (if any)
    // until the breakdown arrives.
    if (vocabulary.isEmpty()) {
        if (fallbackText.isNotBlank()) {
            Column(modifier = modifier) {
                if (fallbackPhonetic.isNotBlank()) {
                    Text(
                        text = fallbackPhonetic,
                        fontSize = 14.sp,
                        color = PinyinColor,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.2.sp,
                        lineHeight = 20.sp
                    )
                    Spacer(Modifier.height(6.dp))
                }
                Text(
                    text = fallbackText,
                    fontSize = wordFontSize,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    lineHeight = wordLineHeight
                )
            }
        }
        return
    }
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        vocabulary.forEach { item ->
            val altScript: String? = item.altScript?.takeIf { it != item.word }

            val displayWord = if (useSimplified) altScript ?: item.word else item.word
            val counterpartWord = if (altScript != null) {
                if (useSimplified) item.word else altScript
            } else null

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(vertical = 4.dp)
            ) {
                val phoneticDisplay = if (counterpartWord != null) {
                    "${item.phonetic} ($counterpartWord)"
                } else {
                    item.phonetic
                }
                if (phoneticDisplay.isNotBlank()) {
                    Text(
                        text       = phoneticDisplay,
                        fontSize   = 13.sp,
                        color      = PinyinColor,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.2.sp
                    )
                }
                Text(
                    text       = displayWord,
                    fontSize   = wordFontSize,
                    fontWeight = FontWeight.Bold,
                    color      = TextPrimary,
                    lineHeight = wordLineHeight
                )
            }
        }
    }
}

@Composable
fun TranslationResultCard(
    result: TranslationResult,
    languagePair: LanguagePair,
    toEnglish: Boolean,
    isPlaying: Boolean,
    savedVocab: List<VocabularyItem>,
    useSimplified: Boolean = false,
    vocabLoading: Boolean = false,
    vocabError: Boolean = false,
    onRetryVocabulary: () -> Unit = {},
    onSpeak: () -> Unit,
    onStop: () -> Unit,
    onSpeakWord: (String) -> Unit,
    onSaveWord: (VocabularyItem) -> Unit,
    onRemoveWord: (VocabularyItem) -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    val displayForeignText = if (languagePair.hasScriptVariants && useSimplified) {
        ChineseScriptConverter.toSimplified(result.foreignText)
    } else {
        result.foreignText
    }
    val scriptLabel = if (languagePair.hasScriptVariants) {
        if (useSimplified) "Simplified Chinese" else "Traditional Chinese"
    } else languagePair.foreignName

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = Surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Divider.copy(alpha = 0.5f))
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 8.dp, top = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SectionLabel(scriptLabel.uppercase(), color = BluePrimary)
                    Row {
                        IconButton(
                            onClick  = { clipboardManager.setText(AnnotatedString(displayForeignText)) },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = TextSecondary.copy(alpha = 0.6f), modifier = Modifier.size(18.dp))
                        }
                        IconButton(
                            onClick  = { if (isPlaying) onStop() else onSpeak() },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                if (isPlaying) Icons.Default.Stop else Icons.AutoMirrored.Filled.VolumeUp,
                                contentDescription = if (isPlaying) "Stop" else "Listen",
                                tint     = if (isPlaying) BluePrimary else TextSecondary.copy(alpha = 0.6f),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                ForeignWithPhonetic(
                    languagePair  = languagePair,
                    vocabulary    = result.vocabulary,
                    useSimplified = useSimplified,
                    fallbackText  = displayForeignText,
                    fallbackPhonetic = result.phoneticText,
                    modifier      = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )

                if (toEnglish) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), 
                        color = Divider.copy(alpha = 0.3f)
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        SectionLabel("ENGLISH TRANSLATION")
                        Text(
                            text       = result.translatedText,
                            style      = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary,
                                lineHeight = 28.sp
                            )
                        )
                    }
                }
            }
        }

        if (result.grammarNote.isNotBlank()) {
            Spacer(Modifier.height(16.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = GrammarBg.copy(alpha = 0.7f),
                border = androidx.compose.foundation.BorderStroke(1.dp, BluePrimary.copy(alpha = 0.1f))
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        Icons.Default.Translate,
                        contentDescription = null,
                        tint = BluePrimary,
                        modifier = Modifier.size(20.dp).padding(top = 2.dp)
                    )
                    Column {
                        SectionLabel("LANGUAGE NOTES", color = BluePrimary)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            result.grammarNote, 
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontSize = 14.sp,
                                color = TextPrimary.copy(alpha = 0.85f),
                                lineHeight = 20.sp
                            )
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(28.dp))

        SectionLabel(
            "VOCABULARY BREAKDOWN",
            modifier = Modifier.padding(start = 4.dp, bottom = 12.dp),
            color = TextSecondary.copy(alpha = 0.8f)
        )

        if (vocabLoading && result.vocabulary.isEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = BluePrimary
                )
                Text(
                    "Loading breakdown…",
                    fontSize = 14.sp,
                    color = TextSecondary.copy(alpha = 0.8f)
                )
            }
        } else if (vocabError && result.vocabulary.isEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Couldn't load breakdown.",
                    fontSize = 14.sp,
                    color = TextSecondary.copy(alpha = 0.8f),
                    modifier = Modifier.weight(1f, fill = false)
                )
                androidx.compose.material3.TextButton(onClick = onRetryVocabulary) {
                    Text("Retry", fontWeight = FontWeight.SemiBold, color = BluePrimary)
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                result.vocabulary.forEach { item ->
                    val isSaved = savedVocab.any { it.word == item.word }
                    VocabularyCard(
                        item         = item,
                        languagePair = languagePair,
                        isSaved      = isSaved,
                        useSimplified = useSimplified,
                        onSpeak      = { onSpeakWord(item.word) },
                        onSaveToggle = { if (isSaved) onRemoveWord(item) else onSaveWord(item) }
                    )
                }
            }
        }
    }
}
