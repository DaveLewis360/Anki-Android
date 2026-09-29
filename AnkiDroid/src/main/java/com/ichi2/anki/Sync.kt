/*
 * Copyright (c) 2022 Ankitects Pty Ltd <http://apps.ankiweb.net>
 *
 * This program is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation; either version 3 of the License, or (at your option) any later
 * version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.ichi2.anki

import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.work.WorkManager
import anki.collection.Progress
import anki.sync.SyncAuth
import anki.sync.SyncCollectionResponse
import anki.sync.syncAuth
import com.google.android.material.snackbar.Snackbar
import com.ichi2.anki.CollectionManager.TR
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.account.SyncCredentialSnapshot
import com.ichi2.anki.account.SyncCredentialStore
import com.ichi2.anki.common.time.TimeManager
import com.ichi2.anki.dialogs.SyncErrorDialog
import com.ichi2.anki.observability.ChangeManager.notifySubscribersAllValuesChanged
import com.ichi2.anki.settings.Prefs
import com.ichi2.anki.snackbar.showSnackbar
import com.ichi2.anki.ui.internationalization.sentenceCase
import com.ichi2.anki.worker.SyncMediaPolicy
import com.ichi2.anki.worker.SyncMediaWorker
import com.ichi2.anki.worker.UniqueWorkNames
import com.ichi2.preferences.VersatileTextWithASwitchPreference
import com.ichi2.utils.dismissSafely
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.ankiweb.rsdroid.Backend
import net.ankiweb.rsdroid.exceptions.BackendInterruptedException
import net.ankiweb.rsdroid.exceptions.BackendSyncException
import timber.log.Timber

object SyncPreferences {
    const val CURRENT_SYNC_URI = "currentSyncUri"
    const val CUSTOM_SYNC_URI = "syncBaseUrl"
    const val CUSTOM_SYNC_ENABLED = CUSTOM_SYNC_URI + VersatileTextWithASwitchPreference.SWITCH_SUFFIX
}

enum class ConflictResolution {
    FULL_DOWNLOAD,
    FULL_UPLOAD,
}

fun currentSyncCredential(): SyncCredentialSnapshot? = SyncCredentialStore.read(AnkiDroidApp.instance.applicationContext)

fun syncAuth(expectedGeneration: String? = null): SyncAuth? {
    // Grab custom sync certificate from preferences (default is the empty string) and set it in CollectionManager
    val currentSyncCertificate = Prefs.customSyncCertificate ?: ""
    CollectionManager.updateCustomCertificate(currentSyncCertificate)

    val credential = currentSyncCredential() ?: return null
    if (expectedGeneration != null && credential.generation != expectedGeneration) return null
    val resolvedEndpoint = credential.endpoint ?: customSyncEndpoint()
    return syncAuth {
        hkey = credential.hkey
        if (resolvedEndpoint != null) {
            endpoint = resolvedEndpoint
        }
        ioTimeoutSecs = Prefs.networkTimeoutSecs
    }
}

/** Endpoint for password login, or the endpoint atomically owned by the current credential. */
fun getEndpoint(): String? = currentSyncCredential()?.endpoint ?: customSyncEndpoint()

private fun customSyncEndpoint(): String? =
    if (Prefs.isCustomSyncEnabled) {
        Prefs.customSyncUri
    } else {
        null
    }

/**
 * Whether the user has a sync account.
 * Returning true does not guarantee that the user actually synced recently,
 * or even that the ankiweb account is still valid.
 */
fun isLoggedIn(): Boolean = currentSyncCredential() != null

fun millisecondsSinceLastSync() = TimeManager.time.intTimeMS() - Prefs.lastSyncTime

private enum class CollectionSyncOutcome {
    COMPLETED,
    AWAITING_CONFLICT_RESOLUTION,
}

