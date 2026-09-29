// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.ui.expressive.screens

import android.content.Context
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ichi2.anki.CollectionManager.TR
import com.ichi2.anki.R
import com.ichi2.anki.ui.expressive.ExpressiveBackupEntry
import com.ichi2.anki.ui.expressive.ExpressiveBackupLimits
import com.ichi2.anki.ui.expressive.ExpressiveMaintenanceError
import com.ichi2.anki.ui.expressive.ExpressiveMaintenanceMode
import com.ichi2.anki.ui.expressive.ExpressiveMaintenanceOperation
import com.ichi2.anki.ui.expressive.ExpressiveMaintenanceProgress
import com.ichi2.anki.ui.expressive.ExpressiveMaintenanceUiState
import com.ichi2.anki.ui.expressive.cancellable
import com.ichi2.anki.ui.internationalization.toSentenceCase

@Composable
internal fun MaintenanceScreen(
    routeMode: ExpressiveMaintenanceMode,
    state: ExpressiveMaintenanceUiState,
    onBack: () -> Unit,
    onCreateBackup: () -> Unit,
    onPickRestore: () -> Unit,
    onPrepareBackupRestore: (ExpressiveBackupEntry) -> Unit,
    onRequestDeleteBackup: (ExpressiveBackupEntry) -> Unit,
    onConfirmDeleteBackup: () -> Unit,
    onUpdateLimits: (ExpressiveBackupLimits) -> Unit,
    onConfirmRestore: () -> Unit,
    onRequestDatabaseCheck: () -> Unit,
    onConfirmDatabaseCheck: () -> Unit,
    onFindEmptyCards: () -> Unit,
    onDeleteEmptyCards: (Boolean) -> Unit,
    onCheckMedia: () -> Unit,
    onTagMissingMedia: () -> Unit,
    onTrashUnusedMedia: () -> Unit,
    onRestoreMediaTrash: () -> Unit,
    onEmptyMediaTrash: () -> Unit,
    onRebuildMediaDatabase: () -> Unit,
    onMergeQuarantinedMedia: () -> Unit,
    onDeleteQuarantinedMedia: () -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val title = remember(routeMode) { routeMode.title(context) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, modifier = Modifier.semantics { heading() }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_baseline_arrow_back_24),
                            contentDescription = stringResource(R.string.previewer_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (state) {
                ExpressiveMaintenanceUiState.Idle -> Progress(stringResource(R.string.expressive_maintenance_loading))
                is ExpressiveMaintenanceUiState.Overview ->
                    when (routeMode) {
                        ExpressiveMaintenanceMode.BACKUPS ->
                            BackupOverview(
                                state = state,
                                onCreateBackup = onCreateBackup,
                                onPickRestore = onPickRestore,
                                onPrepareRestore = onPrepareBackupRestore,
                                onRequestDelete = onRequestDeleteBackup,
                                onUpdateLimits = onUpdateLimits,
                                onMergeQuarantined = onMergeQuarantinedMedia,
                                onDeleteQuarantined = onDeleteQuarantinedMedia,
                            )
                        ExpressiveMaintenanceMode.DATABASE -> {
                            Text(stringResource(R.string.expressive_maintenance_database_description))
                            PrimaryAction(title, onRequestDatabaseCheck)
                        }
                        ExpressiveMaintenanceMode.EMPTY_CARDS -> {
                            Text(stringResource(R.string.expressive_maintenance_empty_cards_description))
                            PrimaryAction(stringResource(R.string.expressive_maintenance_find_empty_cards), onFindEmptyCards)
                        }
                        ExpressiveMaintenanceMode.MEDIA -> MediaOverview(context, onCheckMedia, onRebuildMediaDatabase)
                    }
                is ExpressiveMaintenanceUiState.PickingRestore ->
                    Progress(stringResource(R.string.expressive_maintenance_restore_from_file))
                is ExpressiveMaintenanceUiState.Running ->
                    Progress(
                        announcement = state.progress?.stage ?: state.operation.runningText(context),
                        progress = state.progress,
                        note = if (state.operation.cancellable) null else stringResource(R.string.expressive_maintenance_keep_open),
                    )
                is ExpressiveMaintenanceUiState.ConfirmRestore -> {
                    StatusText(stringResource(R.string.restore_backup_title))
                    Text(state.displayName, style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.restore_backup))
                    Text(stringResource(R.string.expressive_maintenance_restore_safety))
                    state.blockedBy?.let { refusal ->
                        Text(
                            refusal.errorText(context),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                    PrimaryAction(stringResource(R.string.restore), onConfirmRestore)
                    SecondaryAction(stringResource(R.string.dialog_cancel), onBack)
                }
                is ExpressiveMaintenanceUiState.ConfirmDeleteBackup -> {
                    StatusText(stringResource(R.string.expressive_maintenance_delete_backup_title))
                    Text(stringResource(R.string.expressive_maintenance_delete_backup_message, state.entry.displayTime))
                    PrimaryAction(stringResource(R.string.dialog_positive_delete), onConfirmDeleteBackup)
                    SecondaryAction(stringResource(R.string.dialog_cancel), onBack)
                }
                is ExpressiveMaintenanceUiState.ConfirmDatabaseCheck -> {
                    StatusText(title)
                    Text(state.warning)
                    PrimaryAction(stringResource(R.string.integrity_check_continue_anyway), onConfirmDatabaseCheck)
                    SecondaryAction(stringResource(R.string.dialog_cancel), onBack)
                }
                is ExpressiveMaintenanceUiState.EmptyCardsFound -> EmptyCardsResult(state, onDeleteEmptyCards, onDone)
                is ExpressiveMaintenanceUiState.MediaFound ->
                    MediaResult(context, state, onTagMissingMedia, onTrashUnusedMedia, onRestoreMediaTrash, onEmptyMediaTrash, onDone)
                is ExpressiveMaintenanceUiState.Terminal -> TerminalResult(state, onDone)
            }
        }
    }
}

