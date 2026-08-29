// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.ui.expressive

import android.content.Context
import anki.collection.OpChanges
import anki.collection.OpChangesAfterUndo
import anki.collection.OpChangesWithCount
import anki.scheduler.CardAnswer.Rating
import com.ichi2.anki.libanki.DeckId
import com.ichi2.anki.libanki.sched.CurrentQueueState

/** Collection capabilities used by the expressive review loop. */
interface ExpressiveStudyGateway {
    suspend fun selectDeck(did: DeckId)

    suspend fun currentCard(context: Context): StudyCardUi?

    suspend fun revealCard(
        context: Context,
        state: CurrentQueueState,
        typedInput: String? = null,
    ): Pair<String, List<String>>

    suspend fun answer(
        state: CurrentQueueState,
        rating: Rating,
    ): OpChanges

    suspend fun toggleMark(state: CurrentQueueState): Boolean

    suspend fun cycleFlag(state: CurrentQueueState): Int

    suspend fun buryCurrent(state: CurrentQueueState): OpChangesWithCount

    suspend fun suspendCurrent(state: CurrentQueueState): OpChangesWithCount

    suspend fun undoLast(): OpChangesAfterUndo
}