fun DeckPicker.handleNewSync(
    conflict: ConflictResolution?,
    syncMedia: Boolean,
    startupSyncGeneration: Long?,
) {
    val deckPicker = this
    val credentialGeneration = currentSyncCredential()?.generation
    if (credentialGeneration == null) {
        deckPicker.onForegroundSyncFailed(startupSyncGeneration)
        return
    }
    val auth = syncAuth(expectedGeneration = credentialGeneration)
    if (auth == null) {
        deckPicker.onForegroundSyncFailed(startupSyncGeneration)
        return
    }
    launchCatchingTask {
        try {
            try {
                val outcome =
                    try {
                        when (conflict) {
                            ConflictResolution.FULL_DOWNLOAD -> {
                                handleDownload(deckPicker, auth, deckPicker.mediaUsnOnConflict)
                                CollectionSyncOutcome.COMPLETED
                            }
                            ConflictResolution.FULL_UPLOAD -> {
                                handleUpload(deckPicker, auth, deckPicker.mediaUsnOnConflict)
                                CollectionSyncOutcome.COMPLETED
                            }
                            null -> handleNormalSync(deckPicker, auth, syncMedia, credentialGeneration)
                        }
                    } catch (exc: BackendSyncException.BackendSyncAuthFailedException) {
                        // Clear only the credential generation used by this request.
                        clearLogin(expectedGeneration = credentialGeneration)
                        throw exc
                    }
                if (outcome == CollectionSyncOutcome.AWAITING_CONFLICT_RESOLUTION) {
                    deckPicker.onForegroundSyncAwaitingConflict(startupSyncGeneration)
                }
                withCol { notetypes.clearCache() }
                notifySubscribersAllValuesChanged(deckPicker)
                refreshState()
                if (outcome == CollectionSyncOutcome.COMPLETED) {
                    deckPicker.onForegroundSyncCompleted(startupSyncGeneration)
                }
            } catch (exc: Exception) {
                deckPicker.onForegroundSyncFailed(startupSyncGeneration)
                throw exc
            }
        } finally {
            // Always update last sync time to prevent infinite retry loops
            // when sync fails (e.g., collection too large). See issue #19776
            setLastSyncTimeToNow()
        }
    }
}

fun updateLogin(
    username: String,
    hkey: String,
) {
    if (hkey.isBlank()) {
        clearLogin()
        return
    }
    SyncCredentialStore.replace(
        context = AnkiDroidApp.instance.applicationContext,
        hkey = hkey,
        username = username,
    )
}

fun clearLogin(expectedGeneration: String? = null): Boolean =
    SyncCredentialStore.clear(
        context = AnkiDroidApp.instance.applicationContext,
        expectedGeneration = expectedGeneration,
    )

/** Cancels account-owned work before atomically forgetting exactly one credential generation. */
suspend fun forgetSyncAccount(expectedGeneration: String): Boolean {
    val context = AnkiDroidApp.instance.applicationContext
    val workManager = WorkManager.getInstance(context)
    withContext(Dispatchers.IO) {
        workManager.cancelUniqueWork(UniqueWorkNames.SYNC).result.get()
        workManager.cancelUniqueWork(UniqueWorkNames.SYNC_MEDIA).result.get()
        runCatching { cancelSync(CollectionManager.getBackend()) }
        runCatching { cancelMediaSync(CollectionManager.getBackend()) }
    }
    return withContext(NonCancellable) {
        if (!clearLogin(expectedGeneration)) return@withContext false
        withCol { media.forceResync() }
        true
    }
}

fun updateSyncEndpoint(
    expectedGeneration: String,
    endpoint: String,
): Boolean =
    SyncCredentialStore.updateEndpoint(
        context = AnkiDroidApp.instance.applicationContext,
        expectedGeneration = expectedGeneration,
        endpoint = endpoint,
    )

fun cancelSync(backend: Backend) {
    backend.setWantsAbort()
    backend.abortSync()
}

