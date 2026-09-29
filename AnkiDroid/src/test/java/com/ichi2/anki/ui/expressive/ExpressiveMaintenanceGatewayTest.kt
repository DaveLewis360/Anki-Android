// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.ui.expressive

import android.annotation.SuppressLint
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.BackupManager
import com.ichi2.anki.CollectionManager
import com.ichi2.anki.CollectionManager.TR
import com.ichi2.anki.ExpressiveRestoreBlockedByMediaSyncException
import com.ichi2.anki.ExpressiveRestoreOutcome
import com.ichi2.anki.ExpressiveRestorePendingException
import com.ichi2.anki.ExpressiveRestoreRecoveryException
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.collection.CollectionChange
import com.ichi2.anki.collection.DefaultCollectionRepository
import com.ichi2.anki.common.time.TimeManager
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Real backend and on-disk collection: backup, restore, journal recovery, and maintenance semantics. */
@RunWith(AndroidJUnit4::class)
class ExpressiveMaintenanceGatewayTest : RobolectricTest() {
    override fun getCollectionStorageMode() = CollectionStorageMode.ON_DISK

    private val gateway: DefaultExpressiveMaintenanceGateway
        get() = DefaultExpressiveMaintenanceGateway(ApplicationProvider.getApplicationContext<Context>())

    private val mediaFolder: File
        get() = File(CollectionManager.getCollectionDirectory(), "collection.media")

    @Before
    fun resetStagingOwner() {
        ExpressiveRestoreStaging.resetForTesting()
    }

    @After
    fun resetFaultInjection() {
        CollectionManager.expressiveRestorePhaseHookForTesting = null
        CollectionManager.mediaSyncStatusForTesting = null
    }

    @Test
    fun `restore replaces the collection and the forced safety backup captures the previous one`() =
        runTest {
            val base = col.noteCount()
            val backup = backupThenAddNote()
            val generation = gateway.prepareRestore(gateway.resolveBackup(backup.token))
            assertEquals(base + 2, col.noteCount(), "validation never touches the live collection")

            gateway.commitRestore(generation)

            assertEquals(base + 1, col.noteCount())
            assertNoRestoreArtifacts()
            val safety = gateway.backupOverview().first.first()
            gateway.commitRestore(gateway.prepareRestore(gateway.resolveBackup(safety.token)))
            assertEquals(base + 2, col.noteCount())
        }

    @Test
    fun `restoring a backup without media keeps every current media file`() =
        runTest {
            val backup = backupThenAddNote()
            val current = File(mediaFolder.apply { mkdirs() }, "current.png").apply { writeText("current") }

            gateway.commitRestore(gateway.prepareRestore(gateway.resolveBackup(backup.token)))

            assertEquals("current", current.readText())
        }

    @Test
    fun `restored media is merged so backup files return and files the backup lacks are kept`() =
        runTest {
            mediaFolder.mkdirs()
            File(mediaFolder, "shared.png").writeText("backup version")
            File(mediaFolder, "only-in-backup.png").writeText("restored")
            val colpkg = File(tempFolder.root, "with-media.colpkg")
            CollectionManager.exportCollectionPackageGuaranteed(colpkg.absolutePath, includeMedia = true, legacy = false)
            File(mediaFolder, "only-in-backup.png").delete()
            File(mediaFolder, "shared.png").writeText("current version")
            File(mediaFolder, "added-later.png").writeText("kept")

            gateway.commitRestore(gateway.prepareRestore(colpkg))

            assertEquals("restored", File(mediaFolder, "only-in-backup.png").readText())
            assertEquals("backup version", File(mediaFolder, "shared.png").readText())
            assertEquals("kept", File(mediaFolder, "added-later.png").readText())
            assertNoRestoreArtifacts()
        }

    @Test
    fun `a malformed package is rejected before the live collection is touched`() =
        runTest {
            addBasicNote("current", "note")
            val notes = col.noteCount()
            val malformed = File(tempFolder.root, "malformed.colpkg").apply { writeText("not a zip archive") }

            assertFailsWith<Exception> { gateway.prepareRestore(malformed) }

            assertEquals(notes, col.noteCount())
            assertNoRestoreArtifacts()
        }

