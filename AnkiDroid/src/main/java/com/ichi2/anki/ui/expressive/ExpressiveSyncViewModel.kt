// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.ui.expressive

import android.app.Application
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.ichi2.anki.currentSyncCredential
import com.ichi2.anki.sync.MeteredSyncPolicy
import com.ichi2.anki.worker.SyncAttemptRecord
import com.ichi2.anki.worker.SyncAttemptStore
import com.ichi2.anki.worker.SyncMediaPolicy
import com.ichi2.anki.worker.SyncMediaWorker
import com.ichi2.anki.worker.SyncOperation
import com.ichi2.anki.worker.SyncWorker
import com.ichi2.anki.worker.SyncWorkerOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.util.UUID
import com.ichi2.anki.isLoggedIn as hasSyncAccount
import com.ichi2.anki.syncAuth as createSyncAuth

internal enum class ExpressiveSyncWorkPhase {
    IDLE,
    COLLECTION_QUEUED,
    COLLECTION_RUNNING,
    MEDIA_QUEUED,
    MEDIA_RUNNING,
    SUCCEEDED,
    AUTH_REQUIRED,
    FULL_SYNC_REQUIRED,
    FAILED,
    CANCELLED,
}

internal data class ExpressiveSyncSnapshot(
    val attemptId: String? = null,
    val collectionWorkId: String? = null,
    val mediaWorkId: String? = null,
    val phase: ExpressiveSyncWorkPhase,
    val progress: String? = null,
    val serverMediaUsn: Int? = null,
    val mediaPolicy: SyncMediaPolicy = SyncMediaPolicy.NEVER,
)

internal enum class ExpressiveSyncStartResult {
    STARTED,
    AUTH_UNAVAILABLE,
}

internal interface ExpressiveSyncGateway {
    val snapshots: Flow<ExpressiveSyncSnapshot>

    fun isLoggedIn(): Boolean

    fun requiresMeteredConfirmation(): Boolean

    fun hasPendingStartupSync(): Boolean = false

    suspend fun start(
        operation: SyncOperation = SyncOperation.NORMAL,
        attemptId: UUID = UUID.randomUUID(),
        serverMediaUsn: Int? = null,
        mediaPolicy: SyncMediaPolicy = SyncMediaPolicy.current(),
    ): ExpressiveSyncStartResult

    fun cancelCollection(workId: UUID?)

    fun cancelMedia(workId: UUID?)

    fun acknowledgeTerminal(snapshot: ExpressiveSyncSnapshot): Boolean
}

