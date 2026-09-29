// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.migration

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.system.Os
import android.system.OsConstants
import com.ichi2.anki.CollectionManager
import com.ichi2.anki.backend.createDatabaseUsingRustBackend
import com.ichi2.anki.common.preferences.sharedPrefs
import com.ichi2.anki.libanki.CollectionFiles
import com.ichi2.anki.libanki.Storage.collection
import com.ichi2.utils.FileUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.ankiweb.rsdroid.BackendFactory
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

internal data class ExistingProfileMigrationSummary(
    val copiedFiles: Int,
    val copiedBytes: Long,
)

internal sealed interface ExistingProfileRecoveryResult {
    data object NoAction : ExistingProfileRecoveryResult

    data object Recovered : ExistingProfileRecoveryResult

    data class Blocked(
        val reason: String,
    ) : ExistingProfileRecoveryResult
}

internal class ExistingProfileMigrationException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * Cold, copy-only migration from a directly readable Classic AnkiDroid directory.
 *
 * Source files are never opened for writing, moved, renamed, or deleted. A complete copy is
 * staged beside the package-owned target, verified by SHA-256 and SQLite/backend checks, then
 * activated under [CollectionManager]'s serialized collection queue. Unsupported private/scoped
 * storage remains an explicit failure rather than falling back to sharing the Classic path.
 */
