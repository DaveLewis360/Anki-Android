// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.ui.expressive

import anki.collection.OpChanges
import anki.collection.OpChangesWithCount
import anki.scheduler.CustomStudyRequest
import com.ichi2.anki.libanki.DeckId

/** Collection capabilities used by the expressive deck list and custom-study flow. */
interface ExpressiveDeckGateway {
    suspend fun loadDecks(): List<UiDeck>

    suspend fun setCollapsed(
        did: DeckId,
        collapsed: Boolean,
    ): OpChanges

    suspend fun renameDeck(
        did: DeckId,
        newName: String,
    ): OpChanges

    suspend fun deleteDeck(did: DeckId): OpChangesWithCount

    suspend fun createDeck(name: String): DeckId

    suspend fun customStudy(request: CustomStudyRequest): OpChanges
}