internal class WorkManagerExpressiveSyncGateway(
    context: Context,
) : ExpressiveSyncGateway {
    private val applicationContext = context.applicationContext
    private val workManager = WorkManager.getInstance(applicationContext)
    private val attempt = MutableStateFlow<SyncAttemptRecord?>(null)

    override val snapshots: Flow<ExpressiveSyncSnapshot> =
        flow {
            attempt.value = SyncAttemptStore.reconcile(applicationContext, workManager)
            emitAll(
                attempt.flatMapLatest { record ->
                    if (record == null || record.isFullyAcknowledged()) {
                        flowOf(ExpressiveSyncSnapshot(phase = ExpressiveSyncWorkPhase.IDLE))
                    } else {
                        val collection = workManager.getWorkInfoByIdFlow(record.collectionWorkId)
                        val media =
                            record.mediaWorkId?.let(workManager::getWorkInfoByIdFlow)
                                ?: flowOf(null)
                        combine(collection, media) { collectionInfo, mediaInfo -> collectionInfo to mediaInfo }
                            .flatMapLatest { (collectionInfo, mediaInfo) ->
                                val missingAcceptedWork =
                                    collectionInfo == null || (record.mediaWorkId != null && mediaInfo == null)
                                if (missingAcceptedWork) {
                                    flow {
                                        attempt.value = SyncAttemptStore.reconcile(applicationContext, workManager)
                                        emit(ExpressiveSyncSnapshot(phase = ExpressiveSyncWorkPhase.IDLE))
                                    }
                                } else {
                                    flowOf(expressiveSyncSnapshot(record, collectionInfo, mediaInfo))
                                }
                            }
                    }
                },
            )
        }.distinctUntilChanged()

    override fun isLoggedIn(): Boolean = hasSyncAccount()

    override fun requiresMeteredConfirmation(): Boolean = MeteredSyncPolicy.shouldBlock()

    override fun hasPendingStartupSync(): Boolean = SyncAttemptStore.isExpressiveStartupSyncPending(applicationContext)

    override suspend fun start(
        operation: SyncOperation,
        attemptId: UUID,
        serverMediaUsn: Int?,
        mediaPolicy: SyncMediaPolicy,
    ): ExpressiveSyncStartResult {
        val credential = currentSyncCredential() ?: return ExpressiveSyncStartResult.AUTH_UNAVAILABLE
        createSyncAuth(expectedGeneration = credential.generation)
            ?: return ExpressiveSyncStartResult.AUTH_UNAVAILABLE
        SyncWorker.start(
            context = applicationContext,
            credentialGeneration = credential.generation,
            mediaPolicy = mediaPolicy,
            operation = operation,
            attemptId = attemptId,
            serverMediaUsn = serverMediaUsn,
        )
        SyncAttemptStore.clearExpressiveStartupSyncPending(applicationContext)
        attempt.value = SyncAttemptStore.read(applicationContext)
        return ExpressiveSyncStartResult.STARTED
    }

    override fun cancelCollection(workId: UUID?) {
        if (workId == null) SyncWorker.cancel(applicationContext) else SyncWorker.cancelCollection(applicationContext, workId)
    }

    override fun cancelMedia(workId: UUID?) {
        if (workId != null) SyncWorker.cancelMedia(applicationContext, workId)
    }

    override fun acknowledgeTerminal(snapshot: ExpressiveSyncSnapshot): Boolean {
        val attemptId = snapshot.attemptId?.let(::safeUuid) ?: return false
        val collectionId = snapshot.collectionWorkId?.let(::safeUuid) ?: return false
        val mediaId = snapshot.mediaWorkId?.let(::safeUuid)
        if (snapshot.mediaWorkId != null && mediaId == null) return false
        return SyncAttemptStore.acknowledgeTerminal(
            applicationContext,
            attemptId,
            collectionId,
            mediaId,
        )
    }
}

