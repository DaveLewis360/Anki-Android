// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.ui.expressive.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ichi2.anki.CollectionManager.TR
import com.ichi2.anki.R
import com.ichi2.anki.ui.expressive.ExpressiveExportFormat
import com.ichi2.anki.ui.expressive.ExpressiveExportOptions
import com.ichi2.anki.ui.expressive.ExpressiveExportPolicy
import com.ichi2.anki.ui.expressive.ExpressiveExportScope
import com.ichi2.anki.ui.expressive.ExpressiveImportFormat
import com.ichi2.anki.ui.expressive.ExpressiveSelectionKind
import com.ichi2.anki.ui.expressive.ExpressiveTransferError
import com.ichi2.anki.ui.expressive.ExpressiveTransferUiState

@Composable
internal fun TransferScreen(
    state: ExpressiveTransferUiState,
    onBack: () -> Unit,
    onConfirmImport: () -> Unit,
    onExportOptions: (ExpressiveExportOptions) -> Unit,
    onConfirmExport: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack, modifier = Modifier.height(48.dp)) { Text(stringResource(R.string.previewer_back)) }
                Text(
                    text =
                        if (state is ExpressiveTransferUiState.ExportOptions || state is ExpressiveTransferUiState.Ready) {
                            TR.actionsExport()
                        } else {
                            TR.actionsImport()
                        },
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.semantics { heading() },
                )
            }
            when (state) {
                ExpressiveTransferUiState.Idle -> Unit
                is ExpressiveTransferUiState.PickingImport -> StatusText(stringResource(R.string.expressive_import_choose_file))
                is ExpressiveTransferUiState.ConfirmImport -> ImportConfirmation(state, onConfirmImport, onCancel)
                is ExpressiveTransferUiState.ExportOptions -> ExportConfiguration(state, onExportOptions, onConfirmExport, onCancel)
                is ExpressiveTransferUiState.CopyingImport,
                is ExpressiveTransferUiState.Running,
                is ExpressiveTransferUiState.SavingExport,
                -> {
                    LoadingIndicator()
                    StatusText(
                        if (
                            state is ExpressiveTransferUiState.CopyingImport ||
                            (state is ExpressiveTransferUiState.Running && state.mutatingImport)
                        ) {
                            stringResource(R.string.expressive_import_running)
                        } else {
                            stringResource(R.string.expressive_export_running)
                        },
                    )
                    Text(stringResource(R.string.expressive_transfer_cancel_wait_description), style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = onCancel, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                        Text(stringResource(R.string.dialog_cancel))
                    }
                }
                is ExpressiveTransferUiState.Ready -> {
                    StatusText(stringResource(R.string.expressive_export_ready, state.displayName))
                    Text(stringResource(R.string.expressive_export_privacy_description), style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = onSave, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                        Text(stringResource(R.string.expressive_export_save))
                    }
                    Button(onClick = onShare, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                        Text(stringResource(R.string.expressive_export_share))
                    }
                    TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                        Text(stringResource(R.string.dialog_cancel))
                    }
                }
                is ExpressiveTransferUiState.Terminal -> {
                    StatusText(
                        when {
                            state.succeeded && state.imported -> stringResource(R.string.expressive_import_succeeded)
                            state.succeeded -> stringResource(R.string.expressive_export_saved)
                            state.error != null -> state.error.safeText()
                            else -> stringResource(R.string.expressive_transfer_cancelled)
                        },
                    )
                    Button(onClick = onDone, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                        Text(stringResource(R.string.dialog_ok))
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusText(value: String) {
    Text(
        value,
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun ImportConfirmation(
    state: ExpressiveTransferUiState.ConfirmImport,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    StatusText(state.displayName)
    Text(
        when (state.format) {
            ExpressiveImportFormat.COLPKG -> stringResource(R.string.expressive_import_collection_destructive)
            ExpressiveImportFormat.APKG -> stringResource(R.string.expressive_import_apkg_description)
            ExpressiveImportFormat.TEXT -> stringResource(R.string.expressive_import_text_description)
        },
        style = MaterialTheme.typography.bodyLarge,
    )
    Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth().height(48.dp)) {
        Text(
            if (state.format == ExpressiveImportFormat.COLPKG) {
                stringResource(R.string.sync_conflict_replace_title)
            } else {
                TR.actionsImport()
            },
        )
    }
    TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text(stringResource(R.string.dialog_cancel)) }
}

@Composable
private fun ExportConfiguration(
    state: ExpressiveTransferUiState.ExportOptions,
    onOptions: (ExpressiveExportOptions) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    Text(state.scope.description(), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.expressive_export_scope_privacy), style = MaterialTheme.typography.bodyMedium)
    HorizontalDivider()
    val allowed = ExpressiveExportPolicy.allowedFormats(state.scope)
    allowed.forEach { format ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = state.options.format == format, onClick = { onOptions(defaultOptions(format)) })
            Text(format.label(), style = MaterialTheme.typography.bodyLarge)
        }
    }
    HorizontalDivider()
    when (state.options.format) {
        ExpressiveExportFormat.COLPKG -> {
            Toggle(
                TR.exportingIncludeMedia(),
                state.options.withMedia,
            ) { onOptions(state.options.copy(withMedia = it)) }
            Toggle(stringResource(R.string.expressive_export_legacy), state.options.legacy) { onOptions(state.options.copy(legacy = it)) }
        }
        ExpressiveExportFormat.APKG -> {
            Toggle(TR.exportingIncludeSchedulingInformation(), state.options.withScheduling) {
                onOptions(state.options.copy(withScheduling = it))
            }
            Toggle(stringResource(R.string.expressive_export_include_deck_configs), state.options.withDeckConfigs) {
                onOptions(state.options.copy(withDeckConfigs = it))
            }
            Toggle(
                TR.exportingIncludeMedia(),
                state.options.withMedia,
            ) { onOptions(state.options.copy(withMedia = it)) }
            Toggle(stringResource(R.string.expressive_export_legacy), state.options.legacy) { onOptions(state.options.copy(legacy = it)) }
        }
        ExpressiveExportFormat.NOTES_TEXT -> {
            Toggle(
                stringResource(R.string.expressive_export_include_html),
                state.options.withHtml,
            ) { onOptions(state.options.copy(withHtml = it)) }
            Toggle(
                TR.exportingIncludeTags(),
                state.options.withTags,
            ) { onOptions(state.options.copy(withTags = it)) }
            Toggle(
                TR.exportingIncludeDeck(),
                state.options.withDeck,
            ) { onOptions(state.options.copy(withDeck = it)) }
            Toggle(TR.exportingIncludeNotetype(), state.options.withNotetype) {
                onOptions(state.options.copy(withNotetype = it))
            }
            Toggle(
                TR.exportingIncludeGuid(),
                state.options.withGuid,
            ) { onOptions(state.options.copy(withGuid = it)) }
        }
        ExpressiveExportFormat.CARDS_TEXT ->
            Toggle(stringResource(R.string.expressive_export_include_html), state.options.withHtml) {
                onOptions(state.options.copy(withHtml = it))
            }
    }
    Spacer(Modifier.height(8.dp))
    Button(
        onClick = onConfirm,
        modifier = Modifier.fillMaxWidth().height(48.dp),
    ) { Text(stringResource(R.string.expressive_export_action)) }
    TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text(stringResource(R.string.dialog_cancel)) }
}

