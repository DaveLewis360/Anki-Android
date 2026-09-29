// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: Copyright (c) 2022 Ankitects Pty Ltd <https://apps.ankiweb.net>

package com.ichi2.anki

import android.annotation.SuppressLint
import android.system.Os
import android.system.OsConstants
import androidx.annotation.VisibleForTesting
import androidx.annotation.WorkerThread
import anki.backend.backendError
import com.ichi2.anki.CollectionManager.discardBackend
import com.ichi2.anki.CollectionManager.ensureBackend
import com.ichi2.anki.CollectionManager.ensureClosed
import com.ichi2.anki.CollectionManager.ensureClosedInner
import com.ichi2.anki.CollectionManager.ensureOpen
import com.ichi2.anki.CollectionManager.ensureOpenInner
import com.ichi2.anki.CollectionManager.getColUnsafe
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.CollectionManager.withOpenColOrNull
import com.ichi2.anki.CollectionManager.withQueue
import com.ichi2.anki.backend.createDatabaseUsingRustBackend
import com.ichi2.anki.common.android.ApplicationContextInitializer
import com.ichi2.anki.common.android.appContext
import com.ichi2.anki.common.preferences.sharedPrefs
import com.ichi2.anki.common.storage.CollectionHelper
import com.ichi2.anki.common.storage.StorageDecision
import com.ichi2.anki.common.utils.android.Threads
import com.ichi2.anki.common.utils.android.isRobolectric
import com.ichi2.anki.common.utils.isRunningAsUnitTest
import com.ichi2.anki.exception.StorageNotConfiguredException
import com.ichi2.anki.exception.SystemStorageException
import com.ichi2.anki.libanki.Collection
import com.ichi2.anki.libanki.CollectionFiles
import com.ichi2.anki.libanki.LibAnki
import com.ichi2.anki.libanki.Storage.collection
import com.ichi2.anki.libanki.importCollectionPackage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import net.ankiweb.rsdroid.Backend
import net.ankiweb.rsdroid.BackendException
import net.ankiweb.rsdroid.BackendFactory
import net.ankiweb.rsdroid.Translations
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.Properties
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class CollectionReplacementRecoveryException(
    cause: Throwable,
) : RuntimeException("Collection replacement committed, but reopening the collection failed", cause)

/**
 * An Expressive restore failed and its rollback could not complete. The journal is retained, so
 * recovery runs again before the next collection open; the collection is left closed until then.
 */
class ExpressiveRestoreRecoveryException(
    cause: Throwable,
) : RuntimeException("Restore activation failed and rollback is pending", cause)

/** Thrown before anything changes: a running media sync owns the media folder the restore merges into. */
class ExpressiveRestoreBlockedByMediaSyncException : IOException("A media sync is using the media folder")

/** An earlier Expressive restore still owns its journal: it must finish, or roll back, first. */
class ExpressiveRestorePendingException : IOException("An earlier Expressive restore has not finished")

/** A committed Expressive restore: the restored database is active in every case. */
data class ExpressiveRestoreOutcome(
    /** Restored media files that could not be merged; they were moved to a quarantine folder instead. */
    val quarantinedMediaFiles: Int = 0,
    /** Staged media is neither merged nor quarantined yet; the retained journal resumes it before the next restore or open. */
    val mediaPending: Boolean = false,
)

private const val EXPRESSIVE_RESTORE_JOURNAL_VERSION = 1

private enum class ExpressiveRestorePhase {
    PARKING,
    PARKED,
    ACTIVATED,
    COMPLETE,
}

private data class ExpressiveRestoreJournal(
    val phase: ExpressiveRestorePhase = ExpressiveRestorePhase.PARKING,
    val oldNames: Set<String>,
    val newNames: Set<String>,
)

private data class ExpressiveRestorePaths(
    val parent: File,
    val target: File,
    val staging: File,
    val rollback: File,
    val journal: File,
    val journalTemporary: File,
) {
    companion object {
        fun forTarget(targetDirectory: File): ExpressiveRestorePaths {
            val target = targetDirectory.canonicalFile
            val parent = target.parentFile ?: throw IOException("Collection directory has no parent")
            val prefix = ".${target.name}.expressive-restore"
            return ExpressiveRestorePaths(
                parent = parent,
                target = target,
                staging = File(parent, "$prefix-stage").canonicalFile,
                rollback = File(parent, "$prefix-rollback").canonicalFile,
                journal = File(parent, "$prefix.properties").canonicalFile,
                journalTemporary = File(parent, "$prefix.properties.tmp").canonicalFile,
            )
        }
    }
}

object CollectionManager {
    /**
     * The currently active backend, which is created on demand via [ensureBackend], and
     * implicitly via [ensureOpen] and routines like [withCol].
     * The backend is long-lived, and will generally only be closed when switching interface
     * languages or changing schema versions. A closed backend cannot be reused, and a new one
     * must be created.
     */
    @Suppress("DEPRECATION") // deprecation is used to limit access
    @get:JvmName("backend")
    private var backend: Backend?
        get() = LibAnki.backend
        set(value) {
            LibAnki.backend = value
        }

    /**
     * The current collection, which is opened on demand via [withCol]. If you need to
     * close and reopen the collection in an atomic operation, add a new method that
     * calls [withQueue], and then executes [ensureClosedInner] and [ensureOpenInner] inside it.
     * A closed collection can be detected via [withOpenColOrNull] or by checking [Collection.dbClosed].
     */
    @Suppress("DEPRECATION") // deprecation is used to limit access
    private var collection: Collection?
        get() = LibAnki.collection
        set(value) {
            LibAnki.collection = value
        }

    private var queue: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