/** Projects one exact logical attempt; unrelated WorkManager history is never selected. */
internal fun expressiveSyncSnapshot(
    record: SyncAttemptRecord,
    collection: WorkInfo?,
    media: WorkInfo?,
): ExpressiveSyncSnapshot {
    fun snapshot(
        phase: ExpressiveSyncWorkPhase,
        progress: String? = null,
        serverMediaUsn: Int? = null,
    ) = ExpressiveSyncSnapshot(
        attemptId = record.attemptId.toString(),
        collectionWorkId = record.collectionWorkId.toString(),
        mediaWorkId = record.mediaWorkId?.toString(),
        phase = phase,
        progress = progress,
        serverMediaUsn = serverMediaUsn,
        mediaPolicy = record.mediaPolicy,
    )

    if (collection == null) return snapshot(ExpressiveSyncWorkPhase.IDLE)
    return when (collection.state) {
        WorkInfo.State.ENQUEUED,
        WorkInfo.State.BLOCKED,
        -> snapshot(ExpressiveSyncWorkPhase.COLLECTION_QUEUED)
        WorkInfo.State.RUNNING ->
            snapshot(
                ExpressiveSyncWorkPhase.COLLECTION_RUNNING,
                collection.progress.getString(SyncWorker.PROGRESS_TEXT_KEY),
            )
        WorkInfo.State.FAILED -> {
            val outcome = collection.outputData.safeSyncWorkerOutcome()
            when (outcome) {
                SyncWorkerOutcome.AUTH_REQUIRED -> snapshot(ExpressiveSyncWorkPhase.AUTH_REQUIRED)
                SyncWorkerOutcome.FULL_SYNC_REQUIRED ->
                    snapshot(
                        ExpressiveSyncWorkPhase.FULL_SYNC_REQUIRED,
                        serverMediaUsn = collection.outputData.optionalInt(SyncWorker.SERVER_MEDIA_USN_KEY),
                    )
                SyncWorkerOutcome.ERROR,
                null,
                -> snapshot(ExpressiveSyncWorkPhase.FAILED)
            }
        }
        WorkInfo.State.CANCELLED -> snapshot(ExpressiveSyncWorkPhase.CANCELLED)
        WorkInfo.State.SUCCEEDED -> {
            if (record.mediaPolicy == SyncMediaPolicy.NEVER || record.mediaWorkId == null) {
                snapshot(ExpressiveSyncWorkPhase.SUCCEEDED)
            } else {
                when (media?.state) {
                    null -> snapshot(ExpressiveSyncWorkPhase.IDLE)
                    WorkInfo.State.ENQUEUED,
                    WorkInfo.State.BLOCKED,
                    -> snapshot(ExpressiveSyncWorkPhase.MEDIA_QUEUED)
                    WorkInfo.State.RUNNING ->
                        snapshot(
                            ExpressiveSyncWorkPhase.MEDIA_RUNNING,
                            media.progress.getString(SyncMediaWorker.PROGRESS_TEXT_KEY),
                        )
                    WorkInfo.State.SUCCEEDED -> snapshot(ExpressiveSyncWorkPhase.SUCCEEDED)
                    WorkInfo.State.FAILED -> snapshot(ExpressiveSyncWorkPhase.FAILED)
                    WorkInfo.State.CANCELLED -> snapshot(ExpressiveSyncWorkPhase.CANCELLED)
                }
            }
        }
    }
}

private fun androidx.work.Data.safeSyncWorkerOutcome(): SyncWorkerOutcome? =
    getString(SyncWorker.OUTCOME_KEY)?.let { value -> SyncWorkerOutcome.entries.firstOrNull { it.name == value } }

private fun androidx.work.Data.optionalInt(key: String): Int? = getInt(key, Int.MIN_VALUE).takeUnless { it == Int.MIN_VALUE }

private fun safeUuid(value: String): UUID? = runCatching { UUID.fromString(value) }.getOrNull()

sealed interface ExpressiveSyncUiState {
    data object Checking : ExpressiveSyncUiState

    data object Idle : ExpressiveSyncUiState

    data object AwaitingMeteredConfirmation : ExpressiveSyncUiState

    data object Queued : ExpressiveSyncUiState

    data class Running(
        val progress: String?,
    ) : ExpressiveSyncUiState

    data object MediaQueued : ExpressiveSyncUiState

    data class MediaRunning(
        val progress: String?,
    ) : ExpressiveSyncUiState

    data object CancellingCollection : ExpressiveSyncUiState

    data object CancellingMedia : ExpressiveSyncUiState

    data object Succeeded : ExpressiveSyncUiState

    data object NeedsLogin : ExpressiveSyncUiState

    data object AwaitingFullSyncChoice : ExpressiveSyncUiState

    data class AwaitingFullSyncConfirmation(
        val operation: SyncOperation,
    ) : ExpressiveSyncUiState

    data object Failed : ExpressiveSyncUiState

    data object Cancelled : ExpressiveSyncUiState
}

val ExpressiveSyncUiState.isCollectionBlocking: Boolean
    get() =
        this is ExpressiveSyncUiState.Checking ||
            this is ExpressiveSyncUiState.AwaitingMeteredConfirmation ||
            this is ExpressiveSyncUiState.Queued ||
            this is ExpressiveSyncUiState.Running ||
            this is ExpressiveSyncUiState.CancellingCollection ||
            this is ExpressiveSyncUiState.AwaitingFullSyncChoice ||
            this is ExpressiveSyncUiState.AwaitingFullSyncConfirmation

