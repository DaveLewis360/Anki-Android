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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.R
import com.ichi2.anki.ui.expressive.ExpressiveAccountUiState
import com.ichi2.anki.ui.expressive.ExpressiveMaintenanceMode
import com.ichi2.anki.ui.expressive.ExpressiveSyncUiState
import com.ichi2.anki.ui.expressive.isCollectionBlocking

private enum class MoreAction {
    IMPORT,
    EXPORT,
    BACKUPS,
    DATABASE,
    EMPTY_CARDS,
    MEDIA,
    SETTINGS,
    ACCOUNT,
    SYNC,
    HELP,
    SUPPORT,
}

private data class MoreItem(
    val title: String,
    val subtitle: String,
    val iconRes: Int,
    val action: MoreAction,
)

internal fun ExpressiveSyncUiState.subtitle(): String =
    when (this) {
        ExpressiveSyncUiState.Checking -> "Checking sync status…"
        ExpressiveSyncUiState.Idle -> "AnkiWeb account & data"
        ExpressiveSyncUiState.AwaitingMeteredConfirmation -> "Waiting for data-use confirmation…"
        ExpressiveSyncUiState.Queued -> "Waiting for network… · tap to cancel"
        is ExpressiveSyncUiState.Running -> progress ?: "Syncing collection… · tap to cancel"
        ExpressiveSyncUiState.MediaQueued -> "Collection complete; media waiting for network… · tap to cancel"
        is ExpressiveSyncUiState.MediaRunning -> progress ?: "Syncing media… · tap to cancel"
        ExpressiveSyncUiState.CancellingCollection -> "Cancelling collection sync…"
        ExpressiveSyncUiState.CancellingMedia -> "Cancelling media sync…"
        ExpressiveSyncUiState.Succeeded -> "Collection and media sync complete"
        ExpressiveSyncUiState.NeedsLogin -> "Sign in to AnkiWeb to sync"
        ExpressiveSyncUiState.AwaitingFullSyncChoice -> "Choose which collection to keep"
        is ExpressiveSyncUiState.AwaitingFullSyncConfirmation -> "Confirm collection replacement"
        ExpressiveSyncUiState.Failed -> "Sync failed · tap to retry"
        ExpressiveSyncUiState.Cancelled -> "Sync cancelled · tap to retry"
    }

