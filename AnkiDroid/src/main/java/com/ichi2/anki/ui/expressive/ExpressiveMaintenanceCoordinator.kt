// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.ui.expressive

import android.content.Context
import android.content.Intent
import android.system.Os
import android.system.OsConstants
import androidx.annotation.VisibleForTesting
import androidx.core.text.HtmlCompat
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import anki.card_rendering.EmptyCardsReport
import anki.collection.OpChanges
import anki.collection.opChanges
import anki.config.Preferences.BackupLimits
import anki.media.CheckMediaResponse
import com.ichi2.anki.BackupManager
import com.ichi2.anki.CollectionIntegrityStorageCheck
import com.ichi2.anki.CollectionManager
import com.ichi2.anki.CollectionManager.TR
import com.ichi2.anki.ExpressiveRestoreBlockedByMediaSyncException
import com.ichi2.anki.ExpressiveRestoreOutcome
import com.ichi2.anki.ExpressiveRestorePendingException
import com.ichi2.anki.ExpressiveRestoreRecoveryException
import com.ichi2.anki.LocalizedUnambiguousBackupTimeFormatter
import com.ichi2.anki.backend.createDatabaseUsingRustBackend
import com.ichi2.anki.collection.CollectionRepository
import com.ichi2.anki.collection.CollectionWriteResult
import com.ichi2.anki.collection.DefaultCollectionRepository
import com.ichi2.anki.common.coroutines.applicationScope
import com.ichi2.anki.common.time.TimeManager
import com.ichi2.anki.ioDispatcher
import com.ichi2.anki.libanki.CollectionFiles
import com.ichi2.anki.libanki.Storage.collection
import com.ichi2.anki.libanki.importCollectionPackage
import com.ichi2.anki.observability.ChangeManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.ankiweb.rsdroid.BackendException
import net.ankiweb.rsdroid.BackendFactory
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ExpressiveMaintenanceMode {
    BACKUPS,
    DATABASE,
    EMPTY_CARDS,
    MEDIA,
}

enum class ExpressiveMaintenanceOperation {
    LOAD_OVERVIEW,
    CREATE_BACKUP,
    DELETE_BACKUP,
    UPDATE_RETENTION,
    PREPARE_RESTORE,
    RESTORE,
    DATABASE_CHECK,
    FIND_EMPTY_CARDS,
    DELETE_EMPTY_CARDS,
    CHECK_MEDIA,
    TAG_MISSING_MEDIA,
    TRASH_UNUSED_MEDIA,
    RESTORE_MEDIA_TRASH,
    EMPTY_MEDIA_TRASH,
    REBUILD_MEDIA_DATABASE,
    MERGE_QUARANTINED_MEDIA,
    DELETE_QUARANTINED_MEDIA,
}

/** Owns the live collection: collection-backed surfaces leave composition while it runs. */
internal val ExpressiveMaintenanceOperation.ownsCollection: Boolean
    get() = this == ExpressiveMaintenanceOperation.RESTORE || this == ExpressiveMaintenanceOperation.DATABASE_CHECK

/** Must never overlap a collection sync, in either direction. */
internal val ExpressiveMaintenanceOperation.excludesCollectionSync: Boolean
    get() = this == ExpressiveMaintenanceOperation.DATABASE_CHECK || excludesMediaSync

/**
 * Reads or changes the media folder, so it must never overlap a running media sync. A media sync
 * that is only queued (for example waiting for an unmetered network) does not block it.
 */
internal val ExpressiveMaintenanceOperation.excludesMediaSync: Boolean
    get() =
        when (this) {
            ExpressiveMaintenanceOperation.RESTORE,
            ExpressiveMaintenanceOperation.CHECK_MEDIA,
            ExpressiveMaintenanceOperation.TRASH_UNUSED_MEDIA,
            ExpressiveMaintenanceOperation.RESTORE_MEDIA_TRASH,
            ExpressiveMaintenanceOperation.EMPTY_MEDIA_TRASH,
            ExpressiveMaintenanceOperation.REBUILD_MEDIA_DATABASE,
            ExpressiveMaintenanceOperation.MERGE_QUARANTINED_MEDIA,
            -> true
            else -> false
        }

/** A sync must not start while an operation that excludes either sync phase is running. */
internal val ExpressiveMaintenanceOperation.excludesSync: Boolean
    get() = excludesCollectionSync || excludesMediaSync

/** What a sync is doing right now, as far as maintenance admission is concerned. */
internal enum class ExpressiveMaintenanceSyncActivity {
    NONE,

    /** A media sync is running or being cancelled; a merely queued one is [NONE]. */
    MEDIA,

    /** A collection sync owns, or is about to own, the collection. */
    COLLECTION,
}

internal fun ExpressiveSyncUiState.maintenanceSyncActivity(): ExpressiveMaintenanceSyncActivity =
    when {
        isCollectionBlocking -> ExpressiveMaintenanceSyncActivity.COLLECTION
        this is ExpressiveSyncUiState.MediaRunning || this is ExpressiveSyncUiState.CancellingMedia ->
            ExpressiveMaintenanceSyncActivity.MEDIA
        else -> ExpressiveMaintenanceSyncActivity.NONE
    }

/**
 * Whether Home should attempt an interval-aware automatic backup: after it has resumed at least
 * once, and while neither a collection sync nor a collection transfer owns the collection. A
 * media sync never defers it: backups do not touch the media folder.
 */
internal fun shouldAttemptAutomaticBackup(
    resumes: Int,
    syncState: ExpressiveSyncUiState,
    transferBlocksCollection: Boolean,
): Boolean = resumes > 0 && !syncState.isCollectionBlocking && !transferBlocksCollection

/** Read-only or isolated work that may be abandoned; every mutation is non-cancellable once admitted. */
internal val ExpressiveMaintenanceOperation.cancellable: Boolean
    get() =
        this == ExpressiveMaintenanceOperation.LOAD_OVERVIEW ||
            this == ExpressiveMaintenanceOperation.PREPARE_RESTORE ||
            this == ExpressiveMaintenanceOperation.FIND_EMPTY_CARDS

private val ExpressiveMaintenanceOperation.reportsProgress: Boolean
    get() = this == ExpressiveMaintenanceOperation.DATABASE_CHECK || this == ExpressiveMaintenanceOperation.CHECK_MEDIA

/** Leaves no user-visible change: after recreation it is simply reloaded instead of reported as interrupted. */
private val ExpressiveMaintenanceOperation.readOnly: Boolean
    get() =
        this == ExpressiveMaintenanceOperation.LOAD_OVERVIEW ||
            this == ExpressiveMaintenanceOperation.FIND_EMPTY_CARDS ||
            this == ExpressiveMaintenanceOperation.CHECK_MEDIA

enum class ExpressiveMaintenanceError {
    BACKUP_FAILED,
    INVALID_BACKUP,
    RESTORE_FAILED,
    RESTORE_RECOVERY_PENDING,
    PROVIDER_UNAVAILABLE,
    FILE_TOO_LARGE,
    SYNC_ACTIVE,
    MEDIA_SYNC_ACTIVE,
    INTERRUPTED,
    MAINTENANCE_FAILED,

    /** An earlier restore still owns its journal; it finishes before the next open or restore. */
    PREVIOUS_RESTORE_UNFINISHED,

    /** A warning on a succeeded restore: merging restored media resumes before the next restore or open. */
    RESTORED_MEDIA_PENDING,
}

/** Transient backend progress. Only a [stage] change is announced; counters and details update silently. */
data class ExpressiveMaintenanceProgress(
    val stage: String? = null,
    val detail: String? = null,
    val current: Int = 0,
    val total: Int = 0,
)

data class ExpressiveBackupEntry(
    val token: String,
    val displayTime: String,
    val bytes: Long,
)

data class ExpressiveBackupLimits(
    val minimumIntervalMinutes: Int,
    val daily: Int,
    val weekly: Int,
    val monthly: Int,
) {
    init {
        require(minimumIntervalMinutes in MINIMUM_INTERVAL_RANGE)
        require(daily in RETAINED_RANGE && weekly in RETAINED_RANGE && monthly in RETAINED_RANGE)
    }

    companion object {
        /** Mirrors the Classic backup preference bounds. */
        val MINIMUM_INTERVAL_RANGE = 5..99_999
        val RETAINED_RANGE = 0..99_999
    }
}

