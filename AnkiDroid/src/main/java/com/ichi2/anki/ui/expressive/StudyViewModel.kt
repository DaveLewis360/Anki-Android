// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.ui.expressive

import android.app.Application
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import anki.frontend.SetSchedulingStatesRequest
import anki.scheduler.CardAnswer.Rating
import anki.scheduler.UnburyDeckRequest
import com.ichi2.anki.collection.CollectionChange
import com.ichi2.anki.libanki.CardId
import com.ichi2.anki.libanki.DeckId
import com.ichi2.anki.libanki.sched.CurrentQueueState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Owns the native review loop. Durable state stores identities and primitive UI state only; queue
 * objects and rendered HTML are always reconstructed from the backend after process recreation.
 */
class StudyViewModel(
    application: Application,
    private val studyGateway: ExpressiveStudyGateway,
    private val savedStateHandle: SavedStateHandle = SavedStateHandle(),
) : AndroidViewModel(application) {
    private val _state = MutableStateFlow<StudyUiState>(StudyUiState.Loading)
    val state: StateFlow<StudyUiState> = _state.asStateFlow()

    private val _marked = MutableStateFlow(false)
    val marked: StateFlow<Boolean> = _marked.asStateFlow()

    private val _flag = MutableStateFlow(0)
    val flag: StateFlow<Int> = _flag.asStateFlow()

    private val _working = MutableStateFlow(false)
    val working: StateFlow<Boolean> = _working.asStateFlow()

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

    private val _actionErrors = Channel<String>(Channel.BUFFERED)
    val actionErrors: Flow<String> = _actionErrors.receiveAsFlow()

    private val _stopMedia = Channel<Unit>(Channel.CONFLATED)
    val stopMedia: Flow<Unit> = _stopMedia.receiveAsFlow()

    private val _typedAnswer = MutableStateFlow(savedStateHandle[KEY_TYPED_ANSWER] ?: "")
    val typedAnswer: StateFlow<String> = _typedAnswer.asStateFlow()

    private val _seconds = MutableStateFlow(savedStateHandle[KEY_SECONDS] ?: 0)
    val seconds: StateFlow<Int> = _seconds.asStateFlow()

    private val _sessionTotal = MutableStateFlow(savedStateHandle[KEY_SESSION_TOTAL] ?: -1)
    val sessionTotal: StateFlow<Int> = _sessionTotal.asStateFlow()

    private val _transition = MutableStateFlow(CardTransition.QUESTION_RISE)
    val transition: StateFlow<CardTransition> = _transition.asStateFlow()

    private val _schedulingMutation = MutableStateFlow<ExpressiveSchedulingMutationCommand?>(null)
    val schedulingMutation: StateFlow<ExpressiveSchedulingMutationCommand?> = _schedulingMutation.asStateFlow()

    private val _schedulingReady = MutableStateFlow(true)
    val schedulingReady: StateFlow<Boolean> = _schedulingReady.asStateFlow()

    private val _congratulationsInfo = MutableStateFlow<ExpressiveCongratulationsInfo?>(null)
    val congratulationsInfo: StateFlow<ExpressiveCongratulationsInfo?> = _congratulationsInfo.asStateFlow()

    private val reviewerResumed = MutableStateFlow(false)
    private val undoTokens = ArrayDeque<ReviewUndoToken>()
    private val operationMutex = Mutex()
    private var sessionGeneration = 0L
    private var renderRevision = 0L
    private var workingOperation = 0L
    private val collectionChangeInitiator = Any()
    private var lastDeckId: DeckId? = savedStateHandle[KEY_DECK_ID]
    private var retryNeedsDeckSelection = false
    private var attached = false
    private var pendingReload = false
    private var externalRefreshJob: Job? = null
    private var schedulingMutationTimeoutJob: Job? = null
    private var pendingAnsweredRestore = false

    init {
        viewModelScope.launch {
            combine(_state, reviewerResumed, _schedulingReady) { currentState, resumed, schedulingReady ->
                resumed && schedulingReady && currentState is StudyUiState.Question
            }.distinctUntilChanged().collectLatest { shouldRun ->
                if (!shouldRun) return@collectLatest
                while (true) {
                    delay(1000)
                    val next = _seconds.value + 1
                    _seconds.value = next
                    savedStateHandle[KEY_SECONDS] = next
                }
            }
        }
        viewModelScope.launch {
            studyGateway.collectionChanges.collect(::onCollectionChange)
        }
    }

    fun attach(deckId: DeckId) {
        attached = true
        val shouldReload =
            pendingReload || lastDeckId != deckId || sessionGeneration == 0L || retryNeedsDeckSelection
        selectDeckAndLoad(deckId, forceReload = shouldReload)
    }

    fun detach(preserveSession: Boolean) {
        attached = false
        setReviewerResumed(false)
        if (!preserveSession) {
            pendingReload = true
            invalidateUndoHistory()
        }
    }

    fun setReviewerResumed(resumed: Boolean) {
        reviewerResumed.value = resumed
    }

    fun updateTypedAnswer(value: String) {
        _typedAnswer.value = value
        savedStateHandle[KEY_TYPED_ANSWER] = value
    }

    private fun beginWorking(): Long {
        workingOperation += 1
        _working.value = true
        return workingOperation
    }

    private fun finishWorking(operation: Long) {
        if (operation == workingOperation) _working.value = false
    }

    private fun updateUndoAvailability() {
        _canUndo.value = undoTokens.isNotEmpty()
    }

    private fun invalidateUndoHistory() {
        undoTokens.clear()
        updateUndoAvailability()
    }

    private fun reportActionError(message: String) {
        _actionErrors.trySend(message)
    }

    private fun requestMediaStop() {
        _stopMedia.trySend(Unit)
    }

    private fun launchSerialized(block: suspend () -> Unit) {
        viewModelScope.launch {
            operationMutex.withLock { block() }
        }
    }

    private suspend fun <T> ownedWrite(block: suspend () -> T): T = studyGateway.withWriteInitiator(collectionChangeInitiator, block)

    private suspend fun selectDeckOwned(deckId: DeckId) = ownedWrite { studyGateway.selectDeck(deckId) }

    private suspend fun answerOwned(
        state: CurrentQueueState,
        rating: Rating,
    ): ReviewAnswerResult = ownedWrite { studyGateway.answer(state, rating) }

    private suspend fun toggleMarkOwned(state: CurrentQueueState): Boolean = ownedWrite { studyGateway.toggleMark(state) }

    private suspend fun cycleFlagOwned(state: CurrentQueueState): Int = ownedWrite { studyGateway.cycleFlag(state) }

    private suspend fun setFlagOwned(
        state: CurrentQueueState,
        flag: Int,
    ): Int = ownedWrite { studyGateway.setFlag(state, flag) }

    private suspend fun buryCurrentOwned(state: CurrentQueueState) {
        ownedWrite { studyGateway.buryCurrent(state) }
    }

    private suspend fun buryNoteOwned(state: CurrentQueueState) {
        ownedWrite { studyGateway.buryNote(state) }
    }

    private suspend fun suspendCurrentOwned(state: CurrentQueueState) {
        ownedWrite { studyGateway.suspendCurrent(state) }
    }

    private suspend fun suspendNoteOwned(state: CurrentQueueState) {
        ownedWrite { studyGateway.suspendNote(state) }
    }

    private suspend fun undoReviewOwned(expectedToken: ReviewUndoToken): UndoReviewResult =
        ownedWrite { studyGateway.undoReview(expectedToken) }

    private suspend fun unburyDeckOwned(
        deckId: DeckId,
        mode: UnburyDeckRequest.Mode,
    ) = ownedWrite { studyGateway.unburyDeck(deckId, mode) }

    private suspend fun rebuildStudyDeckOwned(deckId: DeckId) = ownedWrite { studyGateway.rebuildStudyDeck(deckId) }

    private suspend fun emptyStudyDeckOwned(deckId: DeckId) = ownedWrite { studyGateway.emptyStudyDeck(deckId) }

    fun selectDeckAndLoad(
        deckId: DeckId,
        forceReload: Boolean = true,
    ) {
        if (!forceReload && lastDeckId == deckId && sessionGeneration > 0 && !retryNeedsDeckSelection) return
        val restoringPersistedSession = sessionGeneration == 0L && lastDeckId == deckId
        val startTimebox = sessionGeneration == 0L
        val operation = beginWorking()
        if (!restoringPersistedSession) {
            _sessionTotal.value = -1
            savedStateHandle[KEY_SESSION_TOTAL] = -1
        }
        invalidateUndoHistory()
        launchSerialized {
            val generation = ++sessionGeneration
            lastDeckId = deckId
            savedStateHandle[KEY_DECK_ID] = deckId
            retryNeedsDeckSelection = false
            pendingReload = false
            invalidateUndoHistory()
            _state.value = StudyUiState.Loading
            _transition.value = CardTransition.QUESTION_RISE
            try {
                selectDeckOwned(deckId)
                if (startTimebox) studyGateway.startTimebox()
                if (generation != sessionGeneration) return@launchSerialized
                loadNextInternal(generation, allowAnsweredRestore = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == sessionGeneration) {
                    retryNeedsDeckSelection = true
                    _state.value = StudyUiState.Error(e.message ?: "Failed to select deck")
                }
            } finally {
                finishWorking(operation)
            }
        }
    }

    /** Reconciles the retained review session with an explicit editor result. */
    suspend fun refreshAfterNoteEditor(
        changed: Boolean,
        reloadRequired: Boolean,
    ) {
        if (!changed && !reloadRequired) return
        requestMediaStop()
        invalidateUndoHistory()
        val deckId = lastDeckId
        if (deckId == null) {
            reportActionError("The study session is not initialized")
            return
        }
        val operation = beginWorking()
        try {
            operationMutex.withLock {
                val generation = if (reloadRequired) ++sessionGeneration else sessionGeneration
                try {
                    preserveCurrentPhaseForReload()
                    _transition.value = CardTransition.QUESTION_RISE
                    if (reloadRequired) {
                        _state.value = StudyUiState.Loading
                        retryNeedsDeckSelection = false
                        selectDeckOwned(deckId)
                    }
                    if (generation != sessionGeneration) return@withLock
                    loadNextInternal(generation, allowAnsweredRestore = true)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (generation == sessionGeneration) {
                        retryNeedsDeckSelection = reloadRequired
                        _state.value = StudyUiState.Error(e.message ?: "Failed to reload after editing")
                    }
                }
            }
        } finally {
            finishWorking(operation)
        }
    }

    fun retry() {
        val deckId = lastDeckId
        if (retryNeedsDeckSelection && deckId != null) {
            selectDeckAndLoad(deckId)
        } else {
            loadNext()
        }
    }

    fun loadNext() {
        if (_working.value) return
        val generation = sessionGeneration
        val operation = beginWorking()
        launchSerialized {
            try {
                loadNextInternal(generation)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == sessionGeneration) {
                    _state.value = StudyUiState.Error(e.message ?: "Failed to load card")
                }
            } finally {
                finishWorking(operation)
            }
        }
    }

    private suspend fun loadNextInternal(
        generation: Long,
        allowAnsweredRestore: Boolean = false,
    ) {
        val loadedCard = studyGateway.currentCard(getApplication())
        if (generation != sessionGeneration) return
        if (loadedCard == null) {
            clearSchedulingMutation()
            val deckId = lastDeckId ?: error("The study session is not initialized")
            val info = studyGateway.congratulationsInfo(deckId)
            if (generation != sessionGeneration) return
            _congratulationsInfo.value = info
            _state.value = StudyUiState.Finished
            clearCardSnapshot()
            return
        }
        _congratulationsInfo.value = null
        loadedCard.queueState.timeboxReached?.let { reached ->
            clearSchedulingMutation()
            _state.value =
                StudyUiState.TimeboxReached(
                    ExpressiveTimeboxInfo(seconds = reached.secs, reviews = reached.reps),
                )
            return
        }
        val card = loadedCard.copy(renderRevision = ++renderRevision)

        val counts = card.queueState.counts
        val remaining = counts.new + counts.lrn + counts.rev
        if (_sessionTotal.value < 0) {
            _sessionTotal.value = remaining
            savedStateHandle[KEY_SESSION_TOTAL] = remaining
        }
        val cardId = card.queueState.topCard.id
        val restoredCardId = savedStateHandle.get<CardId>(KEY_CARD_ID)
        val restoringSameCard = allowAnsweredRestore && restoredCardId == cardId
        val restoreAnswer =
            restoringSameCard &&
                savedStateHandle.get<Boolean>(KEY_ANSWERED) == true
        if (restoredCardId != cardId) updateTypedAnswer("")

        _marked.value = card.marked
        _flag.value = card.flag
        _state.value = StudyUiState.Question(card, remaining)
        saveQuestionSnapshot(cardId)
        pendingAnsweredRestore = restoreAnswer
        prepareSchedulingMutation(card)

        if (restoreAnswer && _schedulingReady.value) {
            restoreAnsweredCardInternal(generation)
        } else if (!restoringSameCard) {
            _seconds.value = 0
            savedStateHandle[KEY_SECONDS] = 0
        }
    }

    private fun prepareSchedulingMutation(card: StudyCardUi) {
        schedulingMutationTimeoutJob?.cancel()
        _schedulingMutation.value = null
        val javascript = card.queueState.customSchedulingJs
        if (javascript.isBlank()) {
            _schedulingReady.value = true
            return
        }

        val statesWithContext =
            card.queueState
                .schedulingStatesWithContext()
                .toBuilder()
                .mergeStates(
                    card.queueState.states
                        .toBuilder()
                        .mergeCurrent(
                            card.queueState.states.current
                                .toBuilder()
                                .setCustomData(card.queueState.topCard.customData)
                                .build(),
                        ).build(),
                ).build()
        val command =
            ExpressiveSchedulingMutationCommand(
                key = UUID.randomUUID().toString(),
                sessionGeneration = sessionGeneration,
                cardId = card.queueState.topCard.id,
                renderRevision = card.renderRevision,
                javascript = javascript,
                statesWithContextBase64 =
                    Base64.encodeToString(statesWithContext.toByteArray(), Base64.NO_WRAP),
            )
        _schedulingReady.value = false
        _schedulingMutation.value = command
        schedulingMutationTimeoutJob =
            viewModelScope.launch {
                delay(SCHEDULING_MUTATION_TIMEOUT_MS)
                failSchedulingMutation(
                    cardId = command.cardId,
                    renderRevision = command.renderRevision,
                    message = "Custom scheduling timed out; using the original intervals",
                )
            }
    }

    fun applySchedulingMutation(
        cardId: CardId,
        renderRevision: Long,
        encodedRequest: String,
    ) {
        val command = currentSchedulingMutation(cardId, renderRevision) ?: return
        try {
            val request =
                SetSchedulingStatesRequest.parseFrom(
                    Base64.decode(encodedRequest, Base64.DEFAULT),
                )
            require(request.key == command.key) { "Custom scheduling returned an invalid session key" }
            val current = _state.value as? StudyUiState.Question
            require(current != null && current.card.queueState.topCard.id == cardId) {
                "Custom scheduling returned for a stale card"
            }
            current.card.queueState.states = request.states
            finishSchedulingMutation(command)
        } catch (failure: Exception) {
            failSchedulingMutation(cardId, renderRevision, failure.message ?: "Custom scheduling failed")
        }
    }

    fun failSchedulingMutation(
        cardId: CardId,
        renderRevision: Long,
        message: String,
    ) {
        val command = currentSchedulingMutation(cardId, renderRevision) ?: return
        reportActionError(message)
        finishSchedulingMutation(command)
    }

    private fun currentSchedulingMutation(
        cardId: CardId,
        renderRevision: Long,
    ): ExpressiveSchedulingMutationCommand? =
        _schedulingMutation.value?.takeIf { command ->
            command.sessionGeneration == sessionGeneration &&
                command.cardId == cardId &&
                command.renderRevision == renderRevision
        }

    private fun finishSchedulingMutation(command: ExpressiveSchedulingMutationCommand) {
        if (_schedulingMutation.value != command) return
        schedulingMutationTimeoutJob?.cancel()
        schedulingMutationTimeoutJob = null
        _schedulingMutation.value = null
        if (!pendingAnsweredRestore) {
            _schedulingReady.value = true
            return
        }

        val generation = sessionGeneration
        val operation = beginWorking()
        launchSerialized {
            try {
                restoreAnsweredCardInternal(generation)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                if (generation == sessionGeneration) {
                    pendingAnsweredRestore = false
                    _schedulingReady.value = true
                    _state.value = StudyUiState.Error(failure.message ?: "Failed to restore the card answer")
                }
            } finally {
                finishWorking(operation)
            }
        }
    }

    private suspend fun restoreAnsweredCardInternal(generation: Long) {
        val current = _state.value as? StudyUiState.Question ?: return
        val (back, intervals) =
            studyGateway.revealCard(getApplication(), current.card.queueState, _typedAnswer.value)
        if (generation != sessionGeneration) return
        pendingAnsweredRestore = false
        _schedulingReady.value = true
        _transition.value = CardTransition.ANSWER_REVEAL
        _state.value = StudyUiState.Answered(AnsweredCard(current.card, back, intervals), current.remaining)
        saveAnsweredSnapshot(current.card.queueState.topCard.id)
    }

    private fun clearSchedulingMutation() {
        schedulingMutationTimeoutJob?.cancel()
        schedulingMutationTimeoutJob = null
        _schedulingMutation.value = null
        _schedulingReady.value = true
        pendingAnsweredRestore = false
    }

    fun continueAfterTimebox() = loadNext()

    fun revealCurrent(typedInput: String? = _typedAnswer.value) {
        val current = _state.value
        if (_working.value || !_schedulingReady.value || current !is StudyUiState.Question) return
        typedInput?.let(::updateTypedAnswer)
        requestMediaStop()
        val generation = sessionGeneration
        val operation = beginWorking()
        _transition.value = CardTransition.ANSWER_REVEAL
        launchSerialized {
            try {
                val (back, intervals) =
                    studyGateway.revealCard(getApplication(), current.card.queueState, typedInput)
                if (generation != sessionGeneration) return@launchSerialized
                _state.value = StudyUiState.Answered(AnsweredCard(current.card, back, intervals), current.remaining)
                saveAnsweredSnapshot(current.card.queueState.topCard.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == sessionGeneration) {
                    _state.value = StudyUiState.Error(e.message ?: "Failed to reveal card")
                }
            } finally {
                finishWorking(operation)
            }
        }
    }

    fun grade(rating: Rating) {
        val current = _state.value
        if (_working.value || current !is StudyUiState.Answered) return
        requestMediaStop()
        val generation = sessionGeneration
        val operation = beginWorking()
        _transition.value =
            if (rating == Rating.AGAIN) CardTransition.QUESTION_BACK else CardTransition.QUESTION_FORWARD
        launchSerialized {
            try {
                try {
                    withContext(NonCancellable) {
                        answerOwned(current.card.card.queueState, rating).also { result ->
                            if (generation == sessionGeneration) {
                                result.undoToken?.let {
                                    undoTokens.addLast(it)
                                    updateUndoAvailability()
                                } ?: reportActionError("Answer saved, but it cannot be undone")
                                result.leech?.let { leech ->
                                    reportActionError(
                                        if (leech.suspended) {
                                            "Card was a leech and has been suspended"
                                        } else {
                                            "Card was a leech"
                                        },
                                    )
                                }
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (generation == sessionGeneration) {
                        reportActionError(e.message ?: "Failed to answer card")
                    }
                    return@launchSerialized
                }
                if (generation != sessionGeneration) return@launchSerialized
                savedStateHandle[KEY_ANSWERED] = false
                try {
                    loadNextInternal(generation)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (generation == sessionGeneration) {
                        _state.value =
                            StudyUiState.Error(
                                e.message?.let { "Answer saved, but the next card could not be loaded: $it" }
                                    ?: "Answer saved, but the next card could not be loaded",
                            )
                    }
                }
            } finally {
                finishWorking(operation)
            }
        }
    }

    fun toggleMark() {
        val queue = currentQueue() ?: return
        if (_working.value) return
        val generation = sessionGeneration
        val operation = beginWorking()
        launchSerialized {
            try {
                val marked = toggleMarkOwned(queue)
                if (generation != sessionGeneration) return@launchSerialized
                invalidateUndoHistory()
                _marked.value = marked
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == sessionGeneration) reportActionError(e.message ?: "Failed to mark card")
            } finally {
                finishWorking(operation)
            }
        }
    }

    fun cycleFlag() {
        val queue = currentQueue() ?: return
        if (_working.value) return
        val generation = sessionGeneration
        val operation = beginWorking()
        launchSerialized {
            try {
                val flag = cycleFlagOwned(queue)
                if (generation != sessionGeneration) return@launchSerialized
                invalidateUndoHistory()
                _flag.value = flag
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == sessionGeneration) reportActionError(e.message ?: "Failed to flag card")
            } finally {
                finishWorking(operation)
            }
        }
    }

    fun setFlag(flag: Int) {
        val queue = currentQueue() ?: return
        if (_working.value) return
        val generation = sessionGeneration
        val operation = beginWorking()
        launchSerialized {
            try {
                val updated = setFlagOwned(queue, flag)
                if (generation != sessionGeneration) return@launchSerialized
                invalidateUndoHistory()
                _flag.value = updated
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == sessionGeneration) reportActionError(e.message ?: "Failed to flag card")
            } finally {
                finishWorking(operation)
            }
        }
    }

    fun undo() {
        if (_working.value) return
        val expectedToken = undoTokens.lastOrNull() ?: return
        requestMediaStop()
        val generation = sessionGeneration
        val operation = beginWorking()
        launchSerialized {
            try {
                when (undoReviewOwned(expectedToken)) {
                    UndoReviewResult.Undone -> {
                        if (generation != sessionGeneration) return@launchSerialized
                        if (undoTokens.lastOrNull() == expectedToken) {
                            undoTokens.removeLast()
                            updateUndoAvailability()
                        }
                        savedStateHandle[KEY_ANSWERED] = false
                        loadNextInternal(generation)
                    }
                    UndoReviewResult.NotOwnedOrNoLongerTop -> {
                        if (generation == sessionGeneration) {
                            invalidateUndoHistory()
                            reportActionError("This review can no longer be undone")
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == sessionGeneration) reportActionError(e.message ?: "Failed to undo review")
            } finally {
                finishWorking(operation)
            }
        }
    }

    fun buryCurrent() {
        val queue = currentQueue() ?: return
        runDestructiveAction { buryCurrentOwned(queue) }
    }

    fun buryNote() {
        val queue = currentQueue() ?: return
        runDestructiveAction { buryNoteOwned(queue) }
    }

    fun suspendCurrent() {
        val queue = currentQueue() ?: return
        runDestructiveAction { suspendCurrentOwned(queue) }
    }

    fun suspendNote() {
        val queue = currentQueue() ?: return
        runDestructiveAction { suspendNoteOwned(queue) }
    }

    fun unburyFinished(mode: UnburyDeckRequest.Mode) {
        runCompletionAction { info -> unburyDeckOwned(info.deckId, mode) }
    }

    fun rebuildFinishedDeck() {
        runCompletionAction { info -> rebuildStudyDeckOwned(info.deckId) }
    }

    fun emptyFinishedDeck() {
        runCompletionAction { info -> emptyStudyDeckOwned(info.deckId) }
    }

    private fun runCompletionAction(block: suspend (ExpressiveCongratulationsInfo) -> Unit) {
        if (_state.value != StudyUiState.Finished) return
        val info = _congratulationsInfo.value ?: return
        if (_working.value) return
        val generation = sessionGeneration
        val operation = beginWorking()
        launchSerialized {
            try {
                block(info)
                if (generation != sessionGeneration) return@launchSerialized
                invalidateUndoHistory()
                loadNextInternal(generation)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                if (generation == sessionGeneration) {
                    reportActionError(failure.message ?: "Study operation failed")
                }
            } finally {
                finishWorking(operation)
            }
        }
    }

    private fun runDestructiveAction(block: suspend () -> Unit) {
        if (_working.value) return
        requestMediaStop()
        val generation = sessionGeneration
        val operation = beginWorking()
        launchSerialized {
            try {
                block()
                if (generation != sessionGeneration) return@launchSerialized
                invalidateUndoHistory()
                savedStateHandle[KEY_ANSWERED] = false
                loadNextInternal(generation)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == sessionGeneration) reportActionError(e.message ?: "Study operation failed")
            } finally {
                finishWorking(operation)
            }
        }
    }

    private fun currentQueue(): CurrentQueueState? =
        when (val current = _state.value) {
            is StudyUiState.Question -> current.card.queueState
            is StudyUiState.Answered -> current.card.card.queueState
            else -> null
        }

    private fun onCollectionChange(change: CollectionChange) {
        val changes = change.changes
        if (!changes.studyQueues && !changes.card && !changes.note && !changes.noteText && !changes.deck) return
        if (change.initiator === collectionChangeInitiator) return
        pendingReload = true
        if (!attached) return

        // Invalidate in-flight operation publications immediately; the serialized reload cannot
        // acquire the mutex until those operations return.
        sessionGeneration += 1
        clearSchedulingMutation()
        requestMediaStop()
        invalidateUndoHistory()
        externalRefreshJob?.cancel()
        externalRefreshJob =
            viewModelScope.launch {
                delay(EXTERNAL_REFRESH_DEBOUNCE_MS)
                operationMutex.withLock {
                    if (!attached || !pendingReload) return@withLock
                    val deckId = lastDeckId ?: return@withLock
                    val operation = beginWorking()
                    val generation = sessionGeneration
                    try {
                        preserveCurrentPhaseForReload()
                        pendingReload = false
                        _transition.value = CardTransition.QUESTION_RISE
                        selectDeckOwned(deckId)
                        if (generation == sessionGeneration) {
                            loadNextInternal(generation, allowAnsweredRestore = true)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (generation == sessionGeneration) {
                            pendingReload = true
                            _state.value = StudyUiState.Error(e.message ?: "Failed to refresh study session")
                        }
                    } finally {
                        finishWorking(operation)
                    }
                }
            }
    }

    private fun preserveCurrentPhaseForReload() {
        when (val current = _state.value) {
            is StudyUiState.Question -> saveQuestionSnapshot(current.card.queueState.topCard.id)
            is StudyUiState.Answered -> saveAnsweredSnapshot(current.card.card.queueState.topCard.id)
            else -> Unit
        }
    }

    private fun saveQuestionSnapshot(cardId: CardId) {
        savedStateHandle[KEY_CARD_ID] = cardId
        savedStateHandle[KEY_ANSWERED] = false
    }

    private fun saveAnsweredSnapshot(cardId: CardId) {
        savedStateHandle[KEY_CARD_ID] = cardId
        savedStateHandle[KEY_ANSWERED] = true
    }

    private fun clearCardSnapshot() {
        savedStateHandle.remove<CardId>(KEY_CARD_ID)
        savedStateHandle[KEY_ANSWERED] = false
        updateTypedAnswer("")
    }

    companion object {
        private const val KEY_DECK_ID = "study.deckId"
        private const val KEY_CARD_ID = "study.cardId"
        private const val KEY_ANSWERED = "study.answered"
        private const val KEY_TYPED_ANSWER = "study.typedAnswer"
        private const val KEY_SECONDS = "study.seconds"
        private const val KEY_SESSION_TOTAL = "study.sessionTotal"
        private const val EXTERNAL_REFRESH_DEBOUNCE_MS = 100L
        private const val SCHEDULING_MUTATION_TIMEOUT_MS = 15_000L

        /** Production factory with an injectable gateway for boundary tests. */
        fun factory(studyGateway: ExpressiveStudyGateway = ExpressiveCollection): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    StudyViewModel(
                        application =
                            checkNotNull(
                                this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY],
                            ),
                        studyGateway = studyGateway,
                        savedStateHandle = createSavedStateHandle(),
                    )
                }
            }
    }
}