val ExpressiveSyncUiState.isActive: Boolean
    get() =
        isCollectionBlocking || this is ExpressiveSyncUiState.MediaQueued ||
            this is ExpressiveSyncUiState.MediaRunning || this is ExpressiveSyncUiState.CancellingMedia

sealed interface ExpressiveSyncEffect {
    data object OpenAccount : ExpressiveSyncEffect
}

class ExpressiveSyncViewModel internal constructor(
    private val gateway: ExpressiveSyncGateway,
    private val savedStateHandle: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {
    private val restoredState = restoreUiState(savedStateHandle)
    private val _state = MutableStateFlow(restoredState ?: ExpressiveSyncUiState.Checking)
    val state: StateFlow<ExpressiveSyncUiState> = _state.asStateFlow()

    private val _refreshKey = MutableStateFlow(0)
    val refreshKey: StateFlow<Int> = _refreshKey.asStateFlow()

    private val _effects = Channel<ExpressiveSyncEffect>(Channel.BUFFERED)
    val effects: Flow<ExpressiveSyncEffect> = _effects.receiveAsFlow()

    private var startPending = false
    private var currentSnapshot: ExpressiveSyncSnapshot? = restoreSnapshot(savedStateHandle)
    private var retryAfterLogin: Boolean
        get() = savedStateHandle[KEY_RETRY_AFTER_LOGIN] ?: false
        set(value) {
            savedStateHandle[KEY_RETRY_AFTER_LOGIN] = value
        }

    init {
        viewModelScope.launch { gateway.snapshots.collect(::applySnapshot) }
        if (restoredState is ExpressiveSyncUiState.NeedsLogin && retryAfterLogin) {
            _effects.trySend(ExpressiveSyncEffect.OpenAccount)
        }
    }

    fun onSyncClick() {
        if (startPending) return
        when (state.value) {
            ExpressiveSyncUiState.Checking,
            ExpressiveSyncUiState.AwaitingMeteredConfirmation,
            ExpressiveSyncUiState.CancellingCollection,
            ExpressiveSyncUiState.CancellingMedia,
            ExpressiveSyncUiState.AwaitingFullSyncChoice,
            is ExpressiveSyncUiState.AwaitingFullSyncConfirmation,
            -> return
            ExpressiveSyncUiState.Queued,
            is ExpressiveSyncUiState.Running,
            -> {
                _state.value = ExpressiveSyncUiState.CancellingCollection
                persistState(_state.value)
                gateway.cancelCollection(currentSnapshot?.collectionWorkId?.let(::safeUuid))
            }
            ExpressiveSyncUiState.MediaQueued,
            is ExpressiveSyncUiState.MediaRunning,
            -> {
                _state.value = ExpressiveSyncUiState.CancellingMedia
                persistState(_state.value)
                gateway.cancelMedia(currentSnapshot?.mediaWorkId?.let(::safeUuid))
            }
            ExpressiveSyncUiState.NeedsLogin -> requestLogin()
            else -> beginSync()
        }
    }

    fun onLoginResult(succeeded: Boolean): Boolean {
        if (!retryAfterLogin) return false
        if (succeeded) {
            clearPersistedState()
            beginSync()
        } else {
            retryAfterLogin = false
            setState(ExpressiveSyncUiState.Idle)
        }
        return true
    }

    fun onMeteredSyncConfirmed() {
        if (state.value !is ExpressiveSyncUiState.AwaitingMeteredConfirmation) return
        startPending = false
        clearPersistedState()
        beginSync(skipMeteredConfirmation = true)
    }

    fun onMeteredSyncCancelled() {
        if (state.value !is ExpressiveSyncUiState.AwaitingMeteredConfirmation) return
        startPending = false
        setState(ExpressiveSyncUiState.Idle)
    }

    fun onKeepLocalSelected() = selectFullSyncOperation(SyncOperation.FULL_UPLOAD)

    fun onKeepRemoteSelected() = selectFullSyncOperation(SyncOperation.FULL_DOWNLOAD)

    fun onFullSyncConfirmationCancelled() {
        if (state.value is ExpressiveSyncUiState.AwaitingFullSyncConfirmation) {
            setState(ExpressiveSyncUiState.AwaitingFullSyncChoice)
        }
    }

    fun onFullSyncChoiceCancelled() {
        if (state.value !is ExpressiveSyncUiState.AwaitingFullSyncChoice) return
        currentSnapshot?.let(::acknowledgeOnce)
        setState(ExpressiveSyncUiState.Cancelled)
    }

    fun onFullSyncConfirmed() {
        val confirmation = state.value as? ExpressiveSyncUiState.AwaitingFullSyncConfirmation ?: return
        val snapshot = currentSnapshot ?: return
        val attemptId = snapshot.attemptId?.let(::safeUuid) ?: return
        startPending = true
        setState(ExpressiveSyncUiState.Queued)
        viewModelScope.launch {
            startWorker(
                operation = confirmation.operation,
                attemptId = attemptId,
                serverMediaUsn = snapshot.serverMediaUsn,
                mediaPolicy = snapshot.mediaPolicy,
            )
        }
    }

    private fun selectFullSyncOperation(operation: SyncOperation) {
        if (state.value !is ExpressiveSyncUiState.AwaitingFullSyncChoice) return
        setState(ExpressiveSyncUiState.AwaitingFullSyncConfirmation(operation))
    }

    private fun beginSync(skipMeteredConfirmation: Boolean = false) {
        if (startPending || (state.value.isActive && state.value !is ExpressiveSyncUiState.AwaitingMeteredConfirmation)) return
        clearPersistedState()
        if (!gateway.isLoggedIn()) {
            setState(ExpressiveSyncUiState.NeedsLogin)
            requestLogin()
            return
        }
        if (!skipMeteredConfirmation && gateway.requiresMeteredConfirmation()) {
            startPending = true
            setState(ExpressiveSyncUiState.AwaitingMeteredConfirmation)
            return
        }
        startPending = true
        setState(ExpressiveSyncUiState.Queued)
        viewModelScope.launch { startWorker() }
    }

    private suspend fun startWorker(
        operation: SyncOperation = SyncOperation.NORMAL,
        attemptId: UUID = UUID.randomUUID(),
        serverMediaUsn: Int? = null,
        mediaPolicy: SyncMediaPolicy = SyncMediaPolicy.current(),
    ) {
        try {
            when (gateway.start(operation, attemptId, serverMediaUsn, mediaPolicy)) {
                ExpressiveSyncStartResult.STARTED -> retryAfterLogin = false
                ExpressiveSyncStartResult.AUTH_UNAVAILABLE -> {
                    startPending = false
                    setState(ExpressiveSyncUiState.NeedsLogin)
                    requestLogin()
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            startPending = false
            setState(ExpressiveSyncUiState.Failed)
        }
    }

    private fun requestLogin() {
        if (retryAfterLogin) return
        retryAfterLogin = true
        persistState(ExpressiveSyncUiState.NeedsLogin)
        _effects.trySend(ExpressiveSyncEffect.OpenAccount)
    }

    private fun applySnapshot(snapshot: ExpressiveSyncSnapshot) {
        if (snapshot.phase != ExpressiveSyncWorkPhase.IDLE) {
            currentSnapshot = snapshot
            persistSnapshot(snapshot)
        }
        if (snapshot.phase == ExpressiveSyncWorkPhase.IDLE) {
            if (startPending || (restoredState != null && state.value != ExpressiveSyncUiState.Checking)) return
            if (gateway.hasPendingStartupSync()) {
                setState(ExpressiveSyncUiState.Idle)
                beginSync()
            } else {
                setState(ExpressiveSyncUiState.Idle)
            }
            return
        }
        startPending = false
        when (snapshot.phase) {
            ExpressiveSyncWorkPhase.IDLE -> Unit
            ExpressiveSyncWorkPhase.COLLECTION_QUEUED -> setState(ExpressiveSyncUiState.Queued)
            ExpressiveSyncWorkPhase.COLLECTION_RUNNING -> setState(ExpressiveSyncUiState.Running(snapshot.progress))
            ExpressiveSyncWorkPhase.MEDIA_QUEUED -> setState(ExpressiveSyncUiState.MediaQueued)
            ExpressiveSyncWorkPhase.MEDIA_RUNNING -> setState(ExpressiveSyncUiState.MediaRunning(snapshot.progress))
            ExpressiveSyncWorkPhase.SUCCEEDED -> {
                val attemptId = snapshot.attemptId
                if (attemptId != null && savedStateHandle.get<String>(KEY_LAST_REFRESHED_ATTEMPT) != attemptId) {
                    savedStateHandle[KEY_LAST_REFRESHED_ATTEMPT] = attemptId
                    _refreshKey.value += 1
                }
                setState(ExpressiveSyncUiState.Succeeded)
                acknowledgeOnce(snapshot)
            }
            ExpressiveSyncWorkPhase.AUTH_REQUIRED -> {
                setState(ExpressiveSyncUiState.NeedsLogin)
                acknowledgeOnce(snapshot)
                requestLogin()
            }
            ExpressiveSyncWorkPhase.FULL_SYNC_REQUIRED -> {
                setState(ExpressiveSyncUiState.AwaitingFullSyncChoice)
                acknowledgeOnce(snapshot)
            }
            ExpressiveSyncWorkPhase.FAILED -> {
                setState(ExpressiveSyncUiState.Failed)
                acknowledgeOnce(snapshot)
            }
            ExpressiveSyncWorkPhase.CANCELLED -> {
                setState(ExpressiveSyncUiState.Cancelled)
                acknowledgeOnce(snapshot)
            }
        }
    }

    private fun acknowledgeOnce(snapshot: ExpressiveSyncSnapshot) {
        val key = "${snapshot.collectionWorkId}:${snapshot.mediaWorkId.orEmpty()}"
        if (savedStateHandle.get<String>(KEY_ACKNOWLEDGED_TERMINAL) == key) return
        persistState(state.value)
        if (gateway.acknowledgeTerminal(snapshot)) {
            savedStateHandle[KEY_ACKNOWLEDGED_TERMINAL] = key
        }
    }

    private fun setState(value: ExpressiveSyncUiState) {
        _state.value = value
        persistState(value)
    }

    private fun persistState(value: ExpressiveSyncUiState) {
        savedStateHandle[KEY_UI_STATE] =
            when (value) {
                ExpressiveSyncUiState.AwaitingMeteredConfirmation -> UI_METERED
                ExpressiveSyncUiState.Succeeded -> UI_SUCCEEDED
                ExpressiveSyncUiState.NeedsLogin -> UI_LOGIN
                ExpressiveSyncUiState.AwaitingFullSyncChoice -> UI_FULL_CHOICE
                is ExpressiveSyncUiState.AwaitingFullSyncConfirmation -> UI_FULL_CONFIRM
                ExpressiveSyncUiState.Failed -> UI_FAILED
                ExpressiveSyncUiState.Cancelled -> UI_CANCELLED
                else -> null
            }
        if (value is ExpressiveSyncUiState.AwaitingFullSyncConfirmation) {
            savedStateHandle[KEY_FULL_OPERATION] = value.operation.name
        }
    }

    private fun persistSnapshot(snapshot: ExpressiveSyncSnapshot) {
        savedStateHandle[KEY_ATTEMPT_ID] = snapshot.attemptId
        savedStateHandle[KEY_COLLECTION_WORK_ID] = snapshot.collectionWorkId
        savedStateHandle[KEY_MEDIA_WORK_ID] = snapshot.mediaWorkId
        savedStateHandle[KEY_SERVER_MEDIA_USN] = snapshot.serverMediaUsn
        savedStateHandle[KEY_MEDIA_POLICY] = snapshot.mediaPolicy.name
    }

    private fun clearPersistedState() {
        savedStateHandle[KEY_UI_STATE] = null
        savedStateHandle[KEY_FULL_OPERATION] = null
    }

    companion object {
        private const val KEY_RETRY_AFTER_LOGIN = "expressive.sync.retryAfterLogin"
        private const val KEY_UI_STATE = "expressive.sync.uiState"
        private const val KEY_FULL_OPERATION = "expressive.sync.fullOperation"
        private const val KEY_LAST_REFRESHED_ATTEMPT = "expressive.sync.lastRefreshedAttempt"
        private const val KEY_ACKNOWLEDGED_TERMINAL = "expressive.sync.acknowledgedTerminal"
        private const val KEY_ATTEMPT_ID = "expressive.sync.attemptId"
        private const val KEY_COLLECTION_WORK_ID = "expressive.sync.collectionWorkId"
        private const val KEY_MEDIA_WORK_ID = "expressive.sync.mediaWorkId"
        private const val KEY_SERVER_MEDIA_USN = "expressive.sync.serverMediaUsn"
        private const val KEY_MEDIA_POLICY = "expressive.sync.mediaPolicy"
        private const val UI_METERED = "metered"
        private const val UI_SUCCEEDED = "succeeded"
        private const val UI_LOGIN = "login"
        private const val UI_FULL_CHOICE = "fullChoice"
        private const val UI_FULL_CONFIRM = "fullConfirm"
        private const val UI_FAILED = "failed"
        private const val UI_CANCELLED = "cancelled"

        internal fun factory(
            gatewayFactory: (Application) -> ExpressiveSyncGateway = { WorkManagerExpressiveSyncGateway(it) },
        ): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    val application = checkNotNull(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY])
                    ExpressiveSyncViewModel(
                        gateway = gatewayFactory(application),
                        savedStateHandle = createSavedStateHandle(),
                    )
                }
            }
    }
}

