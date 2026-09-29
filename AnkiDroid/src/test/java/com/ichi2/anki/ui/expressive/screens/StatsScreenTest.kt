// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.ui.expressive.screens

import org.junit.Test
import java.time.LocalDate
import java.util.Locale
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class StatsScreenTest {
    @Test
    fun `daily review label comes from its date and locale`() {
        val date = LocalDate.of(2025, 1, 6)
        val english = dailyReviewLabel(date, Locale.forLanguageTag("en-US"))
        val hungarian = dailyReviewLabel(date, Locale.forLanguageTag("hu-HU"))

        assertTrue(english.endsWith("\n6"))
        assertTrue(hungarian.endsWith("\n6"))
        assertNotEquals(english, hungarian)
    }
}