/** More: grouped expressive list + gateway to the classic (functional) UI. */
@Composable
fun MoreScreen(
    onOpenSettings: () -> Unit,
    onOpenAccount: () -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onMaintenance: (ExpressiveMaintenanceMode) -> Unit,
    externalCollectionBlocking: Boolean = false,
    accountState: ExpressiveAccountUiState = ExpressiveAccountUiState(),
    syncState: ExpressiveSyncUiState = ExpressiveSyncUiState.Idle,
    onSync: () -> Unit = {},
    onKeepLocal: () -> Unit = {},
    onKeepRemote: () -> Unit = {},
    onCancelFullSyncChoice: () -> Unit = {},
    onConfirmFullSync: () -> Unit = {},
    onCancelFullSyncConfirmation: () -> Unit = {},
) {
    var showHelp by remember { mutableStateOf(false) }
    var showSupport by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val accountSubtitle =
        accountState.account?.username?.let { username ->
            stringResource(R.string.expressive_account_signed_in_as, username)
        } ?: if (accountState.account != null) {
            stringResource(R.string.expressive_account_signed_in_without_identity)
        } else {
            stringResource(R.string.expressive_account_not_signed_in)
        }
    val groups =
        listOf(
            stringResource(R.string.expressive_transfer_group) to
                listOf(
                    MoreItem(
                        stringResource(R.string.expressive_import_title),
                        stringResource(R.string.expressive_import_subtitle),
                        R.drawable.ic_sync,
                        MoreAction.IMPORT,
                    ),
                    MoreItem(
                        stringResource(R.string.expressive_export_title),
                        stringResource(R.string.expressive_export_subtitle),
                        R.drawable.ic_sync,
                        MoreAction.EXPORT,
                    ),
                ),
            stringResource(R.string.expressive_maintenance_group) to
                listOf(
                    MoreItem(
                        stringResource(R.string.expressive_maintenance_backups_title),
                        stringResource(R.string.expressive_maintenance_backups_subtitle),
                        R.drawable.ic_baseline_backup_24,
                        MoreAction.BACKUPS,
                    ),
                    MoreItem(
                        remember { ExpressiveMaintenanceMode.DATABASE.title(context) },
                        stringResource(R.string.expressive_maintenance_database_subtitle),
                        R.drawable.ic_build_black_24,
                        MoreAction.DATABASE,
                    ),
                    MoreItem(
                        remember { ExpressiveMaintenanceMode.EMPTY_CARDS.title(context) },
                        stringResource(R.string.expressive_maintenance_empty_cards_subtitle),
                        R.drawable.ic_delete,
                        MoreAction.EMPTY_CARDS,
                    ),
                    MoreItem(
                        remember { ExpressiveMaintenanceMode.MEDIA.title(context) },
                        stringResource(R.string.expressive_maintenance_media_subtitle),
                        R.drawable.ic_image,
                        MoreAction.MEDIA,
                    ),
                ),
            "Settings" to
                listOf(
                    MoreItem("Settings", "All application preferences", R.drawable.ic_settings_black, MoreAction.SETTINGS),
                    MoreItem(
                        stringResource(R.string.expressive_account_title),
                        accountSubtitle,
                        R.drawable.ic_sync,
                        MoreAction.ACCOUNT,
                    ),
                    MoreItem("Sync", syncState.subtitle(), R.drawable.ic_sync, MoreAction.SYNC),
                ),
            "Help" to
                listOf(
                    MoreItem("Help", "Manuals, FAQ, community", R.drawable.ic_help_black, MoreAction.HELP),
                    MoreItem(
                        "Support AnkiDroid",
                        "Donate, translate, rate",
                        R.drawable.ic_round_favorite_24,
                        MoreAction.SUPPORT,
                    ),
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
                                    enabled =
                                        if (item.action == MoreAction.SYNC) {
                                            !externalCollectionBlocking && syncState !is ExpressiveSyncUiState.Checking
                                        } else {
                                            !externalCollectionBlocking && !syncState.isCollectionBlocking
                                        },
                                    role = Role.Button,
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) {
                                    when (item.action) {
                                        MoreAction.IMPORT -> onImport()
                                        MoreAction.EXPORT -> onExport()
                                        MoreAction.BACKUPS -> onMaintenance(ExpressiveMaintenanceMode.BACKUPS)
                                        MoreAction.DATABASE -> onMaintenance(ExpressiveMaintenanceMode.DATABASE)
                                        MoreAction.EMPTY_CARDS -> onMaintenance(ExpressiveMaintenanceMode.EMPTY_CARDS)
                                        MoreAction.MEDIA -> onMaintenance(ExpressiveMaintenanceMode.MEDIA)
                                        MoreAction.SETTINGS -> onOpenSettings()
                                        MoreAction.ACCOUNT -> onOpenAccount()
                                        MoreAction.SYNC -> onSync()
                                        MoreAction.HELP -> showHelp = true
                                        MoreAction.SUPPORT -> showSupport = true
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
    }

    if (syncState is ExpressiveSyncUiState.AwaitingFullSyncChoice) {
        AlertDialog(
            onDismissRequest = onCancelFullSyncChoice,
            title = { Text("Choose which collection to keep") },
            text = {
                Text("Keep Local uploads this device's collection. Keep Remote replaces it with the AnkiWeb collection.")
            },
            confirmButton = {
                Row {
                    TextButton(onClick = onKeepRemote) { Text("Keep Remote") }
                    TextButton(onClick = onKeepLocal) { Text("Keep Local") }
                }
            },
            dismissButton = {
                TextButton(onClick = onCancelFullSyncChoice) { Text("Cancel") }
            },
        )
    }

    if (syncState is ExpressiveSyncUiState.AwaitingFullSyncConfirmation) {
        val keepingLocal = syncState.operation == com.ichi2.anki.worker.SyncOperation.FULL_UPLOAD
        AlertDialog(
            onDismissRequest = onCancelFullSyncConfirmation,
            title = { Text(if (keepingLocal) "Replace AnkiWeb collection?" else "Replace local collection?") },
            text = {
                Text(
                    if (keepingLocal) {
                        "This will replace the collection on AnkiWeb with the collection on this device."
                    } else {
                        "This will replace the collection on this device with the collection from AnkiWeb. A backup is created first."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = onConfirmFullSync) { Text("Replace") }
            },
            dismissButton = {
                TextButton(onClick = onCancelFullSyncConfirmation) { Text("Back") }
            },
        )
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