private fun restoreUiState(handle: SavedStateHandle): ExpressiveSyncUiState? =
    when (handle.get<String>("expressive.sync.uiState")) {
        "metered" -> ExpressiveSyncUiState.AwaitingMeteredConfirmation
        "succeeded" -> ExpressiveSyncUiState.Succeeded
        "login" -> ExpressiveSyncUiState.NeedsLogin
        "fullChoice" -> ExpressiveSyncUiState.AwaitingFullSyncChoice
        "fullConfirm" ->
            handle
                .get<String>("expressive.sync.fullOperation")
                ?.let { name -> SyncOperation.entries.firstOrNull { it.name == name } }
                ?.let(ExpressiveSyncUiState::AwaitingFullSyncConfirmation)
        "failed" -> ExpressiveSyncUiState.Failed
        "cancelled" -> ExpressiveSyncUiState.Cancelled
        else -> null
    }

private fun restoreSnapshot(handle: SavedStateHandle): ExpressiveSyncSnapshot? {
    val attemptId = handle.get<String>("expressive.sync.attemptId") ?: return null
    val collectionWorkId = handle.get<String>("expressive.sync.collectionWorkId") ?: return null
    val mediaPolicy =
        handle
            .get<String>("expressive.sync.mediaPolicy")
            ?.let { value -> SyncMediaPolicy.entries.firstOrNull { it.name == value } }
            ?: SyncMediaPolicy.NEVER
    return ExpressiveSyncSnapshot(
        attemptId = attemptId,
        collectionWorkId = collectionWorkId,
        mediaWorkId = handle["expressive.sync.mediaWorkId"],
        phase = ExpressiveSyncWorkPhase.FULL_SYNC_REQUIRED,
        serverMediaUsn = handle["expressive.sync.serverMediaUsn"],
        mediaPolicy = mediaPolicy,
    )
}