@Composable
private fun BackupOverview(
    state: ExpressiveMaintenanceUiState.Overview,
    onCreateBackup: () -> Unit,
    onPickRestore: () -> Unit,
    onPrepareRestore: (ExpressiveBackupEntry) -> Unit,
    onRequestDelete: (ExpressiveBackupEntry) -> Unit,
    onUpdateLimits: (ExpressiveBackupLimits) -> Unit,
    onMergeQuarantined: () -> Unit,
    onDeleteQuarantined: () -> Unit,
) {
    val context = LocalContext.current
    if (state.quarantinedMediaFiles > 0) {
        QuarantineNotice(state.quarantinedMediaFiles, onMergeQuarantined, onDeleteQuarantined)
        HorizontalDivider()
    }
    PrimaryAction(stringResource(R.string.menu_create_backup), onCreateBackup)
    SecondaryAction(stringResource(R.string.expressive_maintenance_restore_from_file), onPickRestore)
    HorizontalDivider()
    SectionHeading(stringResource(R.string.backup_restore_select_title))
    if (state.backups.isEmpty()) {
        Text(stringResource(R.string.backup_restore_no_backups))
    } else {
        state.backups.forEach { entry ->
            val restoreLabel = stringResource(R.string.expressive_maintenance_restore_backup_action, entry.displayTime)
            val deleteLabel = stringResource(R.string.expressive_maintenance_delete_backup_action, entry.displayTime)
            ListItem(
                headlineContent = { Text(entry.displayTime) },
                supportingContent = {
                    Text(stringResource(R.string.expressive_maintenance_backup_size, Formatter.formatShortFileSize(context, entry.bytes)))
                },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = { onPrepareRestore(entry) },
                            modifier = Modifier.semantics { contentDescription = restoreLabel },
                        ) { Text(stringResource(R.string.restore)) }
                        IconButton(onClick = { onRequestDelete(entry) }) {
                            Icon(painterResource(R.drawable.ic_delete), contentDescription = deleteLabel)
                        }
                    }
                },
            )
        }
    }
    state.limits?.let { limits ->
        HorizontalDivider()
        RetentionEditor(limits, onUpdateLimits)
    }
}