    @Test
    fun `an activation interrupted by process death is rolled back before the next open`() =
        runTest {
            val base = col.noteCount()
            val backup = backupThenAddNote()
            val generation = gateway.prepareRestore(gateway.resolveBackup(backup.token))
            CollectionManager.expressiveRestorePhaseHookForTesting = { phase ->
                if (phase == "ACTIVATED") throw SimulatedProcessDeath()
            }

            assertFailsWith<SimulatedProcessDeath> { ExpressiveRestoreStaging.commit(generation) }
            CollectionManager.expressiveRestorePhaseHookForTesting = null

            assertEquals(base + 2, col.noteCount())
            assertNoRestoreArtifacts()
        }

    @Test
    fun `a completed activation interrupted before cleanup keeps the restored collection`() =
        runTest {
            val base = col.noteCount()
            val backup = backupThenAddNote()
            val generation = gateway.prepareRestore(gateway.resolveBackup(backup.token))
            CollectionManager.expressiveRestorePhaseHookForTesting = { phase ->
                if (phase == "COMPLETE") throw SimulatedProcessDeath()
            }

            assertFailsWith<SimulatedProcessDeath> { ExpressiveRestoreStaging.commit(generation) }
            CollectionManager.expressiveRestorePhaseHookForTesting = null
            CollectionManager.ensureClosed()

            assertEquals(base + 1, col.noteCount())
            assertNoRestoreArtifacts()
        }

    @Test
    fun `a failed activation rolls back in process and reopens the previous collection`() =
        runTest {
            val base = col.noteCount()
            val backup = backupThenAddNote()
            val generation = gateway.prepareRestore(gateway.resolveBackup(backup.token))
            CollectionManager.expressiveRestorePhaseHookForTesting = { phase ->
                if (phase == "PARKED") throw IOException("simulated rename failure")
            }

            assertFailsWith<IOException> { ExpressiveRestoreStaging.commit(generation) }
            CollectionManager.expressiveRestorePhaseHookForTesting = null

            assertEquals(base + 2, col.noteCount())
            assertNoRestoreArtifacts()
        }

    @Test
    fun `a stale generation cannot commit or discard newer staging`() =
        runTest {
            val base = col.noteCount()
            val backup = backupThenAddNote()
            val stale = gateway.prepareRestore(gateway.resolveBackup(backup.token))
            val current = gateway.prepareRestore(gateway.resolveBackup(backup.token))

            gateway.discardPreparedRestore(stale)
            assertFailsWith<InvalidBackupException> { ExpressiveRestoreStaging.commit(stale) }
            assertTrue(CollectionManager.expressiveRestoreStagingDirectory().isDirectory)

            gateway.commitRestore(current)
            assertEquals(base + 1, col.noteCount())
        }

    @Test
    fun `empty card deletion keeps the first card of a preserved note`() =
        runTest {
            val notes = col.noteCount()
            val cards = col.cardCount()
            addEmptyClozeNote()
            val report = gateway.findEmptyCards()
            assertTrue(report.notesList.single().willDeleteNote)

            assertEquals(2, gateway.deleteEmptyCards(report, preserveNotes = true))

            assertEquals(notes + 1, col.noteCount())
            assertEquals(cards + 1, col.cardCount())
        }

    @Test
    fun `empty card deletion without preservation removes the note`() =
        runTest {
            val notes = col.noteCount()
            addEmptyClozeNote()
            val report = gateway.findEmptyCards()

            assertEquals(3, gateway.deleteEmptyCards(report, preserveNotes = false))

            assertEquals(notes, col.noteCount())
        }

    @Test
    fun `database check publishes exactly one broad collection change`() =
        runTest {
            addBasicNote("front", "back")
            val events = mutableListOf<CollectionChange>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { DefaultCollectionRepository.changes.toList(events) }

            gateway.checkDatabase()
            runCurrent()

            val changes = events.single().changes
            assertTrue(changes.card && changes.note && changes.deck && changes.studyQueues && changes.browserTable && changes.noteText)
        }

