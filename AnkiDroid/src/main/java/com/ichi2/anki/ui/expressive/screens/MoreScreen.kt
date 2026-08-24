// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.ui.expressive.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.R

private data class MoreItem(
    val title: String,
    val subtitle: String,
    val iconRes: Int,
)

/** More: grouped expressive list + gateway to the classic (functional) UI. */
@Composable
fun MoreScreen(
    onOpenSettings: () -> Unit,
    onOpenClassicUi: () -> Unit,
    onSync: () -> Unit = {},
) {
    var showHelp by remember { mutableStateOf(false) }
    var showSupport by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val groups =
        listOf(
            "Settings" to
                listOf(
                    MoreItem("Settings", "All application preferences", R.drawable.ic_settings_black),
                    MoreItem("Sync", "AnkiWeb account & data", R.drawable.ic_sync),
                ),
            "Help" to
                listOf(
                    MoreItem("Help", "Manuals, FAQ, community", R.drawable.ic_help_black),
                    MoreItem("Support AnkiDroid", "Donate, translate, rate", R.drawable.ic_round_favorite_24),
                ),
        )
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = 20.dp, bottom = 140.dp),
    ) {
        Text(
            "More",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Spacer(Modifier.height(18.dp))
        groups.forEach { (groupTitle, items) ->
            Text(
                groupTitle.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
            ) {
                Column {
                    items.forEachIndexed { index, item ->
                        if (index > 0) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(start = 68.dp)
                                    .height(1.dp)
                                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                            )
                        }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) {
                                    when (item.title) {
                                        "Settings" -> onOpenSettings()
                                        "Sync" -> onSync()
                                        "Help" -> showHelp = true
                                        "Support AnkiDroid" -> showSupport = true
                                    }
                                }.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            ) {
                                Box(Modifier.padding(10.dp)) {
                                    Icon(painterResource(item.iconRes), contentDescription = null, Modifier.size(22.dp))
                                }
                            }
                            Spacer(Modifier.width(16.dp))
                            Column {
                                Text(
                                    item.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    item.subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
        }
        // Classic UI gateway — the functional old UI while the new one is being wired
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onOpenClassicUi,
                    ),
        ) {
            Column(Modifier.padding(18.dp)) {
                Text(
                    "Open Classic UI",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "The full functional interface — sync, editing, scheduling — while the expressive UI is being connected.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    if (showHelp) {
        HelpDialog(onDismiss = { showHelp = false })
    }

    if (showSupport) {
        SupportDialog(onDismiss = { showSupport = false })
    }
}

/** Support options mirroring the classic support dialog. */
@Composable
private fun SupportDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val entries =
        listOf(
            "Donate (OpenCollective)" to context.getString(R.string.link_opencollective_donate),
            "Translate" to context.getString(R.string.link_translation),
            "Contribute code" to context.getString(R.string.link_contribution),
        )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Support AnkiDroid", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                entries.forEach { (label, url) ->
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(url)),
                                    )
                                }.padding(vertical = 10.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

/** Help entries mirroring the classic HelpDialog, opened in the browser. */
@Composable
private fun HelpDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val entries =
        listOf(
            "AnkiDroid manual" to AnkiDroidApp.manualUrl,
            "Anki manual" to context.getString(R.string.link_anki_manual),
            "AnkiDroid FAQ" to context.getString(R.string.link_ankidroid_faq),
            "Community forums" to context.getString(R.string.link_forum),
            "Report a bug" to AnkiDroidApp.feedbackUrl,
        )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Help", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                entries.forEach { (label, url) ->
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(url)),
                                    )
                                }.padding(vertical = 10.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}
