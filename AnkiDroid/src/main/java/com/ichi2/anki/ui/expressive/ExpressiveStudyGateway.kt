// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.ui.expressive

import android.content.Context
import anki.collection.OpChanges
import anki.collection.OpChangesWithCount
import anki.scheduler.CardAnswer.Rating
import anki.scheduler.UnburyDeckRequest
import com.ichi2.anki.Flag
import com.ichi2.anki.collection.CollectionChange
import com.ichi2.anki.libanki.CardId
import com.ichi2.anki.libanki.DeckId
import com.ichi2.anki.libanki.UndoStepCounter
import com.ichi2.anki.libanki.sched.CurrentQueueState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext
import timber.log.Timber
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Identifies the exact backend review operation owned by this study session. */
data class ReviewUndoToken(
    val lastStep: UndoStepCounter,
    val cardId: CardId,
)

data class ExpressiveLeechOutcome(
    val suspended: Boolean,
)

data class ReviewAnswerResult(
    /** Null only if the backend committed the answer without exposing an undo step. */
    val undoToken: ReviewUndoToken?,
    val leech: ExpressiveLeechOutcome? = null,
)

data class ExpressiveCongratulationsInfo(
    val deckId: DeckId,
    val isFiltered: Boolean,
    val secsUntilNextLearn: Int,
    val haveUserBuried: Boolean,
    val haveSchedBuried: Boolean,
)

sealed interface UndoReviewResult {
    data object Undone : UndoReviewResult

    data object NotOwnedOrNoLongerTop : UndoReviewResult
}

/** Carries one review session's initiator through ordinary gateway decorators. */
private class ExpressiveStudyWriteInitiator(
    val initiator: Any,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ExpressiveStudyWriteInitiator>
}

internal suspend fun expressiveStudyWriteInitiatorOr(fallback: Any): Any =
    currentCoroutineContext()[ExpressiveStudyWriteInitiator]?.initiator ?: fallback

/** Collection capabilities used by the expressive review loop. */
interface ExpressiveStudyGateway {
    val collectionChanges: Flow<CollectionChange>
        get() = emptyFlow()

    /** Tags writes made inside [block] without bypassing decorators that override the legacy methods. */
    suspend fun <T> withWriteInitiator(
        initiator: Any,
        block: suspend () -> T,
    ): T = withContext(ExpressiveStudyWriteInitiator(initiator)) { block() }

    suspend fun selectDeck(did: DeckId)

    /** Starts one backend timebox for this retained reviewer session. */
    suspend fun startTimebox() = Unit

    suspend fun currentCard(context: Context): StudyCardUi?

    /** The flags' display names: Anki's translated names, or the ones the user gave them. */
    suspend fun flagNames(): Map<Flag, String> = Flag.queryDisplayNames()

    suspend fun congratulationsInfo(deckId: DeckId): ExpressiveCongratulationsInfo =
        ExpressiveCongratulationsInfo(
            deckId = deckId,
            isFiltered = false,
            secsUntilNextLearn = Int.MAX_VALUE,
            haveUserBuried = false,
            haveSchedBuried = false,
        )

    suspend fun revealCard(
        context: Context,
        state: CurrentQueueState,
        typedInput: String? = null,
    ): Pair<String, List<String>>

    suspend fun answer(
        state: CurrentQueueState,
        rating: Rating,
    ): ReviewAnswerResult

    suspend fun toggleMark(state: CurrentQueueState): Boolean

    suspend fun cycleFlag(state: CurrentQueueState): Int

    suspend fun setFlag(
        state: CurrentQueueState,
        flag: Int,
    ): Int = throw UnsupportedOperationException("Direct flags are unavailable.")

    suspend fun buryCurrent(state: CurrentQueueState): OpChangesWithCount

    suspend fun buryNote(state: CurrentQueueState): OpChangesWithCount = throw UnsupportedOperationException("Bury note is unavailable.")

    suspend fun suspendCurrent(state: CurrentQueueState): OpChangesWithCount

    suspend fun suspendNote(state: CurrentQueueState): OpChangesWithCount =
        throw UnsupportedOperationException(
            "Suspend note is unavailable.",
        )

    suspend fun undoReview(expectedToken: ReviewUndoToken): UndoReviewResult

    suspend fun unburyDeck(
        deckId: DeckId,
        mode: UnburyDeckRequest.Mode,
    ): OpChanges = throw UnsupportedOperationException("Unbury is unavailable.")

    suspend fun rebuildStudyDeck(deckId: DeckId): OpChanges = throw UnsupportedOperationException("Filtered deck rebuild is unavailable.")

    suspend fun emptyStudyDeck(deckId: DeckId): OpChanges = throw UnsupportedOperationException("Filtered deck empty is unavailable.")
}

/**
 * The flags' display names, or none if they cannot be read: the Study screen's flag button then
 * steps through the flags instead of listing them. Cancellation is no failure, and propagates.
 */
suspend fun ExpressiveStudyGateway.flagNamesOrNone(): Map<Flag, String> =
    try {
        flagNames()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.w(e, "could not load the flag names")
        emptyMap()
    }

/** Optional production capability that tags writes with one review session's exact initiator. */
internal interface InitiatedExpressiveStudyGateway {
    suspend fun selectDeck(
        did: DeckId,
        initiator: Any,
    )

    suspend fun answer(
        state: CurrentQueueState,
        rating: Rating,
        initiator: Any,
    ): ReviewAnswerResult

    suspend fun toggleMark(
        state: CurrentQueueState,
        initiator: Any,
    ): Boolean

    suspend fun cycleFlag(
        state: CurrentQueueState,
        initiator: Any,
    ): Int

    suspend fun setFlag(
        state: CurrentQueueState,
        flag: Int,
        initiator: Any,
    ): Int

    suspend fun buryCurrent(
        state: CurrentQueueState,
        initiator: Any,
    ): OpChangesWithCount

    suspend fun buryNote(
        state: CurrentQueueState,
        initiator: Any,
    ): OpChangesWithCount

    suspend fun suspendCurrent(
        state: CurrentQueueState,
        initiator: Any,
    ): OpChangesWithCount

    suspend fun suspendNote(
        state: CurrentQueueState,
        initiator: Any,
    ): OpChangesWithCount

    suspend fun undoReview(
        expectedToken: ReviewUndoToken,
        initiator: Any,
    ): UndoReviewResult
}