    @Test
    fun `tagging missing media changes exactly the affected notes`() =
        runTest {
            val affected = addBasicNote("<img src=\"expressive-missing.png\">", "back")
            val unaffected = addBasicNote("plain", "back")

            val report = gateway.checkMedia()
            assertEquals(listOf(affected.id), report.missingMediaNotesList)
            assertEquals(1, gateway.tagMissingMedia(report))

            val tag = TR.mediaCheckMissingMediaTag()
            assertTrue(col.getNote(affected.id).hasTag(col, tag))
            assertFalse(col.getNote(unaffected.id).hasTag(col, tag))
        }

    @Test
    fun `retention settings round trip through the backend`() =
        runTest {
            gateway.updateBackupLimits(ExpressiveBackupLimits(minimumIntervalMinutes = 45, daily = 3, weekly = 2, monthly = 1))

            assertEquals(ExpressiveBackupLimits(45, 3, 2, 1), gateway.backupOverview().second)
        }

    @Test
    fun `a forced backup the backend skips as unchanged still leaves a new backup file`() =
        runTest {
            addBasicNote("kept", "one")
            assertTrue(gateway.createBackup(force = true))
            gateway.backupOverview().first.forEach { gateway.deleteBackup(it.token) }
            assertTrue(gateway.backupOverview().first.isEmpty())

            // Nothing changed since the backend's last attempt, whose file no longer exists.
            assertTrue(gateway.createBackup(force = true))

            val guaranteed = gateway.backupOverview().first.single()
            assertTrue(guaranteed.token.startsWith(appClockBackupPrefix()), "written by the guaranteed path: ${guaranteed.token}")
        }

    @Test
    fun `a restore after the newest backup was deleted still writes a restorable safety backup`() =
        runTest {
            val base = col.noteCount()
            addBasicNote("in package", "one")
            val colpkg = File(tempFolder.root, "older.colpkg")
            CollectionManager.exportCollectionPackageGuaranteed(colpkg.absolutePath, includeMedia = false, legacy = false)
            addBasicNote("current", "two")
            assertTrue(gateway.createBackup(force = true))
            gateway.backupOverview().first.forEach { gateway.deleteBackup(it.token) }

            gateway.commitRestore(gateway.prepareRestore(colpkg))

            assertEquals(base + 1, col.noteCount())
            val safety = gateway.backupOverview().first.single()
            assertTrue(safety.token.startsWith(appClockBackupPrefix()), "written by the guaranteed path: ${safety.token}")
            gateway.commitRestore(gateway.prepareRestore(gateway.resolveBackup(safety.token)))
            assertEquals(base + 2, col.noteCount())
            assertNoRestoreArtifacts()
        }

    @Test
    fun `a failing safety backup leaves the collection and the prepared staging untouched`() =
        runTest {
            val backup = backupThenAddNote()
            val notes = col.noteCount()
            val generation = gateway.prepareRestore(gateway.resolveBackup(backup.token))
            val folder = File(BackupManager.getBackupDirectoryFromCollection(col.colDb))
            assertTrue(folder.deleteRecursively())
            // A regular file where the backup folder belongs: no backup can be written.
            folder.writeText("not a folder")
            try {
                assertFailsWith<SafetyBackupException> { gateway.commitRestore(generation) }

                assertEquals(notes, col.noteCount())
                assertTrue(CollectionManager.expressiveRestoreStagingDirectory().isDirectory)
            } finally {
                folder.delete()
                gateway.discardPreparedRestore(generation)
            }
            assertNoRestoreArtifacts()
        }