sealed interface ExpressiveMaintenanceUiState {
    val requestId: Long?
        get() = null

    data object Idle : ExpressiveMaintenanceUiState

    data class Overview(
        val mode: ExpressiveMaintenanceMode,
        val backups: List<ExpressiveBackupEntry> = emptyList(),
        val limits: ExpressiveBackupLimits? = null,
        val databaseWarning: String? = null,
        /** Restored media a restore could not merge, kept aside until it is merged again or deleted. */
        val quarantinedMediaFiles: Int = 0,
    ) : ExpressiveMaintenanceUiState

    data class PickingRestore(
        override val requestId: Long,
        val launchDispatched: Boolean = false,
    ) : ExpressiveMaintenanceUiState

    data class Running(
        override val requestId: Long,
        val operation: ExpressiveMaintenanceOperation,
        val progress: ExpressiveMaintenanceProgress? = null,
    ) : ExpressiveMaintenanceUiState

    /** A validated, isolated copy is staged; nothing in the live collection has changed yet. */
    data class ConfirmRestore(
        override val requestId: Long,
        val displayName: String,
        val generation: Long,
        val externalToken: String? = null,
        /** Why the last confirmation was refused; the validated staging is kept for a retry. */
        val blockedBy: ExpressiveMaintenanceError? = null,
    ) : ExpressiveMaintenanceUiState

    data class ConfirmDeleteBackup(
        override val requestId: Long,
        val entry: ExpressiveBackupEntry,
    ) : ExpressiveMaintenanceUiState

    data class ConfirmDatabaseCheck(
        override val requestId: Long,
        val warning: String,
    ) : ExpressiveMaintenanceUiState

    data class EmptyCardsFound(
        override val requestId: Long,
        val report: String,
        val cardCount: Int,
        val notesAtRisk: Int,
    ) : ExpressiveMaintenanceUiState

    data class MediaFound(
        override val requestId: Long,
        val report: String,
        val missingCount: Int,
        val unusedCount: Int,
        val haveTrash: Boolean,
    ) : ExpressiveMaintenanceUiState

    /**
     * [error] on a succeeded result is a warning about a step that is still pending. For a
     * succeeded restore, [affectedCount] is the number of quarantined media files; for a manual
     * backup, the number of backups written (0 when the latest backup written by this app still holds
     * exactly the current collection);
     * for a quarantine merge, the number of files that remain kept aside.
     */
    data class Terminal(
        override val requestId: Long,
        val operation: ExpressiveMaintenanceOperation,
        val succeeded: Boolean,
        val error: ExpressiveMaintenanceError? = null,
        val messages: List<String> = emptyList(),
        val affectedCount: Int? = null,
    ) : ExpressiveMaintenanceUiState
}

internal val ExpressiveMaintenanceUiState.isCollectionBlocking: Boolean
    get() = this is ExpressiveMaintenanceUiState.Running && operation.ownsCollection

/** A sync must not start while this is running. */
internal val ExpressiveMaintenanceUiState.blocksSync: Boolean
    get() = this is ExpressiveMaintenanceUiState.Running && operation.excludesSync

internal sealed interface ExpressiveMaintenanceLaunch {
    data class OpenRestoreDocument(
        val requestId: Long,
        val intent: Intent,
    ) : ExpressiveMaintenanceLaunch
}

internal interface ExpressiveMaintenanceGateway {
    suspend fun backupOverview(): Pair<List<ExpressiveBackupEntry>, ExpressiveBackupLimits>

    /**
     * Serialized with every other backup; the completion wait happens outside the collection queue.
     * Returns whether a new backup file was written. A [force]d backup that the backend skips,
     * because nothing changed since its last attempt, still writes an explicit copy unless the
     * backup this process last wrote holds exactly the current collection and still exists.
     *
     * @throws MediaSyncActiveException instead of writing that copy while a media sync runs: the
     * copy closes the collection, which would abort the sync and clear undo
     */
    suspend fun createBackup(force: Boolean): Boolean

    suspend fun deleteBackup(token: String)

    suspend fun updateBackupLimits(limits: ExpressiveBackupLimits)

    suspend fun resolveBackup(token: String): File

    /** Imports [source] into isolated staging and validates it; returns the staging generation. */
    suspend fun prepareRestore(source: File): Long

    /**
     * Guaranteed safety backup, then journaled activation of [generation]; invalidates once per attempt.
     *
     * @throws ExpressiveRestoreBlockedByMediaSyncException before anything changes; [generation] stays owned
     */
    suspend fun commitRestore(generation: Long): ExpressiveRestoreOutcome

    /** Discards staging owned by [generation]; `null` discards only staging no live preparation owns. */
    suspend fun discardPreparedRestore(generation: Long?)

    suspend fun databaseCheckWarning(): String?

    suspend fun checkDatabase(): List<String>

    suspend fun findEmptyCards(): EmptyCardsReport

    suspend fun deleteEmptyCards(
        report: EmptyCardsReport,
        preserveNotes: Boolean,
    ): Int

    suspend fun checkMedia(): CheckMediaResponse

    suspend fun tagMissingMedia(report: CheckMediaResponse): Int

    suspend fun trashUnusedMedia(report: CheckMediaResponse): Int

    suspend fun restoreMediaTrash()

    suspend fun emptyMediaTrash()

    suspend fun rebuildMediaDatabase()

    /** Restored media files a restore could not merge and kept aside. */
    suspend fun quarantinedMediaCount(): Int

    /** Retries merging the kept restored media; returns how many files remain kept aside. */
    suspend fun mergeQuarantinedMedia(): Int

    /** Permanently deletes the kept restored media. */
    suspend fun deleteQuarantinedMedia()

    /** Latest backend progress for [operation], or `null`. Must not block on the collection queue. */
    fun progress(operation: ExpressiveMaintenanceOperation): ExpressiveMaintenanceProgress?
}

private val broadMaintenanceChanges: OpChanges =
    opChanges {
        card = true
        note = true
        deck = true
        studyQueues = true
        browserTable = true
        noteText = true
        config = true
        notetype = true
    }

/** Process-wide lock: at most one backup (automatic, manual, or restore safety) runs at a time. */
private val backupMutex = Mutex()

/** A backup this process wrote, with the collection modification stamp its snapshot holds. */
private data class WrittenBackup(
    val file: File,
    val collectionModified: Long,
)

/** The newest backup this process wrote. Guarded by [backupMutex]. */
private var lastWrittenBackup: WrittenBackup? = null

/** Matches the backend's backup names: `backup-YYYY-MM-DD-HH.MM.SS.colpkg`, in local time. */
private const val GUARANTEED_BACKUP_TIME_FORMAT = "yyyy-MM-dd-HH.mm.ss"
private const val GUARANTEED_BACKUP_NAME_ATTEMPTS = 60

internal class SafetyBackupException(
    cause: Throwable,
) : IOException("The safety backup failed; the collection was not changed", cause)

internal class InvalidBackupException : IOException("Invalid backup")

/** A media operation was refused inside the collection queue because a media sync is running. */
internal class MediaSyncActiveException : IOException("A media sync is running")