    /**
     * Test-only: emulates a number of failure cases when opening the collection
     *
     * @see [CollectionOpenFailure]
     */
    @VisibleForTesting
    var emulatedOpenFailure: CollectionOpenFailure? = null

    private val testMutex = ReentrantLock()

    private var currentSyncCertificate: String = ""

    /** Prevents the startup recovery hook from rolling back the transaction currently being opened. */
    private var expressiveRestoreCommitInProgress = false

    /**
     * Test-only fault injection, invoked after each durable restore journal write with the phase
     * name, and with [ROLLBACK_HOOK] before a rollback starts. Throwing an [Error] simulates process
     * death: the in-process rollback is skipped. Throwing an [Exception] simulates a failed step.
     */
    @VisibleForTesting
    var expressiveRestorePhaseHookForTesting: ((String) -> Unit)? = null

    @VisibleForTesting
    const val ROLLBACK_HOOK = "ROLLBACK"

    /** Test-only: replaces the backend's media sync status in [isMediaSyncRunning]; it may throw. */
    @VisibleForTesting
    var mediaSyncStatusForTesting: (() -> Boolean)? = null

    private val expressiveRestoreDatabaseNames =
        listOf(
            "collection.anki2",
            "collection.anki2-journal",
            "collection.anki2-wal",
            "collection.anki2-shm",
        )

    private const val EXPRESSIVE_RESTORE_MEDIA_FOLDER = "collection.media"

    /** Inside the collection directory; shared with the folder that receives corrupt collections. */
    private const val EXPRESSIVE_RESTORE_QUARANTINE_FOLDER = "broken"
    private const val EXPRESSIVE_RESTORE_QUARANTINE_PREFIX = "expressive-restore-media-"
    private const val EXPRESSIVE_RESTORE_QUARANTINE_MAX = 1_000

    /**
     * Written by the backend importer while staging, and never activated: the live media database
     * keeps describing the live media folder, and media sync detects the merged files by scanning.
     */
    private val expressiveRestoreStagedMediaDatabaseNames =
        setOf(
            "collection.media.db",
            "collection.media.db-journal",
            "collection.media.db-wal",
            "collection.media.db-shm",
        )

    /**
     * Execute the provided block on a serial background queue, to ensure
     * concurrent access does not happen.
     *
     * The background queue is run in a [Dispatchers.IO] context.
     * It's important that the block is not suspendable - if it were, it would allow
     * multiple requests to be interleaved when a suspend point was hit.
     *
     * TODO Disallow running functions that are supposed to be run inside the queue outside of it.
     *   For instance, this can be done by marking a [block] with a context
     *   that cannot be instantiated outside of this class:
     *
     *       suspend fun<T> withQueue(block: context(Queue) () -> T): T {
     *          return withContext(collectionOperationsDispatcher) {
     *              block(queue)
     *          }
     *      }
     *
     *   Then, only functions that are also marked can be run inside the block:
     *
     *       context(Queue) suspend fun canOnlyBeRunInWithQueue()
     */
    private suspend fun <T> withQueue(
        @WorkerThread block: CollectionManager.() -> T,
    ): T {
        if (isRobolectric) {
            // #16253 Robolectric Windows: `withContext(queue)` is insufficient for serial execution
            return testMutex.withLock {
                this@CollectionManager.block()
            }
        }
        return withContext(queue) {
            this@CollectionManager.block()
        }
    }

    /**
     * Execute the provided block with the collection, opening if necessary.
     *
     * Calls are serialized, and run in background [Dispatchers.IO] thread.
     *
     * Parallel calls to this function are guaranteed to be serialized, so you can be
     * sure the collection won't be closed or modified by another thread. This guarantee
     * does not hold if legacy code calls [getColUnsafe].
     *
     * @throws StorageNotConfiguredException If [CollectionHelper.storageDecision] is undecided
     * (user has not selected a storage location).
     * @throws SystemStorageException if startup failed to choose a default collection path
     * ([CollectionHelper.systemStorageFailure])
     */
    suspend fun <T> withCol(
        @WorkerThread block: Collection.() -> T,
    ): T =
        withQueue {
            ensureOpenInner()
            block(collection!!)
        }

    /**
     * Execute the provided block if the collection is already open. See [withCol] for more.
     * Since the block may return a null value, and a null value will also be returned in the
     * case of the collection being closed, if the calling code needs to distinguish between
     * these two cases, it should wrap the return value of the block in a class (eg Optional),
     * instead of returning a nullable object.
     */
    suspend fun <T> withOpenColOrNull(
        @WorkerThread block: Collection.() -> T,
    ): T? =
        withQueue {
            if (collection != null && !collection!!.dbClosed) {
                block(collection!!)
            } else {
                null
            }
        }

    /**
     * Return a handle to the backend, creating if necessary. This should only be used
     * for routines that don't depend on an open or closed collection, such as checking
     * the current progress state when importing a colpkg file. While the backend is
     * thread safe and can be accessed concurrently, if another thread closes the collection
     * and you call a routine that expects an open collection, it will result in an error.
     */
    fun getBackend(): Backend {
        if (backend == null) {
            runBlocking { withQueue { ensureBackendInner() } }
        }
        return backend!!
    }

    /**
     * Translations provided by the Rust backend/Anki desktop code.
     */
    val TR: Translations
        get() {
            // we bypass the lock here so that translations are fast - conflicts are unlikely,
            // as the backend is only ever changed on language preference change or schema switch
            return getBackend().tr
        }

    fun compareAnswer(
        expected: String,
        given: String,
        combining: Boolean = true,
    ): String {
        // bypass the lock, as the type answer code is heavily nested in non-suspend functions
        return getBackend().compareAnswer(expected, given, combining)
    }

    /**
     * Close the currently cached backend and discard it. Saves and closes the collection if open.
     */
    suspend fun discardBackend() {
        withQueue {
            discardBackendInner()
        }
    }

