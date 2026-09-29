// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: Copyright (c) 2024 Brayan Oliveira <69634269+brayandso@users.noreply.github.com>
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
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import anki.sync.MediaSyncProgress
import anki.sync.SyncAuth
import com.ichi2.anki.CollectionManager
import com.ichi2.anki.CollectionManager.TR
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.NotificationChannel
import com.ichi2.anki.R
import com.ichi2.anki.cancelMediaSync
import com.ichi2.anki.currentSyncCredential
import com.ichi2.anki.notifications.NotificationId
import com.ichi2.anki.utils.ext.trySetForeground
import com.ichi2.utils.Permissions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import net.ankiweb.rsdroid.Backend
import timber.log.Timber
import java.util.UUID
import com.ichi2.anki.syncAuth as storedSyncAuth

internal enum class SyncMediaWorkerOutcome {
    AUTH_REQUIRED,
    ERROR,
}

/** Media phase of a durable logical sync attempt. It never blocks collection access. */
class SyncMediaWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    private val cancelIntent = WorkManager.getInstance(context).createCancelPendingIntent(id)
    private val notificationManager: NotificationManagerCompat? =
        if (Permissions.canPostNotifications(context)) NotificationManagerCompat.from(context) else null

    override suspend fun doWork(): Result {
        Timber.v("SyncMediaWorker::doWork")
        val credentialGeneration =
            inputData.getString(SyncWorker.CREDENTIAL_GENERATION_KEY)
                ?: return Result.failure(outcomeData(SyncMediaWorkerOutcome.AUTH_REQUIRED))
        val auth =
            storedSyncAuth(expectedGeneration = credentialGeneration)
                ?: return Result.failure(outcomeData(SyncMediaWorkerOutcome.AUTH_REQUIRED))

        return try {
            syncMedia(auth)
            notificationManager?.cancel(NotificationId.SYNC_MEDIA)
            Result.success()
        } catch (cancellation: CancellationException) {
            Timber.i("SyncMediaWorker cancelled")
            notificationManager?.cancel(NotificationId.SYNC_MEDIA)
            cancelMediaSync(CollectionManager.getBackend())
            throw cancellation
        } catch (throwable: Throwable) {
            Timber.w(throwable, "SyncMediaWorker failed")
            notify {
                setContentTitle(TR.syncMediaFailed())
                setContentText(applicationContext.getString(R.string.connection_error_message))
            }
            Result.failure(outcomeData(SyncMediaWorkerOutcome.ERROR))
        }
    }

    private suspend fun syncMedia(auth: SyncAuth) {
        // syncMedia only starts the sync, which then runs independently in the backend while collection
        // operations remain available. It is started inside the collection queue, so a restore, a deferred
        // restore finish, or a media operation that has just found no running media sync cannot be overtaken.
        val backend =
            withCol {
                syncMedia(auth)
                backend
            }
        delay(1000)
        if (backend.mediaSyncStatus().active) {
            trySetForeground(getForegroundInfo())
            monitorProgress(backend)
        }
    }

    private suspend fun monitorProgress(backend: Backend) {
        var previous: MediaSyncProgress? = null
        while (true) {
            val status = backend.mediaSyncStatus()
            if (!status.active) return
            if (previous != status.progress) {
                previous = status.progress
                val text = status.progress.run { "$added $removed $checked" }
                setProgress(Data.Builder().putString(PROGRESS_TEXT_KEY, text).build())
                notify(getProgressNotification(text))
            }
            delay(NOTIFICATION_UPDATE_RATE_MS)
        }
    }

    private fun outcomeData(outcome: SyncMediaWorkerOutcome): Data = Data.Builder().putString(OUTCOME_KEY, outcome.name).build()

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification =
            buildNotification {
                setContentTitle(applicationContext.getString(R.string.syncing_media))
                setOngoing(true)
                setProgress(0, 0, true)
                addAction(R.drawable.close_icon, TR.syncAbortButton(), cancelIntent)
                foregroundServiceBehavior = NotificationCompat.FOREGROUND_SERVICE_DEFERRED
            }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NotificationId.SYNC_MEDIA, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NotificationId.SYNC_MEDIA, notification)
        }
    }

    private fun notify(notification: Notification) = notificationManager?.notify(NotificationId.SYNC_MEDIA, notification)

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
            setContentTitle(applicationContext.getString(R.string.syncing_media))
            setContentText(progress)
            setOngoing(true)
            addAction(R.drawable.close_icon, TR.syncAbortButton(), cancelIntent)
        }

    companion object {
        internal const val OUTCOME_KEY = "syncMediaWorkerOutcome"
        internal const val PROGRESS_TEXT_KEY = "syncMediaWorkerProgressText"
        const val NOTIFICATION_UPDATE_RATE_MS = 500L

        fun getWorkRequest(
            mediaPolicy: SyncMediaPolicy,
            credentialGeneration: String,
            attemptId: UUID,
        ): OneTimeWorkRequest {
            require(mediaPolicy != SyncMediaPolicy.NEVER)
            val networkType =
                if (mediaPolicy == SyncMediaPolicy.ONLY_UNMETERED) NetworkType.UNMETERED else NetworkType.CONNECTED
            val data =
                Data
                    .Builder()
                    .putString(SyncWorker.ATTEMPT_ID_KEY, attemptId.toString())
                    .putString(SyncWorker.CREDENTIAL_GENERATION_KEY, credentialGeneration)
                    .putString(SyncWorker.MEDIA_POLICY_KEY, mediaPolicy.name)
                    .build()
            return OneTimeWorkRequestBuilder<SyncMediaWorker>()
                .setInputData(data)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(networkType).build())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
        }

        fun start(
            context: Context,
            auth: SyncAuth,
        ) {
            val credential = currentSyncCredential()?.takeIf { it.hkey == auth.hkey } ?: return
            val mediaPolicy = SyncMediaPolicy.current()
            if (mediaPolicy == SyncMediaPolicy.NEVER) return
            val request = getWorkRequest(mediaPolicy, credential.generation, UUID.randomUUID())
            WorkManager
                .getInstance(context)
                .enqueueUniqueWork(UniqueWorkNames.SYNC_MEDIA, ExistingWorkPolicy.KEEP, request)
        }
    }
}
