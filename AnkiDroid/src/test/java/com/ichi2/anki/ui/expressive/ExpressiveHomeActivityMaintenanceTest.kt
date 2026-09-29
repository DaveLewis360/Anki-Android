// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.ui.expressive

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import anki.card_rendering.EmptyCardsReport
import anki.media.CheckMediaResponse
import com.ichi2.anki.ExpressiveRestoreOutcome
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.clearLogin
import com.ichi2.anki.worker.SyncAttemptRecord
import com.ichi2.anki.worker.SyncAttemptStore
import com.ichi2.anki.worker.SyncMediaPolicy
import com.ichi2.anki.worker.SyncOperation
import com.ichi2.anki.worker.SyncWorkIds
import com.ichi2.anki.worker.SyncWorker
import com.ichi2.anki.worker.UniqueWorkNames
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The Activity's maintenance wiring: route back handling and sync admission. */
@RunWith(AndroidJUnit4::class)
class ExpressiveHomeActivityMaintenanceTest : RobolectricTest() {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @After
    fun resetGateway() {
        ExpressiveMaintenanceCoordinatorViewModel.gatewayForTesting = null
    }

    @Test
    fun `system back never abandons an admitted operation and pops the route once it has finished`() =
        runTest {
            val gateway = GatedGateway()
            ExpressiveMaintenanceCoordinatorViewModel.gatewayForTesting = gateway
            val route = ExpressiveDestination.Maintenance(ExpressiveMaintenanceMode.DATABASE)
            val controller =
                Robolectric
                    .buildActivity(ExpressiveHomeActivity::class.java, maintenanceIntent(route))
                    .create()
                    .start()
                    .resume()
            val activity = controller.get()
            val maintenance = activity.maintenanceForTesting
            if (maintenance.state.value is ExpressiveMaintenanceUiState.Idle) maintenance.open(route.mode)
            runCurrent()
            assertIs<ExpressiveMaintenanceUiState.Overview>(maintenance.state.value)

            maintenance.requestDatabaseCheck()
            runCurrent()
            assertIs<ExpressiveMaintenanceUiState.Running>(maintenance.state.value)
            activity.onBackPressedDispatcher.onBackPressed()
            runCurrent()

            assertEquals(route, activity.navigationForTesting.backStack.value.current)
            assertIs<ExpressiveMaintenanceUiState.Running>(maintenance.state.value)

            gateway.databaseGate.complete(Unit)
            runCurrent()
            assertIs<ExpressiveMaintenanceUiState.Terminal>(maintenance.state.value)
            // Back from a result returns to the overview first, then leaves the route.
            activity.onBackPressedDispatcher.onBackPressed()
            runCurrent()
            assertEquals(route, activity.navigationForTesting.backStack.value.current)
            activity.onBackPressedDispatcher.onBackPressed()
            runCurrent()

            assertNotEquals<ExpressiveDestination>(route, activity.navigationForTesting.backStack.value.current)
            assertEquals(1, gateway.databaseChecks)
            controller.pause().stop().destroy()
        }

    @Test
    fun `maintenance admission sees the collection sync the Activity observes`() =
        runTest {
            ExpressiveMaintenanceCoordinatorViewModel.gatewayForTesting = GatedGateway()
            val syncWork =
                OneTimeWorkRequestBuilder<SyncWorker>()
                    .setInitialDelay(1, TimeUnit.DAYS)
                    .build()
            val attemptId = UUID.randomUUID()
            SyncAttemptStore.write(
                context,
                SyncAttemptRecord(
                    attemptId = attemptId,
                    credentialGeneration = "generation",
                    operation = SyncOperation.NORMAL,
                    mediaPolicy = SyncMediaPolicy.NEVER,
                    collectionWorkId = syncWork.id,
                    mediaWorkId = null,
                ),
            )
            WorkManager
                .getInstance(context)
                .enqueueUniqueWork(UniqueWorkNames.SYNC, ExistingWorkPolicy.REPLACE, syncWork)
                .result
                .get()
            val controller =
                Robolectric
                    .buildActivity(ExpressiveHomeActivity::class.java, Intent(context, ExpressiveHomeActivity::class.java))
                    .create()
                    .start()
                    .resume()
            val activity = controller.get()
            val syncState =
                withContext(Dispatchers.Default.limitedParallelism(1)) {
                    withTimeout(5_000) { activity.syncForTesting.state.first { it !is ExpressiveSyncUiState.Checking } }
                }

            assertIs<ExpressiveSyncUiState.Queued>(syncState)
            assertEquals(ExpressiveMaintenanceSyncActivity.COLLECTION, activity.maintenanceForTesting.syncActivity())
            controller.pause().stop().destroy()
            SyncAttemptStore.clearIfMatches(context, SyncWorkIds(attemptId, syncWork.id, null))
        }