    /** See [discardBackend]. This must only be run inside the queue. */
    private fun discardBackendInner() {
        ensureClosedInner()
        if (backend != null) {
            backend!!.close()
            backend = null
        }
    }

    /**
     * Open the backend if it's not already open.
     */
    private suspend fun ensureBackend() {
        withQueue {
            ensureBackendInner()
        }
    }

    /** See [ensureBackend]. This must only be run inside the queue. */
    private fun ensureBackendInner() {
        if (backend == null) {
            backend = BackendFactory.getBackend()
        }
    }

    /**
     * If the collection is open, close it.
     */
    suspend fun ensureClosed() {
        withQueue {
            ensureClosedInner()
        }
    }

    /**
     * Strictly closes the collection before migration recovery can mutate its files.
     *
     * Unlike [ensureClosed], a close failure is propagated and the collection reference is
     * retained, so [withCollectionClosedForRecovery] continues to reject filesystem recovery.
     */
    suspend fun ensureClosedForFilesystemRecovery() {
        withQueue {
            ensureClosedStrictInner()
        }
    }

    /**
     * Runs a non-suspending filesystem recovery while the collection queue is exclusively held.
     * Recovery is refused if a collection is already open, so callers cannot rename its files.
     */
    suspend fun <T> withCollectionClosedForRecovery(
        @WorkerThread block: () -> T,
    ): T =
        withQueue {
            check(collection?.dbClosed != false) { "Collection must be closed before filesystem recovery" }
            block()
        }

    /**
     * Atomically closes the current collection, activates a prepared collection directory, and
     * opens it before another queued collection operation can interleave.
     *
     * [activate] and [rollback] must be non-suspending, same-filesystem operations. If activation
     * or opening fails, [rollback] runs while the collection queue is still held. A collection
     * that was open before activation is reopened only after a successful rollback.
     */
    suspend fun activateCollectionDirectory(
        @WorkerThread activate: () -> Unit,
        @WorkerThread rollback: () -> Unit,
    ) {
        withQueue {
            val wasOpen = collection?.dbClosed == false
            ensureClosedStrictInner()
            try {
                activate()
                ensureOpenInner()
            } catch (activationFailure: Exception) {
                ensureClosedInner()
                val rollbackFailure = runCatching(rollback).exceptionOrNull()
                rollbackFailure?.let(activationFailure::addSuppressed)
                if (wasOpen && rollbackFailure == null) {
                    runCatching(::ensureOpenInner).exceptionOrNull()?.let(activationFailure::addSuppressed)
                }
                throw activationFailure
            }
        }
    }

    /** App-owned sibling directory into which a COLPKG restore must be fully imported and validated. */
    fun expressiveRestoreStagingDirectory(): File = ExpressiveRestorePaths.forTarget(getCollectionDirectory()).staging

    /**
     * Queue-free check for any restore journal, parked database, or staging directory. Blocking
     * file access: call it off the main thread.
     */
    fun hasExpressiveRestoreArtifacts(): Boolean {
        val paths = ExpressiveRestorePaths.forTarget(getCollectionDirectory())
        return listOf(paths.staging, paths.rollback, paths.journal, paths.journalTemporary).any { it.exists() || isRestoreSymlink(it) }
    }

    /**
     * Whether the backend is running a media sync, whoever started it. The status call rethrows,
     * once, the stored error of a media sync that already ended; that sync is not running, so the
     * error is only logged.
     */
    fun isMediaSyncRunning(backend: Backend): Boolean =
        try {
            mediaSyncStatusForTesting?.invoke() ?: backend.mediaSyncStatus().active
        } catch (failure: Exception) {
            Timber.w(failure, "A finished media sync reported an error")
            false
        }

    /** Restored media files kept in quarantine folders because they could not be merged. Blocking file access. */
    fun expressiveRestoreQuarantinedMediaCount(): Int =
        expressiveRestoreQuarantineFolders(getCollectionDirectory()).sumOf { folder ->
            folder.listFiles().orEmpty().count { !isRestoreSymlink(it) && it.isFile }
        }

    /**
     * Retries merging quarantined restored media with the restore's semantics: a kept file replaces
     * a same-named file in the media folder. Files that still cannot be moved stay quarantined.
     *
     * @return the number of files that remain quarantined
     * @throws ExpressiveRestoreBlockedByMediaSyncException while a media sync runs
     */
    suspend fun mergeExpressiveRestoreQuarantine(): Int =
        withQueue {
            ensureBackendInner()
            if (isMediaSyncRunning(backend!!)) throw ExpressiveRestoreBlockedByMediaSyncException()
            val target = getCollectionDirectory()
            val live = File(target, EXPRESSIVE_RESTORE_MEDIA_FOLDER)
            if (!live.isDirectory && !live.mkdirs()) throw IOException("The media folder could not be created")
            expressiveRestoreQuarantineFolders(target).forEach { folder ->
                folder.listFiles().orEmpty().forEach { file ->
                    if (!isRestoreSymlink(file) && file.isFile) file.renameTo(File(live, file.name))
                }
                if (folder.listFiles().orEmpty().isEmpty()) deleteRestoreTree(folder)
            }
            fsyncRestoreDirectory(live)
            expressiveRestoreQuarantinedMediaCount()
        }

    /** Permanently deletes every quarantined restored media file. The media folder is not touched. */
    suspend fun deleteExpressiveRestoreQuarantine() {
        withQueue {
            expressiveRestoreQuarantineFolders(getCollectionDirectory()).forEach(::deleteRestoreTree)
        }
    }

