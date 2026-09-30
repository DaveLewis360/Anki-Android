// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.ui.expressive.screens

import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import anki.scheduler.CardAnswer.Rating
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Answering by gesture feels as answering by button does, and only when the answer is taken. */
class GestureAnswerHapticTest {
    @Test
    fun `on the answer side a gesture answer gives the answer buttons' feedback`() {
        assertEquals(HapticFeedbackType.Reject, gestureAnswerHaptic(Rating.AGAIN, answered = true, working = false))
        listOf(Rating.HARD, Rating.GOOD, Rating.EASY).forEach { rating ->
            assertEquals(HapticFeedbackType.Confirm, gestureAnswerHaptic(rating, answered = true, working = false), "$rating")
        }
    }

    @Test
    fun `a gesture answer that is not taken gives no feedback`() {
        // The view model takes no answer on the question side, nor while the previous one is saved.
        assertNull(gestureAnswerHaptic(Rating.GOOD, answered = false, working = false))
        assertNull(gestureAnswerHaptic(Rating.AGAIN, answered = true, working = true))
    }
}