    @Test
    fun `a failed rollback keeps the journal and the previous collection returns before the next open`() =
        runTest {
            val base = col.noteCount()
            val backup = backupThenAddNote()
            val generation = gateway.prepareRestore(gateway.resolveBackup(backup.token))
            CollectionManager.expressiveRestorePhaseHookForTesting = { phase ->
                when (phase) {
                    "ACTIVATED" -> throw IOException("simulated activation failure")
                    CollectionManager.ROLLBACK_HOOK -> throw IOException("simulated rollback failure")
                }
            }

            assertFailsWith<ExpressiveRestoreRecoveryException> { ExpressiveRestoreStaging.commit(generation) }
            CollectionManager.expressiveRestorePhaseHookForTesting = null

            assertTrue(restoreArtifacts().any { it.name.endsWith(".expressive-restore.properties") }, "the journal is retained")
            assertEquals(base + 2, col.noteCount())
            assertNoRestoreArtifacts()
        }

    @Test
    fun `process death while parking is rolled back before the next open`() =
        runTest {
            val base = col.noteCount()
            val backup = backupThenAddNote()
            val generation = gateway.prepareRestore(gateway.resolveBackup(backup.token))
            CollectionManager.expressiveRestorePhaseHookForTesting = { phase ->
                if (phase == "PARKING") throw SimulatedProcessDeath()
            }

            assertFailsWith<SimulatedProcessDeath> { ExpressiveRestoreStaging.commit(generation) }
            CollectionManager.expressiveRestorePhaseHookForTesting = null

            assertEquals(base + 2, col.noteCount())
            assertNoRestoreArtifacts()
        }

    @Test
    fun `process death partway through the swap is rolled back before the next open`() =
        runTest {
            val base = col.noteCount()
            val backup = backupThenAddNote()
            val generation = gateway.prepareRestore(gateway.resolveBackup(backup.token))
            val staging = CollectionManager.expressiveRestoreStagingDirectory()
            val target = CollectionManager.getCollectionDirectory()
            CollectionManager.expressiveRestorePhaseHookForTesting = { phase ->
                if (phase == "PARKED") {
                    // One restored file is already in place when the process dies.
                    assertTrue(File(staging, "collection.anki2").renameTo(File(target, "collection.anki2")))
                    throw SimulatedProcessDeath()
                }
            }

            assertFailsWith<SimulatedProcessDeath> { ExpressiveRestoreStaging.commit(generation) }
            CollectionManager.expressiveRestorePhaseHookForTesting = null

            assertEquals(base + 2, col.noteCount())
            assertNoRestoreArtifacts()
        }

    @Test
    fun `a media merge interrupted by process death resumes before the next open`() =
        runTest {
            mediaFolder.mkdirs()
            File(mediaFolder, "restored.png").writeText("restored")
            val colpkg = File(tempFolder.root, "with-media.colpkg")
            CollectionManager.exportCollectionPackageGuaranteed(colpkg.absolutePath, includeMedia = true, legacy = false)
            File(mediaFolder, "restored.png").delete()
            val generation = gateway.prepareRestore(colpkg)
            CollectionManager.expressiveRestorePhaseHookForTesting = { phase ->
                if (phase == "COMPLETE") throw SimulatedProcessDeath()
            }

            assertFailsWith<SimulatedProcessDeath> { ExpressiveRestoreStaging.commit(generation) }
            CollectionManager.expressiveRestorePhaseHookForTesting = null
            assertFalse(File(mediaFolder, "restored.png").exists(), "the merge had not started")
            CollectionManager.ensureClosed()
            col.noteCount()

            assertEquals("restored", File(mediaFolder, "restored.png").readText())
            assertNoRestoreArtifacts()
        }