    private fun expressiveRestoreQuarantineFolders(target: File): List<File> {
        val root = File(target, EXPRESSIVE_RESTORE_QUARANTINE_FOLDER)
        if (isRestoreSymlink(root) || !root.isDirectory) return emptyList()
        return root.listFiles().orEmpty().filter { folder ->
            folder.name.startsWith(EXPRESSIVE_RESTORE_QUARANTINE_PREFIX) && !isRestoreSymlink(folder) && folder.isDirectory
        }
    }

    /**
     * Restores the previous database after a process death interrupted an Expressive restore, or
     * finishes a committed one. This also runs automatically before a closed collection is opened.
     */
    suspend fun recoverInterruptedExpressiveRestore() {
        withQueue {
            check(collection?.dbClosed != false) { "Collection must be closed before restore recovery" }
            recoverInterruptedExpressiveRestoreInner(getCollectionDirectory())
        }
    }

    /**
     * Makes the shared restore staging directory safe to reuse: a committed restore whose media merge
     * or cleanup was interrupted is finished, and an uncommitted one is rolled back while the
     * collection is closed. Returns false while a journal remains: an uncommitted restore still
     * awaits its rollback, or finishing a committed one failed again.
     *
     * @throws ExpressiveRestoreBlockedByMediaSyncException instead of merging restored media into a
     * folder a running media sync uses; the journal is kept for a later settle
     */
    suspend fun settleExpressiveRestore(): Boolean =
        withQueue {
            val paths = ExpressiveRestorePaths.forTarget(getCollectionDirectory())
            val journal = readExpressiveRestoreJournal(paths) ?: return@withQueue true
            when {
                journal.phase == ExpressiveRestorePhase.COMPLETE -> {
                    // Only an open collection can have a running media sync: closing one aborts it.
                    if (collection?.dbClosed == false && backend?.let(::isMediaSyncRunning) == true) {
                        throw ExpressiveRestoreBlockedByMediaSyncException()
                    }
                    finishCompletedExpressiveRestore(paths)
                    !paths.journal.exists()
                }
                collection?.dbClosed != false -> {
                    rollbackExpressiveRestore(paths, journal)
                    true
                }
                else -> false
            }
        }

    /**
     * Activates a separately imported and validated collection. Matching Anki's COLPKG import, only
     * the collection database is replaced: it is swapped transactionally and rolled back unless it
     * opens. Restored media is then merged into the live media folder, keeping files the backup does
     * not contain; restored files that cannot be merged are quarantined in `broken/`, never deleted.
     * The backup/ directory is never touched.
     *
     * @throws ExpressiveRestoreBlockedByMediaSyncException before anything changes while a media sync runs
     */
    suspend fun activatePreparedExpressiveRestore(): ExpressiveRestoreOutcome =
        withQueue {
            val paths = ExpressiveRestorePaths.forTarget(getCollectionDirectory())
            // Checked inside the queue: media syncs are only started from inside this queue (by the
            // media worker, or by the backend after a full sync), so none can start before the
            // database is swapped and media is merged.
            ensureBackendInner()
            if (isMediaSyncRunning(backend!!)) throw ExpressiveRestoreBlockedByMediaSyncException()
            // Staging is prepared only after any pending journal was settled, so one found here is rejected:
            // finishing it now would merge and delete the newly prepared staging.
            validatePreparedRestore(paths)
            val newNames = existingDatabaseNames(paths.staging)
            if ("collection.anki2" !in newNames) throw IOException("Prepared restore has no collection database")
            val wasOpen = collection?.dbClosed == false
            ensureClosedStrictInner()
            // A clean close removes SQLite sidecars (for example the WAL), so the live database is
            // captured only after the collection is closed.
            val oldNames = existingDatabaseNames(paths.target)
            val journal = ExpressiveRestoreJournal(oldNames = oldNames, newNames = newNames)
            expressiveRestoreCommitInProgress = true
            try {
                writeExpressiveRestoreJournal(paths, journal.copy(phase = ExpressiveRestorePhase.PARKING))
                if (!paths.rollback.mkdir()) throw IOException("Could not create restore rollback directory")
                oldNames.forEach { name -> moveRestoreEntry(File(paths.target, name), File(paths.rollback, name)) }
                writeExpressiveRestoreJournal(paths, journal.copy(phase = ExpressiveRestorePhase.PARKED))
                newNames.forEach { name -> moveRestoreEntry(File(paths.staging, name), File(paths.target, name)) }
                writeExpressiveRestoreJournal(paths, journal.copy(phase = ExpressiveRestorePhase.ACTIVATED))
                ensureOpenInner()
                writeExpressiveRestoreJournal(paths, journal.copy(phase = ExpressiveRestorePhase.COMPLETE))
            } catch (activationFailure: Exception) {
                ensureClosedInner()
                val rollbackFailure = runCatching { rollbackExpressiveRestore(paths, journal) }.exceptionOrNull()
                if (rollbackFailure != null) {
                    activationFailure.addSuppressed(rollbackFailure)
                    throw ExpressiveRestoreRecoveryException(activationFailure)
                }
                expressiveRestoreCommitInProgress = false
                if (wasOpen) {
                    runCatching(::ensureOpenInner).exceptionOrNull()?.let(activationFailure::addSuppressed)
                }
                throw activationFailure
            } finally {
                expressiveRestoreCommitInProgress = false
            }
            finishCompletedExpressiveRestore(paths)
        }

