// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.ui.expressive.screens

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The finished deck tells what is left for today as Anki's congratulations page does. */
class CongratulationsTextTest {
    @Test
    fun `under a minute it counts seconds, under an hour minutes, under a day hours`() {
        assertEquals("seconds" to 0, nextLearnDue(0))
        assertEquals("seconds" to 59, nextLearnDue(59))
        assertEquals("minutes" to 1, nextLearnDue(60))
        assertEquals("minutes" to 5, nextLearnDue(290))
        assertEquals("minutes" to 60, nextLearnDue(3_599))
        assertEquals("hours" to 1, nextLearnDue(3_600))
        assertEquals("hours" to 24, nextLearnDue(86_399))
    }

    @Test
    fun `a card due a day or more away is not told`() {
        assertNull(nextLearnDue(86_400))
        assertNull(nextLearnDue(Int.MAX_VALUE))
    }

    @Test
    fun `Anki's wrapped texts read as one paragraph`() {
        val wrapped = "Today's review limit has been reached,\n  but there are still cards\nwaiting."

        assertEquals("Today's review limit has been reached, but there are still cards waiting.", wrapped.asParagraph())
    }
}
