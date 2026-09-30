// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.ui.expressive

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.core.net.toUri
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import anki.collection.OpChanges
import com.ichi2.anki.CollectionReplacementRecoveryException
import com.ichi2.anki.ExpressiveRestorePendingException
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.clearLogin
import com.ichi2.anki.updateLogin
import com.ichi2.anki.worker.SyncAttemptRecord
import com.ichi2.anki.worker.SyncAttemptStore
import com.ichi2.anki.worker.SyncMediaPolicy
import com.ichi2.anki.worker.SyncOperation
import com.ichi2.anki.worker.SyncWorkIds
import com.ichi2.anki.worker.SyncWorker
import com.ichi2.anki.worker.UniqueWorkNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class ExpressiveHomeActivityNavigationTest : RobolectricTest() {
    @Test
    fun `initial destination intent round trips every typed route`() {
        val destinations =
            listOf(
                ExpressiveDestination.Home,
                ExpressiveDestination.Browse,
                ExpressiveDestination.Stats,
                ExpressiveDestination.More,
                ExpressiveDestination.Settings,
                ExpressiveDestination.Account,
                ExpressiveDestination.Import,
                ExpressiveDestination.Export,
                ExpressiveDestination.Maintenance(ExpressiveMaintenanceMode.BACKUPS),
                ExpressiveDestination.Maintenance(ExpressiveMaintenanceMode.MEDIA),
                ExpressiveDestination.Study(deckId = 42L, deckName = "Deck"),
            )

        destinations.forEach { destination ->
            val intent = ExpressiveNavigationIntent.putInitialDestination(Intent(), destination)
            assertEquals(destination, ExpressiveNavigationIntent.initialDestination(intent))
        }
    }

    @Test
    fun `account result intent returns durable authentication after recreation`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        updateLogin("user@example.com", "hkey")
        val intent = ExpressiveHomeActivity.getAccountResultIntent(context)

        assertEquals(ExpressiveDestination.Account, ExpressiveNavigationIntent.initialDestination(intent))
        assertTrue(ExpressiveHomeActivity.isAccountResultIntent(intent))

        val activity =
            Robolectric
                .buildActivity(ExpressiveHomeActivity::class.java, intent)
                .create()
                .start()
                .resume()
                .get()

        assertTrue(activity.isFinishing)
        assertEquals(Activity.RESULT_OK, shadowOf(activity).resultCode)
    }

    @Test
    fun `account result system back finishes cancelled instead of revealing More`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        clearLogin()
        val activity =
            Robolectric
                .buildActivity(ExpressiveHomeActivity::class.java, ExpressiveHomeActivity.getAccountResultIntent(context))
                .create()
                .start()
                .resume()
                .get()

        activity.onBackPressedDispatcher.onBackPressed()

        assertTrue(activity.isFinishing)
        assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
    }

    @Test
    fun `saved Activity state restores typed stack before repeated initial intent`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent =
            ExpressiveNavigationIntent.putInitialDestination(
                Intent(context, ExpressiveHomeActivity::class.java),
                ExpressiveDestination.Study(deckId = 42L, deckName = "Initial deck"),
            )
        val first =
            Robolectric
                .buildActivity(ExpressiveHomeActivity::class.java, intent)
                .create()
                .start()
                .resume()
        assertEquals(
            ExpressiveDestination.Study(deckId = 42L, deckName = "Initial deck"),
            first
                .get()
                .navigationForTesting.backStack.value.current,
        )
        first
            .get()
            .navigationForTesting
            .apply {
                selectRoot(ExpressiveDestination.Browse)
                openSettings()
            }
        val savedState = Bundle()
        first
            .saveInstanceState(savedState)
            .pause()
            .stop()
            .destroy()

        val restored =
            Robolectric
                .buildActivity(ExpressiveHomeActivity::class.java, intent)
                .create(savedState)
                .start()
                .resume()

        assertEquals(
            listOf(ExpressiveDestination.Browse, ExpressiveDestination.Settings),
            restored
                .get()
                .navigationForTesting.backStack.value.entries,
        )
        restored
            .pause()
            .stop()
            .destroy()
    }

    @Test
    fun `active sync recreation suppresses Study selection and system back pops durable overlay`() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val initiallySelectedDeck = addDeck("Initially selected", setAsSelected = true)
            val studyDeck = addDeck("Study target")
            col.decks.select(initiallySelectedDeck)
            val syncWork =
                OneTimeWorkRequestBuilder<SyncWorker>()
                    .setInitialDelay(1, TimeUnit.DAYS)
                    .build()
            val attemptId = java.util.UUID.randomUUID()
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
            val intent =
                ExpressiveNavigationIntent.putInitialDestination(
                    Intent(context, ExpressiveHomeActivity::class.java),
                    ExpressiveDestination.Study(deckId = studyDeck, deckName = "Study target"),
                )
            val first =
                Robolectric
                    .buildActivity(ExpressiveHomeActivity::class.java, intent)
                    .create()
                    .start()
                    .resume()
            val firstActivity = first.get()
            val firstSyncState =
                withContext(Dispatchers.Default.limitedParallelism(1)) {
                    withTimeout(5_000) {
                        firstActivity.syncForTesting.state.first { it !is ExpressiveSyncUiState.Checking }
                    }
                }

            assertIs<ExpressiveSyncUiState.Queued>(firstSyncState)
            assertEquals(
                ExpressiveDestination.More,
                effectiveExpressiveDestination(
                    firstActivity.navigationForTesting.backStack.value,
                    firstActivity.syncForTesting.state.value.isCollectionBlocking,
                ),
            )
            assertEquals(initiallySelectedDeck, col.decks.selected())

            val savedState = Bundle()
            first
                .saveInstanceState(savedState)
                .pause()
                .stop()
                .destroy()
            val restored =
                Robolectric
                    .buildActivity(ExpressiveHomeActivity::class.java, intent)
                    .create(savedState)
                    .start()
                    .resume()
            val restoredActivity = restored.get()
            val restoredSyncState =
                withContext(Dispatchers.Default.limitedParallelism(1)) {
                    withTimeout(5_000) {
                        restoredActivity.syncForTesting.state.first { it !is ExpressiveSyncUiState.Checking }
                    }
                }

            assertIs<ExpressiveSyncUiState.Queued>(restoredSyncState)
            assertEquals(
                ExpressiveDestination.Study(studyDeck, "Study target"),
                restoredActivity.navigationForTesting.backStack.value.current,
            )
            assertEquals(
                ExpressiveDestination.More,
                effectiveExpressiveDestination(
                    restoredActivity.navigationForTesting.backStack.value,
                    restoredActivity.syncForTesting.state.value.isCollectionBlocking,
                ),
            )
            assertEquals(initiallySelectedDeck, col.decks.selected())

            restoredActivity.onBackPressedDispatcher.onBackPressed()
            runCurrent()

            assertEquals(ExpressiveDestination.Home, restoredActivity.navigationForTesting.backStack.value.current)
            assertFalse(restoredActivity.isFinishing)

            restoredActivity.onBackPressedDispatcher.onBackPressed()

            assertTrue(restoredActivity.isFinishing)
            restored
                .pause()
                .stop()
                .destroy()
            SyncAttemptStore.clearIfMatches(context, SyncWorkIds(attemptId, syncWork.id, null))
        }

    @Test
    fun `dispatched import picker restores interrupted without replay`() =
        runTest {
            val handle = SavedStateHandle()
            val first = ExpressiveTransferCoordinatorViewModel(handle)

            assertTrue(first.startImport())
            val launch = assertIs<ExpressiveTransferLaunch.OpenImport>(first.launches.first())
            assertEquals(Intent.ACTION_OPEN_DOCUMENT, launch.intent.action)
            assertTrue(launch.intent.hasCategory(Intent.CATEGORY_OPENABLE))
            assertFalse(launch.intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, true))
            assertTrue(first.markImportLaunchDispatched(launch.requestId))

            val restored = ExpressiveTransferCoordinatorViewModel(handle)
            assertNull(withTimeoutOrNull(1) { restored.launches.first() })
            val terminal = assertIs<ExpressiveTransferUiState.Terminal>(restored.state.value)
            assertEquals(ExpressiveTransferError.INTERRUPTED, terminal.error)
        }

    @Test
    fun `canonical defaults and scope format validation fail closed`() {
        val noteSelection = ExpressiveExportScope.Selection(ExpressiveSelectionKind.NOTES, "11111111-1111-1111-1111-111111111111.ids", 2)
        val cardSelection = ExpressiveExportScope.Selection(ExpressiveSelectionKind.CARDS, "22222222-2222-2222-2222-222222222222.ids", 2)
        val cases =
            listOf(
                ExpressiveExportScope.WholeCollection to ExpressiveExportFormat.COLPKG,
                ExpressiveExportScope.Deck(42L, "Deck") to ExpressiveExportFormat.APKG,
                noteSelection to ExpressiveExportFormat.APKG,
                cardSelection to ExpressiveExportFormat.APKG,
            )

        cases.forEach { (scope, expected) ->
            val coordinator = ExpressiveTransferCoordinatorViewModel(SavedStateHandle())
            assertTrue(coordinator.startExport(scope))
            assertEquals(expected, assertIs<ExpressiveTransferUiState.ExportOptions>(coordinator.state.value).options.format)
        }

        assertFalse(
            ExpressiveTransferCoordinatorViewModel(SavedStateHandle()).startExport(
                ExpressiveExportScope.Deck(42L, "Deck"),
                ExpressiveExportFormat.COLPKG,
            ),
        )
        assertFalse(
            ExpressiveTransferCoordinatorViewModel(SavedStateHandle()).startExport(
                noteSelection,
                ExpressiveExportFormat.COLPKG,
            ),
        )
        val coordinator = ExpressiveTransferCoordinatorViewModel(SavedStateHandle())
        assertTrue(coordinator.startExport(ExpressiveExportScope.Deck(42L, "Deck")))
        coordinator.updateExportOptions(ExpressiveExportOptions(ExpressiveExportFormat.COLPKG))
        assertEquals(
            ExpressiveExportFormat.APKG,
            assertIs<ExpressiveTransferUiState.ExportOptions>(coordinator.state.value).options.format,
        )
    }

    @Test
    fun `exact export limits preserve whole deck note and card scope`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "expressive-selection").apply { mkdirs() }
        val noteToken = "33333333-3333-3333-3333-333333333333.ids"
        val cardToken = "44444444-4444-4444-4444-444444444444.ids"
        File(root, noteToken).writeText("11\n12\n")
        File(root, cardToken).writeText("21\n22\n")

        assertTrue(expressiveExportLimit(ExpressiveExportScope.WholeCollection, context).hasWholeCollection())
        assertEquals(42L, expressiveExportLimit(ExpressiveExportScope.Deck(42L, "Deck"), context).deckId)
        assertEquals(
            listOf(11L, 12L),
            expressiveExportLimit(
                ExpressiveExportScope.Selection(ExpressiveSelectionKind.NOTES, noteToken, 2),
                context,
            ).noteIds.noteIdsList,
        )
        assertEquals(
            listOf(21L, 22L),
            expressiveExportLimit(
                ExpressiveExportScope.Selection(ExpressiveSelectionKind.CARDS, cardToken, 2),
                context,
            ).cardIds.cidsList,
        )
    }

    @Test
    fun `saved transfer state contains no raw paths or display names and rejects legacy authority`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val outside = File(context.cacheDir.parentFile, "must-not-delete.apkg").apply { writeText("outside") }
        val handle = SavedStateHandle()
        val coordinator = ExpressiveTransferCoordinatorViewModel(handle)
        assertTrue(coordinator.startExport(ExpressiveExportScope.Deck(42L, "/private/provider-name.apkg")))

        val snapshot = requireNotNull(handle.get<String>(ExpressiveTransferCoordinatorViewModel.STATE_KEY))
        assertFalse(snapshot.contains("/private"))
        assertFalse(snapshot.contains("provider-name"))
        assertFalse(snapshot.contains(context.cacheDir.absolutePath))

        val legacy =
            SavedStateHandle(
                mapOf(
                    "expressive.transfer.state.v1" to
                        "{\"type\":\"terminal\",\"requestId\":7,\"retainedArtifactPath\":\"${outside.absolutePath}\"}",
                ),
            )
        assertIs<ExpressiveTransferUiState.Idle>(ExpressiveTransferCoordinatorViewModel(legacy).state.value)
        assertTrue(outside.exists())
        assertNull(legacy.get<String>("expressive.transfer.state.v1"))
    }

    @Test
    fun `canonical resolvers reject traversal absolute paths and symlinks`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root =
            File(context.cacheDir, "expressive-import").apply { mkdirs() }
        val outside = File(context.cacheDir, "outside.apkg").apply { writeText("outside") }

        assertNull(ExpressiveTransferFiles.ownedImportFile(context, "../outside.apkg"))
        assertNull(ExpressiveTransferFiles.ownedImportFile(context, outside.absolutePath))
        val link = File(root, "55555555-5555-5555-5555-555555555555.apkg")
        runCatching { Files.createSymbolicLink(link.toPath(), outside.toPath()) }
            .onSuccess { assertNull(ExpressiveTransferFiles.ownedImportFile(context, link.name)) }
        assertTrue(outside.exists())
    }

    @Test
    fun `failed bounded staging removes partial app owned output`() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val root =
                File(context.cacheDir, "expressive-import").apply {
                    mkdirs()
                    listFiles().orEmpty().forEach(File::delete)
                }
            val source = File(context.cacheDir, "oversized-source.apkg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val result =
                ExpressiveTransferFiles.stageImport(
                    context,
                    Intent(Intent.ACTION_VIEW).setDataAndType(Uri.fromFile(source), EXPRESSIVE_IMPORT_MIME),
                    maxBytes = 2,
                )

            assertIs<TransferFailure>(result.exceptionOrNull())
            assertEquals(ExpressiveTransferError.FILE_TOO_LARGE, (result.exceptionOrNull() as TransferFailure).safeError)
            assertTrue(root.listFiles().orEmpty().isEmpty())
        }

    @Test
    fun `stale picker result cannot mutate a newer request`() =
        runTest {
            val coordinator = ExpressiveTransferCoordinatorViewModel(SavedStateHandle())
            assertTrue(coordinator.startImport())
            val first = assertIs<ExpressiveTransferLaunch.OpenImport>(coordinator.launches.first())
            coordinator.markImportLaunchDispatched(first.requestId)
            coordinator.pickerCancelled(first.requestId)
            coordinator.dismissTerminal()
            assertTrue(coordinator.startImport())
            val second = assertIs<ExpressiveTransferLaunch.OpenImport>(coordinator.launches.first())
            coordinator.markImportLaunchDispatched(second.requestId)

            assertEquals(first.requestId, coordinator.consumeImportResultRequestId())
            coordinator.pickerCancelled(first.requestId)
            assertEquals(second.requestId, assertIs<ExpressiveTransferUiState.PickingImport>(coordinator.state.value).requestId)
            assertEquals(second.requestId, coordinator.consumeImportResultRequestId())
        }

    @Test
    fun `external import identity is durably claimed before staging and cannot overwrite interrupted state`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val handle = SavedStateHandle()
        val requestIdentity = "66666666-6666-6666-6666-666666666666"
        ExpressiveExternalImportRequests.clearClaimsForTests(context)
        try {
            val first = ExpressiveTransferCoordinatorViewModel(handle)
            assertTrue(requireNotNull(first.startExternalImport(context, requestIdentity)) > 0)

            val independentOwner = ExpressiveTransferCoordinatorViewModel(SavedStateHandle())
            assertNull(independentOwner.startExternalImport(context, requestIdentity))

            val restored = ExpressiveTransferCoordinatorViewModel(handle)
            assertEquals(ExpressiveTransferError.INTERRUPTED, assertIs<ExpressiveTransferUiState.Terminal>(restored.state.value).error)
            assertNull(restored.startExternalImport(context, requestIdentity))
            assertNull(restored.startExternalImport(context, "77777777-7777-7777-7777-777777777777"))
            assertFalse(
                handle
                    .keys()
                    .map { handle.get<Any>(it) }
                    .joinToString()
                    .contains("content://"),
            )
        } finally {
            ExpressiveExternalImportRequests.clearClaimsForTests(context)
        }
    }

    @Test
    fun `saving state restores ready and successful provider copy commits before source deletion`() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val exportRoot = File(requireNotNull(context.externalCacheDir), "export").apply { mkdirs() }
            val token = "88888888-8888-8888-8888-888888888888.apkg"
            val source = File(exportRoot, token).apply { writeText("payload") }
            val savingHandle =
                SavedStateHandle(
                    mapOf(
                        ExpressiveTransferCoordinatorViewModel.STATE_KEY to
                            "{\"version\":2,\"kind\":\"saving-export\",\"requestId\":8,\"exportFormat\":\"APKG\",\"token\":\"$token\"}",
                    ),
                )
            assertIs<ExpressiveTransferUiState.Ready>(ExpressiveTransferCoordinatorViewModel(savingHandle).state.value)

            val readyHandle =
                SavedStateHandle(
                    mapOf(
                        ExpressiveTransferCoordinatorViewModel.STATE_KEY to
                            "{\"version\":2,\"kind\":\"ready\",\"requestId\":8,\"exportFormat\":\"APKG\",\"token\":\"$token\"}",
                    ),
                )
            val coordinator = ExpressiveTransferCoordinatorViewModel(readyHandle)
            coordinator.requestSave()
            val launch = assertIs<ExpressiveTransferLaunch.SaveExport>(coordinator.launches.first())
            assertTrue(coordinator.markSaveLaunchDispatched(launch.requestId))
            assertEquals(launch.requestId, coordinator.consumeSaveResultRequestId())
            val destination = File(context.cacheDir, "saved-provider-copy.apkg")
            coordinator.saveResult(context, launch.requestId, Uri.fromFile(destination), succeededResult = true)
            val terminal =
                withContext(Dispatchers.Default.limitedParallelism(1)) {
                    withTimeout(5_000) {
                        assertIs<ExpressiveTransferUiState.Terminal>(
                            coordinator.state.first { it is ExpressiveTransferUiState.Terminal },
                        )
                    }
                }

            assertTrue(terminal.succeeded)
            assertEquals("payload", destination.readText())
            assertFalse(source.exists())
            assertTrue(
                requireNotNull(
                    readyHandle.get<String>(ExpressiveTransferCoordinatorViewModel.STATE_KEY),
                ).contains("\"succeeded\":true"),
            )
        }

    @Test
    fun `create document and share intents preserve MIME with read only grant`() {
        ExpressiveExportFormat.entries.forEachIndexed { index, format ->
            val ready =
                ExpressiveTransferUiState.Ready(
                    index + 1L,
                    "99999999-9999-9999-9999-99999999999$index.${if (format == ExpressiveExportFormat.COLPKG) "colpkg" else "apkg"}",
                    "export-$index",
                    format,
                )
            val create = ExpressiveTransferFiles.createDocumentIntent(ready)
            assertEquals(Intent.ACTION_CREATE_DOCUMENT, create.action)
            assertEquals(ExpressiveTransferFiles.mimeFor(format), create.type)
            assertEquals(ready.displayName, create.getStringExtra(Intent.EXTRA_TITLE))

            val context = ApplicationProvider.getApplicationContext<Context>()
            val share =
                ExpressiveTransferFiles.createShareIntent(
                    context,
                    ready,
                    "content://owned/$index".toUri(),
                )
            assertEquals(Intent.ACTION_SEND, share.action)
            assertEquals(ready.mime, share.type)
            assertTrue(share.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertEquals(0, share.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            assertEquals(1, share.clipData?.itemCount)
        }
    }

    @Test
    fun `committed replacement recovery invalidates once and remains typed`() =
        runTest {
            var invalidations = 0
            val reopenFailure = IllegalStateException("reopen failed")
            val failure =
                runCatching {
                    runCommittedCollectionReplacement(
                        replace = { throw CollectionReplacementRecoveryException(reopenFailure) },
                        invalidate = { invalidations++ },
                    )
                }.exceptionOrNull()

            assertEquals(1, invalidations)
            val transferFailure = assertIs<TransferFailure>(failure)
            assertEquals(ExpressiveTransferError.COLLECTION_RECOVERY_FAILED, transferFailure.safeError)
            assertSame(reopenFailure, transferFailure.cause?.cause)

            invalidations = 0
            val backendFailure = IllegalStateException("backend failed")
            assertSame(
                backendFailure,
                runCatching {
                    runCommittedCollectionReplacement(
                        replace = { throw backendFailure },
                        invalidate = { invalidations++ },
                    )
                }.exceptionOrNull(),
            )
            assertEquals(0, invalidations)
        }

    @Test
    fun `committed replacement recovery failure deletes source and reports typed terminal`() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val token = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb.colpkg"
            File(context.cacheDir, "expressive-import")
                .apply { mkdirs() }
                .resolve(token)
                .writeText("package")
            val handle =
                SavedStateHandle(
                    mapOf(
                        ExpressiveTransferCoordinatorViewModel.STATE_KEY to
                            "{\"version\":2,\"kind\":\"confirm-import\",\"requestId\":13,\"importFormat\":\"COLPKG\",\"token\":\"$token\"}",
                    ),
                )
            val coordinator = ExpressiveTransferCoordinatorViewModel(handle, RecoveryFailureGateway())

            coordinator.confirmImport(context)
            runCurrent()

            val terminal = assertIs<ExpressiveTransferUiState.Terminal>(coordinator.state.value)
            assertFalse(terminal.succeeded)
            assertTrue(terminal.imported)
            assertEquals(ExpressiveTransferError.COLLECTION_RECOVERY_FAILED, terminal.error)
            assertNull(ExpressiveTransferFiles.ownedImportFile(context, token))
        }

    @Test
    fun `committed collection replacement reports success despite cancellation exactly once`() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val token = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa.colpkg"
            File(context.cacheDir, "expressive-import")
                .apply { mkdirs() }
                .resolve(token)
                .writeText("package")
            val handle =
                SavedStateHandle(
                    mapOf(
                        ExpressiveTransferCoordinatorViewModel.STATE_KEY to
                            "{\"version\":2,\"kind\":\"confirm-import\",\"requestId\":12,\"importFormat\":\"COLPKG\",\"token\":\"$token\"}",
                    ),
                )
            val gateway = CancellingAfterCommitGateway()
            val coordinator = ExpressiveTransferCoordinatorViewModel(handle, gateway)

            coordinator.confirmImport(context)
            runCurrent()

            assertEquals(1, gateway.replacementCalls)
            val terminal = assertIs<ExpressiveTransferUiState.Terminal>(coordinator.state.value)
            assertTrue(terminal.succeeded)
            assertTrue(terminal.imported)
            assertNull(ExpressiveTransferFiles.ownedImportFile(context, token))
        }

    @Test
    fun `a replacement refused by an unfinished restore is reported specifically and releases the staged package`() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val token = "cccccccc-cccc-cccc-cccc-cccccccccccc.colpkg"
            File(context.cacheDir, "expressive-import")
                .apply { mkdirs() }
                .resolve(token)
                .writeText("package")
            val handle =
                SavedStateHandle(
                    mapOf(
                        ExpressiveTransferCoordinatorViewModel.STATE_KEY to
                            "{\"version\":2,\"kind\":\"confirm-import\",\"requestId\":14,\"importFormat\":\"COLPKG\",\"token\":\"$token\"}",
                    ),
                )
            val coordinator = ExpressiveTransferCoordinatorViewModel(handle, PendingRestoreGateway())

            coordinator.confirmImport(context)
            runCurrent()

            val terminal = assertIs<ExpressiveTransferUiState.Terminal>(coordinator.state.value)
            assertFalse(terminal.succeeded)
            assertEquals(ExpressiveTransferError.PREVIOUS_RESTORE_UNFINISHED, terminal.error)
            assertNull(ExpressiveTransferFiles.ownedImportFile(context, token))
        }

    private class PendingRestoreGateway : ExpressiveTransferGateway {
        override suspend fun importApkg(path: String): OpChanges = OpChanges.getDefaultInstance()

        override suspend fun importText(path: String): OpChanges = OpChanges.getDefaultInstance()

        override suspend fun replaceCollection(path: String): Nothing = throw ExpressiveRestorePendingException()

        override suspend fun export(
            path: String,
            scope: ExpressiveExportScope,
            options: ExpressiveExportOptions,
            context: Context,
        ) = Unit

        override fun abort() = Unit
    }

    private class RecoveryFailureGateway : ExpressiveTransferGateway {
        override suspend fun importApkg(path: String): OpChanges = OpChanges.getDefaultInstance()

        override suspend fun importText(path: String): OpChanges = OpChanges.getDefaultInstance()

        override suspend fun replaceCollection(path: String): Nothing =
            throw TransferFailure(ExpressiveTransferError.COLLECTION_RECOVERY_FAILED)

        override suspend fun export(
            path: String,
            scope: ExpressiveExportScope,
            options: ExpressiveExportOptions,
            context: Context,
        ) = Unit

        override fun abort() = Unit
    }

    private class CancellingAfterCommitGateway : ExpressiveTransferGateway {
        var replacementCalls = 0

        override suspend fun importApkg(path: String): OpChanges = OpChanges.getDefaultInstance()

        override suspend fun importText(path: String): OpChanges = OpChanges.getDefaultInstance()

        override suspend fun replaceCollection(path: String) {
            replacementCalls++
            currentCoroutineContext().job.cancel()
        }

        override suspend fun export(
            path: String,
            scope: ExpressiveExportScope,
            options: ExpressiveExportOptions,
            context: Context,
        ) = Unit

        override fun abort() = Unit
    }
}