    private fun validatePreparedRestore(paths: ExpressiveRestorePaths) {
        if (paths.journal.exists() || paths.journalTemporary.exists() || paths.rollback.exists()) {
            throw ExpressiveRestorePendingException()
        }
        val target = paths.target.canonicalFile
        val staging = paths.staging.canonicalFile
        if (!target.isDirectory || !staging.isDirectory || target.parentFile != staging.parentFile) {
            throw IOException("Prepared restore paths are invalid")
        }
        rejectRestoreSymlink(staging)
        staging.listFiles().orEmpty().forEach { entry ->
            rejectRestoreSymlink(entry)
            val supported =
                entry.name in expressiveRestoreDatabaseNames ||
                    entry.name in expressiveRestoreStagedMediaDatabaseNames ||
                    entry.name == EXPRESSIVE_RESTORE_MEDIA_FOLDER
            if (!supported) throw IOException("Prepared restore contains an unsupported entry")
        }
        val media = File(staging, EXPRESSIVE_RESTORE_MEDIA_FOLDER)
        if (media.exists()) {
            if (!media.isDirectory) throw IOException("Prepared restore media is not a folder")
            media.listFiles().orEmpty().forEach { file ->
                rejectRestoreSymlink(file)
                if (!file.isFile) throw IOException("Prepared restore media contains an unsupported entry")
            }
        }
    }

    private fun existingDatabaseNames(directory: File): Set<String> =
        expressiveRestoreDatabaseNames.filterTo(linkedSetOf()) { File(directory, it).exists() }

    private fun writeExpressiveRestoreJournal(
        paths: ExpressiveRestorePaths,
        journal: ExpressiveRestoreJournal,
    ) {
        val properties =
            Properties().apply {
                setProperty("version", EXPRESSIVE_RESTORE_JOURNAL_VERSION.toString())
                setProperty("phase", journal.phase.name)
                setProperty("target", paths.target.canonicalPath)
                setProperty("staging", paths.staging.canonicalPath)
                setProperty("rollback", paths.rollback.canonicalPath)
                setProperty("old", journal.oldNames.sorted().joinToString(","))
                setProperty("new", journal.newNames.sorted().joinToString(","))
            }
        FileOutputStream(paths.journalTemporary).use { output ->
            properties.store(output, null)
            output.flush()
            output.fd.sync()
        }
        // rename(2) atomically replaces the previous phase: a stable journal is always complete.
        if (!paths.journalTemporary.renameTo(paths.journal)) throw IOException("Could not persist the restore journal")
        fsyncRestoreDirectory(paths.parent)
        expressiveRestorePhaseHookForTesting?.invoke(journal.phase.name)
    }

    private fun readExpressiveRestoreJournal(paths: ExpressiveRestorePaths): ExpressiveRestoreJournal? {
        discardTemporaryExpressiveRestoreJournal(paths)
        if (!paths.journal.exists()) return null
        val properties = Properties()
        FileInputStream(paths.journal).use(properties::load)
        if (
            properties.getProperty("version")?.toIntOrNull() != EXPRESSIVE_RESTORE_JOURNAL_VERSION ||
            properties.getProperty("target") != paths.target.canonicalPath ||
            properties.getProperty("staging") != paths.staging.canonicalPath ||
            properties.getProperty("rollback") != paths.rollback.canonicalPath
        ) {
            throw IOException("Restore journal does not match the active collection")
        }

        fun names(key: String): Set<String> =
            properties
                .getProperty(key)
                .orEmpty()
                .split(',')
                .filter(String::isNotEmpty)
                .toSet()
                .also { if (!expressiveRestoreDatabaseNames.containsAll(it)) throw IOException("Restore journal contains invalid entries") }
        return ExpressiveRestoreJournal(
            phase =
                properties
                    .getProperty("phase")
                    ?.let { phase -> ExpressiveRestorePhase.entries.firstOrNull { it.name == phase } }
                    ?: throw IOException("Restore journal phase is unreadable"),
            oldNames = names("old"),
            newNames = names("new"),
        )
    }

    /**
     * A temporary journal is only ever an unfinished write: the stable journal is replaced
     * atomically, and the first phase is durable before any database file moves. It is discarded.
     */
    private fun discardTemporaryExpressiveRestoreJournal(paths: ExpressiveRestorePaths) {
        if (paths.journalTemporary.exists() && !paths.journalTemporary.delete()) {
            throw IOException("Could not remove temporary restore journal")
        }
    }

    private fun recoverInterruptedExpressiveRestoreInner(targetDirectory: File) {
        val paths = ExpressiveRestorePaths.forTarget(targetDirectory)
        val journal = readExpressiveRestoreJournal(paths) ?: return
        if (journal.phase == ExpressiveRestorePhase.COMPLETE) {
            finishCompletedExpressiveRestore(paths)
            return
        }
        rollbackExpressiveRestore(paths, journal)
    }

    /**
     * Puts the previous database back. Every database file that was not parked and is not a previous
     * file still in place belongs to the restored database, including sidecars (for example a WAL)
     * that opening it created: those are removed so they can never be replayed into the old one.
     */
    private fun rollbackExpressiveRestore(
        paths: ExpressiveRestorePaths,
        journal: ExpressiveRestoreJournal,
    ) {
        expressiveRestorePhaseHookForTesting?.invoke(ROLLBACK_HOOK)
        expressiveRestoreDatabaseNames.forEach { name ->
            val live = File(paths.target, name)
            val parked = File(paths.rollback, name)
            when {
                parked.exists() -> {
                    deleteRestoreTree(live)
                    moveRestoreEntry(parked, live)
                }
                name in journal.oldNames -> Unit
                else -> deleteRestoreTree(live)
            }
        }
        deleteRestoreTree(paths.rollback)
        deleteRestoreTree(paths.staging)
        deleteRestoreTree(paths.journalTemporary)
        deleteRestoreTree(paths.journal)
        fsyncRestoreDirectory(paths.parent)
    }

