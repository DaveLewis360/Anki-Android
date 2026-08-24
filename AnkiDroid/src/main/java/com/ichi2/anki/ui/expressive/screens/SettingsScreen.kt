// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.ui.expressive.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ichi2.anki.R
import com.ichi2.anki.ui.expressive.ExpressiveSettings
import com.ichi2.anki.ui.expressive.components.GestureAction
import com.ichi2.anki.ui.expressive.theme.expressivePalettes
import com.ichi2.anki.ui.expressive.theme.supportsDynamicColor

/** Full expressive Settings screen: theme, palette, Material You, about. */
@Composable
fun SettingsScreen(
    settings: ExpressiveSettings,
    onChange: (ExpressiveSettings) -> Unit,
    onBack: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                onClick = onBack,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Icon(
                    painterResource(R.drawable.ic_chevron_right_black),
                    contentDescription = "Back",
                    modifier = Modifier.padding(10.dp).size(24.dp),
                )
            }
            Text(
                "Settings",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 14.dp),
            )
        }
        Spacer(Modifier.height(4.dp))

        SectionCard(title = "APPEARANCE") {
            SettingLabel("Theme")
            Spacer(Modifier.height(8.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("system" to "System", "light" to "Light", "dark" to "Dark").forEachIndexed { index, (key, label) ->
                    SegmentedButton(
                        selected = settings.themeMode == key,
                        onClick = { onChange(settings.copy(themeMode = key)) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = 3),
                    ) {
                        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                    }
                }
            }

            if (supportsDynamicColor) {
                Spacer(Modifier.height(18.dp))
                ToggleRow(
                    title = "Dynamic color",
                    subtitle = "Use wallpaper colors (Material You)",
                    checked = settings.dynamicColor,
                    onToggle = { onChange(settings.copy(dynamicColor = it)) },
                )
            }

            Spacer(Modifier.height(18.dp))
            SettingLabel(
                "Color palette",
                color =
                    if (settings.dynamicColor &&
                        supportsDynamicColor
                    ) {
                        MaterialTheme.colorScheme.outline
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
            )
            Spacer(Modifier.height(10.dp))
            if (settings.dynamicColor && supportsDynamicColor) {
                Text(
                    "Turn off dynamic color to pick a palette",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    expressivePalettes.forEach { palette ->
                        val selected = settings.paletteKey == palette.key
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                border =
                                    androidx.compose.foundation.BorderStroke(
                                        width = if (selected) 3.dp else 1.dp,
                                        color =
                                            if (selected) {
                                                palette.primaryLight
                                            } else {
                                                MaterialTheme.colorScheme.outlineVariant
                                            },
                                    ),
                                modifier =
                                    Modifier.clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                    ) { onChange(settings.copy(paletteKey = palette.key, dynamicColor = false)) },
                            ) {
                                Box(
                                    Modifier
                                        .padding(4.dp)
                                        .size(34.dp)
                                        .clip(CircleShape)
                                        .background(palette.primaryLight),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (selected) {
                                        Icon(
                                            painterResource(R.drawable.ic_checkmark),
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                palette.label,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        SectionCard(title = "GESTURES") {
            GestureSettingRow("Tap on question", settings.gestureTapQuestion) {
                onChange(settings.copy(gestureTapQuestion = it))
            }
            GestureSettingRow("Tap on answer", settings.gestureTapAnswer) {
                onChange(settings.copy(gestureTapAnswer = it))
            }
            GestureSettingRow("Swipe left", settings.gestureSwipeLeft) {
                onChange(settings.copy(gestureSwipeLeft = it))
            }
            GestureSettingRow("Swipe right", settings.gestureSwipeRight) {
                onChange(settings.copy(gestureSwipeRight = it))
            }
            GestureSettingRow("Double tap", settings.gestureDoubleTap) {
                onChange(settings.copy(gestureDoubleTap = it))
            }
            GestureSettingRow("Long press", settings.gestureLongPress) {
                onChange(settings.copy(gestureLongPress = it))
            }
        }

        SectionCard(title = "SYNC") {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                InfoRow(iconRes = R.drawable.ic_sync, title = "AnkiWeb account", subtitle = "Sync your collection")
                InfoRow(iconRes = R.drawable.ic_round_favorite_24, title = "Support AnkiDroid", subtitle = "Donate, translate, rate")
            }
        }

        SectionCard(title = "ABOUT") {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                InfoRow(iconRes = R.drawable.ic_help_black, title = "Help & feedback", subtitle = "Using AnkiDroid, community")
                InfoRow(iconRes = R.drawable.ic_bar_chart_black, title = "Version", subtitle = "2.25.0 M3 Expressive")
            }
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
        )
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(18.dp)) { content() }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SettingLabel(
    text: String,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = color)
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onToggle)
    }
}

@Composable
private fun InfoRow(
    iconRes: Int,
    title: String,
    subtitle: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ) {
            Box(Modifier.padding(10.dp)) {
                Icon(painterResource(iconRes), contentDescription = null, Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(
            painterResource(R.drawable.ic_chevron_right_black),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun GestureSettingRow(
    label: String,
    currentKey: String,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(label) },
        trailingContent = {
            Text(
                GestureAction.fromKey(currentKey).label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        },
        modifier =
            Modifier
                .clip(MaterialTheme.shapes.medium)
                .clickable { open = true },
    )
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(label, style = MaterialTheme.typography.titleLarge) },
            text = {
                Column {
                    GestureAction.entries.forEach { action ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSelect(action.key)
                                    open = false
                                }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = action.key == currentKey,
                                onClick = {
                                    onSelect(action.key)
                                    open = false
                                },
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(action.label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { open = false }) { Text("Close") }
            },
        )
    }
}
