package com.mettyoung.deconstructchinese.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.expandVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mettyoung.deconstructchinese.model.LanguagePair
import com.mettyoung.deconstructchinese.model.VocabularyItem
import com.mettyoung.deconstructchinese.ui.components.VocabularyCard
import com.mettyoung.deconstructchinese.ui.theme.*

@Composable
fun VocabularyScreen(
    modifier: Modifier = Modifier,
    vocabulary: List<VocabularyItem>,
    languagePair: LanguagePair,
    useSimplified: Boolean = false,
    onDismiss: () -> Unit,
    onRemove: (VocabularyItem) -> Unit,
    onSpeak: (String) -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Column {
                Text(
                    "Vocabulary",
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = (-0.5).sp
                    ),
                    color = TextPrimary
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    Surface(
                        color = GoldAccent.copy(alpha = 0.15f),
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            "${vocabulary.size} words",
                            color = GoldAccent,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Saved for review",
                        color = TextSecondary.copy(alpha = 0.7f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        if (vocabulary.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(bottom = 64.dp)
                ) {
                    Surface(
                        modifier = Modifier.size(120.dp),
                        color = Card.copy(alpha = 0.5f),
                        shape = androidx.compose.foundation.shape.CircleShape
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.Bookmark, 
                                contentDescription = null, 
                                modifier = Modifier.size(48.dp),
                                tint = Divider
                            )
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                    Text(
                        "Your list is empty", 
                        color = TextPrimary,
                        style = MaterialTheme.typography.titleLarge
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Tap the bookmark icon on any\nword to save it here.", 
                        color = TextSecondary.copy(alpha = 0.6f),
                        fontSize = 15.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        lineHeight = 22.sp
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(vocabulary, key = { it.word }) { item ->
                    AnimatedVisibility(
                        visible = true,
                        enter = fadeIn(tween(400)) + expandVertically()
                    ) {
                        VocabularyCard(
                            item         = item,
                            languagePair = languagePair,
                            isSaved      = true,
                            useSimplified = useSimplified,
                            onSpeak      = { onSpeak(item.word) },
                            onSaveToggle = { onRemove(item) }
                        )
                    }
                }
                item { Spacer(Modifier.height(32.dp)) }
            }
        }
    }
}