    @Test
    fun `sync states map to maintenance admission without blocking on a queued media sync`() {
        assertEquals(ExpressiveMaintenanceSyncActivity.COLLECTION, ExpressiveSyncUiState.Running(null).maintenanceSyncActivity())
        assertEquals(ExpressiveMaintenanceSyncActivity.COLLECTION, ExpressiveSyncUiState.Queued.maintenanceSyncActivity())
        assertEquals(ExpressiveMaintenanceSyncActivity.MEDIA, ExpressiveSyncUiState.MediaRunning(null).maintenanceSyncActivity())
        assertEquals(ExpressiveMaintenanceSyncActivity.MEDIA, ExpressiveSyncUiState.CancellingMedia.maintenanceSyncActivity())
        assertEquals(ExpressiveMaintenanceSyncActivity.NONE, ExpressiveSyncUiState.MediaQueued.maintenanceSyncActivity())
        assertEquals(ExpressiveMaintenanceSyncActivity.NONE, ExpressiveSyncUiState.Idle.maintenanceSyncActivity())
    }

    @Test
    fun `automatic backups follow resumes and collection ownership, never media sync`() {
        assertFalse(shouldAttemptAutomaticBackup(0, ExpressiveSyncUiState.Idle, transferBlocksCollection = false))
        assertTrue(shouldAttemptAutomaticBackup(1, ExpressiveSyncUiState.Idle, transferBlocksCollection = false))
        assertTrue(shouldAttemptAutomaticBackup(1, ExpressiveSyncUiState.MediaQueued, transferBlocksCollection = false))
        assertTrue(shouldAttemptAutomaticBackup(1, ExpressiveSyncUiState.MediaRunning(null), transferBlocksCollection = false))
        assertFalse(shouldAttemptAutomaticBackup(1, ExpressiveSyncUiState.Queued, transferBlocksCollection = false))
        assertFalse(shouldAttemptAutomaticBackup(1, ExpressiveSyncUiState.Idle, transferBlocksCollection = true))
    }

    @Test
    fun `the restore picker result is routed back to the request that launched it`() =
        runTest {
            ExpressiveMaintenanceCoordinatorViewModel.gatewayForTesting = GatedGateway()
            val controller =
                Robolectric
                    .buildActivity(ExpressiveHomeActivity::class.java, Intent(context, ExpressiveHomeActivity::class.java))
                    .create()
                    .start()
                    .resume()
            val activity = controller.get()
            val maintenance = activity.maintenanceForTesting
            maintenance.open(ExpressiveMaintenanceMode.BACKUPS)
            runCurrent()

            maintenance.startRestorePicker()
            runCurrent()

            assertIs<ExpressiveMaintenanceUiState.PickingRestore>(maintenance.state.value)
            val picker = requireNotNull(shadowOf(activity).nextStartedActivityForResult).intent
            assertEquals(Intent.ACTION_OPEN_DOCUMENT, picker.action)
            shadowOf(activity).receiveResult(picker, Activity.RESULT_CANCELED, null)
            runCurrent()

            assertIs<ExpressiveMaintenanceUiState.Overview>(maintenance.state.value)
            controller.pause().stop().destroy()
        }