@Composable
private fun Toggle(
    label: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChecked, modifier = Modifier.semantics { this.role = Role.Switch })
    }
}

private fun defaultOptions(format: ExpressiveExportFormat) = ExpressiveExportOptions(format = format)

@Composable
private fun ExpressiveExportScope.description(): String =
    when (this) {
        ExpressiveExportScope.WholeCollection -> stringResource(R.string.expressive_export_whole_collection)
        is ExpressiveExportScope.Deck -> stringResource(R.string.expressive_export_deck_scope, displayName)
        is ExpressiveExportScope.Selection ->
            stringResource(
                if (kind ==
                    ExpressiveSelectionKind.NOTES
                ) {
                    R.string.expressive_export_selected_notes
                } else {
                    R.string.expressive_export_selected_cards
                },
                count,
            )
    }

@Composable
private fun ExpressiveExportFormat.label(): String =
    stringResource(
        when (this) {
            ExpressiveExportFormat.COLPKG -> R.string.expressive_export_format_colpkg
            ExpressiveExportFormat.APKG -> R.string.expressive_export_format_apkg
            ExpressiveExportFormat.NOTES_TEXT -> R.string.expressive_export_format_notes
            ExpressiveExportFormat.CARDS_TEXT -> R.string.expressive_export_format_cards
        },
    )

@Composable
private fun ExpressiveTransferError.safeText(): String =
    stringResource(
        when (this) {
            ExpressiveTransferError.UNSUPPORTED_FILE -> R.string.expressive_transfer_unsupported_file
            ExpressiveTransferError.UNSUPPORTED_DATABASE -> R.string.expressive_transfer_unsupported_database
            ExpressiveTransferError.PROVIDER_UNAVAILABLE -> R.string.expressive_transfer_provider_unavailable
            ExpressiveTransferError.FILE_TOO_LARGE -> R.string.expressive_transfer_file_too_large
            ExpressiveTransferError.COPY_FAILED -> R.string.expressive_transfer_copy_failed
            ExpressiveTransferError.IMPORT_FAILED -> R.string.expressive_transfer_import_failed
            ExpressiveTransferError.COLLECTION_RECOVERY_FAILED -> R.string.expressive_transfer_import_failed
            ExpressiveTransferError.EXPORT_FAILED -> R.string.expressive_transfer_export_failed
            ExpressiveTransferError.SAVE_FAILED -> R.string.expressive_transfer_save_failed
            ExpressiveTransferError.SHARE_UNAVAILABLE -> R.string.expressive_transfer_share_unavailable
            ExpressiveTransferError.INTERRUPTED -> R.string.expressive_transfer_interrupted
            ExpressiveTransferError.PREVIOUS_RESTORE_UNFINISHED -> R.string.expressive_maintenance_error_previous_restore
        },
    )