internal class DefaultExpressiveMaintenanceGateway(
    private val context: Context,
    private val repository: CollectionRepository = DefaultCollectionRepository,
) : ExpressiveMaintenanceGateway {
    override suspend fun backupOverview(): Pair<List<ExpressiveBackupEntry>, ExpressiveBackupLimits> {
        val formatter = LocalizedUnambiguousBackupTimeFormatter()
        return repository.read {
            val entries =
                BackupManager
                    .getBackups(colDb)
                    .reversed()
                    .map { ExpressiveBackupEntry(it.name, formatter.getTimeOfBackupAsText(it), it.length()) }
            entries to backend.getPreferences().backups.toUi()
        }
    }

    override suspend fun createBackup(force: Boolean): Boolean =
        backupMutex.withLock {
            val (folder, created) = startBackendBackup(force)
            if (created || !force) return@withLock created
            // Unchanged since the backend's last attempt. Nothing needs writing only if a backup this
            // process wrote holds exactly the current collection and still exists; otherwise that
            // attempt failed, its file was deleted, or it was not made through this gateway.
            if (lastBackupHoldsCollection(File(folder))) return@withLock false
            if (withContext(Dispatchers.IO) { CollectionManager.isMediaSyncRunning(CollectionManager.getBackend()) }) {
                throw MediaSyncActiveException()
            }
            writeExplicitBackup(File(folder))
            true
        }

    /**
     * A new file that holds the current collection, or an exception. No shortcut here: the
     * collection is about to be replaced. The caller has already refused a running media sync.
     */
    private suspend fun createSafetyBackup() {
        backupMutex.withLock {
            val (folder, created) = startBackendBackup(force = true)
            if (!created) writeExplicitBackup(File(folder))
        }
    }

    /**
     * Starts a backend backup and awaits it; returns the backup folder and whether a backup was
     * started. A written backup is recorded with the modification stamp of the snapshot it holds.
     */
    private suspend fun startBackendBackup(force: Boolean): Pair<String, Boolean> {
        val collectionFile = repository.read { colDb }
        val before = withContext(Dispatchers.IO) { backupVersions(collectionFile) }
        val (folder, created, modified) =
            repository.read {
                val path = BackupManager.getBackupDirectoryFromCollection(colDb)
                // Read in the same queue task as the backend's snapshot, so nothing can change in between.
                val snapshotModified = mod
                Triple(path, createBackup(path, force, waitForCompletion = false), snapshotModified)
            }
        try {
            withContext(Dispatchers.IO) { CollectionManager.getBackend().awaitBackupCompletion() }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            // Only a backup started by this call can fail it; otherwise the error is an earlier attempt's.
            if (created) throw failure
            Timber.w(failure, "An earlier backup attempt had failed")
        }
        if (created) {
            val written = withContext(Dispatchers.IO) { backupVersions(collectionFile).entries.filter { before[it.key] != it.value } }
            written.maxByOrNull { it.value }?.let { (name, _) ->
                lastWrittenBackup = WrittenBackup(File(folder, name), modified)
            }
        }
        return folder to created
    }

    /** Backup file names with their modification times; a rewritten same-named file counts as new. */
    private fun backupVersions(collectionFile: File): Map<String, Long> =
        BackupManager.getBackups(collectionFile).associate { it.name to it.lastModified() }

    private suspend fun lastBackupHoldsCollection(folder: File): Boolean {
        val last = lastWrittenBackup ?: return false
        val modified = repository.read { mod }
        if (last.collectionModified != modified) return false
        return withContext(Dispatchers.IO) {
            last.file.parentFile?.canonicalFile == folder.canonicalFile && last.file.isFile && last.file.length() > 0L
        }
    }

    /**
     * Named like a backend backup, so it is listed, restorable, and thinned like the others. The
     * export closes and reopens the collection, which clears the backend's undo history.
     */
    private suspend fun writeExplicitBackup(folder: File) {
        val target =
            withContext(Dispatchers.IO) {
                if (!folder.isDirectory && !folder.mkdirs()) throw IOException("The backup folder is unavailable")
                val format = SimpleDateFormat(GUARANTEED_BACKUP_TIME_FORMAT, Locale.US)
                val now = TimeManager.time.intTimeMS()
                (0 until GUARANTEED_BACKUP_NAME_ATTEMPTS)
                    .asSequence()
                    .map { offset -> File(folder, "backup-${format.format(Date(now + offset * 1_000L))}.colpkg") }
                    .firstOrNull { !it.exists() }
                    ?: throw IOException("No free backup name")
            }
        // The export neither changes the collection nor its stamp; it only closes and reopens it.
        val modified = repository.read { mod }
        CollectionManager.exportCollectionPackageGuaranteed(target.absolutePath, includeMedia = false, legacy = false)
        withContext(Dispatchers.IO) {
            if (!target.isFile || target.length() <= 0L) throw IOException("The backup was not written")
        }
        lastWrittenBackup = WrittenBackup(target, modified)
    }

    override suspend fun deleteBackup(token: String) {
        backupMutex.withLock {
            val database = repository.read { colDb }
            withContext(Dispatchers.IO) {
                val file = BackupManager.resolveBackupToken(database, token) ?: throw InvalidBackupException()
                if (!file.delete() && file.exists()) throw IOException("Backup could not be deleted")
            }
        }
    }

    override suspend fun updateBackupLimits(limits: ExpressiveBackupLimits) {
        repository.write(this) {
            val preferences = backend.getPreferences()
            val updated =
                preferences.backups
                    .toBuilder()
                    .setMinimumIntervalMins(limits.minimumIntervalMinutes)
                    .setDaily(limits.daily)
                    .setWeekly(limits.weekly)
                    .setMonthly(limits.monthly)
                    .build()
            backend.setPreferences(preferences.toBuilder().setBackups(updated).build())
            CollectionWriteResult(Unit, opChanges { config = true })
        }
    }

    override suspend fun resolveBackup(token: String): File {
        val database = repository.read { colDb }
        return withContext(Dispatchers.IO) { BackupManager.resolveBackupToken(database, token) ?: throw InvalidBackupException() }
    }

    override suspend fun prepareRestore(source: File): Long = ExpressiveRestoreStaging.prepare(source)

    override suspend fun commitRestore(generation: Long): ExpressiveRestoreOutcome {
        // Checked first so a media sync is never aborted by the safety copy; activation rechecks inside the collection queue.
        if (withContext(Dispatchers.IO) { CollectionManager.isMediaSyncRunning(CollectionManager.getBackend()) }) {
            throw ExpressiveRestoreBlockedByMediaSyncException()
        }
        try {
            createSafetyBackup()
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            throw SafetyBackupException(failure)
        }
        try {
            return ExpressiveRestoreStaging.commit(generation)
        } finally {
            // Success replaces the collection; a rolled-back failure still closed and reopened it.
            withContext(Dispatchers.Main + NonCancellable) {
                ChangeManager.notifySubscribers(broadMaintenanceChanges, this@DefaultExpressiveMaintenanceGateway)
            }
        }
    }

    override suspend fun discardPreparedRestore(generation: Long?) = ExpressiveRestoreStaging.discard(generation)

    override suspend fun databaseCheckWarning(): String? =
        withContext(Dispatchers.IO) {
            val check = CollectionIntegrityStorageCheck.createInstance(context)
            if (check.shouldWarnOnIntegrityCheck()) check.getWarningDetails(context) else null
        }

    override suspend fun checkDatabase(): List<String> =
        repository.write(this) {
            CollectionWriteResult(fixIntegrity(), broadMaintenanceChanges)
        }

    override suspend fun findEmptyCards(): EmptyCardsReport = repository.read { getEmptyCards() }

    override suspend fun deleteEmptyCards(
        report: EmptyCardsReport,
        preserveNotes: Boolean,
    ): Int =
        repository.write(this) {
            val result = removeCardsAndOrphanedNotes(emptyCardIdsToDelete(report, preserveNotes))
            CollectionWriteResult(result.count, result.changes)
        }

    override suspend fun checkMedia(): CheckMediaResponse =
        repository.read {
            requireNoMediaSync()
            media.check()
        }

    override suspend fun tagMissingMedia(report: CheckMediaResponse): Int {
        val tag = TR.mediaCheckMissingMediaTag()
        return repository.write(this) {
            val result = tags.bulkAdd(report.missingMediaNotesList, tag)
            CollectionWriteResult(result.count, result.changes)
        }
    }

    override suspend fun trashUnusedMedia(report: CheckMediaResponse): Int =
        repository.read {
            requireNoMediaSync()
            media.trashFiles(report.unusedList)
            report.unusedList.size
        }

    override suspend fun restoreMediaTrash() =
        repository.read {
            requireNoMediaSync()
            media.restoreTrash()
        }

    override suspend fun emptyMediaTrash() =
        repository.read {
            requireNoMediaSync()
            media.emptyTrash()
        }

    override suspend fun rebuildMediaDatabase() =
        repository.read {
            requireNoMediaSync()
            media.forceResync()
        }

    override suspend fun quarantinedMediaCount(): Int =
        withContext(Dispatchers.IO) { CollectionManager.expressiveRestoreQuarantinedMediaCount() }

    override suspend fun mergeQuarantinedMedia(): Int = CollectionManager.mergeExpressiveRestoreQuarantine()

    override suspend fun deleteQuarantinedMedia() = CollectionManager.deleteExpressiveRestoreQuarantine()

    /** Rechecked inside the collection queue: admission only sees the Expressive sync state. */
    private fun com.ichi2.anki.libanki.Collection.requireNoMediaSync() {
        if (CollectionManager.isMediaSyncRunning(backend)) throw MediaSyncActiveException()
    }

    override fun progress(operation: ExpressiveMaintenanceOperation): ExpressiveMaintenanceProgress? {
        val progress = CollectionManager.getBackend().latestProgress()
        return when {
            operation == ExpressiveMaintenanceOperation.DATABASE_CHECK && progress.hasDatabaseCheck() ->
                progress.databaseCheck.let { check ->
                    ExpressiveMaintenanceProgress(
                        stage = check.stage.takeIf(String::isNotBlank),
                        current = check.stageCurrent,
                        total = check.stageTotal,
                    )
                }
            operation == ExpressiveMaintenanceOperation.CHECK_MEDIA && progress.hasMediaCheck() ->
                ExpressiveMaintenanceProgress(detail = progress.mediaCheck.takeIf(String::isNotBlank))
            else -> null
        }?.takeIf { it.stage != null || it.detail != null || it.total > 0 }
    }

    private fun BackupLimits.toUi() =
        ExpressiveBackupLimits(
            minimumIntervalMinutes = minimumIntervalMins.coerceIn(ExpressiveBackupLimits.MINIMUM_INTERVAL_RANGE),
            daily = daily.coerceIn(ExpressiveBackupLimits.RETAINED_RANGE),
            weekly = weekly.coerceIn(ExpressiveBackupLimits.RETAINED_RANGE),
            monthly = monthly.coerceIn(ExpressiveBackupLimits.RETAINED_RANGE),
        )
}

