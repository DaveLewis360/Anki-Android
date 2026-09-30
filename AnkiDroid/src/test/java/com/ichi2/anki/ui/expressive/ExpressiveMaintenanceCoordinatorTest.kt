// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.ui.expressive

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import anki.card_rendering.EmptyCardsReport
import anki.media.CheckMediaResponse
import com.ichi2.anki.ExpressiveRestoreBlockedByMediaSyncException
import com.ichi2.anki.ExpressiveRestoreOutcome
import com.ichi2.anki.ExpressiveRestorePendingException
import com.ichi2.anki.RobolectricTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class ExpressiveMaintenanceCoordinatorTest : RobolectricTest() {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()
    private val coordinators = mutableListOf<ExpressiveMaintenanceCoordinatorViewModel>()
    private val entry = ExpressiveBackupEntry("backup-2026-01-02-03.04.colpkg", "2 Jan 2026 03:04", 2048)

    @After
    override fun tearDown() {
        coordinators.forEach { it.viewModelScope.cancel() }
        super.tearDown()
    }

    @Test
    fun `automatic backup stays invisible, never blocks the collection, and is single flight`() =
        runTest {
            val gateway = FakeMaintenanceGateway(backupGate = CompletableDeferred())
            val coordinator = coordinator(gateway = gateway)

            coordinator.maybeStartAutomaticBackup()
            coordinator.maybeStartAutomaticBackup()
            runCurrent()

            assertEquals(listOf(false), gateway.backupForces)
            assertIs<ExpressiveMaintenanceUiState.Idle>(coordinator.state.value)
            assertFalse(coordinator.state.value.isCollectionBlocking)
            assertTrue(coordinator.open(ExpressiveMaintenanceMode.BACKUPS))
            runCurrent()
            assertIs<ExpressiveMaintenanceUiState.Overview>(coordinator.state.value)

            gateway.backupGate!!.complete(Unit)
            runCurrent()
            assertFalse(coordinator.automaticBackupActive)
        }

    @Test
    fun `automatic backup is not attempted while a sync owns the collection`() =
        runTest {
            val gateway = FakeMaintenanceGateway()
            val coordinator = coordinator(gateway = gateway)
            coordinator.syncActivity = { ExpressiveMaintenanceSyncActivity.COLLECTION }

            coordinator.maybeStartAutomaticBackup()
            runCurrent()

            assertTrue(gateway.backupForces.isEmpty())
        }

    @Test
    fun `a running media sync defers neither automatic backups nor the database check`() =
        runTest {
            val gateway = FakeMaintenanceGateway()
            val coordinator = coordinator(gateway = gateway)
            coordinator.syncActivity = { ExpressiveMaintenanceSyncActivity.MEDIA }

            coordinator.maybeStartAutomaticBackup()
            runCurrent()
            assertEquals(listOf(false), gateway.backupForces)

            coordinator.open(ExpressiveMaintenanceMode.DATABASE)
            runCurrent()
            coordinator.requestDatabaseCheck()
            runCurrent()
            assertTrue(assertIs<ExpressiveMaintenanceUiState.Terminal>(coordinator.state.value).succeeded)
            assertEquals(1, gateway.databaseChecks)
        }

    @Test
    fun `a running media sync refuses media work with a specific reason`() =
        runTest {
            val gateway = FakeMaintenanceGateway()
            val coordinator = coordinator(gateway = gateway)
            coordinator.syncActivity = { ExpressiveMaintenanceSyncActivity.MEDIA }
            coordinator.open(ExpressiveMaintenanceMode.MEDIA)

            coordinator.checkMedia()
            runCurrent()

            val refused = assertIs<ExpressiveMaintenanceUiState.Terminal>(coordinator.state.value)
            assertEquals(ExpressiveMaintenanceError.MEDIA_SYNC_ACTIVE, refused.error)
            assertEquals(0, gateway.mediaChecks)
        }

    @Test
    fun `a media sync found inside the collection queue is reported as media sync`() =
        runTest {
            val gateway = FakeMaintenanceGateway(mediaFailure = MediaSyncActiveException())
            val coordinator = coordinator(gateway = gateway)
            coordinator.open(ExpressiveMaintenanceMode.MEDIA)

            coordinator.checkMedia()
            runCurrent()

            val refused = assertIs<ExpressiveMaintenanceUiState.Terminal>(coordinator.state.value)
            assertEquals(ExpressiveMaintenanceError.MEDIA_SYNC_ACTIVE, refused.error)
        }

    @Test
    fun `overview loading neither owns the collection nor blocks sync`() =
        runTest {
            val gateway = FakeMaintenanceGateway(backups = listOf(entry), overviewGate = CompletableDeferred())
            val coordinator = coordinator(gateway = gateway)

            assertTrue(coordinator.open(ExpressiveMaintenanceMode.BACKUPS))
            runCurrent()

            val loading = assertIs<ExpressiveMaintenanceUiState.Running>(coordinator.state.value)
            assertEquals(ExpressiveMaintenanceOperation.LOAD_OVERVIEW, loading.operation)
            assertFalse(coordinator.state.value.isCollectionBlocking)
            assertFalse(coordinator.state.value.blocksSync)

            gateway.overviewGate!!.complete(Unit)
            runCurrent()
            assertEquals(listOf(entry), assertIs<ExpressiveMaintenanceUiState.Overview>(coordinator.state.value).backups)
        }

    @Test
    fun `database check owns the collection, cannot be abandoned, and reports backend problems`() =
        runTest {
            val gateway = FakeMaintenanceGateway(databaseGate = CompletableDeferred(), databaseResult = listOf("Fixed 1 card."))
            val coordinator = coordinator(gateway = gateway)
            coordinator.open(ExpressiveMaintenanceMode.DATABASE)
            runCurrent()

            coordinator.requestDatabaseCheck()
            runCurrent()

            val running = assertIs<ExpressiveMaintenanceUiState.Running>(coordinator.state.value)
            assertEquals(ExpressiveMaintenanceOperation.DATABASE_CHECK, running.operation)
            assertTrue(coordinator.state.value.isCollectionBlocking)
            assertTrue(coordinator.state.value.blocksSync)
            assertFalse(coordinator.back(ExpressiveMaintenanceMode.DATABASE))
            assertFalse(coordinator.open(ExpressiveMaintenanceMode.BACKUPS))

            gateway.databaseGate!!.complete(Unit)
            runCurrent()

            val terminal = assertIs<ExpressiveMaintenanceUiState.Terminal>(coordinator.state.value)
            assertTrue(terminal.succeeded)
            assertEquals(listOf("Fixed 1 card."), terminal.messages)
            assertEquals(1, gateway.databaseChecks)
        }

    @Test
    fun `low storage requires explicit confirmation before the database check starts`() =
        runTest {
            val gateway = FakeMaintenanceGateway(databaseWarning = "Low space")
            val coordinator = coordinator(gateway = gateway)
            coordinator.open(ExpressiveMaintenanceMode.DATABASE)
            runCurrent()

            coordinator.requestDatabaseCheck()

            assertEquals("Low space", assertIs<ExpressiveMaintenanceUiState.ConfirmDatabaseCheck>(coordinator.state.value).warning)
            assertEquals(0, gateway.databaseChecks)
            coordinator.confirmDatabaseCheck()
            runCurrent()
            assertTrue(assertIs<ExpressiveMaintenanceUiState.Terminal>(coordinator.state.value).succeeded)
            assertEquals(1, gateway.databaseChecks)
        }

    @Test
    fun `sync exclusive maintenance is refused while a sync is active`() =
        runTest {
            val gateway = FakeMaintenanceGateway()
            val coordinator = coordinator(gateway = gateway)
            coordinator.syncActivity = { ExpressiveMaintenanceSyncActivity.COLLECTION }
            coordinator.open(ExpressiveMaintenanceMode.MEDIA)

            coordinator.checkMedia()
            runCurrent()

            val refused = assertIs<ExpressiveMaintenanceUiState.Terminal>(coordinator.state.value)
            assertEquals(ExpressiveMaintenanceError.SYNC_ACTIVE, refused.error)
            assertEquals(0, gateway.mediaChecks)
        }

    @Test
    fun `restore is validated before confirmation and its commit survives cancellation exactly once`() =
        runTest {
            val gateway = FakeMaintenanceGateway(backups = listOf(entry), commitGate = CompletableDeferred(), preparedGeneration = 7)
            val coordinator = coordinator(gateway = gateway)
            coordinator.open(ExpressiveMaintenanceMode.BACKUPS)
            runCurrent()

            coordinator.prepareLocalRestore(entry)
            runCurrent()

            val confirm = assertIs<ExpressiveMaintenanceUiState.ConfirmRestore>(coordinator.state.value)
            assertEquals(7, confirm.generation)
            assertEquals(listOf(entry.token), gateway.resolvedTokens)
            assertTrue(gateway.commits.isEmpty())

            coordinator.confirmRestore()
            runCurrent()
            assertTrue(coordinator.state.value.isCollectionBlocking)
            assertFalse(coordinator.back(ExpressiveMaintenanceMode.BACKUPS))

            coordinator.viewModelScope.cancel()
            gateway.commitGate!!.complete(Unit)
            runCurrent()

            assertEquals(listOf(7L), gateway.commits)
            assertTrue(assertIs<ExpressiveMaintenanceUiState.Terminal>(coordinator.state.value).succeeded)
        }

    @Test
    fun `restore confirmation during sync keeps the validated staging for a retry`() =
        runTest {
            val gateway = FakeMaintenanceGateway(backups = listOf(entry), preparedGeneration = 3)
            val coordinator = coordinator(gateway = gateway)
            var activity = ExpressiveMaintenanceSyncActivity.COLLECTION
            coordinator.syncActivity = { activity }
            coordinator.open(ExpressiveMaintenanceMode.BACKUPS)
            runCurrent()
            coordinator.prepareLocalRestore(entry)
            runCurrent()

            coordinator.confirmRestore()
            val blocked = assertIs<ExpressiveMaintenanceUiState.ConfirmRestore>(coordinator.state.value)
            assertEquals(ExpressiveMaintenanceError.SYNC_ACTIVE, blocked.blockedBy)

            activity = ExpressiveMaintenanceSyncActivity.MEDIA
            coordinator.confirmRestore()
            val mediaBlocked = assertIs<ExpressiveMaintenanceUiState.ConfirmRestore>(coordinator.state.value)
            assertEquals(ExpressiveMaintenanceError.MEDIA_SYNC_ACTIVE, mediaBlocked.blockedBy)
            assertTrue(gateway.commits.isEmpty())
            assertTrue(gateway.discards.isEmpty())

            activity = ExpressiveMaintenanceSyncActivity.NONE
            coordinator.confirmRestore()
            runCurrent()
            assertEquals(listOf(3L), gateway.commits)
        }

    @Test
    fun `a media sync found during activation keeps the validated staging for a retry`() =
        runTest {
            val gateway =
                FakeMaintenanceGateway(
                    backups = listOf(entry),
                    preparedGeneration = 4,
                    commitFailures = ArrayDeque(listOf(ExpressiveRestoreBlockedByMediaSyncException())),
                )
            val coordinator = coordinator(gateway = gateway)
            coordinator.open(ExpressiveMaintenanceMode.BACKUPS)
            runCurrent()
            coordinator.prepareLocalRestore(entry)
            runCurrent()

            coordinator.confirmRestore()
            runCurrent()

            val blocked = assertIs<ExpressiveMaintenanceUiState.ConfirmRestore>(coordinator.state.value)
            assertEquals(ExpressiveMaintenanceError.MEDIA_SYNC_ACTIVE, blocked.blockedBy)
            assertEquals(4, blocked.generation)
            assertTrue(gateway.discards.isEmpty())

            coordinator.confirmRestore()
            runCurrent()
            assertTrue(assertIs<ExpressiveMaintenanceUiState.Terminal>(coordinator.state.value).succeeded)
            assertEquals(listOf(4L, 4L), gateway.commits)
        }

    @Test
    fun `a failed safety backup fails the restore and releases its staging`() =
        runTest {
            val gateway =
                FakeMaintenanceGateway(
                    backups = listOf(entry),
                    preparedGeneration = 5,
                    commitFailures = ArrayDeque(listOf(SafetyBackupException(IOException("disk full")))),
                )
            val coordinator = coordinator(gateway = gateway)
            coordinator.open(ExpressiveMaintenanceMode.BACKUPS)
            runCurrent()
            coordinator.prepareLocalRestore(entry)
            runCurrent()

            coordinator.confirmRestore()
            runCurrent()

            val terminal = assertIs<ExpressiveMaintenanceUiState.Terminal>(coordinator.state.value)
            assertFalse(terminal.succeeded)
            assertEquals(ExpressiveMaintenanceError.BACKUP_FAILED, terminal.error)
            assertEquals(listOf<Long?>(5L), gateway.discards)
        }

    @Test
    fun `a restore reports quarantined media and a pending media merge`() =
        runTest {
            val gateway =
                FakeMaintenanceGateway(
                    backups = listOf(entry),
                    commitOutcomes =
                        ArrayDeque(
                            listOf(ExpressiveRestoreOutcome(quarantinedMediaFiles = 2), ExpressiveRestoreOutcome(mediaPending = true)),
                        ),
                )
            val coordinator = coordinator(gateway = gateway)
            coordinator.open(ExpressiveMaintenanceMode.BACKUPS)
            runCurrent()
            coordinator.prepareLocalRestore(entry)
            runCurrent()
            coordinator.confirmRestore()
            runCurrent()

            val quarantined = assertIs<ExpressiveMaintenanceUiState.Terminal>(coordinator.state.value)
            assertTrue(quarantined.succeeded)
            assertEquals(2, quarantined.affectedCount)
            assertNull(quarantined.error)

            coordinator.open(ExpressiveMaintenanceMode.BACKUPS)
            runCurrent()
            coordinator.prepareLocalRestore(entry)
            runCurrent()
            coordinator.confirmRestore()
            runCurrent()

            val pending = assertIs<ExpressiveMaintenanceUiState.Terminal>(coordinator.state.value)
            assertTrue(pending.succeeded)
            assertEquals(ExpressiveMaintenanceError.RESTORED_MEDIA_PENDING, pending.error)
        }

    @Test
    fun `a cleared coordinator releases the staging and import it still owned`() =
        runTest {
            val gateway = FakeMaintenanceGateway(backups = listOf(entry), preparedGeneration = 9)
            val store = ViewModelStore()
            val coordinator =
                ViewModelProvider(
                    store,
                    viewModelFactory {
                        initializer {
                            ExpressiveMaintenanceCoordinatorViewModel(
                                savedStateHandle = SavedStateHandle(),
                                gateway = gateway,
                                context = context,
                                automaticBackupDelayMillis = 0,
                                progressPollMillis = 0,
                                cleanupScope = this@runTest,
                            )
                        }
                    },
                )[ExpressiveMaintenanceCoordinatorViewModel::class.java]
            coordinator.open(ExpressiveMaintenanceMode.BACKUPS)
            runCurrent()
            coordinator.prepareLocalRestore(entry)
            runCurrent()
            assertIs<ExpressiveMaintenanceUiState.ConfirmRestore>(coordinator.state.value)

            store.clear()
            runCurrent()

            assertEquals(listOf<Long?>(9L), gateway.discards)
        }

    @Test
    fun `a manual backup of an unchanged collection reports that nothing was written`() =
        runTest {
            val gateway = FakeMaintenanceGateway(backupWritten = false)
            val coordinator = coordinator(gateway = gateway)
            coordinator.open(ExpressiveMaintenanceMode.BACKUPS)
            runCurrent()

            coordinator.createBackup()
            runCurrent()

            val terminal = assertIs<ExpressiveMaintenanceUiState.Terminal>(coordinator.state.value)
            assertTrue(terminal.succeeded)
            assertEquals(0, terminal.affectedCount)
            assertEquals(listOf(true), gateway.backupForces)
        }

    @Test
    fun `restored media kept aside is shown in the overview and can be merged again or deleted`() =
        runTest {
            val gateway = FakeMaintenanceGateway(quarantined = 3, quarantineRemainingAfterMerge = 1)
            val coordinator = coordinator(gateway = gateway)
            coordinator.open(ExpressiveMaintenanceMode.BACKUPS)
            runCurrent()
            assertEquals(3, assertIs<ExpressiveMaintenanceUiState.Overview>(coordinator.state.value).quarantinedMediaFiles)

            coordinator.mergeQuarantinedMedia()
            runCurrent()
            val merged = assertIs<ExpressiveMaintenanceUiState.Terminal>(coordinator.state.value)
            assertEquals(ExpressiveMaintenanceOperation.MERGE_QUARANTINED_MEDIA, merged.operation)
            assertEquals(1, merged.affectedCount, "files that still could not be merged")

            coordinator.open(ExpressiveMaintenanceMode.BACKUPS)
            runCurrent()
            assertEquals(1, assertIs<ExpressiveMaintenanceUiState.Overview>(coordinator.state.value).quarantinedMediaFiles)
            coordinator.deleteQuarantinedMedia()
            runCurrent()

            val deleted = assertIs<ExpressiveMaintenanceUiState.Terminal>(coordinator.state.value)
            assertEquals(ExpressiveMaintenanceOperation.DELETE_QUARANTINED_MEDIA, deleted.operation)
            assertTrue(deleted.succeeded)
            assertEquals(1, gateway.quarantineMerges)
            assertEquals(1, gateway.quarantineDeletes)
        }

    @Test
    fun `merging kept media waits for a running media sync but deleting it does not`() =
        runTest {
            val gateway = FakeMaintenanceGateway(quarantined = 2)
            val coordinator = coordinator(gateway = gateway)
            coordinator.syncActivity = { ExpressiveMaintenanceSyncActivity.MEDIA }
            coordinator.open(ExpressiveMaintenanceMode.BACKUPS)
            runCurrent()

            coordinator.mergeQuarantinedMedia()
            val refused = assertIs<ExpressiveMaintenanceUiState.Terminal>(coordinator.state.value)
            assertEquals(ExpressiveMaintenanceError.MEDIA_SYNC_ACTIVE, refused.error)
            assertEquals(0, gateway.quarantineMerges)

            coordinator.open(ExpressiveMaintenanceMode.BACKUPS)
            runCurrent()
            coordinator.deleteQuarantinedMedia()
            runCurrent()
            assertTrue(assertIs<ExpressiveMaintenanceUiState.Terminal>(coordinator.state.value).succeeded)
            assertEquals(1, gateway.quarantineDeletes)
        }

    @Test
    fun `an earlier restore that has not finished is reported specifically`() =
        runTest {
            val gateway = FakeMaintenanceGateway(backups = listOf(entry), prepareFailure = ExpressiveRestorePendingException())
            val coordinator = coordinator(gateway = gateway)
            coordinator.open(ExpressiveMaintenanceMode.BACKUPS)
            runCurrent()

            coordinator.prepareLocalRestore(entry)
            runCurrent()

            val terminal = assertIs<ExpressiveMaintenanceUiState.Terminal>(coordinator.state.value)
            assertEquals(ExpressiveMaintenanceError.PREVIOUS_RESTORE_UNFINISHED, terminal.error)
        }

    @Test
    fun `every new coordinator sweeps restore staging that no live preparation owns`() =
        runTest {
            val gateway = FakeMaintenanceGateway()
            val coordinator = coordinator(gateway = gateway)

            coordinator.cleanupInterruptedArtifacts()
            coordinator.cleanupInterruptedArtifacts()
            runCurrent()

            assertEquals(listOf<Long?>(null), gateway.discards)
        }

    @Test
    fun `invalid backup fails validation and never reaches confirmation`() =
        runTest {
            val gateway = FakeMaintenanceGateway(backups = listOf(entry), prepareFailure = InvalidBackupException())
            val coordinator = coordinator(gateway = gateway)
            coordinator.open(ExpressiveMaintenanceMode.BACKUPS)
            runCurrent()

            coordinator.prepareLocalRestore(entry)
            runCurrent()

            val terminal = assertIs<ExpressiveMaintenanceUiState.Terminal>(coordinator.state.value)
            assertEquals(ExpressiveMaintenanceOperation.PREPARE_RESTORE, terminal.operation)
            assertEquals(ExpressiveMaintenanceError.INVALID_BACKUP, terminal.error)
            assertTrue(gateway.commits.isEmpty())
        }

    @Test
    fun `back during validation cancels it, returns to the overview, and removes unowned staging`() =
        runTest {
            val gateway = FakeMaintenanceGateway(backups = listOf(entry), prepareGate = CompletableDeferred())
            val coordinator = coordinator(gateway = gateway)
            coordinator.open(ExpressiveMaintenanceMode.BACKUPS)
            runCurrent()
            coordinator.prepareLocalRestore(entry)
            runCurrent()
            assertIs<ExpressiveMaintenanceUiState.Running>(coordinator.state.value)

            assertFalse(coordinator.back(ExpressiveMaintenanceMode.BACKUPS))
            runCurrent()

            assertIs<ExpressiveMaintenanceUiState.Overview>(coordinator.state.value)
            assertEquals(listOf<Long?>(null), gateway.discards)
            assertTrue(coordinator.back(ExpressiveMaintenanceMode.BACKUPS))
            assertIs<ExpressiveMaintenanceUiState.Idle>(coordinator.state.value)
        }

    @Test
    fun `process death never replays an admitted restore and cleans up exactly once`() =
        runTest {
            val handle = SavedStateHandle()
            val first = coordinator(handle, FakeMaintenanceGateway(backups = listOf(entry), commitGate = CompletableDeferred()))
            first.open(ExpressiveMaintenanceMode.BACKUPS)
            runCurrent()
            first.prepareLocalRestore(entry)
            runCurrent()
            first.confirmRestore()
            runCurrent()
            assertIs<ExpressiveMaintenanceUiState.Running>(first.state.value)

            val secondGateway = FakeMaintenanceGateway()
            val restored = coordinator(handle, secondGateway)

            val terminal = assertIs<ExpressiveMaintenanceUiState.Terminal>(restored.state.value)
            assertEquals(ExpressiveMaintenanceOperation.RESTORE, terminal.operation)
            assertEquals(ExpressiveMaintenanceError.INTERRUPTED, terminal.error)
            assertTrue(secondGateway.commits.isEmpty())
            assertTrue(secondGateway.backupForces.isEmpty())

            restored.cleanupInterruptedArtifacts()
            restored.cleanupInterruptedArtifacts()
            runCurrent()
            assertEquals(listOf<Long?>(null), secondGateway.discards)
        }

    @Test
    fun `saved state never contains display names or provider paths`() =
        runTest {
            val private = ExpressiveBackupEntry(entry.token, "/storage/private/Secret collection", 1)
            val handle = SavedStateHandle()
            val coordinator = coordinator(handle, FakeMaintenanceGateway(backups = listOf(private)))
            coordinator.open(ExpressiveMaintenanceMode.BACKUPS)
            runCurrent()
            coordinator.prepareLocalRestore(private)
            runCurrent()
            assertIs<ExpressiveMaintenanceUiState.ConfirmRestore>(coordinator.state.value)

            val snapshot = requireNotNull(handle.get<String>(ExpressiveMaintenanceCoordinatorViewModel.STATE_KEY))
            assertFalse(snapshot.contains("Secret"))
            assertFalse(snapshot.contains("/storage"))
            assertFalse(snapshot.contains(private.token))
        }

    @Test
    fun `external restore stages an opaque copy and back removes it`() =
        runTest {
            val gateway = FakeMaintenanceGateway(prepareGate = CompletableDeferred())
            val handle = SavedStateHandle()
            val coordinator = coordinator(handle, gateway)
            val uri = "content://com.example.documents/private/Secret%20collection.colpkg".toUri()
            shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(byteArrayOf(1, 2, 3)))
            coordinator.open(ExpressiveMaintenanceMode.BACKUPS)
            runCurrent()

            coordinator.startRestorePicker()
            val launch = assertIs<ExpressiveMaintenanceLaunch.OpenRestoreDocument>(coordinator.launches.first())
            assertEquals(Intent.ACTION_OPEN_DOCUMENT, launch.intent.action)
            assertTrue(coordinator.markRestoreLaunchDispatched(launch.requestId))
            assertEquals(launch.requestId, coordinator.consumeRestoreResultRequestId())
            coordinator.stageExternalRestore(
                launch.requestId,
                Intent().setDataAndType(uri, EXPRESSIVE_COLPKG_MIME),
            )
            // The copy is made on a real IO thread: wait until isolated validation has started on it.
            awaitReal { gateway.prepareStarted.isCompleted }
            val token = requireNotNull(handle.get<String>(ExpressiveMaintenanceCoordinatorViewModel.PENDING_IMPORT_TOKEN_KEY))
            assertTrue(ExpressiveTransferFiles.isOpaqueToken(token))
            assertFalse(token.contains("Secret"))
            val staged = requireNotNull(ExpressiveTransferFiles.ownedImportFile(context, token))
            assertEquals(3, staged.length())

            assertFalse(coordinator.back(ExpressiveMaintenanceMode.BACKUPS))
            awaitReal { !staged.exists() && coordinator.state.value is ExpressiveMaintenanceUiState.Overview }

            assertNull(handle.get<String>(ExpressiveMaintenanceCoordinatorViewModel.PENDING_IMPORT_TOKEN_KEY))
            assertEquals(listOf<Long?>(null), gateway.discards)
        }

    @Test
    fun `a late result from abandoned work cannot overwrite the current state`() =
        runTest {
            val gateway = FakeMaintenanceGateway(emptyCardsGate = CompletableDeferred(), ignoreCancellation = true)
            val coordinator = coordinator(gateway = gateway)
            coordinator.open(ExpressiveMaintenanceMode.EMPTY_CARDS)
            coordinator.findEmptyCards()
            runCurrent()

            assertFalse(coordinator.back(ExpressiveMaintenanceMode.EMPTY_CARDS))
            gateway.emptyCardsGate!!.complete(Unit)
            runCurrent()

            assertIs<ExpressiveMaintenanceUiState.Overview>(coordinator.state.value)
        }

    @Test
    fun `empty card deletion forwards preserve notes and reports the backend count`() =
        runTest {
            val gateway = FakeMaintenanceGateway(emptyCardsReport = emptyCardsReport(), deletedCards = 1)
            val coordinator = coordinator(gateway = gateway)
            coordinator.open(ExpressiveMaintenanceMode.EMPTY_CARDS)
            coordinator.findEmptyCards()
            runCurrent()

            val found = assertIs<ExpressiveMaintenanceUiState.EmptyCardsFound>(coordinator.state.value)
            assertEquals(2, found.cardCount)
            assertEquals(1, found.notesAtRisk)
            assertEquals("Empty report", found.report)

            coordinator.deleteEmptyCards(preserveNotes = true)
            runCurrent()

            assertEquals(listOf(true), gateway.preserveNotes)
            assertEquals(1, assertIs<ExpressiveMaintenanceUiState.Terminal>(coordinator.state.value).affectedCount)
        }

    @Test
    fun `preserving notes keeps only the first card of notes that would be deleted`() {
        val report = emptyCardsReport()

        assertEquals(listOf(11L), emptyCardIdsToDelete(report, preserveNotes = true))
        assertEquals(listOf(10L, 11L), emptyCardIdsToDelete(report, preserveNotes = false))
    }

    @Test
    fun `media mutations act on the exact checked report`() =
        runTest {
            val media =
                CheckMediaResponse
                    .newBuilder()
                    .addMissing("missing.png")
                    .addMissingMediaNotes(5L)
                    .addUnused("unused.png")
                    .setHaveTrash(true)
                    .setReport("Missing: missing.png")
                    .build()
            val gateway = FakeMaintenanceGateway(mediaReport = media, taggedNotes = 1)
            val coordinator = coordinator(gateway = gateway)
            coordinator.open(ExpressiveMaintenanceMode.MEDIA)
            coordinator.checkMedia()
            runCurrent()

            val found = assertIs<ExpressiveMaintenanceUiState.MediaFound>(coordinator.state.value)
            assertEquals(1, found.missingCount)
            assertEquals(1, found.unusedCount)
            assertTrue(found.haveTrash)

            coordinator.tagMissingMedia()
            runCurrent()

            assertSame(media, gateway.taggedReports.single())
            assertEquals(1, assertIs<ExpressiveMaintenanceUiState.Terminal>(coordinator.state.value).affectedCount)
        }

    private fun coordinator(
        handle: SavedStateHandle = SavedStateHandle(),
        gateway: FakeMaintenanceGateway = FakeMaintenanceGateway(),
    ): ExpressiveMaintenanceCoordinatorViewModel =
        ExpressiveMaintenanceCoordinatorViewModel(
            savedStateHandle = handle,
            gateway = gateway,
            context = context,
            automaticBackupDelayMillis = 0,
            progressPollMillis = 0,
        ).also(coordinators::add)

    /** Polls in real time: staging copies on real IO threads that the test scheduler does not control. */
    private suspend fun awaitReal(condition: () -> Boolean) {
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(5_000) {
                while (!condition()) delay(10)
            }
        }
    }

    private fun emptyCardsReport(): EmptyCardsReport =
        EmptyCardsReport
            .newBuilder()
            .setReport("<b>Empty report</b>")
            .addNotes(
                EmptyCardsReport.NoteWithEmptyCards
                    .newBuilder()
                    .setNoteId(1L)
                    .addAllCardIds(listOf(10L, 11L))
                    .setWillDeleteNote(true),
            ).build()

    private class FakeMaintenanceGateway(
        private val backups: List<ExpressiveBackupEntry> = emptyList(),
        val backupGate: CompletableDeferred<Unit>? = null,
        val overviewGate: CompletableDeferred<Unit>? = null,
        val prepareGate: CompletableDeferred<Unit>? = null,
        val commitGate: CompletableDeferred<Unit>? = null,
        val databaseGate: CompletableDeferred<Unit>? = null,
        val emptyCardsGate: CompletableDeferred<Unit>? = null,
        private val ignoreCancellation: Boolean = false,
        private val preparedGeneration: Long = 1,
        private val prepareFailure: Exception? = null,
        private val databaseWarning: String? = null,
        private val databaseResult: List<String> = emptyList(),
        private val emptyCardsReport: EmptyCardsReport = EmptyCardsReport.getDefaultInstance(),
        private val deletedCards: Int = 0,
        private val mediaReport: CheckMediaResponse = CheckMediaResponse.getDefaultInstance(),
        private val mediaFailure: Exception? = null,
        private val taggedNotes: Int = 0,
        private val commitFailures: ArrayDeque<Exception> = ArrayDeque(),
        private val commitOutcomes: ArrayDeque<ExpressiveRestoreOutcome> = ArrayDeque(),
        private val backupWritten: Boolean = true,
        var quarantined: Int = 0,
        private val quarantineRemainingAfterMerge: Int = 0,
    ) : ExpressiveMaintenanceGateway {
        val prepareStarted = CompletableDeferred<Unit>()
        val backupForces = mutableListOf<Boolean>()
        val resolvedTokens = mutableListOf<String>()
        val commits = mutableListOf<Long>()
        val discards = mutableListOf<Long?>()
        val preserveNotes = mutableListOf<Boolean>()
        val taggedReports = mutableListOf<CheckMediaResponse>()
        var databaseChecks = 0
        var mediaChecks = 0
        var quarantineMerges = 0
        var quarantineDeletes = 0

        override suspend fun backupOverview(): Pair<List<ExpressiveBackupEntry>, ExpressiveBackupLimits> {
            overviewGate?.await()
            return backups to ExpressiveBackupLimits(30, 12, 10, 9)
        }

        override suspend fun createBackup(force: Boolean): Boolean {
            backupForces += force
            backupGate?.await()
            return backupWritten
        }

        override suspend fun deleteBackup(token: String) = Unit

        override suspend fun updateBackupLimits(limits: ExpressiveBackupLimits) = Unit

        override suspend fun resolveBackup(token: String): File {
            resolvedTokens += token
            return File(token)
        }

        override suspend fun prepareRestore(source: File): Long {
            prepareStarted.complete(Unit)
            prepareGate?.await()
            prepareFailure?.let { throw it }
            return preparedGeneration
        }

        override suspend fun commitRestore(generation: Long): ExpressiveRestoreOutcome {
            commitGate?.await()
            commits += generation
            commitFailures.removeFirstOrNull()?.let { throw it }
            return commitOutcomes.removeFirstOrNull() ?: ExpressiveRestoreOutcome()
        }

        override suspend fun discardPreparedRestore(generation: Long?) {
            discards += generation
        }

        override suspend fun databaseCheckWarning(): String? = databaseWarning

        override suspend fun checkDatabase(): List<String> {
            databaseGate?.await()
            databaseChecks++
            return databaseResult
        }

        override suspend fun findEmptyCards(): EmptyCardsReport {
            emptyCardsGate?.let { gate -> if (ignoreCancellation) withContext(NonCancellable) { gate.await() } else gate.await() }
            return emptyCardsReport
        }

        override suspend fun deleteEmptyCards(
            report: EmptyCardsReport,
            preserveNotes: Boolean,
        ): Int {
            this.preserveNotes += preserveNotes
            return deletedCards
        }

        override suspend fun checkMedia(): CheckMediaResponse {
            mediaFailure?.let { throw it }
            mediaChecks++
            return mediaReport
        }

        override suspend fun tagMissingMedia(report: CheckMediaResponse): Int {
            taggedReports += report
            return taggedNotes
        }

        override suspend fun trashUnusedMedia(report: CheckMediaResponse): Int = report.unusedCount

        override suspend fun restoreMediaTrash() = Unit

        override suspend fun emptyMediaTrash() = Unit

        override suspend fun rebuildMediaDatabase() = Unit

        override suspend fun quarantinedMediaCount(): Int = quarantined

        override suspend fun mergeQuarantinedMedia(): Int {
            quarantineMerges++
            quarantined = quarantineRemainingAfterMerge
            return quarantined
        }

        override suspend fun deleteQuarantinedMedia() {
            quarantineDeletes++
            quarantined = 0
        }

        override fun progress(operation: ExpressiveMaintenanceOperation): ExpressiveMaintenanceProgress? = null
    }
}
