// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.ui.expressive

import anki.collection.OpChanges
import anki.scheduler.CustomStudyRequest.Cram.CramKind
import com.ichi2.anki.collection.CollectionChange
import com.ichi2.anki.libanki.DeckId
import com.ichi2.anki.libanki.UndoStepCounter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** Collection capabilities used by the expressive deck list and custom-study flow. */
interface ExpressiveDeckGateway {
    /** Active invalidations only. Consumers must always perform an initial read. */
    val collectionChanges: Flow<CollectionChange>
        get() = emptyFlow()

    suspend fun loadDecks(): List<UiDeck>

    suspend fun selectedDeckId(): DeckId

    suspend fun setCollapsed(
        did: DeckId,
        collapsed: Boolean,
    ): OpChanges

    suspend fun renameDeck(
        did: DeckId,
        newName: String,
    ): OpChanges

    suspend fun deletePreview(did: DeckId): ExpressiveDeckDeletePreview

    suspend fun deleteDeck(did: DeckId): ExpressiveDeckDeletion

    suspend fun undoDeckDeletion(expectedUndoStep: UndoStepCounter): Boolean

    suspend fun createDeck(
        name: String,
        parentDid: DeckId? = null,
    ): DeckId

    suspend fun customStudyDefaults(did: DeckId): ExpressiveCustomStudyDefaults

    suspend fun customStudy(
        did: DeckId,
        action: ExpressiveCustomStudyAction,
    ): ExpressiveCustomStudyResult

    suspend fun rebuildFilteredDeck(did: DeckId)

    suspend fun emptyFilteredDeck(did: DeckId)
}

data class ExpressiveDeckDeletePreview(
    val cardCount: Int,
    val filtered: Boolean,
)

data class ExpressiveDeckDeletion(
    val deletedCards: Int,
    val undoStep: UndoStepCounter?,
)

data class ExpressiveCustomStudyDefaults(
    val extendNew: Int,
    val extendReview: Int,
    val availableNew: Int,
    val availableNewInChildren: Int,
    val availableReview: Int,
    val availableReviewInChildren: Int,
    val tags: List<String>,
)

sealed interface ExpressiveCustomStudyAction {
    val amount: Int

    data class ExtendNew(
        override val amount: Int,
    ) : ExpressiveCustomStudyAction

    data class ExtendReview(
        override val amount: Int,
    ) : ExpressiveCustomStudyAction

    data class Forgotten(
        override val amount: Int,
    ) : ExpressiveCustomStudyAction

    data class ReviewAhead(
        override val amount: Int,
    ) : ExpressiveCustomStudyAction

    data class PreviewNew(
        override val amount: Int,
    ) : ExpressiveCustomStudyAction

    data class Cram(
        override val amount: Int,
        val kind: CramKind,
        val includeTags: List<String>,
        val excludeTags: List<String>,
    ) : ExpressiveCustomStudyAction
}

val ExpressiveCustomStudyAction.createsSession: Boolean
    get() = this !is ExpressiveCustomStudyAction.ExtendNew && this !is ExpressiveCustomStudyAction.ExtendReview

data class ExpressiveCustomStudyResult(
    val sessionDeckId: DeckId?,
)