    @Test
    fun `restored media that cannot be merged is quarantined instead of deleted`() =
        runTest {
            mediaFolder.mkdirs()
            File(mediaFolder, "blocked.png").writeText("restored")
            File(mediaFolder, "fine.png").writeText("fine")
            val colpkg = File(tempFolder.root, "with-media.colpkg")
            CollectionManager.exportCollectionPackageGuaranteed(colpkg.absolutePath, includeMedia = true, legacy = false)
            File(mediaFolder, "fine.png").delete()
            File(mediaFolder, "blocked.png").delete()
            // A non-empty folder with the same name makes that one rename fail deterministically.
            File(File(mediaFolder, "blocked.png").apply { mkdirs() }, "inner").writeText("current")

            val outcome = gateway.commitRestore(gateway.prepareRestore(colpkg))

            assertEquals(ExpressiveRestoreOutcome(quarantinedMediaFiles = 1), outcome)
            assertEquals("fine", File(mediaFolder, "fine.png").readText())
            assertEquals("current", File(File(mediaFolder, "blocked.png"), "inner").readText())
            val quarantine = File(CollectionManager.getCollectionDirectory(), "broken/expressive-restore-media-1")
            assertEquals("restored", File(quarantine, "blocked.png").readText())
            assertNoRestoreArtifacts()
        }

    @Test
    fun `a manual backup of an unchanged collection writes nothing while its latest backup still holds it`() =
        runTest {
            addBasicNote("kept", "one")
            assertTrue(gateway.createBackup(force = true))
            val before = gateway.backupOverview().first

            assertFalse(gateway.createBackup(force = true))

            assertEquals(before, gateway.backupOverview().first)
        }

    @Test
    fun `a manual backup after a failed attempt writes an explicit copy although the backend skips it`() =
        runTest {
            addBasicNote("kept", "one")
            assertTrue(gateway.createBackup(force = true))
            addBasicNote("changed", "two")
            val folder = File(BackupManager.getBackupDirectoryFromCollection(col.colDb))
            val kept = folder.listFiles().orEmpty().map { it.name to it.readBytes() }
            assertTrue(folder.deleteRecursively())
            // A regular file where the backup folder belongs: the backend's attempt at this change fails.
            folder.writeText("not a folder")
            assertFailsWith<Exception> { gateway.createBackup(force = true) }
            assertTrue(folder.delete() && folder.mkdirs())
            kept.forEach { (name, bytes) -> File(folder, name).writeBytes(bytes) }

            // The backend skips the retry, as its last attempt saw this change; that attempt left no file.
            assertTrue(gateway.createBackup(force = true))

            val backups = gateway.backupOverview().first
            assertEquals(2, backups.size)
            assertTrue(backups.any { it.token.startsWith(appClockBackupPrefix()) }, "written by the explicit copy: $backups")
        }

    @Test
    fun `a manual backup never closes the collection under a running media sync`() =
        runTest {
            addBasicNote("kept", "one")
            assertTrue(gateway.createBackup(force = true))
            gateway.backupOverview().first.forEach { gateway.deleteBackup(it.token) }
            CollectionManager.mediaSyncStatusForTesting = { true }

            assertFailsWith<MediaSyncActiveException> { gateway.createBackup(force = true) }

            assertTrue(gateway.backupOverview().first.isEmpty())
        }

    @Test
    fun `a media sync status that throws is treated as not running`() =
        runTest {
            val base = col.noteCount()
            val backup = backupThenAddNote()
            val generation = gateway.prepareRestore(gateway.resolveBackup(backup.token))
            CollectionManager.mediaSyncStatusForTesting = { throw IllegalStateException("stored error of a finished media sync") }

            gateway.commitRestore(generation)
            gateway.checkMedia()

            assertEquals(base + 1, col.noteCount())
            assertNoRestoreArtifacts()
        }

    @Test
    fun `an in-queue media sync refusal hands the validated staging back to its owner`() =
        runTest {
            val base = col.noteCount()
            val backup = backupThenAddNote()
            val generation = gateway.prepareRestore(gateway.resolveBackup(backup.token))
            CollectionManager.mediaSyncStatusForTesting = { true }

            assertFailsWith<ExpressiveRestoreBlockedByMediaSyncException> { ExpressiveRestoreStaging.commit(generation) }

            assertEquals(base + 2, col.noteCount())
            assertTrue(CollectionManager.expressiveRestoreStagingDirectory().isDirectory)
            CollectionManager.mediaSyncStatusForTesting = null
            ExpressiveRestoreStaging.commit(generation)
            assertEquals(base + 1, col.noteCount())
            assertNoRestoreArtifacts()
        }

