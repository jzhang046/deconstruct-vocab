package com.mettyoung.deconstructchinese.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mettyoung.deconstructchinese.model.LanguagePair
import com.mettyoung.deconstructchinese.ui.theme.Background
import com.mettyoung.deconstructchinese.ui.theme.BluePrimary
import com.mettyoung.deconstructchinese.ui.theme.Divider
import com.mettyoung.deconstructchinese.ui.theme.TextSecondary

@Composable
fun LanguageDirectionBar(
    languagePair: LanguagePair,
    toEnglish: Boolean,
    useSimplified: Boolean,
    onSwap: () -> Unit,
    modifier: Modifier = Modifier
) {
    val foreignLabel = if (languagePair.hasScriptVariants) {
        if (useSimplified) "Simplified" else "Traditional"
    } else languagePair.foreignName
    val sourceLabel = if (toEnglish) foreignLabel else "English"
    val targetLabel = if (toEnglish) "English" else foreignLabel

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = CircleShape,
        color = Color.White,
        border = androidx.compose.foundation.BorderStroke(1.dp, Divider.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier.padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            DirectionSegment(text = sourceLabel, active = true, onClick = onSwap)
            
            IconButton(
                onClick = onSwap,
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Background)
            ) {
                Icon(
                    Icons.Default.SwapHoriz, 
                    contentDescription = "Swap direction", 
                    tint = BluePrimary,
                    modifier = Modifier.size(20.dp)
                )
            }
            
            DirectionSegment(text = targetLabel, active = false, onClick = onSwap)
        }
    }
}

@Composable
private fun RowScope.DirectionSegment(text: String, active: Boolean, onClick: () -> Unit) {
    val color by animateColorAsState(
        if (active) BluePrimary else TextSecondary,
        animationSpec = tween(300)
    )
    
    Box(
        modifier = Modifier
            .weight(1f)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = color,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
            fontSize = 14.sp,
            letterSpacing = 0.2.sp
        )
    }
}
