// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: Copyright (c) 2025 Brayan Oliveira <69634269+brayandso@users.noreply.github.com>
package com.ichi2.anki.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.ichi2.anki.NOTIFICATION_MIN_DELAY_MS
import com.ichi2.anki.RobolectricTest
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class SyncMediaWorkerTest : RobolectricTest() {
    @Test
    @Suppress("SimplifyBooleanWithConstants")
    fun `notification update delay is not lower than min delay`() {
        assert(SyncMediaWorker.NOTIFICATION_UPDATE_RATE_MS >= NOTIFICATION_MIN_DELAY_MS)
    }

    @Test
    fun `only unmetered policy is enforced by durable WorkManager constraint`() {
        val request =
            SyncMediaWorker.getWorkRequest(
                mediaPolicy = SyncMediaPolicy.ONLY_UNMETERED,
                credentialGeneration = "generation",
                attemptId = UUID.randomUUID(),
            )

        assertEquals(NetworkType.UNMETERED, request.workSpec.constraints.requiredNetworkType)
    }

    @Test
    fun `always policy requires connectivity and input contains no credential material`() {
        val request =
            SyncMediaWorker.getWorkRequest(
                mediaPolicy = SyncMediaPolicy.ALWAYS,
                credentialGeneration = "generation",
                attemptId = UUID.randomUUID(),
            )

        assertEquals(NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
        val keys = request.workSpec.input.keyValueMap.keys
        listOf("hkey", "password", "endpoint", "username", "certificate", "server", "exception", "message").forEach { unsafe ->
            assertFalse(keys.any { it.contains(unsafe, ignoreCase = true) }, "unsafe WorkManager key: $unsafe")
        }
    }

    @Test
    fun `never policy cannot create media work`() {
        assertFailsWith<IllegalArgumentException> {
            SyncMediaWorker.getWorkRequest(
                mediaPolicy = SyncMediaPolicy.NEVER,
                credentialGeneration = "generation",
                attemptId = UUID.randomUUID(),
            )
        }
    }

    @Test
    fun `same process starts preserve and adopt the accepted logical attempt`() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            clearAttemptStore(context)
            val firstAttempt = UUID.randomUUID()
            val secondAttempt = UUID.randomUUID()

            val first =
                SyncWorker.start(
                    context = context,
                    credentialGeneration = UUID.randomUUID().toString(),
                    mediaPolicy = SyncMediaPolicy.NEVER,
                    attemptId = firstAttempt,
                )
            val second =
                SyncWorker.start(
                    context = context,
                    credentialGeneration = UUID.randomUUID().toString(),
                    mediaPolicy = SyncMediaPolicy.ALWAYS,
                    attemptId = secondAttempt,
                )

            assertNotEquals(firstAttempt, secondAttempt)
            assertEquals(first, second)
            assertEquals(first, SyncAttemptStore.read(context)?.ids())
            assertNull(SyncAttemptStore.readPending(context))
        }

    @Test
    fun `startup reconciliation clears an unaccepted pending candidate`() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            clearAttemptStore(context)
            val pending = attemptRecord()
            SyncAttemptStore.writePending(context, pending)

            assertNull(SyncAttemptStore.reconcile(context, WorkManager.getInstance(context)))
            assertNull(SyncAttemptStore.readPending(context))
            assertNull(SyncAttemptStore.read(context))
        }

    @Test
    fun `startup reconciliation promotes work accepted before process death`() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            clearAttemptStore(context)
            val pending = attemptRecord()
            val request =
                OneTimeWorkRequestBuilder<SyncWorker>()
                    .setId(pending.collectionWorkId)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .addTag(SyncAttemptStore.recoveryTag(pending))
                    .build()
            val workManager = WorkManager.getInstance(context)
            SyncAttemptStore.writePending(context, pending)
            workManager
                .beginUniqueWork(UniqueWorkNames.SYNC, ExistingWorkPolicy.KEEP, request)
                .enqueue()
                .result
                .get()

            assertEquals(pending, SyncAttemptStore.reconcile(context, workManager))
            assertEquals(pending, SyncAttemptStore.read(context))
            assertNull(SyncAttemptStore.readPending(context))
        }

    @Test
    fun `terminal acknowledgement requires exact IDs and commits idempotently`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        clearAttemptStore(context)
        val record = attemptRecord(mediaWorkId = UUID.randomUUID())
        SyncAttemptStore.replaceAccepted(context, record)

        assertFalse(
            SyncAttemptStore.acknowledgeTerminal(
                context,
                record.attemptId,
                record.collectionWorkId,
                UUID.randomUUID(),
            ),
        )
        assertTrue(
            SyncAttemptStore.acknowledgeTerminal(
                context,
                record.attemptId,
                record.collectionWorkId,
                record.mediaWorkId,
            ),
        )
        assertTrue(
            SyncAttemptStore.acknowledgeTerminal(
                context,
                record.attemptId,
                record.collectionWorkId,
                record.mediaWorkId,
            ),
        )
    }

    private fun attemptRecord(mediaWorkId: UUID? = null): SyncAttemptRecord =
        SyncAttemptRecord(
            attemptId = UUID.randomUUID(),
            credentialGeneration = UUID.randomUUID().toString(),
            operation = SyncOperation.NORMAL,
            mediaPolicy = if (mediaWorkId == null) SyncMediaPolicy.NEVER else SyncMediaPolicy.ALWAYS,
            collectionWorkId = UUID.randomUUID(),
            mediaWorkId = mediaWorkId,
        )

    private fun clearAttemptStore(context: Context) {
        SyncAttemptStore.readPending(context)?.ids()?.let { SyncAttemptStore.discardPendingIfMatches(context, it) }
        SyncAttemptStore.clearAccepted(context)
    }
}