/** Classic parity: when notes are preserved, the first card of every note that would vanish is kept. */
internal fun emptyCardIdsToDelete(
    report: EmptyCardsReport,
    preserveNotes: Boolean,
): List<Long> =
    report.notesList.flatMap { note ->
        if (preserveNotes && note.willDeleteNote) note.cardIdsList.drop(1) else note.cardIdsList
    }

/**
 * Process-wide owner of the single restore staging directory. Every generation is imported and
 * validated in isolation; commit, discard, and preparation are serialized, and a stale owner can
 * never remove staging that a newer preparation created. Before staging is reused or removed, a
 * committed restore that still owns staged media finishes merging it. Ownership lives only in
 * process memory, so `discard(null)` doubles as the sweep for staging a previous owner left behind.
 */
internal object ExpressiveRestoreStaging {
    private const val STAGING_SUFFIX = ".expressive-restore-stage"
    private var mutex = Mutex()
    private var generationCounter = 0L
    private var preparedGeneration: Long? = null

    /**
     * Test-only: forgets ownership and any lock a coroutine of an earlier test left behind, since
     * tests share this process-wide object but not their dispatchers.
     */
    @VisibleForTesting
    internal fun resetForTesting() {
        mutex = Mutex()
        preparedGeneration = null
    }

    suspend fun prepare(
        source: File,
        staging: File = CollectionManager.expressiveRestoreStagingDirectory(),
    ): Long =
        mutex.withLock {
            preparedGeneration = null
            if (!CollectionManager.settleExpressiveRestore()) throw ExpressiveRestorePendingException()
            withContext(Dispatchers.IO) { prepareBlocking(source, staging) }
            (++generationCounter).also { preparedGeneration = it }
        }

    suspend fun commit(
        generation: Long,
        staging: File = CollectionManager.expressiveRestoreStagingDirectory(),
    ): ExpressiveRestoreOutcome =
        mutex.withLock {
            if (preparedGeneration != generation) throw InvalidBackupException()
            preparedGeneration = null
            try {
                CollectionManager.activatePreparedExpressiveRestore()
            } catch (failure: ExpressiveRestoreBlockedByMediaSyncException) {
                // Nothing changed: the validated staging stays owned so the user can retry.
                preparedGeneration = generation
                throw failure
            } catch (failure: ExpressiveRestoreRecoveryException) {
                // The retained journal owns the staging directory until recovery completes.
                throw failure
            } catch (failure: Exception) {
                runCatching { withContext(Dispatchers.IO) { delete(staging) } }.exceptionOrNull()?.let(failure::addSuppressed)
                throw failure
            }
        }

    suspend fun discard(
        generation: Long?,
        staging: File = CollectionManager.expressiveRestoreStagingDirectory(),
    ) {
        // Skipping is always safe when nothing exists: the usual startup sweep then takes neither
        // this lock nor the collection queue. Staging created meanwhile belongs to its preparation.
        if (generation == null && !withContext(Dispatchers.IO) { CollectionManager.hasExpressiveRestoreArtifacts() }) return
        mutex.withLock {
            val owner = preparedGeneration
            if (owner != null && owner != generation) return@withLock
            preparedGeneration = null
            // A retained journal owns the staging directory until it settles; a later settle retries.
            val settled =
                try {
                    CollectionManager.settleExpressiveRestore()
                } catch (_: ExpressiveRestoreBlockedByMediaSyncException) {
                    false
                }
            if (!settled) return@withLock
            withContext(Dispatchers.IO) { delete(staging) }
        }
    }

