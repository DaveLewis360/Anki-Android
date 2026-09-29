// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.ui.expressive

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.work.Data
import androidx.work.WorkInfo
import app.cash.turbine.test
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.worker.SyncAttemptRecord
import com.ichi2.anki.worker.SyncMediaPolicy
import com.ichi2.anki.worker.SyncMediaWorker
import com.ichi2.anki.worker.SyncOperation
import com.ichi2.anki.worker.SyncWorker
import com.ichi2.anki.worker.SyncWorkerOutcome
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class ExpressiveSyncViewModelTest : RobolectricTest() {
    private val viewModels = mutableListOf<ExpressiveSyncViewModel>()

    @After
    override fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        super.tearDown()
    }

    @Test
    fun `initial reconciliation blocks collection access until first snapshot`() =
        runTest {
            val gateway = FakeSyncGateway(emitInitialSnapshot = false)
            val viewModel = createViewModel(gateway)

            assertIs<ExpressiveSyncUiState.Checking>(viewModel.state.value)
            assertTrue(viewModel.state.value.isCollectionBlocking)
            viewModel.onSyncClick()
            assertEquals(0, gateway.starts.size)

            gateway.emitSnapshot(ExpressiveSyncSnapshot(phase = ExpressiveSyncWorkPhase.IDLE))
            runCurrent()

            assertIs<ExpressiveSyncUiState.Idle>(viewModel.state.value)
        }

    @Test
    fun `rapid sync taps start one logical attempt without raw startup failure`() =
        runTest {
            val gateway = FakeSyncGateway()
            val viewModel = createViewModel(gateway)
            runCurrent()

            viewModel.onSyncClick()
            viewModel.onSyncClick()
            runCurrent()

            assertEquals(1, gateway.starts.size)
            assertIs<ExpressiveSyncUiState.Queued>(viewModel.state.value)

            val failing = FakeSyncGateway().apply { startFailure = IllegalStateException("private provider detail") }
            val failedViewModel = createViewModel(failing)
            runCurrent()
            failedViewModel.onSyncClick()
            runCurrent()
            assertIs<ExpressiveSyncUiState.Failed>(failedViewModel.state.value)
        }

    @Test
    fun `collection and media phases cancel their exact work and only collection blocks`() =
        runTest {
            val gateway = FakeSyncGateway()
            val viewModel = createViewModel(gateway)
            runCurrent()
            val collectionId = UUID.randomUUID()
            val mediaId = UUID.randomUUID()

            gateway.mutableSnapshots.value =
                snapshot(ExpressiveSyncWorkPhase.COLLECTION_RUNNING, collectionId = collectionId, mediaId = mediaId)
            runCurrent()
            assertTrue(viewModel.state.value.isCollectionBlocking)
            viewModel.onSyncClick()
            assertEquals(collectionId, gateway.cancelledCollection)

            gateway.mutableSnapshots.value =
                snapshot(ExpressiveSyncWorkPhase.MEDIA_RUNNING, collectionId = collectionId, mediaId = mediaId)
            runCurrent()
            assertTrue(!viewModel.state.value.isCollectionBlocking)
            viewModel.onSyncClick()
            assertEquals(mediaId, gateway.cancelledMedia)
        }

    @Test
    fun `login success retries pending sync exactly once and survives recreation`() =
        runTest {
            val handle = SavedStateHandle()
            val gateway = FakeSyncGateway(loggedIn = false)
            val first = ExpressiveSyncViewModel(gateway, handle).also(viewModels::add)
            runCurrent()
            first.effects.test {
                first.onSyncClick()
                assertIs<ExpressiveSyncEffect.OpenAccount>(awaitItem())
            }
            first.viewModelScope.cancel()

            gateway.loggedIn = true
            val restored = ExpressiveSyncViewModel(gateway, handle).also(viewModels::add)
            runCurrent()

            assertTrue(restored.onLoginResult(succeeded = true))
            runCurrent()
            assertEquals(1, gateway.starts.size)
            assertTrue(!restored.onLoginResult(succeeded = true))
        }

    @Test
    fun `metered confirmation is durable and gates start`() =
        runTest {
            val handle = SavedStateHandle()
            val gateway = FakeSyncGateway(metered = true)
            val first = ExpressiveSyncViewModel(gateway, handle).also(viewModels::add)
            runCurrent()
            first.onSyncClick()
            assertIs<ExpressiveSyncUiState.AwaitingMeteredConfirmation>(first.state.value)
            first.viewModelScope.cancel()

            val restored = ExpressiveSyncViewModel(gateway, handle).also(viewModels::add)
            runCurrent()
            assertIs<ExpressiveSyncUiState.AwaitingMeteredConfirmation>(restored.state.value)
            restored.onMeteredSyncConfirmed()
            runCurrent()

            assertEquals(1, gateway.starts.size)
        }

    @Test
    fun `logical success waits for media terminal refreshes and acknowledges once`() =
        runTest {
            val gateway = FakeSyncGateway()
            val viewModel = createViewModel(gateway)
            runCurrent()
            val attemptId = UUID.randomUUID()
            val collectionId = UUID.randomUUID()
            val mediaId = UUID.randomUUID()

            gateway.mutableSnapshots.value =
                snapshot(
                    ExpressiveSyncWorkPhase.MEDIA_QUEUED,
                    attemptId = attemptId,
                    collectionId = collectionId,
                    mediaId = mediaId,
                )
            runCurrent()
            assertEquals(0, viewModel.refreshKey.value)
            assertIs<ExpressiveSyncUiState.MediaQueued>(viewModel.state.value)

            gateway.mutableSnapshots.value =
                snapshot(
                    ExpressiveSyncWorkPhase.SUCCEEDED,
                    attemptId = attemptId,
                    collectionId = collectionId,
                    mediaId = mediaId,
                )
            runCurrent()
            gateway.mutableSnapshots.value =
                snapshot(
                    ExpressiveSyncWorkPhase.MEDIA_RUNNING,
                    attemptId = attemptId,
                    collectionId = collectionId,
                    mediaId = mediaId,
                )
            runCurrent()
            gateway.mutableSnapshots.value =
                snapshot(
                    ExpressiveSyncWorkPhase.SUCCEEDED,
                    attemptId = attemptId,
                    collectionId = collectionId,
                    mediaId = mediaId,
                )
            runCurrent()

            assertEquals(1, viewModel.refreshKey.value)
            assertEquals(1, gateway.acknowledgements.size)
            assertIs<ExpressiveSyncUiState.Succeeded>(viewModel.state.value)
        }

    @Test
    fun `terminal acknowledgement is retried after durable commit failure and then marked once`() =
        runTest {
            val handle = SavedStateHandle()
            val gateway = FakeSyncGateway().apply { acknowledgementSucceeds = false }
            val terminal = snapshot(ExpressiveSyncWorkPhase.SUCCEEDED)
            val first = ExpressiveSyncViewModel(gateway, handle).also(viewModels::add)
            runCurrent()

            gateway.mutableSnapshots.value = terminal
            runCurrent()
            assertEquals(1, gateway.acknowledgements.size)
            first.viewModelScope.cancel()

            gateway.acknowledgementSucceeds = true
            val restored = ExpressiveSyncViewModel(gateway, handle).also(viewModels::add)
            runCurrent()
            assertEquals(2, gateway.acknowledgements.size)
            restored.viewModelScope.cancel()

            ExpressiveSyncViewModel(gateway, handle).also(viewModels::add)
            runCurrent()
            assertEquals(2, gateway.acknowledgements.size)
        }

    @Test
    fun `ambiguous full sync uses native second confirmation and preserves exact attempt after recreation`() =
        runTest {
            val handle = SavedStateHandle()
            val gateway = FakeSyncGateway()
            val first = ExpressiveSyncViewModel(gateway, handle).also(viewModels::add)
            runCurrent()
            val attemptId = UUID.randomUUID()
            val conflict =
                snapshot(
                    phase = ExpressiveSyncWorkPhase.FULL_SYNC_REQUIRED,
                    attemptId = attemptId,
                    serverMediaUsn = 42,
                    mediaPolicy = SyncMediaPolicy.ONLY_UNMETERED,
                )
            gateway.mutableSnapshots.value = conflict
            runCurrent()

            assertIs<ExpressiveSyncUiState.AwaitingFullSyncChoice>(first.state.value)
            assertEquals(1, gateway.acknowledgements.size)
            first.viewModelScope.cancel()

            gateway.mutableSnapshots.value = ExpressiveSyncSnapshot(phase = ExpressiveSyncWorkPhase.IDLE)
            val restored = ExpressiveSyncViewModel(gateway, handle).also(viewModels::add)
            runCurrent()
            assertIs<ExpressiveSyncUiState.AwaitingFullSyncChoice>(restored.state.value)
            restored.onKeepRemoteSelected()
            assertEquals(
                SyncOperation.FULL_DOWNLOAD,
                assertIs<ExpressiveSyncUiState.AwaitingFullSyncConfirmation>(restored.state.value).operation,
            )
            restored.onFullSyncConfirmed()
            runCurrent()

            val start = gateway.starts.single()
            assertEquals(SyncOperation.FULL_DOWNLOAD, start.operation)
            assertEquals(attemptId, start.attemptId)
            assertEquals(42, start.serverMediaUsn)
            assertEquals(SyncMediaPolicy.ONLY_UNMETERED, start.mediaPolicy)
        }

    @Test
    fun `projection follows exact IDs and treats media as the final terminal`() {
        val attemptId = UUID.randomUUID()
        val collectionId = UUID.randomUUID()
        val mediaId = UUID.randomUUID()
        val record =
            SyncAttemptRecord(
                attemptId = attemptId,
                credentialGeneration = "generation",
                operation = SyncOperation.NORMAL,
                mediaPolicy = SyncMediaPolicy.ALWAYS,
                collectionWorkId = collectionId,
                mediaWorkId = mediaId,
            )
        val collection = workInfo(collectionId, WorkInfo.State.SUCCEEDED)
        val media = workInfo(mediaId, WorkInfo.State.RUNNING, progress = "media progress")

        val running = expressiveSyncSnapshot(record, collection, media)
        assertEquals(ExpressiveSyncWorkPhase.MEDIA_RUNNING, running.phase)
        assertEquals(collectionId.toString(), running.collectionWorkId)
        assertEquals(mediaId.toString(), running.mediaWorkId)

        val succeeded = expressiveSyncSnapshot(record, collection, workInfo(mediaId, WorkInfo.State.SUCCEEDED))
        assertEquals(ExpressiveSyncWorkPhase.SUCCEEDED, succeeded.phase)
        assertEquals(ExpressiveSyncWorkPhase.IDLE, expressiveSyncSnapshot(record, null, null).phase)
        assertEquals(ExpressiveSyncWorkPhase.IDLE, expressiveSyncSnapshot(record, collection, null).phase)
    }

    @Test
    fun `typed output safely preserves full sync direction data without raw messages`() {
        val record =
            SyncAttemptRecord(
                attemptId = UUID.randomUUID(),
                credentialGeneration = "generation",
                operation = SyncOperation.NORMAL,
                mediaPolicy = SyncMediaPolicy.ALWAYS,
                collectionWorkId = UUID.randomUUID(),
                mediaWorkId = UUID.randomUUID(),
            )
        val full =
            workInfo(
                record.collectionWorkId,
                WorkInfo.State.FAILED,
                outcome = SyncWorkerOutcome.FULL_SYNC_REQUIRED,
                serverMediaUsn = 7,
            )
        val malformed =
            workInfo(
                record.collectionWorkId,
                WorkInfo.State.FAILED,
                rawOutcome = "private backend exception text",
            )

        assertEquals(7, expressiveSyncSnapshot(record, full, null).serverMediaUsn)
        assertEquals(ExpressiveSyncWorkPhase.FAILED, expressiveSyncSnapshot(record, malformed, null).phase)
        assertNull(expressiveSyncSnapshot(record, malformed, null).progress)
    }

    private fun createViewModel(gateway: ExpressiveSyncGateway): ExpressiveSyncViewModel =
        ExpressiveSyncViewModel(gateway).also(viewModels::add)

    private fun snapshot(
        phase: ExpressiveSyncWorkPhase,
        attemptId: UUID = UUID.randomUUID(),
        collectionId: UUID = UUID.randomUUID(),
        mediaId: UUID = UUID.randomUUID(),
        serverMediaUsn: Int? = null,
        mediaPolicy: SyncMediaPolicy = SyncMediaPolicy.ALWAYS,
    ) = ExpressiveSyncSnapshot(
        attemptId = attemptId.toString(),
        collectionWorkId = collectionId.toString(),
        mediaWorkId = mediaId.toString(),
        phase = phase,
        serverMediaUsn = serverMediaUsn,
        mediaPolicy = mediaPolicy,
    )

    private fun workInfo(
        id: UUID,
        state: WorkInfo.State,
        outcome: SyncWorkerOutcome? = null,
        rawOutcome: String? = null,
        progress: String? = null,
        serverMediaUsn: Int? = null,
    ): WorkInfo {
        val workInfo = mock<WorkInfo>()
        whenever(workInfo.id).thenReturn(id)
        whenever(workInfo.state).thenReturn(state)
        whenever(workInfo.progress).thenReturn(
            Data.Builder().apply { progress?.let { putString(SyncMediaWorker.PROGRESS_TEXT_KEY, it) } }.build(),
        )
        whenever(workInfo.outputData).thenReturn(
            Data
                .Builder()
                .apply {
                    putString(SyncWorker.OUTCOME_KEY, outcome?.name ?: rawOutcome)
                    serverMediaUsn?.let { putInt(SyncWorker.SERVER_MEDIA_USN_KEY, it) }
                }.build(),
        )
        return workInfo
    }

    private data class Start(
        val operation: SyncOperation,
        val attemptId: UUID,
        val serverMediaUsn: Int?,
        val mediaPolicy: SyncMediaPolicy,
    )

    private class FakeSyncGateway(
        var loggedIn: Boolean = true,
        var metered: Boolean = false,
        emitInitialSnapshot: Boolean = true,
    ) : ExpressiveSyncGateway {
        val mutableSnapshots = MutableStateFlow(ExpressiveSyncSnapshot(phase = ExpressiveSyncWorkPhase.IDLE))
        private val delayedSnapshots = MutableSharedFlow<ExpressiveSyncSnapshot>()
        override val snapshots: Flow<ExpressiveSyncSnapshot> =
            if (emitInitialSnapshot) mutableSnapshots else delayedSnapshots
        val starts = mutableListOf<Start>()
        val acknowledgements = mutableListOf<ExpressiveSyncSnapshot>()
        var cancelledCollection: UUID? = null
        var cancelledMedia: UUID? = null
        var startResult = ExpressiveSyncStartResult.STARTED
        var startFailure: Exception? = null
        var acknowledgementSucceeds = true

        suspend fun emitSnapshot(snapshot: ExpressiveSyncSnapshot) {
            delayedSnapshots.emit(snapshot)
        }

        override fun isLoggedIn(): Boolean = loggedIn

        override fun requiresMeteredConfirmation(): Boolean = metered

        override suspend fun start(
            operation: SyncOperation,
            attemptId: UUID,
            serverMediaUsn: Int?,
            mediaPolicy: SyncMediaPolicy,
        ): ExpressiveSyncStartResult {
            starts += Start(operation, attemptId, serverMediaUsn, mediaPolicy)
            startFailure?.let { throw it }
            return startResult
        }

        override fun cancelCollection(workId: UUID?) {
            cancelledCollection = workId
        }

        override fun cancelMedia(workId: UUID?) {
            cancelledMedia = workId
        }

        override fun acknowledgeTerminal(snapshot: ExpressiveSyncSnapshot): Boolean {
            acknowledgements += snapshot
            return acknowledgementSucceeds
        }
    }
}