private suspend fun handleNormalSync(
    deckPicker: DeckPicker,
    auth: SyncAuth,
    syncMedia: Boolean,
    credentialGeneration: String,
): CollectionSyncOutcome {
    Timber.i("Sync: Normal collection sync")
    var auth2 = auth
    val output =
        deckPicker.withProgress(
            extractProgress = {
                if (progress.hasNormalSync()) {
                    text = progress.normalSync.run { "$added\n$removed" }
                }
            },
            onCancel = ::cancelSync,
            manualCancelButton = R.string.dialog_cancel,
        ) {
            withCol {
                syncCollection(auth2, syncMedia = false) // media is synced by SyncMediaWorker
            }
        }

    if (output.hasNewEndpoint() && output.newEndpoint.isNotEmpty()) {
        Timber.i("sync endpoint updated")
        if (!updateSyncEndpoint(credentialGeneration, output.newEndpoint)) {
            throw CancellationException("Sync account changed while updating endpoint")
        }
        auth2 =
            syncAuth {
                this.hkey = auth.hkey
                endpoint = output.newEndpoint
            }
    }
    val mediaUsn =
        if (syncMedia) {
            output.serverMediaUsn
        } else {
            null
        }

    Timber.i("sync result: ${output.required}")
    return when (output.required) {
        // a successful sync returns this value
        SyncCollectionResponse.ChangesRequired.NO_CHANGES -> {
            // scheduler version may have changed
            withCol { _loadScheduler() }
            val message = if (syncMedia) R.string.col_synced_media_in_background else R.string.sync_database_acknowledge
            deckPicker.showSyncLogMessage(message, output.serverMessage)
            deckPicker.refreshState()
            if (syncMedia) {
                SyncMediaWorker.start(deckPicker, auth2)
            }
            CollectionSyncOutcome.COMPLETED
        }

        SyncCollectionResponse.ChangesRequired.FULL_DOWNLOAD -> {
            handleDownload(deckPicker, auth2, mediaUsn)
            CollectionSyncOutcome.COMPLETED
        }

        SyncCollectionResponse.ChangesRequired.FULL_UPLOAD -> {
            handleUpload(deckPicker, auth2, mediaUsn)
            CollectionSyncOutcome.COMPLETED
        }

        SyncCollectionResponse.ChangesRequired.FULL_SYNC -> {
            deckPicker.mediaUsnOnConflict = mediaUsn
            deckPicker.showSyncErrorDialog(SyncErrorDialog.Type.DIALOG_SYNC_CONFLICT_RESOLUTION)
            CollectionSyncOutcome.AWAITING_CONFLICT_RESOLUTION
        }

        SyncCollectionResponse.ChangesRequired.NORMAL_SYNC,
        SyncCollectionResponse.ChangesRequired.UNRECOGNIZED,
        null,
        -> {
            TODO("should never happen")
        }
    }
}

private fun fullDownloadProgress(title: String): ProgressContext.() -> Unit =
    {
        fun Progress.FullSync.toAmount() = ProgressContext.Amount(transferred.toLong(), total.toLong())

        text = title
        if (progress.hasFullSync() && progress.fullSync.total > 0) {
            amount = progress.fullSync.toAmount()
        }
    }

private suspend fun handleDownload(
    deckPicker: DeckPicker,
    auth: SyncAuth,
    mediaUsn: Int?,
) {
    Timber.i("Sync: Full collection download requested")
    deckPicker.withProgress(
        progressContext = ProgressContext.ofBytes(context = deckPicker).copy(separator = "\n"),
        extractProgress = fullDownloadProgress(TR.syncDownloadingFromAnkiweb()),
        onCancel = ::cancelSync,
        manualCancelButton = R.string.dialog_cancel,
    ) {
        withCol {
            try {
                createBackup(
                    BackupManager.getBackupDirectoryFromCollection(colDb),
                    force = true,
                    waitForCompletion = true,
                )
                close(downgrade = false, forFullSync = true)
                fullUploadOrDownload(auth, upload = false, serverUsn = mediaUsn)
            } finally {
                reopen(afterFullSync = true)
            }
        }
        deckPicker.refreshState()
        if (mediaUsn != null) {
            SyncMediaWorker.start(deckPicker, auth)
        }
    }

    Timber.i("Full Download Completed")
    deckPicker.showSyncLogMessage(R.string.backup_one_way_sync_from_server, "")
}