    /** The backend importer integrity-checks the package; a separate backend then opens the copy. */
    private fun prepareBlocking(
        source: File,
        staging: File,
    ) {
        if (!source.isFile || !source.canRead() || source.length() <= 0L) throw InvalidBackupException()
        delete(staging)
        if (!staging.mkdir()) throw IOException("Restore staging directory could not be created")
        try {
            val backend = BackendFactory.getBackend()
            var stagedCollection: com.ichi2.anki.libanki.Collection? = null
            try {
                importCollectionPackage(backend, CollectionFiles.FolderBasedCollection(staging), source.canonicalPath)
                if (!File(staging, "collection.anki2").let { it.isFile && it.length() > 0L }) throw InvalidBackupException()
                stagedCollection =
                    collection(
                        collectionFiles = CollectionFiles.FolderBasedCollection(staging),
                        databaseBuilder = { createDatabaseUsingRustBackend(it) },
                        backend = backend,
                    )
            } finally {
                runCatching { stagedCollection?.close() }
                runCatching { backend.close() }
            }
        } catch (failure: Exception) {
            runCatching { delete(staging) }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    private fun delete(staging: File) {
        if (!staging.name.endsWith(STAGING_SUFFIX)) throw IOException("Restore staging path is not app-owned")
        deleteWithoutFollowingLinks(staging)
    }

    private fun deleteWithoutFollowingLinks(file: File) {
        val symbolic = runCatching { OsConstants.S_ISLNK(Os.lstat(file.absolutePath).st_mode) }.getOrDefault(false)
        if (!symbolic && !file.exists()) return
        if (file.isDirectory && !symbolic) file.listFiles().orEmpty().forEach(::deleteWithoutFollowingLinks)
        if (!file.delete() && (symbolic || file.exists())) throw IOException("Restore staging could not be cleaned")
    }
}

internal class ExpressiveMaintenanceCoordinatorViewModel(
    private val savedStateHandle: SavedStateHandle,
    private val gateway: ExpressiveMaintenanceGateway,
    context: Context,
    private val automaticBackupDelayMillis: Long = AUTOMATIC_BACKUP_DELAY_MILLIS,
    private val progressPollMillis: Long = PROGRESS_POLL_MILLIS,
    /** Outlives the ViewModel: releases what a cleared coordinator still owned. */
    private val cleanupScope: CoroutineScope = applicationScope,
) : ViewModel() {
    /** Retained work can run for minutes; it must never keep an Activity alive. */
    private val appContext: Context = context.applicationContext
    private val restoredCleanupToken =
        savedStateHandle.get<String>(PENDING_IMPORT_TOKEN_KEY)?.takeIf(ExpressiveTransferFiles::isOpaqueToken)
    private val initial = savedStateHandle.get<String>(STATE_KEY)?.let(MaintenanceStateCodec::decode).toSafeRestoredState()
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<ExpressiveMaintenanceUiState> = _state.asStateFlow()
    private val _launches = Channel<ExpressiveMaintenanceLaunch>(Channel.BUFFERED)
    val launches: Flow<ExpressiveMaintenanceLaunch> = _launches.receiveAsFlow()
    private val pendingRestoreResults = ArrayDeque<Long>()
    private var operation: Job? = null
    private var automaticBackup: Job? = null
    private var interruptedCleanupStarted = false
    private var emptyCardsReport: EmptyCardsReport? = null
    private var mediaReport: CheckMediaResponse? = null

    /** Supplied by the host Activity: admission rechecks durable sync ownership synchronously. */
    var syncActivity: () -> ExpressiveMaintenanceSyncActivity = { ExpressiveMaintenanceSyncActivity.NONE }

    internal val automaticBackupActive: Boolean
        get() = automaticBackup?.isActive == true

    init {
        savedStateHandle.remove<String>(LEGACY_STATE_KEY)
        persist(initial)
    }

    /**
     * Interval-aware automatic backup; never enters the visible state and never blocks the
     * collection. Only a collection sync defers it: backups never touch the media folder.
     */
    fun maybeStartAutomaticBackup() {
        if (automaticBackupActive) return
        automaticBackup =
            viewModelScope.launch {
                if (automaticBackupDelayMillis > 0) delay(automaticBackupDelayMillis)
                // Rechecked after the delay: a sync or a collection-owning operation may have started meanwhile.
                if (_state.value.isCollectionBlocking || syncActivity() == ExpressiveMaintenanceSyncActivity.COLLECTION) {
                    return@launch
                }
                try {
                    gateway.createBackup(force = false)
                } catch (failure: CancellationException) {
                    throw failure
                } catch (failure: Exception) {
                    Timber.w(failure, "Automatic backup failed")
                }
            }
    }

    /** Opens [mode]'s overview. Refused only while an admitted operation is still running. */
    fun open(mode: ExpressiveMaintenanceMode): Boolean {
        val current = _state.value
        if (current is ExpressiveMaintenanceUiState.Running) return false
        discardTransient(current)
        loadOverview(mode)
        return true
    }

    fun returnToOverview(mode: ExpressiveMaintenanceMode) {
        open(mode)
    }

    /** System/top-bar back. Returns true when the Maintenance route should be popped. */
    fun back(mode: ExpressiveMaintenanceMode): Boolean =
        when (val current = _state.value) {
            is ExpressiveMaintenanceUiState.Running ->
                when {
                    // Admitted mutations own their outcome; back never abandons them.
                    !current.operation.cancellable -> false
                    current.operation == ExpressiveMaintenanceOperation.LOAD_OVERVIEW -> {
                        abandonRunningOperation()
                        closeSession()
                        true
                    }
                    else -> {
                        abandonRunningOperation()
                        loadOverview(mode)
                        false
                    }
                }
            ExpressiveMaintenanceUiState.Idle,
            is ExpressiveMaintenanceUiState.Overview,
            -> {
                closeSession()
                true
            }
            is ExpressiveMaintenanceUiState.Terminal ->
                if (current.operation == ExpressiveMaintenanceOperation.LOAD_OVERVIEW) {
                    closeSession()
                    true
                } else {
                    open(mode)
                    false
                }
            else -> {
                open(mode)
                false
            }
        }

    fun createBackup() {
        val requestId = nextId()
        runOperation(
            ExpressiveMaintenanceOperation.CREATE_BACKUP,
            requestId,
            onSuccess = { written ->
                ExpressiveMaintenanceUiState.Terminal(
                    requestId,
                    ExpressiveMaintenanceOperation.CREATE_BACKUP,
                    true,
                    affectedCount = if (written) 1 else 0,
                )
            },
        ) { gateway.createBackup(force = true) }
    }

    /** Retries merging restored media that a restore kept aside. */
    fun mergeQuarantinedMedia() {
        val current = _state.value as? ExpressiveMaintenanceUiState.Overview ?: return
        if (current.quarantinedMediaFiles <= 0) return
        runOperation(ExpressiveMaintenanceOperation.MERGE_QUARANTINED_MEDIA) { gateway.mergeQuarantinedMedia() }
    }

    fun deleteQuarantinedMedia() {
        val current = _state.value as? ExpressiveMaintenanceUiState.Overview ?: return
        if (current.quarantinedMediaFiles <= 0) return
        runOperation(ExpressiveMaintenanceOperation.DELETE_QUARANTINED_MEDIA) { gateway.deleteQuarantinedMedia() }
    }

    fun requestDeleteBackup(entry: ExpressiveBackupEntry) {
        if (_state.value !is ExpressiveMaintenanceUiState.Overview) return
        set(ExpressiveMaintenanceUiState.ConfirmDeleteBackup(nextId(), entry))
    }

    fun confirmDeleteBackup() {
        val current = _state.value as? ExpressiveMaintenanceUiState.ConfirmDeleteBackup ?: return
        runOperation(ExpressiveMaintenanceOperation.DELETE_BACKUP, current.requestId) { gateway.deleteBackup(current.entry.token) }
    }

    fun updateBackupLimits(limits: ExpressiveBackupLimits) {
        if (_state.value !is ExpressiveMaintenanceUiState.Overview) return
        runOperation(ExpressiveMaintenanceOperation.UPDATE_RETENTION) { gateway.updateBackupLimits(limits) }
    }

    fun startRestorePicker() {
        if (_state.value !is ExpressiveMaintenanceUiState.Overview) return
        val requestId = nextId()
        set(ExpressiveMaintenanceUiState.PickingRestore(requestId))
        _launches.trySend(ExpressiveMaintenanceLaunch.OpenRestoreDocument(requestId, restoreDocumentIntent()))
    }

    /** Called immediately before the ActivityResult launch so a late result has an exact owner. */
    fun markRestoreLaunchDispatched(requestId: Long): Boolean {
        val current = _state.value as? ExpressiveMaintenanceUiState.PickingRestore ?: return false
        if (current.requestId != requestId || current.launchDispatched) return false
        pendingRestoreResults.addLast(requestId)
        set(current.copy(launchDispatched = true))
        return true
    }

    fun consumeRestoreResultRequestId(): Long? = pendingRestoreResults.removeFirstOrNull()

    fun restorePickerCancelled(requestId: Long) {
        val current = _state.value as? ExpressiveMaintenanceUiState.PickingRestore ?: return
        if (current.requestId == requestId) loadOverview(ExpressiveMaintenanceMode.BACKUPS)
    }

    fun restorePickerLaunchFailed(requestId: Long) {
        pendingRestoreResults.remove(requestId)
        val current = _state.value as? ExpressiveMaintenanceUiState.PickingRestore ?: return
        if (current.requestId == requestId) {
            set(
                ExpressiveMaintenanceUiState.Terminal(
                    requestId,
                    ExpressiveMaintenanceOperation.PREPARE_RESTORE,
                    false,
                    ExpressiveMaintenanceError.PROVIDER_UNAVAILABLE,
                ),
            )
        }
    }

    /** Stages the picked document immediately into app-owned storage, then validates it in isolation. */
    fun stageExternalRestore(
        requestId: Long,
        intent: Intent,
    ) {
        val current = _state.value as? ExpressiveMaintenanceUiState.PickingRestore ?: return
        if (current.requestId != requestId) return
        if (ExpressiveTransferFiles.extractSingleUri(intent)?.scheme != "content") {
            set(
                ExpressiveMaintenanceUiState.Terminal(
                    requestId,
                    ExpressiveMaintenanceOperation.PREPARE_RESTORE,
                    false,
                    ExpressiveMaintenanceError.PROVIDER_UNAVAILABLE,
                ),
            )
            return
        }
        set(ExpressiveMaintenanceUiState.Running(requestId, ExpressiveMaintenanceOperation.PREPARE_RESTORE))
        operation =
            viewModelScope.launch {
                var token: String? = null
                try {
                    // The grant belongs to the app's UID, so the application context's resolver can read it.
                    val staged = ExpressiveTransferFiles.stageImport(appContext, intent).getOrThrow()
                    token = staged.cacheToken
                    savedStateHandle[PENDING_IMPORT_TOKEN_KEY] = staged.cacheToken
                    if (staged.format != ExpressiveImportFormat.COLPKG) throw InvalidBackupException()
                    val source =
                        withContext(ioDispatcher) { ExpressiveTransferFiles.ownedImportFile(appContext, staged.cacheToken) }
                            ?: throw InvalidBackupException()
                    val generation = gateway.prepareRestore(source)
                    val confirmed =
                        completeIfRunning(requestId) {
                            ExpressiveMaintenanceUiState.ConfirmRestore(requestId, staged.displayName, generation, staged.cacheToken)
                        }
                    if (!confirmed) withContext(NonCancellable) { discardStaged(token, generation) }
                } catch (_: CancellationException) {
                    withContext(NonCancellable) {
                        // A blocking import may have finished staging before cancellation was observed.
                        discardStaged(token, generation = null)
                        runCatching { gateway.discardPreparedRestore(null) }
                        completeIfRunning(requestId) { interrupted(requestId, ExpressiveMaintenanceOperation.PREPARE_RESTORE) }
                    }
                } catch (failure: Exception) {
                    Timber.w(failure, "External restore could not be prepared")
                    withContext(NonCancellable) { discardStaged(token, generation = null) }
                    completeIfRunning(requestId) { failed(requestId, ExpressiveMaintenanceOperation.PREPARE_RESTORE, failure) }
                }
            }
    }

    fun prepareLocalRestore(entry: ExpressiveBackupEntry) {
        if (_state.value !is ExpressiveMaintenanceUiState.Overview) return
        val requestId = nextId()
        set(ExpressiveMaintenanceUiState.Running(requestId, ExpressiveMaintenanceOperation.PREPARE_RESTORE))
        operation =
            viewModelScope.launch {
                try {
                    val generation = gateway.prepareRestore(gateway.resolveBackup(entry.token))
                    val confirmed =
                        completeIfRunning(requestId) {
                            ExpressiveMaintenanceUiState.ConfirmRestore(requestId, entry.displayTime, generation)
                        }
                    if (!confirmed) withContext(NonCancellable) { runCatching { gateway.discardPreparedRestore(generation) } }
                } catch (_: CancellationException) {
                    withContext(NonCancellable) {
                        runCatching { gateway.discardPreparedRestore(null) }
                        completeIfRunning(requestId) { interrupted(requestId, ExpressiveMaintenanceOperation.PREPARE_RESTORE) }
                    }
                } catch (failure: Exception) {
                    Timber.w(failure, "Local restore could not be prepared")
                    completeIfRunning(requestId) { failed(requestId, ExpressiveMaintenanceOperation.PREPARE_RESTORE, failure) }
                }
            }
    }

    /** Non-cancellable once admitted: safety backup, activation, invalidation, and terminal state. */
    fun confirmRestore() {
        val current = _state.value as? ExpressiveMaintenanceUiState.ConfirmRestore ?: return
        syncRefusal(ExpressiveMaintenanceOperation.RESTORE)?.let { refusal ->
            // The validated staging is kept: the user can retry once sync has finished.
            set(current.copy(blockedBy = refusal))
            return
        }
        set(ExpressiveMaintenanceUiState.Running(current.requestId, ExpressiveMaintenanceOperation.RESTORE))
        operation =
            viewModelScope.launch {
                try {
                    withContext(NonCancellable) {
                        val outcome = gateway.commitRestore(current.generation)
                        deletePendingImport(current.externalToken)
                        completeIfRunning(current.requestId) {
                            ExpressiveMaintenanceUiState.Terminal(
                                requestId = current.requestId,
                                operation = ExpressiveMaintenanceOperation.RESTORE,
                                succeeded = true,
                                error = if (outcome.mediaPending) ExpressiveMaintenanceError.RESTORED_MEDIA_PENDING else null,
                                affectedCount = outcome.quarantinedMediaFiles.takeIf { it > 0 },
                            )
                        }
                    }
                } catch (_: CancellationException) {
                    withContext(NonCancellable) {
                        completeIfRunning(current.requestId) { interrupted(current.requestId, ExpressiveMaintenanceOperation.RESTORE) }
                    }
                } catch (_: ExpressiveRestoreBlockedByMediaSyncException) {
                    // Refused before anything changed; the staging generation is still owned.
                    completeIfRunning(current.requestId) { current.copy(blockedBy = ExpressiveMaintenanceError.MEDIA_SYNC_ACTIVE) }
                } catch (failure: Exception) {
                    Timber.w(failure, "Restore failed")
                    withContext(NonCancellable) {
                        discardStaged(current.externalToken, current.generation)
                        completeIfRunning(current.requestId) {
                            failed(current.requestId, ExpressiveMaintenanceOperation.RESTORE, failure)
                        }
                    }
                }
            }
    }

    fun requestDatabaseCheck() {
        val current = _state.value as? ExpressiveMaintenanceUiState.Overview ?: return
        val requestId = nextId()
        val warning = current.databaseWarning
        if (warning != null) {
            set(ExpressiveMaintenanceUiState.ConfirmDatabaseCheck(requestId, warning))
        } else {
            runDatabaseCheck(requestId)
        }
    }

    fun confirmDatabaseCheck() {
        val current = _state.value as? ExpressiveMaintenanceUiState.ConfirmDatabaseCheck ?: return
        runDatabaseCheck(current.requestId)
    }

    private fun runDatabaseCheck(requestId: Long) {
        runOperation(
            ExpressiveMaintenanceOperation.DATABASE_CHECK,
            requestId,
            onSuccess = { problems ->
                ExpressiveMaintenanceUiState.Terminal(
                    requestId,
                    ExpressiveMaintenanceOperation.DATABASE_CHECK,
                    true,
                    messages = problems,
                )
            },
        ) { gateway.checkDatabase() }
    }

    fun findEmptyCards() {
        if (_state.value !is ExpressiveMaintenanceUiState.Overview) return
        val requestId = nextId()
        runOperation(
            ExpressiveMaintenanceOperation.FIND_EMPTY_CARDS,
            requestId,
            onSuccess = { (report, text) ->
                emptyCardsReport = report
                ExpressiveMaintenanceUiState.EmptyCardsFound(
                    requestId = requestId,
                    report = text,
                    cardCount = report.notesList.sumOf { it.cardIdsCount },
                    notesAtRisk = report.notesList.count { it.willDeleteNote },
                )
            },
        ) {
            val report = gateway.findEmptyCards()
            // The backend report is unbounded HTML: it is parsed off the main thread.
            report to withContext(ioDispatcher) { plainTextReport(report.report) }
        }
    }

    fun deleteEmptyCards(preserveNotes: Boolean) {
        val current = _state.value as? ExpressiveMaintenanceUiState.EmptyCardsFound ?: return
        val report = emptyCardsReport ?: return
        runOperation(
            ExpressiveMaintenanceOperation.DELETE_EMPTY_CARDS,
            current.requestId,
            onSuccess = { count ->
                emptyCardsReport = null
                ExpressiveMaintenanceUiState.Terminal(
                    current.requestId,
                    ExpressiveMaintenanceOperation.DELETE_EMPTY_CARDS,
                    true,
                    affectedCount = count,
                )
            },
        ) { gateway.deleteEmptyCards(report, preserveNotes) }
    }

    fun checkMedia() {
        if (_state.value !is ExpressiveMaintenanceUiState.Overview) return
        val requestId = nextId()
        runOperation(
            ExpressiveMaintenanceOperation.CHECK_MEDIA,
            requestId,
            onSuccess = { report ->
                mediaReport = report
                ExpressiveMaintenanceUiState.MediaFound(
                    requestId = requestId,
                    report = report.report.take(MAX_REPORT_CHARS),
                    missingCount = report.missingCount,
                    unusedCount = report.unusedCount,
                    haveTrash = report.haveTrash,
                )
            },
        ) { gateway.checkMedia() }
    }

    fun tagMissingMedia() = withMediaReport(ExpressiveMaintenanceOperation.TAG_MISSING_MEDIA) { gateway.tagMissingMedia(it) }

    fun trashUnusedMedia() = withMediaReport(ExpressiveMaintenanceOperation.TRASH_UNUSED_MEDIA) { gateway.trashUnusedMedia(it) }

    fun restoreMediaTrash() = withMediaReport(ExpressiveMaintenanceOperation.RESTORE_MEDIA_TRASH) { gateway.restoreMediaTrash() }

    fun emptyMediaTrash() = withMediaReport(ExpressiveMaintenanceOperation.EMPTY_MEDIA_TRASH) { gateway.emptyMediaTrash() }

    fun rebuildMediaDatabase() {
        if (_state.value !is ExpressiveMaintenanceUiState.Overview) return
        runOperation(ExpressiveMaintenanceOperation.REBUILD_MEDIA_DATABASE) { gateway.rebuildMediaDatabase() }
    }

    private fun withMediaReport(
        operation: ExpressiveMaintenanceOperation,
        block: suspend (CheckMediaResponse) -> Any,
    ) {
        val current = _state.value as? ExpressiveMaintenanceUiState.MediaFound ?: return
        val report = mediaReport ?: return
        runOperation(
            operation,
            current.requestId,
            onSuccess = { result ->
                mediaReport = null
                ExpressiveMaintenanceUiState.Terminal(current.requestId, operation, true, affectedCount = result as? Int)
            },
        ) { block(report) }
    }

    /**
     * Removes artifacts that nothing can own any more. Runs once per instance. Restore staging that
     * no live preparation owns is always swept: its owner lived in a finished Activity or in a
     * previous process, and it can hold every media file of a restored package.
     */
    fun cleanupInterruptedArtifacts() {
        if (interruptedCleanupStarted) return
        interruptedCleanupStarted = true
        viewModelScope.launch(NonCancellable) {
            restoredCleanupToken?.let { token -> deletePendingImport(token) }
            // Only unowned staging is removed; a preparation started since recreation keeps its copy.
            runCatching { gateway.discardPreparedRestore(null) }.onFailure { Timber.w(it, "Restore staging could not be swept") }
        }
    }

    /** Nothing can confirm a preparation once its coordinator is gone: release what it still owns. */
    override fun onCleared() {
        val confirm = _state.value as? ExpressiveMaintenanceUiState.ConfirmRestore ?: return
        cleanupScope.launch(NonCancellable) { discardStaged(confirm.externalToken, confirm.generation) }
    }

    private fun loadOverview(mode: ExpressiveMaintenanceMode) {
        emptyCardsReport = null
        mediaReport = null
        when (mode) {
            ExpressiveMaintenanceMode.BACKUPS ->
                runOperation(
                    ExpressiveMaintenanceOperation.LOAD_OVERVIEW,
                    onSuccess = { (overview, kept) ->
                        ExpressiveMaintenanceUiState.Overview(mode, overview.first, overview.second, quarantinedMediaFiles = kept)
                    },
                ) { gateway.backupOverview() to gateway.quarantinedMediaCount() }
            ExpressiveMaintenanceMode.DATABASE ->
                runOperation(
                    ExpressiveMaintenanceOperation.LOAD_OVERVIEW,
                    onSuccess = { warning -> ExpressiveMaintenanceUiState.Overview(mode, databaseWarning = warning) },
                ) { gateway.databaseCheckWarning() }
            ExpressiveMaintenanceMode.EMPTY_CARDS,
            ExpressiveMaintenanceMode.MEDIA,
            -> set(ExpressiveMaintenanceUiState.Overview(mode))
        }
    }

    private fun <T> runOperation(
        operation: ExpressiveMaintenanceOperation,
        requestId: Long = nextId(),
        onSuccess: (T) -> ExpressiveMaintenanceUiState = { result ->
            ExpressiveMaintenanceUiState.Terminal(requestId, operation, true, affectedCount = result as? Int)
        },
        block: suspend () -> T,
    ) {
        if (_state.value is ExpressiveMaintenanceUiState.Running) return
        syncRefusal(operation)?.let { refusal ->
            set(ExpressiveMaintenanceUiState.Terminal(requestId, operation, false, refusal))
            return
        }
        set(ExpressiveMaintenanceUiState.Running(requestId, operation))
        this.operation =
            viewModelScope.launch {
                val poller = if (operation.reportsProgress && progressPollMillis > 0) launchProgressPoller(requestId, operation) else null
                try {
                    if (operation.cancellable) {
                        val result = block()
                        completeIfRunning(requestId) { onSuccess(result) }
                    } else {
                        withContext(NonCancellable) {
                            val result = block()
                            completeIfRunning(requestId) { onSuccess(result) }
                        }
                    }
                } catch (_: CancellationException) {
                    withContext(NonCancellable) { completeIfRunning(requestId) { interrupted(requestId, operation) } }
                } catch (failure: Exception) {
                    Timber.w(failure, "Maintenance operation %s failed", operation)
                    completeIfRunning(requestId) { failed(requestId, operation, failure) }
                } finally {
                    poller?.cancel()
                }
            }
    }

    private fun CoroutineScope.launchProgressPoller(
        requestId: Long,
        operation: ExpressiveMaintenanceOperation,
    ): Job =
        launch {
            while (isActive) {
                delay(progressPollMillis)
                val progress = withContext(ioDispatcher) { runCatching { gateway.progress(operation) }.getOrNull() } ?: continue
                val current = _state.value as? ExpressiveMaintenanceUiState.Running ?: return@launch
                if (current.requestId != requestId) return@launch
                // Transient: progress is never persisted.
                if (current.progress != progress) _state.value = current.copy(progress = progress)
            }
        }

    /** The refusal for [operation] under the current sync activity, or `null` when it may start. */
    private fun syncRefusal(operation: ExpressiveMaintenanceOperation): ExpressiveMaintenanceError? {
        if (!operation.excludesSync) return null
        return when (syncActivity()) {
            ExpressiveMaintenanceSyncActivity.COLLECTION ->
                ExpressiveMaintenanceError.SYNC_ACTIVE.takeIf { operation.excludesCollectionSync }
            ExpressiveMaintenanceSyncActivity.MEDIA -> ExpressiveMaintenanceError.MEDIA_SYNC_ACTIVE.takeIf { operation.excludesMediaSync }
            ExpressiveMaintenanceSyncActivity.NONE -> null
        }
    }

    private fun discardTransient(current: ExpressiveMaintenanceUiState) {
        emptyCardsReport = null
        mediaReport = null
        if (current is ExpressiveMaintenanceUiState.ConfirmRestore) {
            viewModelScope.launch(NonCancellable) { discardStaged(current.externalToken, current.generation) }
        }
    }

    /** Supersedes the running request before cancelling it, so the abandoned work can no longer publish. */
    private fun abandonRunningOperation() {
        val job = operation
        set(ExpressiveMaintenanceUiState.Idle)
        job?.cancel()
    }

    private fun closeSession() {
        emptyCardsReport = null
        mediaReport = null
        set(ExpressiveMaintenanceUiState.Idle)
    }

    private suspend fun discardStaged(
        token: String?,
        generation: Long?,
    ) {
        deletePendingImport(token)
        if (generation != null) runCatching { gateway.discardPreparedRestore(generation) }
    }

    /** A staged import can be several gigabytes: it is unlinked off the main thread. */
    private suspend fun deletePendingImport(token: String?) {
        token ?: return
        withContext(ioDispatcher) { ExpressiveTransferFiles.deleteImport(appContext, token) }
        if (savedStateHandle.get<String>(PENDING_IMPORT_TOKEN_KEY) == token) savedStateHandle.remove<String>(PENDING_IMPORT_TOKEN_KEY)
    }

    private fun interrupted(
        requestId: Long,
        operation: ExpressiveMaintenanceOperation,
    ) = ExpressiveMaintenanceUiState.Terminal(requestId, operation, false, ExpressiveMaintenanceError.INTERRUPTED)

    private fun failed(
        requestId: Long,
        operation: ExpressiveMaintenanceOperation,
        failure: Throwable,
    ) = ExpressiveMaintenanceUiState.Terminal(requestId, operation, false, failure.toMaintenanceError(operation))

    /** Publishes [value] only if [requestId] is still the admitted running request. */
    private inline fun completeIfRunning(
        requestId: Long,
        value: () -> ExpressiveMaintenanceUiState,
    ): Boolean {
        val current = _state.value
        if (current !is ExpressiveMaintenanceUiState.Running || current.requestId != requestId) return false
        set(value())
        return true
    }

    private fun set(value: ExpressiveMaintenanceUiState) {
        _state.value = value
        persist(value)
    }

    private fun persist(value: ExpressiveMaintenanceUiState) {
        savedStateHandle[STATE_KEY] = MaintenanceStateCodec.encode(value)
    }

    private fun nextId(): Long {
        val next = (savedStateHandle.get<Long>(NEXT_ID_KEY) ?: 0L) + 1L
        savedStateHandle[NEXT_ID_KEY] = next
        return next
    }

    companion object {
        internal const val STATE_KEY = "expressive.maintenance.state.v2"
        internal const val PENDING_IMPORT_TOKEN_KEY = "expressive.maintenance.pending-import-token"
        private const val LEGACY_STATE_KEY = "expressive.maintenance.state.v1"
        private const val NEXT_ID_KEY = "expressive.maintenance.next-id"
        private const val AUTOMATIC_BACKUP_DELAY_MILLIS = 1_000L
        private const val PROGRESS_POLL_MILLIS = 250L
        private const val MAX_REPORT_CHARS = 50_000

        fun factory(context: Context): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    ExpressiveMaintenanceCoordinatorViewModel(
                        savedStateHandle = createSavedStateHandle(),
                        gateway = gatewayForTesting ?: DefaultExpressiveMaintenanceGateway(context.applicationContext),
                        context = context.applicationContext,
                    )
                }
            }

        /** Test-only: the gateway of coordinators the [factory] creates, replacing the backend one. */
        @VisibleForTesting
        internal var gatewayForTesting: ExpressiveMaintenanceGateway? = null

        private fun plainTextReport(html: String): String =
            HtmlCompat
                .fromHtml(html, HtmlCompat.FROM_HTML_MODE_COMPACT)
                .toString()
                .trim()
                .take(MAX_REPORT_CHARS)
    }
}