    @Test
    fun `a sync request is refused while maintenance excludes sync`() =
        runTest {
            clearLogin()
            val gateway = GatedGateway()
            ExpressiveMaintenanceCoordinatorViewModel.gatewayForTesting = gateway
            val controller =
                Robolectric
                    .buildActivity(ExpressiveHomeActivity::class.java, Intent(context, ExpressiveHomeActivity::class.java))
                    .create()
                    .start()
                    .resume()
            val activity = controller.get()
            val idle =
                withContext(Dispatchers.Default.limitedParallelism(1)) {
                    withTimeout(5_000) { activity.syncForTesting.state.first { it !is ExpressiveSyncUiState.Checking } }
                }
            assertIs<ExpressiveSyncUiState.Idle>(idle)
            val maintenance = activity.maintenanceForTesting
            maintenance.open(ExpressiveMaintenanceMode.DATABASE)
            runCurrent()
            maintenance.requestDatabaseCheck()
            runCurrent()
            assertTrue(maintenance.state.value.blocksSync)

            activity.requestSyncForTesting()
            runCurrent()
            assertIs<ExpressiveSyncUiState.Idle>(activity.syncForTesting.state.value)

            gateway.databaseGate.complete(Unit)
            runCurrent()
            activity.requestSyncForTesting()
            runCurrent()

            // Not refused any more: signed out, the sync asks for a login.
            assertIs<ExpressiveSyncUiState.NeedsLogin>(activity.syncForTesting.state.value)
            controller.pause().stop().destroy()
        }

    private fun maintenanceIntent(destination: ExpressiveDestination): Intent =
        ExpressiveNavigationIntent.putInitialDestination(Intent(context, ExpressiveHomeActivity::class.java), destination)

    /** Loads instantly; the database check waits for [databaseGate]. */
    private class GatedGateway : ExpressiveMaintenanceGateway {
        val databaseGate = CompletableDeferred<Unit>()
        var databaseChecks = 0

        override suspend fun backupOverview(): Pair<List<ExpressiveBackupEntry>, ExpressiveBackupLimits> =
            emptyList<ExpressiveBackupEntry>() to ExpressiveBackupLimits(30, 12, 10, 9)

        override suspend fun createBackup(force: Boolean): Boolean = true

        override suspend fun deleteBackup(token: String) = Unit

        override suspend fun updateBackupLimits(limits: ExpressiveBackupLimits) = Unit

        override suspend fun resolveBackup(token: String): File = File(token)

        override suspend fun prepareRestore(source: File): Long = 1

        override suspend fun commitRestore(generation: Long): ExpressiveRestoreOutcome = ExpressiveRestoreOutcome()

        override suspend fun discardPreparedRestore(generation: Long?) = Unit

        override suspend fun databaseCheckWarning(): String? = null

        override suspend fun checkDatabase(): List<String> {
            databaseChecks++
            databaseGate.await()
            return emptyList()
        }

        override suspend fun findEmptyCards(): EmptyCardsReport = EmptyCardsReport.getDefaultInstance()

        override suspend fun deleteEmptyCards(
            report: EmptyCardsReport,
            preserveNotes: Boolean,
        ): Int = 0

        override suspend fun checkMedia(): CheckMediaResponse = CheckMediaResponse.getDefaultInstance()

        override suspend fun tagMissingMedia(report: CheckMediaResponse): Int = 0

        override suspend fun trashUnusedMedia(report: CheckMediaResponse): Int = 0

        override suspend fun restoreMediaTrash() = Unit

        override suspend fun emptyMediaTrash() = Unit

        override suspend fun rebuildMediaDatabase() = Unit

        override suspend fun quarantinedMediaCount(): Int = 0

        override suspend fun mergeQuarantinedMedia(): Int = 0

        override suspend fun deleteQuarantinedMedia() = Unit

        override fun progress(operation: ExpressiveMaintenanceOperation): ExpressiveMaintenanceProgress? = null
    }
}
