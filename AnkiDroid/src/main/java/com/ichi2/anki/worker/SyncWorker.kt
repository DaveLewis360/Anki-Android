/*
 * Copyright (c) 2024 Brayan Oliveira <brayandso.dev@gmail.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.ichi2.anki.worker

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import anki.collection.Progress
import anki.sync.SyncAuth
import anki.sync.SyncCollectionResponse
import com.ichi2.anki.BackupManager
import com.ichi2.anki.CollectionManager
import com.ichi2.anki.CollectionManager.TR
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.NotificationChannel
import com.ichi2.anki.R
import com.ichi2.anki.cancelSync
import com.ichi2.anki.clearLogin
import com.ichi2.anki.notifications.NotificationId
import com.ichi2.anki.observability.ChangeManager.notifySubscribersAllValuesChanged
import com.ichi2.anki.reopen
import com.ichi2.anki.setLastSyncTimeToNow
import com.ichi2.anki.updateSyncEndpoint
import com.ichi2.anki.utils.ext.trySetForeground
import com.ichi2.utils.Permissions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.ankiweb.rsdroid.exceptions.BackendSyncException
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import com.ichi2.anki.syncAuth as storedSyncAuth

/** Closed, non-sensitive collection worker result contract. */
internal enum class SyncWorkerOutcome {
    AUTH_REQUIRED,
    FULL_SYNC_REQUIRED,
    ERROR,
}

private data class CollectionResult(
    val outcome: SyncWorkerOutcome? = null,
    val serverMediaUsn: Int? = null,
)

private class CredentialGenerationChangedException : Exception()

private fun WorkInfo.State.isTerminal(): Boolean =
    this == WorkInfo.State.SUCCEEDED || this == WorkInfo.State.FAILED || this == WorkInfo.State.CANCELLED

/**
 * Durable owner of normal and one-way collection sync. A successful collection request is chained
 * to its exact [SyncMediaWorker] request by [start], so the logical attempt is terminal only after
 * media is terminal (unless [SyncMediaPolicy.NEVER] was captured).
 */
class SyncWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    private val cancelIntent = WorkManager.getInstance(context).createCancelPendingIntent(id)
    private val syncAbortRequested = AtomicBoolean(false)
    private val notificationManager: NotificationManagerCompat? =
        if (Permissions.canPostNotifications(context)) NotificationManagerCompat.from(context) else null

    override suspend fun doWork(): Result {
        Timber.v("SyncWorker::doWork")
        trySetForeground(getForegroundInfo())

        val input = SyncInput.from(inputData) ?: return Result.failure(output(outcome = SyncWorkerOutcome.ERROR))
        val auth =
            storedSyncAuth(expectedGeneration = input.credentialGeneration)
                ?: return Result.failure(output(input, SyncWorkerOutcome.AUTH_REQUIRED))

        return try {
            val result =
                when (input.operation) {
                    SyncOperation.NORMAL -> normalSync(auth, input)
                    SyncOperation.FULL_UPLOAD -> {
                        fullSync(auth, upload = true, input.serverMediaUsn)
                        CollectionResult(serverMediaUsn = input.serverMediaUsn)
                    }
                    SyncOperation.FULL_DOWNLOAD -> {
                        fullSync(auth, upload = false, input.serverMediaUsn)
                        CollectionResult(serverMediaUsn = input.serverMediaUsn)
                    }
                }
            notificationManager?.cancel(NotificationId.SYNC)
            setLastSyncTimeToNow()
            if (result.outcome == null) {
                invalidateCollection()
                Result.success(output(input, serverMediaUsn = result.serverMediaUsn))
            } else {
                Result.failure(output(input, result.outcome, result.serverMediaUsn))
            }
        } catch (cancellation: CancellationException) {
            Timber.i("SyncWorker cancelled")
            notificationManager?.cancel(NotificationId.SYNC)
            abortSyncOnce()
            throw cancellation
        } catch (_: CredentialGenerationChangedException) {
            Timber.i("SyncWorker stopped because the account generation changed")
            notificationManager?.cancel(NotificationId.SYNC)
            Result.failure(output(input, SyncWorkerOutcome.AUTH_REQUIRED))
        } catch (_: BackendSyncException.BackendSyncAuthFailedException) {
            Timber.i("SyncWorker authentication failed")
            clearLogin(expectedGeneration = input.credentialGeneration)
            notifyFailure()
            Result.failure(output(input, SyncWorkerOutcome.AUTH_REQUIRED))
        } catch (throwable: Throwable) {
            Timber.w(throwable, "SyncWorker failed")
            notifyFailure()
            Result.failure(output(input, SyncWorkerOutcome.ERROR))
        }
    }

    private suspend fun normalSync(
        initialAuth: SyncAuth,
        input: SyncInput,
    ): CollectionResult =
        monitorProgress {
            val response = withCol { syncCollection(initialAuth, syncMedia = false) }
            var effectiveAuth = initialAuth
            if (response.hasNewEndpoint() && response.newEndpoint.isNotEmpty()) {
                // Redirect ownership is resolved before every possible normal-sync outcome.
                if (!updateSyncEndpoint(input.credentialGeneration, response.newEndpoint)) {
                    throw CredentialGenerationChangedException()
                }
                effectiveAuth =
                    storedSyncAuth(expectedGeneration = input.credentialGeneration)
                        ?: throw CredentialGenerationChangedException()
            }
            val mediaUsn = response.serverMediaUsn.takeIf { input.mediaPolicy != SyncMediaPolicy.NEVER }
            when (response.required) {
                SyncCollectionResponse.ChangesRequired.NO_CHANGES -> {
                    withCol { _loadScheduler() }
                    CollectionResult(serverMediaUsn = mediaUsn)
                }
                SyncCollectionResponse.ChangesRequired.FULL_DOWNLOAD -> {
                    fullSync(effectiveAuth, upload = false, mediaUsn)
                    CollectionResult(serverMediaUsn = mediaUsn)
                }
                SyncCollectionResponse.ChangesRequired.FULL_UPLOAD -> {
                    fullSync(effectiveAuth, upload = true, mediaUsn)
                    CollectionResult(serverMediaUsn = mediaUsn)
                }
                SyncCollectionResponse.ChangesRequired.FULL_SYNC ->
                    CollectionResult(SyncWorkerOutcome.FULL_SYNC_REQUIRED, mediaUsn)
                SyncCollectionResponse.ChangesRequired.NORMAL_SYNC,
                SyncCollectionResponse.ChangesRequired.UNRECOGNIZED,
                null,
                -> error("Unexpected collection sync requirement")
            }
        }

    @OptIn(InternalCoroutinesApi::class)
    private suspend fun fullSync(
        auth: SyncAuth,
        upload: Boolean,
        serverMediaUsn: Int?,
    ) = monitorProgress {
        val syncJob = currentCoroutineContext().job
        withCol {
            if (!upload) {
                createBackup(
                    BackupManager.getBackupDirectoryFromCollection(colDb),
                    force = true,
                    waitForCompletion = true,
                )
            }
            try {
                close(downgrade = false, forFullSync = true)
                val cancellationHandle =
                    syncJob.invokeOnCompletion(
                        onCancelling = true,
                        invokeImmediately = true,
                    ) { cause ->
                        if (cause is CancellationException) abortSyncOnce()
                    }
                try {
                    fullUploadOrDownload(auth, upload = upload, serverUsn = serverMediaUsn)
                } catch (cancellation: CancellationException) {
                    abortSyncOnce()
                    throw cancellation
                } finally {
                    cancellationHandle.dispose()
                }
            } finally {
                reopen(afterFullSync = true)
            }
            notetypes.clearCache()
        }
    }

    private fun abortSyncOnce() {
        if (syncAbortRequested.compareAndSet(false, true)) {
            cancelSync(CollectionManager.getBackend())
        }
    }

    private suspend fun invalidateCollection() {
        withCol { notetypes.clearCache() }
        withContext(Dispatchers.Main) {
            notifySubscribersAllValuesChanged(this@SyncWorker)
        }
    }

    private suspend fun <T> monitorProgress(block: suspend () -> T): T {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            var previous: Progress? = null
            while (true) {
                val progress = CollectionManager.getBackend().latestProgress()
                if (progress != previous) {
                    previous = progress
                    val text =
                        when {
                            progress.hasNormalSync() -> progress.normalSync.run { "$added\n$removed" }
                            progress.hasFullSync() -> progress.fullSync.run { "$transferred / $total" }
                            else -> null
                        }
                    if (text != null) {
                        setProgress(Data.Builder().putString(PROGRESS_TEXT_KEY, text).build())
                        notify(getProgressNotification(text))
                    }
                }
                delay(SyncMediaWorker.NOTIFICATION_UPDATE_RATE_MS)
            }
        }
        return try {
            block()
        } finally {
            scope.cancel()
        }
    }

    private fun notifyFailure() {
        notify {
            setContentTitle(applicationContext.getString(R.string.sync_error))
            setContentText(applicationContext.getString(R.string.connection_error_message))
        }
    }

    private fun output(
        input: SyncInput? = null,
        outcome: SyncWorkerOutcome? = null,
        serverMediaUsn: Int? = null,
    ): Data =
        Data
            .Builder()
            .apply {
                input?.let {
                    putString(ATTEMPT_ID_KEY, it.attemptId.toString())
                    putString(OPERATION_KEY, it.operation.name)
                    putString(MEDIA_POLICY_KEY, it.mediaPolicy.name)
                    putString(COLLECTION_WORK_ID_KEY, id.toString())
                    it.mediaWorkId?.let { mediaId -> putString(MEDIA_WORK_ID_KEY, mediaId.toString()) }
                }
                outcome?.let { putString(OUTCOME_KEY, it.name) }
                serverMediaUsn?.let { putInt(SERVER_MEDIA_USN_KEY, it) }
            }.build()

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification =
            buildNotification {
                setContentTitle(TR.syncSyncing())
                setOngoing(true)
                setProgress(0, 0, true)
                addAction(R.drawable.close_icon, applicationContext.getString(R.string.dialog_cancel), cancelIntent)
                foregroundServiceBehavior = NotificationCompat.FOREGROUND_SERVICE_DEFERRED
            }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NotificationId.SYNC, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NotificationId.SYNC, notification)
        }
    }

    private fun notify(notification: Notification) = notificationManager?.notify(NotificationId.SYNC, notification)

    private fun notify(builder: NotificationCompat.Builder.() -> Unit) = notify(buildNotification(builder))

    private fun buildNotification(block: NotificationCompat.Builder.() -> Unit): Notification =
        NotificationCompat
            .Builder(applicationContext, NotificationChannel.SYNC.id)
            .apply {
                priority = NotificationCompat.PRIORITY_LOW
                setSmallIcon(R.drawable.ic_star_notify)
                setCategory(NotificationCompat.CATEGORY_PROGRESS)
                setSilent(true)
                block()
            }.build()

    private fun getProgressNotification(progress: CharSequence): Notification =
        buildNotification {
            setContentTitle(TR.syncSyncing())
            setContentText(progress)
            setOngoing(true)
            addAction(R.drawable.close_icon, applicationContext.getString(R.string.dialog_cancel), cancelIntent)
        }

    private data class SyncInput(
        val attemptId: UUID,
        val credentialGeneration: String,
        val operation: SyncOperation,
        val mediaPolicy: SyncMediaPolicy,
        val mediaWorkId: UUID?,
        val serverMediaUsn: Int?,
    ) {
        companion object {
            fun from(data: Data): SyncInput? =
                runCatching {
                    SyncInput(
                        attemptId = UUID.fromString(data.getString(ATTEMPT_ID_KEY)),
                        credentialGeneration = requireNotNull(data.getString(CREDENTIAL_GENERATION_KEY)),
                        operation = enumValueOf(requireNotNull(data.getString(OPERATION_KEY))),
                        mediaPolicy = enumValueOf(requireNotNull(data.getString(MEDIA_POLICY_KEY))),
                        mediaWorkId = data.getString(MEDIA_WORK_ID_KEY)?.let(UUID::fromString),
                        serverMediaUsn = data.getInt(SERVER_MEDIA_USN_KEY, Int.MIN_VALUE).takeUnless { it == Int.MIN_VALUE },
                    )
                }.getOrNull()
        }
    }

    companion object {
        private val startMutex = Mutex()

        internal const val ATTEMPT_ID_KEY = "syncAttemptId"
        internal const val CREDENTIAL_GENERATION_KEY = "credentialGeneration"
        internal const val OPERATION_KEY = "syncOperation"
        internal const val MEDIA_POLICY_KEY = "syncMediaPolicy"
        internal const val COLLECTION_WORK_ID_KEY = "collectionWorkId"
        internal const val MEDIA_WORK_ID_KEY = "mediaWorkId"
        internal const val SERVER_MEDIA_USN_KEY = "serverMediaUsn"
        internal const val OUTCOME_KEY = "syncWorkerOutcome"
        internal const val PROGRESS_TEXT_KEY = "syncWorkerProgressText"

        suspend fun start(
            context: Context,
            credentialGeneration: String,
            mediaPolicy: SyncMediaPolicy = SyncMediaPolicy.current(),
            operation: SyncOperation = SyncOperation.NORMAL,
            attemptId: UUID = UUID.randomUUID(),
            serverMediaUsn: Int? = null,
        ): SyncWorkIds =
            startMutex.withLock {
                val applicationContext = context.applicationContext
                val workManager = WorkManager.getInstance(applicationContext)
                val current = SyncAttemptStore.reconcile(applicationContext, workManager)
                if (current != null) {
                    val currentInfo =
                        withContext(Dispatchers.IO) {
                            workManager.getWorkInfoById(current.collectionWorkId).get()
                        }
                    if (currentInfo != null && (!currentInfo.state.isTerminal() || !current.isFullyAcknowledged())) {
                        return@withLock current.ids()
                    }
                }

                val mediaRequest =
                    if (mediaPolicy == SyncMediaPolicy.NEVER) {
                        null
                    } else {
                        SyncMediaWorker.getWorkRequest(mediaPolicy, credentialGeneration, attemptId)
                    }
                val collectionWorkId = UUID.randomUUID()
                val candidate =
                    SyncAttemptRecord(
                        attemptId = attemptId,
                        credentialGeneration = credentialGeneration,
                        operation = operation,
                        mediaPolicy = mediaPolicy,
                        collectionWorkId = collectionWorkId,
                        mediaWorkId = mediaRequest?.id,
                    )
                val collectionData =
                    Data
                        .Builder()
                        .apply {
                            putString(ATTEMPT_ID_KEY, attemptId.toString())
                            putString(CREDENTIAL_GENERATION_KEY, credentialGeneration)
                            putString(OPERATION_KEY, operation.name)
                            putString(MEDIA_POLICY_KEY, mediaPolicy.name)
                            mediaRequest?.let { putString(MEDIA_WORK_ID_KEY, it.id.toString()) }
                            serverMediaUsn?.let { putInt(SERVER_MEDIA_USN_KEY, it) }
                        }.build()
                val collectionRequest =
                    OneTimeWorkRequestBuilder<SyncWorker>()
                        .setId(collectionWorkId)
                        .setInputData(collectionData)
                        .addTag(SyncAttemptStore.recoveryTag(candidate))
                        .setConstraints(
                            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                        ).setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                        .build()
                val ids = candidate.ids()
                SyncAttemptStore.writePending(applicationContext, candidate)
                val continuation =
                    workManager.beginUniqueWork(UniqueWorkNames.SYNC, ExistingWorkPolicy.KEEP, collectionRequest)
                val enqueueOperation = mediaRequest?.let { continuation.then(it).enqueue() } ?: continuation.enqueue()
                try {
                    withContext(Dispatchers.IO) { enqueueOperation.result.get() }
                } catch (throwable: Throwable) {
                    SyncAttemptStore.discardPendingIfMatches(applicationContext, ids)
                    throw throwable
                }

                val candidateInfo =
                    withContext(Dispatchers.IO) {
                        workManager.getWorkInfoById(collectionWorkId).get()
                    }
                if (candidateInfo != null) {
                    return@withLock checkNotNull(SyncAttemptStore.promotePending(applicationContext, ids)).ids()
                }

                // KEEP rejected this candidate. Preserve and adopt the actual unique-work owner.
                SyncAttemptStore.discardPendingIfMatches(applicationContext, ids)
                val accepted = SyncAttemptStore.reconcile(applicationContext, workManager)
                checkNotNull(accepted) { "Unique sync enqueue was rejected without an accepted owner" }
                accepted.ids()
            }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UniqueWorkNames.SYNC)
        }

        fun cancelCollection(
            context: Context,
            workId: UUID,
        ) {
            WorkManager.getInstance(context).cancelWorkById(workId)
        }

        fun cancelMedia(
            context: Context,
            workId: UUID,
        ) {
            WorkManager.getInstance(context).cancelWorkById(workId)
        }
    }
}