    /**
     * Idempotent and resumable: merges restored media, quarantines what cannot be merged, then
     * removes the parked database, the staging directory, and finally the journal, so an
     * interruption resumes at the next open or restore. The restored database is already committed,
     * so a failure here is logged rather than thrown; the retained journal keeps every staged file.
     */
    private fun finishCompletedExpressiveRestore(paths: ExpressiveRestorePaths): ExpressiveRestoreOutcome {
        var quarantined = 0
        return try {
            quarantined = mergeRestoredMedia(paths)
            deleteRestoreTree(paths.rollback)
            deleteRestoreTree(paths.staging)
            deleteRestoreTree(paths.journalTemporary)
            deleteRestoreTree(paths.journal)
            fsyncRestoreDirectory(paths.parent)
            ExpressiveRestoreOutcome(quarantinedMediaFiles = quarantined)
        } catch (failure: Exception) {
            Timber.w(failure, "Finishing a committed Expressive restore is retried before the next restore or open")
            // Only media still in staging is pending; a cleanup-only failure has already merged or quarantined it.
            val staged = File(paths.staging, EXPRESSIVE_RESTORE_MEDIA_FOLDER)
            val mediaPending =
                runCatching { !isRestoreSymlink(staged) && staged.listFiles().orEmpty().isNotEmpty() }.getOrDefault(true)
            ExpressiveRestoreOutcome(quarantinedMediaFiles = quarantined, mediaPending = mediaPending)
        }
    }

    /**
     * Anki semantics: restored files replace same-named ones; files the backup lacks are kept. What
     * cannot be moved in stays recoverable: the staged folder, then holding only those files, is
     * moved to `broken/` as a whole before staging is removed.
     *
     * @return the number of restored files that were quarantined instead of merged
     */
    private fun mergeRestoredMedia(paths: ExpressiveRestorePaths): Int {
        val staged = File(paths.staging, EXPRESSIVE_RESTORE_MEDIA_FOLDER)
        if (isRestoreSymlink(staged) || !staged.isDirectory) return 0
        val live = File(paths.target, EXPRESSIVE_RESTORE_MEDIA_FOLDER)
        if (!live.isDirectory && !live.mkdirs()) throw IOException("The media folder could not be created")
        staged.listFiles().orEmpty().forEach { file ->
            // A file that cannot be renamed stays in the staged folder and is quarantined below.
            if (!isRestoreSymlink(file) && file.isFile) file.renameTo(File(live, file.name))
        }
        fsyncRestoreDirectory(live)
        val unmerged = staged.listFiles().orEmpty().size
        if (unmerged == 0) return 0
        quarantineUnmergedMedia(paths, staged)
        Timber.w("%d restored media files could not be merged and were quarantined", unmerged)
        return unmerged
    }

    private fun quarantineUnmergedMedia(
        paths: ExpressiveRestorePaths,
        staged: File,
    ) {
        val root = File(paths.target, EXPRESSIVE_RESTORE_QUARANTINE_FOLDER)
        rejectRestoreSymlink(root)
        if (!root.isDirectory && !root.mkdirs()) throw IOException("The restore quarantine folder could not be created")
        val destination =
            (1..EXPRESSIVE_RESTORE_QUARANTINE_MAX)
                .asSequence()
                .map { File(root, "$EXPRESSIVE_RESTORE_QUARANTINE_PREFIX$it") }
                .firstOrNull { !it.exists() && !isRestoreSymlink(it) }
                ?: throw IOException("No free restore quarantine folder name")
        moveRestoreEntry(staged, destination)
    }

    /** Same-filesystem move that refuses to replace an existing destination. */
    private fun moveRestoreEntry(
        source: File,
        target: File,
    ) {
        if (!source.exists()) throw IOException("Restore source is missing")
        if (target.exists() || isRestoreSymlink(target)) throw IOException("Restore destination already exists")
        if (!source.renameTo(target)) throw IOException("Could not move a restore entry")
        fsyncRestoreDirectory(target.parentFile ?: throw IOException("Restore destination has no parent"))
    }

    /** Deletes [file] (recursively for directories) without following symbolic links. */
    private fun deleteRestoreTree(file: File) {
        val symbolic = isRestoreSymlink(file)
        if (!symbolic && !file.exists()) return
        if (!symbolic && file.isDirectory) file.listFiles().orEmpty().forEach(::deleteRestoreTree)
        if (!file.delete() && (symbolic || file.exists())) throw IOException("Could not remove restore-owned path")
    }

    private fun isRestoreSymlink(file: File): Boolean =
        runCatching { OsConstants.S_ISLNK(Os.lstat(file.absolutePath).st_mode) }.getOrDefault(false)

    private fun rejectRestoreSymlink(file: File) {
        if (isRestoreSymlink(file)) throw IOException("Restore symbolic links are not supported")
    }

    private fun fsyncRestoreDirectory(directory: File) {
        runCatching {
            val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
            try {
                Os.fsync(descriptor)
            } finally {
                Os.close(descriptor)
            }
        }.onFailure { Timber.w(it, "Directory fsync unavailable during Expressive restore") }
    }

    /** Critical replacement close: unlike [ensureClosedInner], failures are not swallowed. */
    private fun ensureClosedStrictInner() {
        val current = collection ?: return
        if (!current.dbClosed) {
            current.close()
        }
        collection = null
    }

    /** See [ensureClosed]. This must only be run inside the queue. */
    private fun ensureClosedInner() {
        if (collection == null) {
            return
        }
        try {
            collection!!.close()
        } catch (exc: Exception) {
            Timber.e("swallowing error on close: $exc")
        }
        collection = null
    }

    /**
     * Open the collection, if it's not already open.
     *
     * Automatically called by [withCol]. Can be called directly to ensure collection
     * is loaded at a certain point in time, or to ensure no errors occur.
     *
     * @throws StorageNotConfiguredException If [CollectionHelper.storageDecision] is undecided
     * (user has not selected a storage location).
     * @throws SystemStorageException if startup failed to choose a default collection path
     * ([CollectionHelper.systemStorageFailure])
     */
    suspend fun ensureOpen() {
        withQueue {
            ensureOpenInner()
        }
    }