internal class ExistingProfileMigrationService(
    context: Context,
) {
    private val preferences = context.applicationContext.sharedPrefs()

    suspend fun migrate(
        sourceDirectory: File,
        targetDirectory: File,
    ): ExistingProfileMigrationSummary {
        migrationMutex.lock()
        return try {
            withContext(Dispatchers.IO) {
                migrateBlocking(sourceDirectory, targetDirectory)
            }
        } finally {
            migrationMutex.unlock()
        }
    }

    private suspend fun migrateBlocking(
        sourceDirectory: File,
        targetDirectory: File,
    ): ExistingProfileMigrationSummary {
        val recovery =
            CollectionManager.withCollectionClosedForRecovery {
                recoverInterruptedMigrationUnlocked(targetDirectory, preferences)
            }
        when (recovery) {
            ExistingProfileRecoveryResult.NoAction,
            ExistingProfileRecoveryResult.Recovered,
            -> Unit
            is ExistingProfileRecoveryResult.Blocked -> throw ExistingProfileMigrationException(recovery.reason)
        }

        val source = canonicalDirectory(sourceDirectory, "Classic source")
        val target = targetDirectory.canonicalFile
        validatePathSeparation(source, target)
        val paths = MigrationPaths.forTarget(target)
        ensureOwnedSiblingPaths(paths)
        ensurePristineTarget(target)
        ensureSourceIsQuiescent(source)

        val sourceFiles = sourcePayload(source)
        val before = buildManifest(source, sourceFiles)
        ensureSpace(paths.parent, before)
        ensureNoUnownedArtifacts(paths)
        if (!paths.staging.mkdirs()) {
            throw ExistingProfileMigrationException("Could not create migration staging directory: ${paths.staging}")
        }
        writeJournal(paths, MigrationPhase.COPYING, source, before)

        try {
            copyPayload(source, paths.staging, before)
            ensureSourceIsQuiescent(source)
            val after = buildManifest(source, sourcePayload(source))
            if (before != after) {
                throw ExistingProfileMigrationException(
                    "Classic data changed while it was being copied. Fully close Classic AnkiDroid and retry.",
                )
            }
            val staged = buildManifest(paths.staging, stagedPayload(paths.staging, before))
            if (before != staged) {
                throw ExistingProfileMigrationException("The staged copy did not match the Classic source.")
            }

            validateSqlite(File(paths.staging, COLLECTION_DB))
            File(paths.staging, MEDIA_DB).takeIf(File::exists)?.let(::validateSqlite)
            validateWithBackend(paths.staging)
            val validated = buildManifest(paths.staging, allRegularPayloadFiles(paths.staging))
            writeJournal(paths, MigrationPhase.READY, source, validated)
            activate(paths, source, validated)
            return ExistingProfileMigrationSummary(validated.entries.size, validated.totalBytes)
        } catch (failure: CancellationException) {
            cleanupIncompleteCopy(paths)
            throw failure
        } catch (failure: Exception) {
            cleanupIncompleteCopy(paths)
            if (failure is ExistingProfileMigrationException) throw failure
            throw ExistingProfileMigrationException("Classic data could not be copied safely.", failure)
        }
    }

    private fun cleanupIncompleteCopy(paths: MigrationPaths) {
        if (readJournal(paths)?.phase in setOf(MigrationPhase.COPYING, MigrationPhase.READY)) {
            safeDeleteOwned(paths.staging, paths)
            paths.journal.delete()
            paths.journalTemporary.delete()
        }
    }

    private suspend fun activate(
        paths: MigrationPaths,
        source: File,
        manifest: MigrationManifest,
    ) {
        var targetParked = false
        var stagingActivated = false
        try {
            CollectionManager.activateCollectionDirectory(
                activate = {
                    ensurePristineTarget(paths.target)
                    ensureSpace(paths.parent, manifest)
                    writeJournal(paths, MigrationPhase.TARGET_PARKING, source, manifest)
                    if (paths.target.exists()) {
                        rename(paths.target, paths.rollback)
                        targetParked = true
                    }
                    writeJournal(paths, MigrationPhase.TARGET_PARKED, source, manifest)
                    rename(paths.staging, paths.target)
                    stagingActivated = true
                    writeJournal(paths, MigrationPhase.ACTIVATED, source, manifest)
                },
                rollback = {
                    if (stagingActivated && paths.target.exists()) {
                        safeDeleteOwned(paths.staging, paths)
                        rename(paths.target, paths.staging)
                    }
                    if (targetParked && paths.rollback.exists()) {
                        rename(paths.rollback, paths.target)
                    }
                    writeJournal(paths, MigrationPhase.READY, source, manifest)
                },
            )
            writeJournal(paths, MigrationPhase.COMPLETE, source, manifest)
            markMigrationCompleted(preferences)
            safeDeleteOwned(paths.rollback, paths)
            paths.journal.delete()
            paths.journalTemporary.delete()
            fsyncDirectory(paths.parent)
        } catch (failure: Exception) {
            if (stagingActivated) {
                try {
                    withContext(NonCancellable) {
                        CollectionManager.ensureClosedForFilesystemRecovery()
                    }
                } catch (closeFailure: Exception) {
                    failure.addSuppressed(closeFailure)
                }
            }
            throw failure
        }
    }

    private fun sourcePayload(source: File): List<File> {
        val database = File(source, COLLECTION_DB)
        if (!database.isFile || !database.canRead() || database.length() <= 0L) {
            throw ExistingProfileMigrationException("Classic collection.anki2 is missing, empty, or unreadable.")
        }
        val media = File(source, MEDIA_DIRECTORY)
        if (!media.isDirectory || !media.canRead()) {
            throw ExistingProfileMigrationException("Classic collection.media is missing or unreadable.")
        }
        val files = mutableListOf(database)
        File(source, MEDIA_DB).takeIf { it.isFile }?.let(files::add)
        File(source, NO_MEDIA).takeIf { it.isFile }?.let(files::add)
        collectRegularFiles(source, media, files)
        return files.sortedBy { it.relativeTo(source).invariantSeparatorsPath }
    }

    private fun stagedPayload(
        staging: File,
        sourceManifest: MigrationManifest,
    ): List<File> =
        sourceManifest.entries.map { entry ->
            File(staging, entry.relativePath).also { file ->
                if (!file.isFile) {
                    throw ExistingProfileMigrationException("Staged file is missing: ${entry.relativePath}")
                }
            }
        }

    private fun allRegularPayloadFiles(staging: File): List<File> {
        val files = mutableListOf<File>()
        collectRegularFiles(staging, staging, files)
        return files.sortedBy { it.relativeTo(staging).invariantSeparatorsPath }
    }

    private fun collectRegularFiles(
        root: File,
        directory: File,
        output: MutableList<File>,
    ) {
        rejectSymbolicLink(directory)
        FileUtil.listFiles(directory).sortedBy(File::getName).forEach { child ->
            ensureInside(root, child)
            rejectSymbolicLink(child)
            when {
                child.isDirectory -> collectRegularFiles(root, child, output)
                child.isFile -> output.add(child)
                else -> throw ExistingProfileMigrationException("Unsupported Classic source entry: $child")
            }
        }
    }

    private fun buildManifest(
        root: File,
        files: List<File>,
    ): MigrationManifest {
        val entries =
            files
                .map { file ->
                    ensureInside(root, file)
                    rejectSymbolicLink(file)
                    MigrationManifestEntry(
                        relativePath = file.relativeTo(root).invariantSeparatorsPath,
                        size = file.length(),
                        sha256 = sha256(file),
                    )
                }.sortedBy(MigrationManifestEntry::relativePath)
        val digest = MessageDigest.getInstance("SHA-256")
        entries.forEach { entry ->
            digest.update(entry.relativePath.toByteArray(Charsets.UTF_8))
            digest.update(0)
            digest.update(entry.size.toString().toByteArray(Charsets.US_ASCII))
            digest.update(0)
            digest.update(entry.sha256.toByteArray(Charsets.US_ASCII))
            digest.update('\n'.code.toByte())
        }
        return MigrationManifest(
            entries = entries,
            totalBytes = entries.fold(0L) { total, entry -> Math.addExact(total, entry.size) },
            digest = digest.digest().toHex(),
        )
    }

    private fun copyPayload(
        source: File,
        staging: File,
        manifest: MigrationManifest,
    ) {
        File(staging, MEDIA_DIRECTORY).mkdirs()
        manifest.entries.forEach { entry ->
            val input = File(source, entry.relativePath)
            val output = File(staging, entry.relativePath)
            output.parentFile?.let { parent ->
                if (!parent.exists() && !parent.mkdirs()) {
                    throw ExistingProfileMigrationException("Could not create staging directory: $parent")
                }
            }
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(input).use { sourceStream ->
                FileOutputStream(output).use { targetStream ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        val count = sourceStream.read(buffer)
                        if (count < 0) break
                        targetStream.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                    }
                    targetStream.flush()
                    targetStream.fd.sync()
                }
            }
            if (output.length() != entry.size || digest.digest().toHex() != entry.sha256) {
                throw ExistingProfileMigrationException("Copy verification failed for ${entry.relativePath}.")
            }
        }
        fsyncDirectory(staging)
    }

    private fun validateSqlite(database: File) {
        val sqlite =
            try {
                SQLiteDatabase.openDatabase(database.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            } catch (failure: Exception) {
                throw ExistingProfileMigrationException("Copied database could not be opened: ${database.name}", failure)
            }
        sqlite.use { db ->
            db.rawQuery("PRAGMA quick_check", null).use { cursor ->
                if (!cursor.moveToFirst()) {
                    throw ExistingProfileMigrationException("SQLite validation returned no result for ${database.name}.")
                }
                do {
                    if (cursor.getString(0) != "ok") {
                        throw ExistingProfileMigrationException("SQLite validation failed for ${database.name}.")
                    }
                } while (cursor.moveToNext())
            }
        }
    }

    private fun validateWithBackend(staging: File) {
        val backend = BackendFactory.getBackend()
        var stagedCollection: com.ichi2.anki.libanki.Collection? = null
        try {
            stagedCollection =
                collection(
                    collectionFiles = CollectionFiles.FolderBasedCollection(staging),
                    databaseBuilder = { createDatabaseUsingRustBackend(it) },
                    backend = backend,
                )
        } catch (failure: Exception) {
            throw ExistingProfileMigrationException("Copied collection is not compatible with this app.", failure)
        } finally {
            runCatching { stagedCollection?.close() }
            runCatching { backend.close() }
        }
    }

    private fun ensureSourceIsQuiescent(source: File) {
        val activeSidecars =
            listOf(
                "$COLLECTION_DB-journal",
                "$COLLECTION_DB-wal",
                "$COLLECTION_DB-shm",
                "$MEDIA_DB-journal",
                "$MEDIA_DB-wal",
                "$MEDIA_DB-shm",
            ).map { File(source, it) }.filter { it.exists() && it.length() > 0L }
        if (activeSidecars.isNotEmpty()) {
            throw ExistingProfileMigrationException(
                "Classic AnkiDroid appears to be using this profile (${activeSidecars.first().name}). Fully close it and retry.",
            )
        }
    }

    private fun ensureSpace(
        targetParent: File,
        manifest: MigrationManifest,
    ) {
        if (!targetParent.exists() && !targetParent.mkdirs()) {
            throw ExistingProfileMigrationException("Could not create target parent directory: $targetParent")
        }
        val databaseBytes = manifest.entries.firstOrNull { it.relativePath == COLLECTION_DB }?.size ?: 0L
        val required =
            try {
                Math.addExact(
                    Math.addExact(manifest.totalBytes, Math.multiplyExact(databaseBytes, 2L)),
                    SPACE_SAFETY_MARGIN_BYTES,
                )
            } catch (overflow: ArithmeticException) {
                throw ExistingProfileMigrationException("Classic profile is too large to calculate migration space safely.", overflow)
            }
        val available =
            try {
                FileUtil.determineBytesAvailable(targetParent.absolutePath)
            } catch (failure: Exception) {
                throw ExistingProfileMigrationException("Available target space could not be determined.", failure)
            }
        if (available < required) {
            throw ExistingProfileMigrationException(
                "Not enough free space to copy Classic data. Required: $required bytes; available: $available bytes.",
            )
        }
    }

    private fun ensurePristineTarget(target: File) {
        if (!target.exists()) return
        if (!target.isDirectory) {
            throw ExistingProfileMigrationException("The Expressive target is not a directory: $target")
        }
        val conflicts = FileUtil.listFiles(target).filterNot { it.name == NO_MEDIA && it.isFile }
        if (conflicts.isNotEmpty()) {
            throw ExistingProfileMigrationException(
                "Expressive already contains collection data. Existing data will not be overwritten.",
            )
        }
    }

    private fun canonicalDirectory(
        directory: File,
        label: String,
    ): File {
        val canonical = directory.canonicalFile
        if (!canonical.isDirectory || !canonical.canRead()) {
            throw ExistingProfileMigrationException("$label is unavailable or unreadable: $canonical")
        }
        rejectSymbolicLink(canonical)
        return canonical
    }

    private fun validatePathSeparation(
        source: File,
        target: File,
    ) {
        val sourcePath = source.canonicalPath
        val targetPath = target.canonicalPath
        val sourcePrefix = sourcePath + File.separator
        val targetPrefix = targetPath + File.separator
        if (sourcePath == targetPath || sourcePath.startsWith(targetPrefix) || targetPath.startsWith(sourcePrefix)) {
            throw ExistingProfileMigrationException(
                "Classic source and Expressive target must be separate directories. The source will not be reused in place.",
            )
        }
    }

    private fun ensureInside(
        root: File,
        child: File,
    ) {
        val rootPath = root.canonicalPath + File.separator
        if (!child.canonicalPath.startsWith(rootPath)) {
            throw ExistingProfileMigrationException("Source entry escapes the selected directory: $child")
        }
    }

    private fun rejectSymbolicLink(file: File) {
        val symbolic =
            try {
                OsConstants.S_ISLNK(Os.lstat(file.absolutePath).st_mode)
            } catch (failure: Exception) {
                throw ExistingProfileMigrationException("Could not inspect source entry: $file", failure)
            }
        if (symbolic) {
            throw ExistingProfileMigrationException("Symbolic links are not supported during migration: $file")
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    }

    private fun writeJournal(
        paths: MigrationPaths,
        phase: MigrationPhase,
        source: File,
        manifest: MigrationManifest,
    ) {
        val journal =
            MigrationJournal(
                version = JOURNAL_VERSION,
                phase = phase,
                sourcePath = source.canonicalPath,
                targetPath = paths.target.canonicalPath,
                stagingPath = paths.staging.canonicalPath,
                rollbackPath = paths.rollback.canonicalPath,
                manifestDigest = manifest.digest,
                copiedFiles = manifest.entries.size,
                copiedBytes = manifest.totalBytes,
            )
        val bytes = json.encodeToString(journal).toByteArray(Charsets.UTF_8)
        FileOutputStream(paths.journalTemporary).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
        rename(paths.journalTemporary, paths.journal, replace = true)
        fsyncDirectory(paths.parent)
    }

    private fun readJournal(paths: MigrationPaths): MigrationJournal? {
        if (!paths.journal.exists()) return null
        return try {
            json.decodeFromString(paths.journal.readText())
        } catch (failure: Exception) {
            throw ExistingProfileMigrationException(
                "The interrupted migration journal is unreadable. No collection data was changed.",
                failure,
            )
        }
    }

    private fun rename(
        source: File,
        target: File,
        replace: Boolean = false,
    ) {
        if (!source.exists()) {
            throw ExistingProfileMigrationException("Migration path is missing: $source")
        }
        if (target.exists() && !replace) {
            throw ExistingProfileMigrationException("Migration destination already exists: $target")
        }
        try {
            Os.rename(source.absolutePath, target.absolutePath)
            fsyncDirectory(target.parentFile ?: throw ExistingProfileMigrationException("Missing parent for $target"))
        } catch (failure: Exception) {
            throw ExistingProfileMigrationException("Could not atomically activate migration data.", failure)
        }
    }

    private fun fsyncDirectory(directory: File) {
        runCatching {
            val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
            try {
                Os.fsync(descriptor)
            } finally {
                Os.close(descriptor)
            }
        }.onFailure { Timber.w(it, "Directory fsync unavailable for %s", directory) }
    }

    private fun ensureNoUnownedArtifacts(paths: MigrationPaths) {
        val existing =
            listOf(paths.staging, paths.rollback, paths.journal, paths.journalTemporary)
                .filter(File::exists)
        if (existing.isNotEmpty()) {
            throw ExistingProfileMigrationException(
                "Unrecognized migration artifact already exists and will not be overwritten: ${existing.first()}",
            )
        }
    }

    private fun ensureOwnedSiblingPaths(paths: MigrationPaths) {
        val expected = MigrationPaths.forTarget(paths.target)
        if (paths != expected) {
            throw ExistingProfileMigrationException("Migration paths are not owned by the Expressive target.")
        }
    }

    private fun safeDeleteOwned(
        path: File,
        paths: MigrationPaths,
    ) {
        if (!path.exists()) return
        if (path != paths.staging && path != paths.rollback) {
            throw ExistingProfileMigrationException("Refusing to delete a non-migration path: $path")
        }
        deleteWithoutFollowingLinks(path)
    }

    private fun deleteWithoutFollowingLinks(file: File) {
        val isSymbolicLink = runCatching { OsConstants.S_ISLNK(Os.lstat(file.absolutePath).st_mode) }.getOrDefault(false)
        if (file.isDirectory && !isSymbolicLink) {
            FileUtil.listFiles(file).forEach(::deleteWithoutFollowingLinks)
        }
        if (!file.delete() && file.exists()) {
            throw ExistingProfileMigrationException("Could not clean migration-owned path: $file")
        }
    }

    companion object {
        private const val COLLECTION_DB = "collection.anki2"
        private const val MEDIA_DIRECTORY = "collection.media"
        private const val MEDIA_DB = "collection.media.db"
        private const val NO_MEDIA = ".nomedia"
        private const val JOURNAL_VERSION = 1
        private const val PREF_MIGRATION_COMPLETED = "expressive.classic_migration.completed"
        private const val COPY_BUFFER_BYTES = 128 * 1024
        private const val SPACE_SAFETY_MARGIN_BYTES = 64L * 1024L * 1024L
        private val json = Json { ignoreUnknownKeys = false }
        private val migrationMutex = Mutex()

        fun hasCompletedMigration(context: Context): Boolean = context.sharedPrefs().getBoolean(PREF_MIGRATION_COMPLETED, false)

        private fun markMigrationCompleted(preferences: android.content.SharedPreferences) {
            val committed = preferences.edit().putBoolean(PREF_MIGRATION_COMPLETED, true).commit()
            if (!committed) {
                throw ExistingProfileMigrationException(
                    "Classic migration completed, but its completion marker could not be persisted. Recovery must retry before startup.",
                )
            }
        }

        fun hasInterruptedMigration(targetDirectory: File): Boolean =
            runCatching {
                val paths = MigrationPaths.forTarget(targetDirectory.canonicalFile)
                paths.journal.exists() || paths.journalTemporary.exists()
            }.getOrDefault(false)

        /** Restores the pre-migration target before normal startup can open a partial activation. */
        suspend fun recoverInterruptedMigration(
            context: Context,
            targetDirectory: File,
        ): ExistingProfileRecoveryResult =
            try {
                val preferences = context.applicationContext.sharedPrefs()
                migrationMutex.lock()
                try {
                    withContext(Dispatchers.IO) {
                        CollectionManager.withCollectionClosedForRecovery {
                            recoverInterruptedMigrationUnlocked(targetDirectory, preferences)
                        }
                    }
                } finally {
                    migrationMutex.unlock()
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                Timber.e(failure, "Could not enter guarded Classic migration recovery")
                ExistingProfileRecoveryResult.Blocked(
                    "Classic migration recovery could not safely close the collection. Retry recovery before opening it.",
                )
            }

        private fun recoverInterruptedMigrationUnlocked(
            targetDirectory: File,
            preferences: android.content.SharedPreferences,
        ): ExistingProfileRecoveryResult {
            val target =
                runCatching { targetDirectory.canonicalFile }.getOrElse {
                    return ExistingProfileRecoveryResult.Blocked("Expressive target path cannot be resolved.")
                }
            val paths = MigrationPaths.forTarget(target)
            return try {
                promoteTemporaryJournal(paths)
                if (!paths.journal.exists()) return ExistingProfileRecoveryResult.NoAction
                val journal = json.decodeFromString<MigrationJournal>(paths.journal.readText())
                if (!journal.matches(paths)) {
                    return ExistingProfileRecoveryResult.Blocked(
                        "Interrupted migration metadata does not match the Expressive target.",
                    )
                }
                when (journal.phase) {
                    MigrationPhase.COMPLETE -> {
                        markMigrationCompleted(preferences)
                        deleteOwnedForRecovery(paths.rollback, paths)
                        deleteOwnedForRecovery(paths.staging, paths)
                    }
                    MigrationPhase.COPYING,
                    MigrationPhase.READY,
                    -> {
                        deleteOwnedForRecovery(paths.staging, paths)
                        if (!paths.target.exists() && paths.rollback.exists()) {
                            renameForRecovery(paths.rollback, paths.target)
                        }
                    }
                    MigrationPhase.TARGET_PARKING,
                    MigrationPhase.TARGET_PARKED,
                    MigrationPhase.ACTIVATED,
                    -> rollbackForRecovery(paths)
                }
                paths.journal.delete()
                paths.journalTemporary.delete()
                ExistingProfileRecoveryResult.Recovered
            } catch (failure: Exception) {
                Timber.e(failure, "Could not recover interrupted Classic migration")
                ExistingProfileRecoveryResult.Blocked(
                    "An interrupted Classic migration could not be rolled back safely. Retry recovery before opening the collection.",
                )
            }
        }

        private fun promoteTemporaryJournal(paths: MigrationPaths) {
            if (!paths.journalTemporary.exists()) return
            val temporary =
                runCatching { json.decodeFromString<MigrationJournal>(paths.journalTemporary.readText()) }
                    .getOrNull()
            if (temporary?.matches(paths) == true) {
                Os.rename(paths.journalTemporary.absolutePath, paths.journal.absolutePath)
                return
            }

            val stable =
                paths.journal
                    .takeIf(File::exists)
                    ?.let { file -> runCatching { json.decodeFromString<MigrationJournal>(file.readText()) }.getOrNull() }
            if (stable?.matches(paths) == true) {
                if (!paths.journalTemporary.delete() && paths.journalTemporary.exists()) {
                    throw ExistingProfileMigrationException("Invalid temporary migration metadata could not be removed.")
                }
                return
            }
            throw ExistingProfileMigrationException("No valid migration journal is available for recovery.")
        }

        private fun MigrationJournal.matches(paths: MigrationPaths): Boolean =
            version == JOURNAL_VERSION &&
                targetPath == paths.target.canonicalPath &&
                stagingPath == paths.staging.canonicalPath &&
                rollbackPath == paths.rollback.canonicalPath

        private fun rollbackForRecovery(paths: MigrationPaths) {
            if (paths.rollback.exists()) {
                if (paths.target.exists()) {
                    deleteOwnedForRecovery(paths.staging, paths)
                    renameForRecovery(paths.target, paths.staging)
                }
                renameForRecovery(paths.rollback, paths.target)
                deleteOwnedForRecovery(paths.staging, paths)
            } else if (paths.target.exists()) {
                deleteOwnedForRecovery(paths.staging, paths)
                renameForRecovery(paths.target, paths.staging)
                deleteOwnedForRecovery(paths.staging, paths)
            }
        }

        private fun deleteOwnedForRecovery(
            path: File,
            paths: MigrationPaths,
        ) {
            if (!path.exists()) return
            if (path != paths.staging && path != paths.rollback) {
                throw ExistingProfileMigrationException("Recovery refused a non-owned path: $path")
            }
            deleteRecoveryTree(path)
        }

        private fun deleteRecoveryTree(file: File) {
            val symbolic = runCatching { OsConstants.S_ISLNK(Os.lstat(file.absolutePath).st_mode) }.getOrDefault(false)
            if (file.isDirectory && !symbolic) {
                FileUtil.listFiles(file).forEach(::deleteRecoveryTree)
            }
            if (!file.delete() && file.exists()) throw IOException("Could not delete recovery path: $file")
        }

        private fun renameForRecovery(
            source: File,
            target: File,
        ) {
            if (target.exists()) throw IOException("Recovery destination exists: $target")
            Os.rename(source.absolutePath, target.absolutePath)
        }
    }
}

@Serializable
private data class MigrationJournal(
    val version: Int,
    val phase: MigrationPhase,
    val sourcePath: String,
    val targetPath: String,
    val stagingPath: String,
    val rollbackPath: String,
    val manifestDigest: String,
    val copiedFiles: Int,
    val copiedBytes: Long,
)

@Serializable
private enum class MigrationPhase {
    COPYING,
    READY,
    TARGET_PARKING,
    TARGET_PARKED,
    ACTIVATED,
    COMPLETE,
}

private data class MigrationManifest(
    val entries: List<MigrationManifestEntry>,
    val totalBytes: Long,
    val digest: String,
)

private data class MigrationManifestEntry(
    val relativePath: String,
    val size: Long,
    val sha256: String,
)

private data class MigrationPaths(
    val parent: File,
    val target: File,
    val staging: File,
    val rollback: File,
    val journal: File,
    val journalTemporary: File,
) {
    companion object {
        fun forTarget(target: File): MigrationPaths {
            val parent = target.parentFile ?: throw ExistingProfileMigrationException("Target has no parent: $target")
            val prefix = ".${target.name}.classic-migration"
            return MigrationPaths(
                parent = parent,
                target = target,
                staging = File(parent, "$prefix-stage"),
                rollback = File(parent, "$prefix-rollback"),
                journal = File(parent, "$prefix.json"),
                journalTemporary = File(parent, "$prefix.json.tmp"),
            )
        }
    }
}

private fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte) }
