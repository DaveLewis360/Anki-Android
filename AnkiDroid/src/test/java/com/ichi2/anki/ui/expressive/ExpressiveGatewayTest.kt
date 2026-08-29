// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.ui.expressive

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import anki.scheduler.CardAnswer.Rating
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.libanki.DeckId
import com.ichi2.anki.libanki.sched.CurrentQueueState
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

@RunWith(AndroidJUnit4::class)
class ExpressiveGatewayTest : RobolectricTest() {
    /**
     * Uses the in-memory collection integration harness instead of launching the full Compose
     * activity, whose asynchronous rendering is not stable in this Robolectric test setup.
     * [HomeScreen][com.ichi2.anki.ui.expressive.screens.HomeScreen] performs this gateway load on
     * opening, so this covers its complete collection access path without a Compose timing seam.
     */
    @Test
    fun `loading Expressive Home does not mutate the collection`() =
        runTest {
            val deckCount = col.decks.count()
            val noteCount = col.noteCount()
            val cardCount = col.cardCount()
            val gateway: ExpressiveDeckGateway = ExpressiveCollection

            gateway.loadDecks()

            assertEquals(deckCount, col.decks.count())
            assertEquals(noteCount, col.noteCount())
            assertEquals(cardCount, col.cardCount())
            assertNull(col.decks.byName("Gépelős teszt"))
        }

    @Test
    fun `StudyViewModel accepts an injected gateway without queue state fabrication`() =
        runTest {
            val gateway = FinishedStudyGateway()
            val viewModel =
                StudyViewModel(
                    application = ApplicationProvider.getApplicationContext<Application>(),
                    studyGateway = gateway,
                )

            viewModel.selectDeckAndLoad(TEST_DECK_ID)
            advanceUntilIdle()

            assertEquals(TEST_DECK_ID, gateway.selectedDeckId)
            assertSame(StudyUiState.Finished, viewModel.state.value)
        }

    private class FinishedStudyGateway : ExpressiveStudyGateway {
        var selectedDeckId: DeckId? = null

        override suspend fun selectDeck(did: DeckId) {
            selectedDeckId = did
        }

        override suspend fun currentCard(context: Context): StudyCardUi? = null

        override suspend fun revealCard(
            context: Context,
            state: CurrentQueueState,
            typedInput: String?,
        ): Nothing = error("Not used in this boundary test")

        override suspend fun answer(
            state: CurrentQueueState,
            rating: Rating,
        ): Nothing = error("Not used in this boundary test")

        override suspend fun toggleMark(state: CurrentQueueState): Nothing = error("Not used in this boundary test")

        override suspend fun cycleFlag(state: CurrentQueueState): Nothing = error("Not used in this boundary test")

        override suspend fun buryCurrent(state: CurrentQueueState): Nothing = error("Not used in this boundary test")

        override suspend fun suspendCurrent(state: CurrentQueueState): Nothing = error("Not used in this boundary test")

        override suspend fun undoLast(): Nothing = error("Not used in this boundary test")
    }

    private companion object {
        const val TEST_DECK_ID: DeckId = 42L
    }
}
