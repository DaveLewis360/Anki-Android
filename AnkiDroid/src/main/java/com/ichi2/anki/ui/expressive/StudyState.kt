// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.ui.expressive

import anki.scheduler.CardAnswer.Rating

/** How the next rendered card page should enter. */
enum class CardTransition {
    QUESTION_RISE,
    QUESTION_FORWARD,
    QUESTION_BACK,
    ANSWER_REVEAL,
}

/** One card in the study session. */
data class AnsweredCard(
    val card: StudyCardUi,
    val backHtml: String,
    val intervals: List<String>,
)

sealed interface StudyUiState {
    data object Loading : StudyUiState

    data class Question(
        val card: StudyCardUi,
        val remaining: Int,
    ) : StudyUiState

    data class Answered(
        val card: AnsweredCard,
        val remaining: Int,
    ) : StudyUiState

    data object Finished : StudyUiState

    data class Error(
        val message: String,
    ) : StudyUiState
}

fun StudyUiState.remainingCards(): Int =
    when (this) {
        is StudyUiState.Question -> remaining
        is StudyUiState.Answered -> remaining
        else -> 0
    }

fun ratingFor(index: Int): Rating =
    when (index) {
        0 -> Rating.AGAIN
        1 -> Rating.HARD
        2 -> Rating.GOOD
        else -> Rating.EASY
    }
