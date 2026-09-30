// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.ui.expressive

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.ui.expressive.screens.customSchedulingFailure
import net.ankiweb.rsdroid.BackendException
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

/** The study screen's messages are put into words where they are shown, in the screen's language. */
@RunWith(AndroidJUnit4::class)
class StudyMessageTest : RobolectricTest() {
    private val fallback = StudyMessage.Resource(R.string.expressive_study_error_load_card)

    @Test
    fun `the backend's words are kept, as it translates them itself`() {
        assertEquals(
            StudyMessage.Text("La collection est fermée"),
            StudyMessage.of(BackendException("La collection est fermée"), R.string.expressive_study_error_load_card),
        )
    }

    @Test
    fun `any other failure is told in the app's words, whatever English it carries`() {
        assertEquals(fallback, StudyMessage.of(IllegalStateException("closed pool"), R.string.expressive_study_error_load_card))
        assertEquals(fallback, StudyMessage.of(IllegalStateException(), R.string.expressive_study_error_load_card))
        assertEquals(fallback, StudyMessage.of(null, R.string.expressive_study_error_load_card))
    }

    @Test
    fun `an app message takes its arguments`() {
        val message = StudyMessage.Resource(R.string.expressive_study_error_next_card_detail, listOf("no card"))

        assertEquals(targetContext.getString(R.string.expressive_study_error_next_card_detail, "no card"), message.resolve(targetContext))
        assertEquals("as is", StudyMessage.Text("as is").resolve(targetContext))
    }

    @Test
    fun `failed custom scheduling tells the error the deck's code reported, if it reported one`() {
        assertEquals(StudyMessage.Resource(R.string.expressive_study_custom_scheduling_failed), customSchedulingFailure(" "))
        assertEquals(
            StudyMessage.Resource(R.string.expressive_study_custom_scheduling_failed_detail, listOf("states is undefined")),
            customSchedulingFailure("states is undefined"),
        )
        val long = customSchedulingFailure("x".repeat(600)) as StudyMessage.Resource
        assertEquals(500, (long.args.single() as String).length, "a runaway error is cut short")
    }
}
