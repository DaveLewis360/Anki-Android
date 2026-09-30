// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.ui.expressive

import android.content.Context
import androidx.annotation.StringRes
import anki.scheduler.CardAnswer.Rating
import com.ichi2.anki.libanki.CardId
import net.ankiweb.rsdroid.BackendException
import timber.log.Timber

data class ExpressiveTimeboxInfo(
    val seconds: Int,
    val reviews: Int,
)

data class ExpressiveSchedulingMutationCommand(
    val key: String,
    val sessionGeneration: Long,
    val cardId: CardId,
    val renderRevision: Long,
    val javascript: String,
    val statesWithContextBase64: String,
)

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

    data class TimeboxReached(
        val info: ExpressiveTimeboxInfo,
    ) : StudyUiState

    data object Finished : StudyUiState

    data class Error(
        val message: StudyMessage,
    ) : StudyUiState
}

/**
 * A message for the learner. The view model tells which one; the screen puts it into words, in the
 * screen's language, which the app may set apart from the system's.
 */
sealed interface StudyMessage {
    /** Already in words, as the backend's messages are: it translates them itself. */
    data class Text(
        val text: String,
    ) : StudyMessage

    /** One of the app's messages, with its arguments. */
    data class Resource(
        @StringRes val id: Int,
        val args: List<Any> = emptyList(),
    ) : StudyMessage

    fun resolve(context: Context): String =
        when (this) {
            is Text -> text
            is Resource -> context.getString(id, *args.toTypedArray())
        }

    companion object {
        /**
         * What [failure] says, if the backend says it: it words its errors in the learner's
         * language. Anything else is the app's [fallback] message, and the failure is logged.
         */
        fun of(
            failure: Throwable?,
            @StringRes fallback: Int,
        ): StudyMessage {
            if (failure is BackendException) failure.message?.let { return Text(it) }
            if (failure != null) Timber.w(failure, "study failure, shown as the app's message")
            return Resource(fallback)
        }
    }
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