private fun restoreDocumentIntent(): Intent =
    Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "*/*"
        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
        putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(EXPRESSIVE_COLPKG_MIME, "application/x-colpkg", "application/octet-stream"))
    }

internal fun Throwable.toMaintenanceError(operation: ExpressiveMaintenanceOperation): ExpressiveMaintenanceError =
    when {
        this is SafetyBackupException -> ExpressiveMaintenanceError.BACKUP_FAILED
        this is ExpressiveRestoreRecoveryException -> ExpressiveMaintenanceError.RESTORE_RECOVERY_PENDING
        this is ExpressiveRestoreBlockedByMediaSyncException || this is MediaSyncActiveException ->
            ExpressiveMaintenanceError.MEDIA_SYNC_ACTIVE
        this is ExpressiveRestorePendingException -> ExpressiveMaintenanceError.PREVIOUS_RESTORE_UNFINISHED
        this is InvalidBackupException -> ExpressiveMaintenanceError.INVALID_BACKUP
        this is TransferFailure ->
            when (safeError) {
                ExpressiveTransferError.FILE_TOO_LARGE -> ExpressiveMaintenanceError.FILE_TOO_LARGE
                ExpressiveTransferError.PROVIDER_UNAVAILABLE,
                ExpressiveTransferError.COPY_FAILED,
                -> ExpressiveMaintenanceError.PROVIDER_UNAVAILABLE
                else -> ExpressiveMaintenanceError.INVALID_BACKUP
            }
        operation == ExpressiveMaintenanceOperation.PREPARE_RESTORE && this is BackendException ->
            ExpressiveMaintenanceError.INVALID_BACKUP
        operation == ExpressiveMaintenanceOperation.RESTORE -> ExpressiveMaintenanceError.RESTORE_FAILED
        operation == ExpressiveMaintenanceOperation.CREATE_BACKUP -> ExpressiveMaintenanceError.BACKUP_FAILED
        else -> ExpressiveMaintenanceError.MAINTENANCE_FAILED
    }

