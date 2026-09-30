// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.ui.expressive.screens

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import anki.scheduler.CustomStudyRequest.Cram.CramKind
import com.ichi2.anki.CollectionManager.TR
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.libanki.DeckId
import com.ichi2.anki.ui.expressive.ExpressiveCollection
import com.ichi2.anki.ui.expressive.ExpressiveCustomStudyAction
import com.ichi2.anki.ui.expressive.ExpressiveCustomStudyDefaults
import com.ichi2.anki.ui.expressive.ExpressiveCustomStudyResult
import com.ichi2.anki.ui.expressive.ExpressiveDeckGateway
import com.ichi2.anki.ui.expressive.theme.ExpressiveAnkiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

/** Custom study offers Anki's modes in Anki's words, as a radio group, and starts the one chosen. */
@RunWith(AndroidJUnit4::class)
class CustomStudyDialogTest : RobolectricTest() {
    @get:Rule
    val composeRule = createComposeRule()

    private val gateway =
        RecordingGateway(
            ExpressiveCustomStudyDefaults(
                extendNew = 10,
                extendReview = 50,
                availableNew = 3,
                availableNewInChildren = 2,
                availableReview = 0,
                availableReviewInChildren = 0,
                tags = listOf("french", "verbs"),
            ),
        )

    @Test
    fun `each mode is a radio button chosen by its whole row`() {
        show()

        composeRule
            .onNodeWithText(TR.customStudyReviewAhead())
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .assertHeightIsAtLeast(48.dp)
            .performClick()

        composeRule.onNodeWithText(TR.customStudyReviewAhead()).assertIsSelected()
        composeRule.onNodeWithText(TR.customStudyIncreaseTodaysNewCardLimit()).assertIsNotSelected()
        // The amount's field is labelled with what the amount means.
        composeRule.onNodeWithText(TR.customStudyReviewAheadBy()).performScrollTo().assert(hasSetTextAction())
    }

    @Test
    fun `extending today's new card limit tells what is available, and may lower it`() {
        val opened = mutableListOf<DeckId?>()
        show(onDone = { opened += it })

        composeRule.onNodeWithText(TR.customStudyAvailableNewCards2("3 ${TR.customStudyAvailableChildCount(2)}")).assertExists()
        // The prompt tells that a limit may be lowered, as Classic's does.
        composeRule.onNodeWithText(targetContext.getString(R.string.custom_study_new_extend)).performScrollTo().performTextReplacement("-3")
        composeRule.onNodeWithText(targetContext.getString(R.string.dialog_ok)).performClick()
        composeRule.waitUntil { opened.isNotEmpty() }

        assertEquals(listOf<ExpressiveCustomStudyAction>(ExpressiveCustomStudyAction.ExtendNew(-3)), gateway.actions)
        assertEquals(listOf<DeckId?>(SESSION_DECK), opened)
    }

    @Test
    fun `studying by card state or tag starts the kind of cards and the tags chosen`() {
        val opened = mutableListOf<DeckId?>()
        show(onDone = { opened += it })

        composeRule.onNodeWithText(TR.customStudyStudyByCardStateOrTag()).performScrollTo().performClick()
        composeRule.onNodeWithText(TR.customStudyDueCardsOnly()).performScrollTo().performClick()
        composeRule.onNodeWithText(TR.customStudySelect()).performScrollTo().performTextReplacement("50")
        composeRule
            .onNodeWithText(TR.customStudyRequireOneOrMoreOfThese().asFieldLabel())
            .performScrollTo()
            .performTextReplacement("french, verbs, french")
        composeRule.onNodeWithText(TR.customStudySelectTagsToExclude().asFieldLabel()).performScrollTo().performTextReplacement("leech")
        composeRule.onNodeWithText(targetContext.getString(R.string.dialog_ok)).performClick()
        composeRule.waitUntil { opened.isNotEmpty() }

        val cram =
            ExpressiveCustomStudyAction.Cram(
                amount = 50,
                kind = CramKind.CRAM_KIND_DUE,
                includeTags = listOf("french", "verbs"),
                excludeTags = listOf("leech"),
            )
        assertEquals(listOf<ExpressiveCustomStudyAction>(cram), gateway.actions)
    }

    @Test
    fun `options that cannot be loaded are told in the app's words, not the failure's English`() {
        show(RecordingGateway(defaults = null))

        composeRule.onNodeWithText(targetContext.getString(R.string.expressive_custom_study_defaults_failed)).assertExists()
        composeRule.onNodeWithText("the collection is closed", substring = true).assertDoesNotExist()
    }

    private fun show(
        deckGateway: ExpressiveDeckGateway = gateway,
        onDone: (DeckId?) -> Unit = {},
    ) {
        composeRule.setContent {
            ExpressiveAnkiTheme(themeMode = "light", dynamicColor = false) {
                CustomStudyDialog(deckId = 1, deckName = "French", deckGateway = deckGateway, onDismiss = {}, onDone = onDone)
            }
        }
    }

    /** Offers [defaults], or fails with English if there are none, and records the custom study it is asked for. */
    private class RecordingGateway(
        private val defaults: ExpressiveCustomStudyDefaults?,
    ) : ExpressiveDeckGateway by ExpressiveCollection {
        val actions = mutableListOf<ExpressiveCustomStudyAction>()

        override suspend fun customStudyDefaults(did: DeckId): ExpressiveCustomStudyDefaults =
            defaults ?: throw IllegalStateException("the collection is closed")

        override suspend fun customStudy(
            did: DeckId,
            action: ExpressiveCustomStudyAction,
        ): ExpressiveCustomStudyResult {
            actions += action
            return ExpressiveCustomStudyResult(sessionDeckId = SESSION_DECK)
        }
    }

    private companion object {
        const val SESSION_DECK: DeckId = 42
    }
}