/** Restored media a restore could not merge: it can be merged again, or deleted after confirmation. */
@Composable
private fun QuarantineNotice(
    count: Int,
    onMerge: () -> Unit,
    onDelete: () -> Unit,
) {
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    SectionHeading(stringResource(R.string.expressive_maintenance_quarantine_title))
    Text(pluralStringResource(R.plurals.expressive_maintenance_quarantine_notice, count, count))
    PrimaryAction(stringResource(R.string.expressive_maintenance_quarantine_merge), onMerge)
    SecondaryAction(stringResource(R.string.expressive_maintenance_quarantine_delete)) { confirmDelete = true }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.expressive_maintenance_quarantine_delete)) },
            text = { Text(stringResource(R.string.expressive_maintenance_quarantine_delete_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDelete()
                    },
                ) { Text(stringResource(R.string.dialog_positive_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.dialog_cancel)) } },
        )
    }
}

@Composable
private fun RetentionEditor(
    limits: ExpressiveBackupLimits,
    onSave: (ExpressiveBackupLimits) -> Unit,
) {
    var interval by rememberSaveable(limits) { mutableStateOf(limits.minimumIntervalMinutes.toString()) }
    var daily by rememberSaveable(limits) { mutableStateOf(limits.daily.toString()) }
    var weekly by rememberSaveable(limits) { mutableStateOf(limits.weekly.toString()) }
    var monthly by rememberSaveable(limits) { mutableStateOf(limits.monthly.toString()) }
    SectionHeading(stringResource(R.string.backup_limits_frequency))
    val intervalRange = ExpressiveBackupLimits.MINIMUM_INTERVAL_RANGE
    val retainedRange = ExpressiveBackupLimits.RETAINED_RANGE
    val parsedInterval =
        NumberField(stringResource(R.string.pref__minutes_between_automatic_backups__title), interval, intervalRange) {
            interval = it
        }
    SectionHeading(stringResource(R.string.backup_limits_lifetime))
    val parsedDaily = NumberField(stringResource(R.string.pref__daily_backups_to_keep__title), daily, retainedRange) { daily = it }
    val parsedWeekly = NumberField(stringResource(R.string.pref__weekly_backups_to_keep__title), weekly, retainedRange) { weekly = it }
    val parsedMonthly = NumberField(stringResource(R.string.pref__monthly_backups_to_keep__title), monthly, retainedRange) { monthly = it }
    val updated =
        if (parsedInterval != null && parsedDaily != null && parsedWeekly != null && parsedMonthly != null) {
            ExpressiveBackupLimits(parsedInterval, parsedDaily, parsedWeekly, parsedMonthly)
        } else {
            null
        }
    Button(
        enabled = updated != null && updated != limits,
        onClick = { updated?.let(onSave) },
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) { Text(stringResource(R.string.expressive_maintenance_retention_save)) }
}