@Serializable
private data class SavedMaintenanceState(
    val version: Int,
    val kind: String,
    val requestId: Long? = null,
    val operation: String? = null,
    val succeeded: Boolean? = null,
    val error: String? = null,
    val messages: List<String> = emptyList(),
    val affectedCount: Int? = null,
)

/** Versioned wire format: no paths, provider names, or display names are ever persisted. */
private object MaintenanceStateCodec {
    private const val VERSION = 2
    private const val MAX_MESSAGES = 50
    private const val MAX_MESSAGE_CHARS = 500
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(state: ExpressiveMaintenanceUiState): String =
        json.encodeToString(
            when (state) {
                // Read-only work is reloaded after recreation; admitted work is reported as interrupted.
                is ExpressiveMaintenanceUiState.Running ->
                    if (state.operation.readOnly) SavedMaintenanceState(VERSION, "idle") else active(state.requestId, state.operation)
                is ExpressiveMaintenanceUiState.PickingRestore ->
                    if (state.launchDispatched) {
                        active(state.requestId, ExpressiveMaintenanceOperation.PREPARE_RESTORE)
                    } else {
                        SavedMaintenanceState(VERSION, "idle")
                    }
                is ExpressiveMaintenanceUiState.ConfirmRestore -> active(state.requestId, ExpressiveMaintenanceOperation.PREPARE_RESTORE)
                is ExpressiveMaintenanceUiState.Terminal ->
                    SavedMaintenanceState(
                        version = VERSION,
                        kind = "terminal",
                        requestId = state.requestId,
                        operation = state.operation.name,
                        succeeded = state.succeeded,
                        error = state.error?.name,
                        messages = state.messages.take(MAX_MESSAGES).map { it.take(MAX_MESSAGE_CHARS) },
                        affectedCount = state.affectedCount,
                    )
                // Reports and confirmations are recomputed from a fresh overview after recreation.
                else -> SavedMaintenanceState(VERSION, "idle")
            },
        )