    @Test
    fun `a failing explicit safety copy leaves the collection and the prepared staging untouched`() =
        runTest {
            val backup = backupThenAddNote()
            val notes = col.noteCount()
            val generation = gateway.prepareRestore(gateway.resolveBackup(backup.token))
            // The backend then skips the safety backup as unchanged, so the explicit copy is required.
            assertTrue(gateway.createBackup(force = true))
            gateway.backupOverview().first.forEach { gateway.deleteBackup(it.token) }
            val folder = File(BackupManager.getBackupDirectoryFromCollection(col.colDb))
            assertTrue(folder.deleteRecursively())
            folder.writeText("not a folder")
            try {
                assertFailsWith<SafetyBackupException> { gateway.commitRestore(generation) }

                assertEquals(notes, col.noteCount())
                assertTrue(CollectionManager.expressiveRestoreStagingDirectory().isDirectory)
            } finally {
                folder.delete()
                gateway.discardPreparedRestore(generation)
            }
            assertNoRestoreArtifacts()
        }

    @Test
    fun `a finish that cannot quarantine keeps its journal, refuses the next restore, and completes at the next sweep`() =
        runTest {
            val colpkg = packageWithBlockedMedia()
            val obstacle = File(CollectionManager.getCollectionDirectory(), "broken").apply { writeText("not a folder") }

            val outcome = gateway.commitRestore(gateway.prepareRestore(colpkg))

            assertEquals(ExpressiveRestoreOutcome(mediaPending = true), outcome)
            assertTrue(restoreArtifacts().any { it.name.endsWith(".expressive-restore.properties") }, "the journal is retained")
            assertFailsWith<ExpressiveRestorePendingException> { gateway.prepareRestore(colpkg) }
            assertTrue(obstacle.delete())

            gateway.discardPreparedRestore(null)

            assertNoRestoreArtifacts()
            assertEquals(1, gateway.quarantinedMediaCount())
            // Once the conflicting folder is gone, the kept file can be added again.
            assertTrue(File(mediaFolder, "blocked.png").deleteRecursively())
            assertEquals(0, gateway.mergeQuarantinedMedia())
            assertEquals("restored", File(mediaFolder, "blocked.png").readText())
            assertEquals(0, gateway.quarantinedMediaCount())
        }

    @Test
    fun `a deferred finish never merges restored media while a media sync runs`() =
        runTest {
            val colpkg = packageWithBlockedMedia()
            val obstacle = File(CollectionManager.getCollectionDirectory(), "broken").apply { writeText("not a folder") }
            assertTrue(gateway.commitRestore(gateway.prepareRestore(colpkg)).mediaPending)
            assertTrue(obstacle.delete())
            CollectionManager.mediaSyncStatusForTesting = { true }

            gateway.discardPreparedRestore(null)

            assertTrue(restoreArtifacts().any { it.name.endsWith(".expressive-restore.properties") }, "the journal waits")
            assertEquals(0, gateway.quarantinedMediaCount())
            assertFailsWith<ExpressiveRestoreBlockedByMediaSyncException> { gateway.prepareRestore(colpkg) }

            CollectionManager.mediaSyncStatusForTesting = null
            gateway.discardPreparedRestore(null)
            assertNoRestoreArtifacts()
            assertEquals(1, gateway.quarantinedMediaCount())
        }

    @Test
    fun `kept restored media can be deleted without touching the media folder`() =
        runTest {
            val colpkg = packageWithBlockedMedia()
            assertEquals(1, gateway.commitRestore(gateway.prepareRestore(colpkg)).quarantinedMediaFiles)
            assertEquals(1, gateway.quarantinedMediaCount())

            gateway.deleteQuarantinedMedia()

            assertEquals(0, gateway.quarantinedMediaCount())
            assertEquals("current", File(File(mediaFolder, "blocked.png"), "inner").readText())
            assertFalse(File(CollectionManager.getCollectionDirectory(), "broken/expressive-restore-media-1").exists())
        }