/** Returns the parsed value when it is inside [range]. */
@Composable
private fun NumberField(
    label: String,
    value: String,
    range: IntRange,
    onChange: (String) -> Unit,
): Int? {
    val parsed = value.toIntOrNull()?.takeIf { it in range }
    OutlinedTextField(
        value = value,
        onValueChange = { text -> if (text.length <= 5 && text.all(Char::isDigit)) onChange(text) },
        label = { Text(label) },
        singleLine = true,
        isError = parsed == null,
        supportingText =
            if (parsed == null) {
                { Text(stringResource(R.string.expressive_maintenance_retention_invalid, range.first, range.last)) }
            } else {
                null
            },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
    return parsed
}

@Composable
private fun MediaOverview(
    context: Context,
    onCheckMedia: () -> Unit,
    onRebuild: () -> Unit,
) {
    var confirmRebuild by rememberSaveable { mutableStateOf(false) }
    Text(stringResource(R.string.expressive_maintenance_media_description))
    PrimaryAction(remember { TR.mediaCheckCheckMediaAction().toSentenceCase(context, R.string.sentence_check_media) }, onCheckMedia)
    HorizontalDivider()
    SecondaryAction(stringResource(R.string.expressive_maintenance_rebuild_media)) { confirmRebuild = true }
    if (confirmRebuild) {
        AlertDialog(
            onDismissRequest = { confirmRebuild = false },
            title = { Text(stringResource(R.string.expressive_maintenance_rebuild_media)) },
            text = { Text(stringResource(R.string.expressive_maintenance_rebuild_media_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRebuild = false
                        onRebuild()
                    },
                ) { Text(stringResource(R.string.dialog_continue)) }
            },
            dismissButton = { TextButton(onClick = { confirmRebuild = false }) { Text(stringResource(R.string.dialog_cancel)) } },
        )
    }
}

@Composable
private fun EmptyCardsResult(
    state: ExpressiveMaintenanceUiState.EmptyCardsFound,
    onDelete: (Boolean) -> Unit,
    onDone: () -> Unit,
) {
    if (state.cardCount == 0) {
        StatusText(remember { TR.emptyCardsNotFound() })
        PrimaryAction(stringResource(R.string.dialog_ok), onDone)
        return
    }
    var preserveNotes by rememberSaveable { mutableStateOf(true) }
    StatusText(pluralStringResource(R.plurals.expressive_maintenance_empty_cards_found, state.cardCount, state.cardCount))
    if (state.notesAtRisk > 0) {
        Text(pluralStringResource(R.plurals.expressive_maintenance_notes_at_risk, state.notesAtRisk, state.notesAtRisk))
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = preserveNotes, role = Role.Switch, onValueChange = { preserveNotes = it }),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(remember { TR.emptyCardsPreserveNotesCheckbox() }, Modifier.weight(1f))
        Switch(checked = preserveNotes, onCheckedChange = null)
    }
    PrimaryAction(stringResource(R.string.expressive_maintenance_delete_empty_cards)) { onDelete(preserveNotes) }
    SecondaryAction(stringResource(R.string.dialog_cancel), onDone)
    if (state.report.isNotBlank()) {
        HorizontalDivider()
        Text(state.report, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun MediaResult(
    context: Context,
    state: ExpressiveMaintenanceUiState.MediaFound,
    onTagMissing: () -> Unit,
    onTrashUnused: () -> Unit,
    onRestoreTrash: () -> Unit,
    onEmptyTrash: () -> Unit,
    onDone: () -> Unit,
) {
    var confirmTrash by rememberSaveable { mutableStateOf(false) }
    if (state.missingCount > 0) {
        PrimaryAction(remember { TR.mediaCheckAddTag().toSentenceCase(context, R.string.sentence_tag_missing) }, onTagMissing)
    }
    if (state.unusedCount > 0) {
        PrimaryAction(remember { TR.mediaCheckDeleteUnused().toSentenceCase(context, R.string.sentence_check_media_delete_unused) }) {
            confirmTrash = true
        }
    }
    if (state.haveTrash) {
        SecondaryAction(remember { TR.mediaCheckRestoreTrash().toSentenceCase(context, R.string.sentence_restore_deleted) }, onRestoreTrash)
        SecondaryAction(remember { TR.mediaCheckEmptyTrash().toSentenceCase(context, R.string.sentence_empty_trash) }, onEmptyTrash)
    }
    SecondaryAction(stringResource(R.string.dialog_ok), onDone)
    HorizontalDivider()
    Text(state.report, style = MaterialTheme.typography.bodySmall)
    if (confirmTrash) {
        AlertDialog(
            onDismissRequest = { confirmTrash = false },
            text = { Text(remember { TR.mediaCheckDeleteUnusedConfirm() }) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmTrash = false
                        onTrashUnused()
                    },
                ) { Text(stringResource(R.string.dialog_positive_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmTrash = false }) { Text(stringResource(R.string.dialog_cancel)) } },
        )
    }
}

@Composable
private fun TerminalResult(
    state: ExpressiveMaintenanceUiState.Terminal,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val summary =
        if (state.succeeded) {
            state.successText(context)
        } else {
            state.error.errorText(context)
        }
    summary?.let { StatusText(it) }
    if (state.succeeded) {
        state.warningText(context)?.let { warning ->
            Text(
                warning,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
    state.messages.forEach { Text(it) }
    PrimaryAction(stringResource(R.string.dialog_ok), onDone)
}

/**
 * Only [announcement] is a live region, so TalkBack speaks a new stage once instead of every
 * counter step. Counters and backend details are read when focused.
 */
@Composable
private fun Progress(
    announcement: String,
    progress: ExpressiveMaintenanceProgress? = null,
    note: String? = null,
) {
    val total = progress?.total ?: 0
    if (total > 0) {
        val fraction = (progress?.current ?: 0).coerceIn(0, total).toFloat() / total
        CircularProgressIndicator(progress = { fraction })
    } else {
        CircularProgressIndicator()
    }
    StatusText(announcement)
    if (total > 0) {
        Text(
            stringResource(R.string.expressive_maintenance_progress_counter, progress?.current ?: 0, total),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    progress?.detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    note?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
}

@Composable
private fun StatusText(value: String) {
    Text(value, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}

@Composable
private fun SectionHeading(value: String) {
    Text(value, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
}

@Composable
private fun PrimaryAction(
    label: String,
    onClick: () -> Unit,
) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(label) }
}

@Composable
private fun SecondaryAction(
    label: String,
    onClick: () -> Unit,
) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(label) }
}

internal fun ExpressiveMaintenanceMode.title(context: Context): String =
    when (this) {
        ExpressiveMaintenanceMode.BACKUPS -> context.getString(R.string.expressive_maintenance_backups_title)
        ExpressiveMaintenanceMode.DATABASE -> TR.databaseCheckTitle().toSentenceCase(context, R.string.sentence_check_db)
        ExpressiveMaintenanceMode.EMPTY_CARDS -> TR.emptyCardsWindowTitle().toSentenceCase(context, R.string.sentence_empty_cards)
        ExpressiveMaintenanceMode.MEDIA -> TR.mediaCheckWindowTitle().toSentenceCase(context, R.string.sentence_check_media)
    }

private fun ExpressiveMaintenanceOperation.runningText(context: Context): String =
    when (this) {
        ExpressiveMaintenanceOperation.LOAD_OVERVIEW -> context.getString(R.string.expressive_maintenance_loading)
        ExpressiveMaintenanceOperation.CREATE_BACKUP -> context.getString(R.string.expressive_maintenance_creating_backup)
        ExpressiveMaintenanceOperation.DELETE_BACKUP -> context.getString(R.string.expressive_maintenance_deleting_backup)
        ExpressiveMaintenanceOperation.UPDATE_RETENTION -> context.getString(R.string.expressive_maintenance_saving_settings)
        ExpressiveMaintenanceOperation.PREPARE_RESTORE -> context.getString(R.string.expressive_maintenance_validating_restore)
        ExpressiveMaintenanceOperation.RESTORE -> context.getString(R.string.expressive_maintenance_restoring)
        ExpressiveMaintenanceOperation.DATABASE_CHECK -> context.getString(R.string.expressive_maintenance_checking_database)
        ExpressiveMaintenanceOperation.FIND_EMPTY_CARDS -> context.getString(R.string.expressive_maintenance_finding_empty_cards)
        ExpressiveMaintenanceOperation.DELETE_EMPTY_CARDS -> TR.emptyCardsDeleting()
        ExpressiveMaintenanceOperation.CHECK_MEDIA -> context.getString(R.string.check_media_message)
        ExpressiveMaintenanceOperation.TAG_MISSING_MEDIA -> context.getString(R.string.check_media_adding_missing_tag)
        ExpressiveMaintenanceOperation.TRASH_UNUSED_MEDIA,
        ExpressiveMaintenanceOperation.RESTORE_MEDIA_TRASH,
        ExpressiveMaintenanceOperation.EMPTY_MEDIA_TRASH,
        ExpressiveMaintenanceOperation.REBUILD_MEDIA_DATABASE,
        ExpressiveMaintenanceOperation.MERGE_QUARANTINED_MEDIA,
        ExpressiveMaintenanceOperation.DELETE_QUARANTINED_MEDIA,
        -> context.getString(R.string.expressive_maintenance_working)
    }

private fun ExpressiveMaintenanceUiState.Terminal.successText(context: Context): String? {
    val count = affectedCount ?: 0
    return when (operation) {
        ExpressiveMaintenanceOperation.CREATE_BACKUP ->
            if (affectedCount == 0) {
                context.getString(R.string.expressive_maintenance_backup_unchanged)
            } else {
                context.getString(R.string.expressive_maintenance_backup_created)
            }
        ExpressiveMaintenanceOperation.DELETE_BACKUP -> context.getString(R.string.expressive_maintenance_backup_deleted)
        ExpressiveMaintenanceOperation.UPDATE_RETENTION -> context.getString(R.string.expressive_maintenance_settings_saved)
        ExpressiveMaintenanceOperation.RESTORE -> context.getString(R.string.expressive_maintenance_restored)
        ExpressiveMaintenanceOperation.DATABASE_CHECK ->
            if (messages.isEmpty()) {
                TR.databaseCheckRebuilt()
            } else {
                context.resources.getQuantityString(R.plurals.expressive_maintenance_database_problems, messages.size, messages.size)
            }
        ExpressiveMaintenanceOperation.DELETE_EMPTY_CARDS -> TR.emptyCardsDeletedCount(count)
        ExpressiveMaintenanceOperation.TAG_MISSING_MEDIA ->
            context.resources.getQuantityString(R.plurals.expressive_maintenance_notes_tagged, count, count)
        ExpressiveMaintenanceOperation.TRASH_UNUSED_MEDIA ->
            context.resources.getQuantityString(R.plurals.expressive_maintenance_media_trashed, count, count)
        ExpressiveMaintenanceOperation.RESTORE_MEDIA_TRASH -> TR.mediaCheckTrashRestored()
        ExpressiveMaintenanceOperation.EMPTY_MEDIA_TRASH -> TR.mediaCheckTrashEmptied()
        ExpressiveMaintenanceOperation.REBUILD_MEDIA_DATABASE -> context.getString(R.string.expressive_maintenance_rebuild_media_done)
        ExpressiveMaintenanceOperation.MERGE_QUARANTINED_MEDIA ->
            if (count == 0) {
                context.getString(R.string.expressive_maintenance_quarantine_merged)
            } else {
                context.resources.getQuantityString(R.plurals.expressive_maintenance_quarantine_remaining, count, count)
            }
        ExpressiveMaintenanceOperation.DELETE_QUARANTINED_MEDIA -> context.getString(R.string.expressive_maintenance_quarantine_deleted)
        ExpressiveMaintenanceOperation.LOAD_OVERVIEW,
        ExpressiveMaintenanceOperation.PREPARE_RESTORE,
        ExpressiveMaintenanceOperation.FIND_EMPTY_CARDS,
        ExpressiveMaintenanceOperation.CHECK_MEDIA,
        -> null
    }
}

/** A succeeded restore whose media could not all be merged; see [ExpressiveMaintenanceUiState.Terminal]. */
private fun ExpressiveMaintenanceUiState.Terminal.warningText(context: Context): String? {
    if (operation != ExpressiveMaintenanceOperation.RESTORE) return null
    val quarantined = affectedCount ?: 0
    return when {
        error == ExpressiveMaintenanceError.RESTORED_MEDIA_PENDING ->
            context.getString(R.string.expressive_maintenance_restore_media_pending)
        quarantined > 0 ->
            context.resources.getQuantityString(R.plurals.expressive_maintenance_restore_media_quarantined, quarantined, quarantined)
        else -> null
    }
}

private fun ExpressiveMaintenanceError?.errorText(context: Context): String =
    context.getString(
        when (this) {
            ExpressiveMaintenanceError.BACKUP_FAILED -> R.string.expressive_maintenance_error_backup
            ExpressiveMaintenanceError.INVALID_BACKUP -> R.string.backup_invalid_file_error
            ExpressiveMaintenanceError.RESTORE_FAILED -> R.string.expressive_maintenance_error_restore
            ExpressiveMaintenanceError.RESTORE_RECOVERY_PENDING -> R.string.expressive_maintenance_error_recovery_pending
            ExpressiveMaintenanceError.PROVIDER_UNAVAILABLE -> R.string.expressive_maintenance_error_provider
            ExpressiveMaintenanceError.FILE_TOO_LARGE -> R.string.expressive_maintenance_error_too_large
            ExpressiveMaintenanceError.SYNC_ACTIVE -> R.string.expressive_maintenance_error_sync_active
            ExpressiveMaintenanceError.MEDIA_SYNC_ACTIVE -> R.string.expressive_maintenance_error_media_sync_active
            ExpressiveMaintenanceError.INTERRUPTED -> R.string.expressive_maintenance_error_interrupted
            ExpressiveMaintenanceError.RESTORED_MEDIA_PENDING -> R.string.expressive_maintenance_restore_media_pending
            ExpressiveMaintenanceError.PREVIOUS_RESTORE_UNFINISHED -> R.string.expressive_maintenance_error_previous_restore
            ExpressiveMaintenanceError.MAINTENANCE_FAILED, null -> R.string.expressive_maintenance_error_failed
        },
    )
