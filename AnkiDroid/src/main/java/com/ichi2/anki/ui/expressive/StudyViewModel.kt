// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.ui.expressive

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import anki.scheduler.CardAnswer.Rating
import com.ichi2.anki.libanki.DeckId
import com.ichi2.anki.libanki.sched.CurrentQueueState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Holds the review-loop state across configuration changes. All collection work runs
 * in [viewModelScope], so a rotation can never cancel an in-flight grade, and the
 * current phase (question/answer html, counters) survives recreation.
 */
class StudyViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val _state = MutableStateFlow<StudyUiState>(StudyUiState.Loading)
    val state: StateFlow<StudyUiState> = _state.asStateFlow()

    private val _marked = MutableStateFlow(false)
    val marked: StateFlow<Boolean> = _marked.asStateFlow()

    private val _flag = MutableStateFlow(0)
    val flag: StateFlow<Int> = _flag.asStateFlow()

    private val _working = MutableStateFlow(false)
    val working: StateFlow<Boolean> = _working.asStateFlow()

    private val _seconds = MutableStateFlow(0)
    val seconds: StateFlow<Int> = _seconds.asStateFlow()

    private val _sessionTotal = MutableStateFlow(-1)
    val sessionTotal: StateFlow<Int> = _sessionTotal.asStateFlow()

    private val _transition = MutableStateFlow(CardTransition.QUESTION_RISE)
    val transition: StateFlow<CardTransition> = _transition.asStateFlow()

    private var timerJob: Job? = null

    init {
        // One-shot timer per question phase, owned by the ViewModel so the count
        // survives rotation.
        viewModelScope.launch {
            _state.collect { s ->
                timerJob?.cancel()
                if (s is StudyUiState.Question) {
                    timerJob =
                        launch {
                            while (true) {
                                delay(1000)
                                _seconds.value += 1
                            }
                        }
                }
            }
        }
    }

    private fun setWorking() {
        _working.value = true
    }

    fun selectDeckAndLoad(deckId: DeckId) {
        _state.value = StudyUiState.Loading
        _sessionTotal.value = -1
        _transition.value = CardTransition.QUESTION_RISE
        viewModelScope.launch {
            try {
                ExpressiveCollection.selectDeck(deckId)
            } catch (e: Exception) {
                android.util.Log.w("ExpressiveStudy", "selectDeck failed", e)
            }
            loadNext()
        }
    }

    fun loadNext() {
        viewModelScope.launch {
            _state.value =
                try {
                    val card = ExpressiveCollection.currentCard(getApplication())
                    if (card == null) {
                        StudyUiState.Finished
                    } else {
                        val counts = card.queueState.counts
                        val remaining = counts.new + counts.lrn + counts.rev
                        if (_sessionTotal.value < 0) _sessionTotal.value = remaining
                        _seconds.value = 0
                        _marked.value = card.marked
                        _flag.value = card.flag
                        StudyUiState.Question(card, remaining)
                    }
                } catch (e: Exception) {
                    StudyUiState.Error(e.message ?: "Failed to load card")
                }
            _working.value = false
        }
    }

    fun revealCurrent(typedInput: String? = null) {
        val s = _state.value
        if (_working.value || s !is StudyUiState.Question) return
        setWorking()
        _transition.value = CardTransition.ANSWER_REVEAL
        viewModelScope.launch {
            _state.value =
                try {
                    val (back, intervals) =
                        ExpressiveCollection.revealCard(getApplication(), s.card.queueState, typedInput)
                    StudyUiState.Answered(AnsweredCard(s.card, back, intervals), s.remaining)
                } catch (e: Exception) {
                    StudyUiState.Error(e.message ?: "Failed to reveal card")
                }
            _working.value = false
        }
    }

    fun grade(rating: Rating) {
        val s = _state.value
        if (_working.value || s !is StudyUiState.Answered) return
        setWorking()
        _transition.value =
            if (rating == Rating.AGAIN) CardTransition.QUESTION_BACK else CardTransition.QUESTION_FORWARD
        viewModelScope.launch {
            try {
                ExpressiveCollection.answer(s.card.card.queueState, rating)
            } catch (e: Exception) {
                android.util.Log.w("ExpressiveStudy", "study operation failed", e)
            }
            loadNext()
        }
    }

    fun toggleMark() {
        val qs = currentQueue() ?: return
        if (_working.value) return
        setWorking()
        viewModelScope.launch {
            try {
                _marked.value = ExpressiveCollection.toggleMark(qs)
            } catch (e: Exception) {
                android.util.Log.w("ExpressiveStudy", "study operation failed", e)
            }
            _working.value = false
        }
    }

    fun cycleFlag() {
        val qs = currentQueue() ?: return
        if (_working.value) return
        setWorking()
        viewModelScope.launch {
            try {
                _flag.value = ExpressiveCollection.cycleFlag(qs)
            } catch (e: Exception) {
                android.util.Log.w("ExpressiveStudy", "study operation failed", e)
            }
            _working.value = false
        }
    }

    fun undo() = runAction { ExpressiveCollection.undoLast() }

    fun buryCurrent() {
        val qs = currentQueue() ?: return
        runAction { ExpressiveCollection.buryCurrent(qs) }
    }

    fun suspendCurrent() {
        val qs = currentQueue() ?: return
        runAction { ExpressiveCollection.suspendCurrent(qs) }
    }

    private fun runAction(block: suspend () -> Unit) {
        if (_working.value) return
        setWorking()
        viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                android.util.Log.w("ExpressiveStudy", "study operation failed", e)
            }
            loadNext()
        }
    }

    private fun currentQueue(): CurrentQueueState? =
        when (val s = _state.value) {
            is StudyUiState.Question -> s.card.queueState
            is StudyUiState.Answered -> s.card.card.queueState
            else -> null
        }
}