    /**
     * See [ensureOpen]. This must only be run inside the queue.
     *
     * @throws StorageNotConfiguredException If [CollectionHelper.storageDecision] is not
     * [StorageDecision.Decided] (user has not selected a storage location).
     * @throws SystemStorageException if startup failed to choose a default collection path
     * ([CollectionHelper.systemStorageFailure])
     * @throws IllegalStateException if executed before `AnkiDroidApp.onCreate`
     */
    private fun ensureOpenInner() {
        // the decision is gated on the same preferences getCollectionDirectory() reads from
        val decision =
            CollectionHelper.storageDecisionTestOverride
                ?: ApplicationContextInitializer.instanceOrNull
                    ?.sharedPrefs()
                    ?.let { CollectionHelper.storageDecision(it) }
                // test-only: in-memory test collections have no preferences and read no path
                ?: if (isRunningAsUnitTest) {
                    StorageDecision.Decided
                } else {
                    // the app instance is unset: treat this as a bug.
                    throw IllegalStateException("Collection accessed before AnkiDroidApp was initialized")
                }
        if (decision != StorageDecision.Decided) {
            // rethrow a recorded startup storage failure: an OS bug/SD card issue must not be
            // reported as the expected 'storage not configured' state
            throw CollectionHelper.systemStorageFailure ?: StorageNotConfiguredException()
        }
        ensureBackendInner()
        emulatedOpenFailure?.triggerFailure()
        if (collection == null || collection!!.dbClosed) {
            // Only a closed collection can be opened from disk: finish an interrupted restore first.
            if (!expressiveRestoreCommitInProgress) {
                recoverInterruptedExpressiveRestoreInner(getCollectionDirectory())
            }
            val collectionPath = collectionPathInValidFolder()
            collection =
                collection(
                    collectionFiles = collectionPath,
                    databaseBuilder = { backend -> createDatabaseUsingRustBackend(backend) },
                    backend = backend,
                )
        }
    }

    suspend fun deleteCollectionDirectory() {
        withQueue {
            ensureClosedInner()
            getCollectionDirectory().deleteRecursively()
        }
    }

    fun getCollectionDirectory() = CollectionHelper.getCurrentAnkiDroidDirectory(appContext.sharedPrefs())

    /** Ensures the AnkiDroid directory is created, then returns the path to the
     * folder and the name of the collection file inside it. */
    fun collectionPathInValidFolder(): CollectionFiles {
        val dir = getCollectionDirectory()
        CollectionHelper.initializeAnkiDroidDirectory(dir)
        return CollectionFiles.FolderBasedCollection(dir)
    }

    /**
     * Like [withQueue], but can be used in a synchronous context.
     *
     * Note: [runBlocking] inside `RobolectricTest.runTest` will lead to deadlocks, so
     * under Robolectric, this uses a mutex
     */
    private fun <T> blockForQueue(block: CollectionManager.() -> T): T =
        if (isRobolectric) {
            testMutex.withLock {
                block(this)
            }
        } else {
            runBlocking {
                withQueue(block)
            }
        }

    fun closeCollectionBlocking() {
        runBlocking { ensureClosed() }
    }

    /**
     * Returns a reference to the open collection. This is not
     * safe, as code in other threads could open or close
     * the collection while the reference is held. [withCol]
     * is a better alternative.
     *
     * @throws StorageNotConfiguredException If [CollectionHelper.storageDecision] is undecided
     * (user has not selected a storage location).
     * @throws SystemStorageException if startup failed to choose a default collection path
     * ([CollectionHelper.systemStorageFailure])
     */
    fun getColUnsafe(): Collection =
        logUIHangs {
            blockForQueue {
                ensureOpenInner()
                collection!!
            }
        }

    /**
     Execute [block]. If it takes more than 100ms of real time, Timber an error like:
     > Blocked main thread for 2424ms: com.ichi2.anki.DeckPicker.onCreateOptionsMenu(DeckPicker.kt:624)
     */
    // using TimeManager breaks a sched test that makes assumptions about the time, so we
    // access the time directly
    @SuppressLint("DirectSystemCurrentTimeMillisUsage")
    private fun <T> logUIHangs(block: () -> T): T {
        val start = System.currentTimeMillis()
        return block().also {
            val elapsed = System.currentTimeMillis() - start
            if (Threads.isOnMainThread && elapsed > 100) {
                val stackTraceElements = Thread.currentThread().stackTrace
                // locate the probable calling file/line in the stack trace, by filtering
                // out our own code, and standard dalvik/java.lang stack frames
                val caller =
                    stackTraceElements
                        .filter {
                            val klass = it.className
                            val toCheck =
                                listOf(
                                    "CollectionManager",
                                    "dalvik",
                                    "java.lang",
                                    "CollectionHelper",
                                    "AnkiActivity",
                                )
                            for (text in toCheck) {
                                if (text in klass) {
                                    return@filter false
                                }
                            }
                            true
                        }.first()
                Timber.w("blocked main thread for %dms:\n%s", elapsed, caller)
            }
        }
    }

    /**
     * True if the collection is open. Unsafe, as it has the potential to race.
     */
    fun isOpenUnsafe(): Boolean =
        logUIHangs {
            blockForQueue {
                if (emulatedOpenFailure != null) {
                    false
                } else {
                    collection?.dbClosed == false // non-failure mode.
                }
            }
        }

    /**
     Use [col] as collection in tests.
     This collection persists only up to the next (direct or indirect) call to `ensureClosed`
     */
    fun setColForTests(col: Collection?) {
        blockForQueue {
            if (col == null) {
                ensureClosedInner()
            }
            collection = col
        }
    }