    fun decode(encoded: String): SavedMaintenanceState? =
        runCatching { json.decodeFromString<SavedMaintenanceState>(encoded).takeIf { it.version == VERSION } }.getOrNull()

    private fun active(
        requestId: Long,
        operation: ExpressiveMaintenanceOperation,
    ) = SavedMaintenanceState(VERSION, "active", requestId = requestId, operation = operation.name)
}

private fun SavedMaintenanceState?.toSafeRestoredState(): ExpressiveMaintenanceUiState {
    val saved = this ?: return ExpressiveMaintenanceUiState.Idle
    val op = saved.operation?.let { runCatching { ExpressiveMaintenanceOperation.valueOf(it) }.getOrNull() }
    val requestId = saved.requestId
    return when (saved.kind) {
        // Admitted work is never replayed after process death.
        "active" ->
            if (op == null || requestId == null) {
                ExpressiveMaintenanceUiState.Idle
            } else {
                ExpressiveMaintenanceUiState.Terminal(requestId, op, false, ExpressiveMaintenanceError.INTERRUPTED)
            }
        "terminal" -> {
            val succeeded = saved.succeeded
            if (op == null || requestId == null || succeeded == null) {
                ExpressiveMaintenanceUiState.Idle
            } else {
                ExpressiveMaintenanceUiState.Terminal(
                    requestId = requestId,
                    operation = op,
                    succeeded = succeeded,
                    error = saved.error?.let { runCatching { ExpressiveMaintenanceError.valueOf(it) }.getOrNull() },
                    messages = saved.messages,
                    affectedCount = saved.affectedCount,
                )
            }
        }
        else -> ExpressiveMaintenanceUiState.Idle
    }
}
