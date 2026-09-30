// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.ui.expressive.screens

import com.ichi2.anki.ui.expressive.screens.CardPaneStep.ENTER
import com.ichi2.anki.ui.expressive.screens.CardPaneStep.HIDE_UNTIL_VISIBLE
import com.ichi2.anki.ui.expressive.screens.CardPaneStep.SETTLE
import com.ichi2.anki.ui.expressive.screens.ExpressiveCardSide.ANSWER
import com.ichi2.anki.ui.expressive.screens.ExpressiveCardSide.QUESTION
import org.junit.Test
import kotlin.test.assertEquals

/**
 * The card pane's step for each render: the previous card hides while a new question loads, the
 * answer replaces its question in place, and nothing hides or slides with animations off.
 */
class CardPaneStepTest {
    @Test
    fun `a new question hides the previous card until it can be drawn, then enters`() {
        assertEquals(HIDE_UNTIL_VISIBLE, step(QUESTION, visible = false, hidden = false))
        assertEquals(HIDE_UNTIL_VISIBLE, step(QUESTION, visible = false, hidden = true))
        assertEquals(ENTER, step(QUESTION, visible = true, hidden = true))
        // Drawable only after the fallback showed the pane.
        assertEquals(ENTER, step(QUESTION, visible = true, hidden = false))
    }

    @Test
    fun `the answer keeps its question on screen while it loads`() {
        assertEquals(SETTLE, step(ANSWER, visible = false, hidden = false))
        assertEquals(SETTLE, step(ANSWER, visible = true, hidden = false))
    }

    @Test
    fun `a reveal before the question appeared keeps the previous card hidden until the answer can be drawn`() {
        assertEquals(HIDE_UNTIL_VISIBLE, step(ANSWER, visible = false, hidden = true))
        assertEquals(SETTLE, step(ANSWER, visible = true, hidden = true))
    }

    @Test
    fun `with animations off nothing hides or slides, whatever the render`() {
        for (side in ExpressiveCardSide.entries) {
            for (visible in listOf(false, true)) {
                for (hidden in listOf(false, true)) {
                    assertEquals(SETTLE, cardPaneStep(side, visible, hidden, animationsOff = true), "$side $visible $hidden")
                }
            }
        }
    }

    private fun step(
        side: ExpressiveCardSide,
        visible: Boolean,
        hidden: Boolean,
    ) = cardPaneStep(side, visible, hidden, animationsOff = false)
}