private suspend fun handleUpload(
    deckPicker: DeckPicker,
    auth: SyncAuth,
    mediaUsn: Int?,
) {
    Timber.i("Sync: Full collection upload requested")
    deckPicker.withProgress(
        progressContext = ProgressContext.ofBytes(context = deckPicker).copy(separator = "\n"),
        extractProgress = fullDownloadProgress(TR.syncUploadingToAnkiweb()),
        onCancel = ::cancelSync,
        manualCancelButton = R.string.dialog_cancel,
    ) {
        withCol {
            close(downgrade = false, forFullSync = true)
            try {
                fullUploadOrDownload(auth, upload = true, serverUsn = mediaUsn)
            } finally {
                reopen(afterFullSync = true)
            }
        }
        deckPicker.refreshState()
        if (mediaUsn != null) {
            SyncMediaWorker.start(deckPicker, auth)
        }
    }
    Timber.i("Full Upload Completed")
    deckPicker.showSyncLogMessage(R.string.sync_log_uploading_message, "")
}

fun cancelMediaSync(backend: Backend) {
    backend.setWantsAbort()
    backend.abortMediaSync()
}

/**
 * Whether media should be fetched on sync. Options from preferences are:
 * * Always
 * * Only if unmetered
 * * Never
 */
fun shouldFetchMedia(): Boolean = SyncMediaPolicy.current() != SyncMediaPolicy.NEVER

suspend fun monitorMediaSync(deckPicker: DeckPicker) {
    val backend = CollectionManager.getBackend()
    val scope = CoroutineScope(Dispatchers.IO)
    var isAborted = false

    val dialog =
        withContext(Dispatchers.Main) {
            AlertDialog
                .Builder(deckPicker)
                .setTitle(with(deckPicker) { TR.sentenceCase.mediaSyncLog })
                .setMessage("")
                .setPositiveButton(R.string.dialog_continue) { _, _ ->
                    scope.cancel()
                }.setNegativeButton(TR.syncAbortButton()) { _, _ ->
                    isAborted = true
                    cancelMediaSync(backend)
                }.show()
        }

    fun showMessage(msg: String) = deckPicker.showSnackbar(msg, Snackbar.LENGTH_SHORT)

    scope.launch {
        try {
            while (true) {
                // this will throw if the sync exited with an error
                val resp = backend.mediaSyncStatus()
                if (!resp.active) {
                    break
                }
                val text = resp.progress.run { "$added\n$removed\n$checked" }
                dialog.setMessage(text)
                delay(100)
            }
            showMessage(if (isAborted) TR.syncMediaAborted() else TR.syncMediaComplete())
        } catch (_: BackendInterruptedException) {
            showMessage(TR.syncMediaAborted())
        } catch (_: CancellationException) {
            // do nothing
        } catch (_: Exception) {
            showMessage(TR.syncMediaFailed())
        } finally {
            dialog.dismissSafely()
        }
    }
}

/**
 * Show a simple snackbar message or notification if the activity is not in foreground
 * @param messageResource String resource for message
 */
fun DeckPicker.showSyncLogMessage(
    @StringRes messageResource: Int,
    syncMessage: String?,
) {
    if (activityPaused) {
        val res = AnkiDroidApp.appResources
        showSimpleNotification(
            res.getString(R.string.app_name),
            res.getString(messageResource),
            NotificationChannel.SYNC,
        )
    } else {
        if (syncMessage.isNullOrEmpty()) {
            showSnackbar(messageResource)
        } else {
            val res = AnkiDroidApp.appResources
            showSimpleMessageDialog(title = res.getString(messageResource), message = syncMessage)
        }
    }
}

fun setLastSyncTimeToNow() {
    Prefs.lastSyncTime = TimeManager.time.intTimeMS()
}