    @Test
    fun `a collection package import settles a retained restore journal before replacing`() =
        runTest {
            val base = col.noteCount()
            val restored = packageWithBlockedMedia()
            addBasicNote("only in the replacement", "one")
            val replacement = File(tempFolder.root, "replacement.colpkg")
            CollectionManager.exportCollectionPackageGuaranteed(replacement.absolutePath, includeMedia = false, legacy = false)
            val obstacle = File(CollectionManager.getCollectionDirectory(), "broken").apply { writeText("not a folder") }
            assertTrue(gateway.commitRestore(gateway.prepareRestore(restored)).mediaPending)
            assertEquals(base, col.noteCount())

            assertFailsWith<ExpressiveRestorePendingException> { CollectionManager.importColpkg(replacement.absolutePath) }
            assertEquals(base, col.noteCount(), "the replacement is refused while the journal cannot be settled")

            assertTrue(obstacle.delete())
            CollectionManager.importColpkg(replacement.absolutePath)

            assertEquals(base + 1, col.noteCount())
            assertNoRestoreArtifacts()
            assertEquals(1, gateway.quarantinedMediaCount())
        }

    @Test
    fun `staging that no live preparation owns is swept and owned staging is kept`() =
        runTest {
            val staging = CollectionManager.expressiveRestoreStagingDirectory()
            File(File(staging, "collection.media").apply { mkdirs() }, "orphan.png").writeText("orphan")

            gateway.discardPreparedRestore(null)
            assertNoRestoreArtifacts()

            val backup = backupThenAddNote()
            val generation = gateway.prepareRestore(gateway.resolveBackup(backup.token))
            gateway.discardPreparedRestore(null)
            assertTrue(staging.isDirectory, "a live preparation keeps its staging")

            gateway.discardPreparedRestore(generation)
            assertNoRestoreArtifacts()
        }

    /** Adds a note, backs up, then adds another: the backup holds one note fewer than the live collection. */
    private suspend fun backupThenAddNote(): ExpressiveBackupEntry {
        addBasicNote("kept", "one")
        assertTrue(gateway.createBackup(force = true))
        val backup = gateway.backupOverview().first.first()
        addBasicNote("current", "two")
        return backup
    }

    /** A cloze note whose three cards are all empty, so deleting all of them would delete the note. */
    @SuppressLint("CheckResult")
    private fun addEmptyClozeNote() {
        addNoteUsingNoteTypeName("Cloze", "{{c1::Hello}} {{c3::There}} {{c2::World}}", "").apply {
            setField(0, "No cloze")
            col.updateNote(this)
        }
    }

    /**
     * A package whose `blocked.png` cannot be merged back: a non-empty folder of that name now
     * occupies the live media folder. The package holds the collection as it is now.
     */
    private suspend fun packageWithBlockedMedia(): File {
        mediaFolder.mkdirs()
        File(mediaFolder, "blocked.png").writeText("restored")
        val colpkg = File(tempFolder.root, "blocked-media.colpkg")
        CollectionManager.exportCollectionPackageGuaranteed(colpkg.absolutePath, includeMedia = true, legacy = false)
        File(mediaFolder, "blocked.png").delete()
        File(File(mediaFolder, "blocked.png").apply { mkdirs() }, "inner").writeText("current")
        return colpkg
    }

    /**
     * Guaranteed copies are named from the app clock (a fixed [com.ichi2.anki.common.time.MockTime]
     * in tests), while the backend names its own backups from the real clock.
     */
    private fun appClockBackupPrefix(): String =
        "backup-" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(TimeManager.time.intTimeMS()))

    private fun assertNoRestoreArtifacts() {
        val leftovers = restoreArtifacts()
        assertTrue(leftovers.isEmpty(), "unexpected restore artifacts: $leftovers")
    }

    private fun restoreArtifacts(): List<File> {
        val parent = requireNotNull(CollectionManager.getCollectionDirectory().canonicalFile.parentFile)
        return parent.listFiles().orEmpty().filter { ".expressive-restore" in it.name }
    }

    private class SimulatedProcessDeath : Error("simulated process death")
}