    /**
     * Strict replacement protocol used by destructive COLPKG import. A close failure prevents any
     * mutation. If close partially succeeds, recovery is still attempted. Once replacement commits,
     * a reopen failure is reported with a dedicated type so callers can invalidate committed data.
     */
    @VisibleForTesting
    fun runStrictCollectionReplacementProtocol(
        close: () -> Unit,
        isClosed: () -> Boolean,
        replace: () -> Unit,
        reopen: () -> Unit,
    ) {
        var primaryFailure: Throwable? = null
        var replacementCommitted = false
        try {
            close()
            replace()
            replacementCommitted = true
        } catch (failure: Throwable) {
            primaryFailure = failure
        }

        val reopenFailure =
            if (isClosed()) {
                runCatching(reopen).exceptionOrNull()
            } else {
                null
            }
        primaryFailure?.let { failure ->
            reopenFailure?.let(failure::addSuppressed)
            throw failure
        }
        if (reopenFailure != null) {
            if (replacementCommitted) throw CollectionReplacementRecoveryException(reopenFailure)
            throw reopenFailure
        }
    }

    /**
     * Full-export close/backend/reopen protocol. If close partially fails after closing the
     * wrapper, recovery is still attempted. Reopen failures remain explicit or suppressed on the
     * primary close/backend failure.
     */
    @VisibleForTesting
    fun runFullCollectionExportProtocol(
        close: () -> Unit,
        isClosed: () -> Boolean,
        export: () -> Unit,
        reopen: () -> Unit,
    ) {
        var primaryFailure: Throwable? = null
        try {
            close()
            export()
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            if (isClosed()) {
                try {
                    reopen()
                } catch (reopenFailure: Throwable) {
                    primaryFailure?.addSuppressed(reopenFailure) ?: throw reopenFailure
                }
            }
        }
    }

    /**
     * Replace the collection with the provided colpkg file and validate that the replacement can
     * be reopened before returning. The queue remains exclusively owned for the entire operation.
     */
    suspend fun importColpkg(colpkgPath: String) {
        withQueue {
            runStrictCollectionReplacementProtocol(
                close = {
                    // Replacement must not begin while an existing database handle may still be live.
                    // Unlike best-effort shutdown paths, a close failure is fatal here.
                    ensureClosedStrictInner()
                    // A retained Expressive restore journal is settled first: settled by the reopen below,
                    // it would merge old restored media over the package's files or roll the import back.
                    val target = getCollectionDirectory()
                    recoverInterruptedExpressiveRestoreInner(target)
                    if (ExpressiveRestorePaths.forTarget(target).journal.exists()) throw ExpressiveRestorePendingException()
                },
                isClosed = { collection?.dbClosed != false },
                replace = {
                    ensureBackendInner()
                    importCollectionPackage(backend!!, collectionPathInValidFolder(), colpkgPath)
                },
                reopen = {
                    // A failed replacement can still have changed or closed collection files. Reopen
                    // before releasing the queue so callers receive either a usable collection or an
                    // explicit recovery failure.
                    ensureOpenInner()
                },
            )
        }
    }

    /**
     * Export a full collection while exclusively owning the queue. Close, backend export, abort,
     * and recovery form one guarded protocol: every failure after a close attempt restores the
     * wrapper when possible, and a recovery failure is never hidden.
     */
    suspend fun exportCollectionPackageGuaranteed(
        outPath: String,
        includeMedia: Boolean,
        legacy: Boolean,
    ) {
        withQueue {
            ensureOpenInner()
            val activeCollection = collection!!
            runFullCollectionExportProtocol(
                close = { activeCollection.close(forFullSync = true) },
                isClosed = { activeCollection.dbClosed },
                export = {
                    backend!!.exportCollectionPackage(
                        outPath = outPath,
                        includeMedia = includeMedia,
                        legacy = legacy,
                    )
                },
                reopen = {
                    // Backend cancellation/failure has completed before recovery begins.
                    activeCollection.reopen()
                    collection = activeCollection
                },
            )
        }
    }

    fun setTestDispatcher(dispatcher: CoroutineDispatcher) {
        // note: we avoid the call to .limitedParallelism() here,
        // as it does not seem to be compatible with the test scheduler
        queue = dispatcher
    }

    /**
     * Update the custom TLS certificate used in the backend for its requests to the sync server.
     *
     * If the cert parameter hasn't changed from the cached sync certificate, then just return true.
     * Otherwise, set the custom certificate in the backend and get the success value.
     *
     * If cert was a valid certificate, then cache it in currentSyncCertificate and return true.
     * Otherwise, return false to indicate that a custom sync certificate was not applied.
     *
     * Passing in an empty string unsets any custom sync certificate in the backend.
     */
    fun updateCustomCertificate(cert: String): Boolean {
        if (cert == currentSyncCertificate) {
            return true
        }

        return getBackend().setCustomCertificate(cert).apply {
            if (this) {
                currentSyncCertificate = cert
            }
        }
    }

    enum class CollectionOpenFailure {
        /** Raises [BackendException.BackendDbException.BackendDbLockedException] */
        LOCKED,

        /** Raises [BackendException.BackendFatalError] */
        FATAL_ERROR,

        ;

        fun triggerFailure() {
            when (this) {
                LOCKED -> throw BackendException.BackendDbException.BackendDbLockedException(backendError {})
                FATAL_ERROR -> throw BackendException.BackendFatalError(backendError {})
            }
        }
    }
}

fun Collection.reopen(afterFullSync: Boolean = false) =
    this.reopen(afterFullSync = afterFullSync) { backend -> createDatabaseUsingRustBackend(backend) }
