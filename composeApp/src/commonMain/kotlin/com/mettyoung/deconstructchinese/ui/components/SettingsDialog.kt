package com.mettyoung.deconstructchinese.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mettyoung.deconstructchinese.model.LanguagePair
import com.mettyoung.deconstructchinese.model.TranslationProvider
import com.mettyoung.deconstructchinese.ui.theme.BluePrimary
import com.mettyoung.deconstructchinese.ui.theme.Divider
import com.mettyoung.deconstructchinese.ui.theme.Surface
import com.mettyoung.deconstructchinese.ui.theme.TextPrimary
import com.mettyoung.deconstructchinese.ui.theme.TextSecondary

@Composable
fun SettingsDialog(
    languagePair: LanguagePair,
    onLanguagePairChange: (LanguagePair) -> Unit,
    useSimplified: Boolean,
    onUseSimplifiedChange: (Boolean) -> Unit,
    selectedProvider: TranslationProvider?,
    onSelectedProviderChange: (TranslationProvider?) -> Unit,
    apiKeyOverrides: Map<TranslationProvider, String>,
    onApiKeyChange: (TranslationProvider, String) -> Unit,
    modelOverrides: Map<TranslationProvider, String>,
    onModelChange: (TranslationProvider, String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        shape = MaterialTheme.shapes.large,
        title = {
            Text(
                "Settings",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-0.5).sp
                )
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(24.dp),
                modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionLabel("STUDY LANGUAGE")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        LanguagePair.entries.forEach { pair ->
                            val selected = pair == languagePair
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(MaterialTheme.shapes.medium)
                                    .background(if (selected) BluePrimary else BluePrimary.copy(alpha = 0.04f))
                                    .clickable { onLanguagePairChange(pair) }
                                    .padding(horizontal = 12.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    pair.label,
                                    color = if (selected) Color.White else TextPrimary,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }
                }

                if (languagePair.hasScriptVariants) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionLabel("CHINESE SCRIPT")
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.medium)
                                .background(BluePrimary.copy(alpha = 0.04f))
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    if (useSimplified) "Simplified (简体)" else "Traditional (繁體)",
                                    color = TextPrimary,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 15.sp
                                )
                                Text(
                                    if (useSimplified) "Preferred for Mainland China" else "Preferred for Taiwan/HK",
                                    color = TextSecondary.copy(alpha = 0.7f),
                                    fontSize = 12.sp
                                )
                            }
                            Switch(
                                checked = useSimplified,
                                onCheckedChange = onUseSimplifiedChange,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = BluePrimary,
                                    uncheckedThumbColor = Color.White,
                                    uncheckedTrackColor = Divider
                                ),
                                modifier = Modifier.size(44.dp, 24.dp)
                            )
                        }
                    }
                }

                // AI provider: 3-part config — pick a provider, then its key
                // and model. "Auto" (null) hides the key/model fields; it
                // just uses the app's built-in default key + model.
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionLabel("AI PROVIDER")
                    LabeledDropdown(
                        selectedLabel = selectedProvider?.label ?: "Auto",
                        options = listOf("Auto" to null) + TranslationProvider.entries.map { it.label to it },
                        onSelect = { onSelectedProviderChange(it) }
                    )
                }

                if (selectedProvider != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionLabel("API KEY")
                        ApiKeyField(
                            value = apiKeyOverrides[selectedProvider].orEmpty(),
                            onValueChange = { onApiKeyChange(selectedProvider, it) }
                        )
                        Text(
                            "Leave blank to use the app's built-in key, if any.",
                            color = TextSecondary.copy(alpha = 0.7f),
                            fontSize = 12.sp
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionLabel("MODEL")
                        val currentModel = modelOverrides[selectedProvider].orEmpty().ifBlank { selectedProvider.defaultModel }
                        LabeledDropdown(
                            selectedLabel = currentModel,
                            options = selectedProvider.models.map { it to it },
                            onSelect = { onModelChange(selectedProvider, it) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = BluePrimary),
                modifier = Modifier.height(44.dp).padding(horizontal = 8.dp),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
            ) {
                Text("Done", fontWeight = FontWeight.Bold)
            }
        }
    )
}

// A plain DropdownMenu anchored to a read-only text field, rather than
// ExposedDropdownMenuBox — that API isn't present in this material3 alpha.
// DropdownMenu is a Popup, so a plain fillMaxWidth() on its content fills the
// whole window, not the anchor — the anchor's measured width is captured via
// onSizeChanged and applied to the menu explicitly instead.
@Composable
private fun <T> LabeledDropdown(
    selectedLabel: String,
    options: List<Pair<String, T>>,
    onSelect: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var anchorWidthPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    Box(modifier = Modifier.fillMaxWidth().wrapContentSize(Alignment.TopStart)) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            enabled = false,
            trailingIcon = {
                Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = TextSecondary.copy(alpha = 0.6f))
            },
            colors = OutlinedTextFieldDefaults.colors(
                disabledBorderColor = Divider,
                disabledTextColor = TextPrimary,
                disabledTrailingIconColor = TextSecondary.copy(alpha = 0.6f)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { anchorWidthPx = it.width }
        )
        // Transparent overlay: the field is disabled (so it can't be typed
        // into or grab focus/keyboard) but still needs to react to taps.
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable { expanded = true }
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.width(with(density) { anchorWidthPx.toDp() })
        ) {
            options.forEach { (label, value) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onSelect(value)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun ApiKeyField(
    value: String,
    onValueChange: (String) -> Unit
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = {
            Text("Using built-in key", color = TextSecondary.copy(alpha = 0.5f), fontSize = 13.sp)
        },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = if (visible) "Hide key" else "Show key",
                    tint = TextSecondary.copy(alpha = 0.6f)
                )
            }
        },
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = BluePrimary,
            unfocusedBorderColor = Divider,
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary
        ),
        modifier = Modifier.fillMaxWidth()
    )
}
