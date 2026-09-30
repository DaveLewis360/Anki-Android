// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.ui.expressive.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import anki.scheduler.UnburyDeckRequest
import com.ichi2.anki.CollectionManager.TR
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.ui.expressive.ExpressiveCongratulationsInfo
import com.ichi2.anki.ui.expressive.ExpressiveTimeboxInfo
import com.ichi2.anki.ui.expressive.components.GestureAction
import com.ichi2.anki.ui.expressive.theme.ExpressiveAnkiTheme
import com.ichi2.anki.ui.internationalization.SentenceCase
import com.ichi2.anki.ui.internationalization.toSentenceCase
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The panes and dialogs of the Study screen: they speak Anki's own words, and each action is a
 * button TalkBack can find and reach.
 */
@RunWith(AndroidJUnit4::class)
class StudyPanesTest : RobolectricTest() {
    @get:Rule
    val composeRule = createComposeRule()

    private val finished =
        ExpressiveCongratulationsInfo(
            deckId = 1,
            isFiltered = false,
            secsUntilNextLearn = 300,
            haveUserBuried = false,
            haveSchedBuried = false,
            reviewLimitReached = true,
            learnRemaining = 2,
        )

    private val backToDecks get() = targetContext.getString(R.string.expressive_study_back_to_decks)
    private val customStudy get() = with(targetContext) { SentenceCase.customStudy }
    private val deckOptions get() = with(targetContext) { SentenceCase.deckOptions }

    @Test
    fun `a finished deck tells, in Anki's words, what is left for today`() {
        show { Congrats(finished) }

        composeRule
            .onNodeWithText(TR.schedulingCongratulationsFinished())
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        listOf(
            // As Anki's own page, the learning cards left today follow when the next one is due.
            TR.schedulingNextLearnDue(unit = "minutes", amount = 5) + " " + TR.schedulingLearnRemaining(remaining = 2),
            TR.schedulingTodayReviewLimitReached(),
            TR.schedulingHowToCustomStudy(customStudy = TR.schedulingCustomStudy()),
        ).forEach { composeRule.onNodeWithText(it.asParagraph()).assertExists() }
        composeRule.onNodeWithText(TR.schedulingTodayNewLimitReached().asParagraph()).assertDoesNotExist()
        composeRule.onNodeWithText(TR.schedulingBuriedCardsFound(unburyThem = TR.schedulingUnburyThem()).asParagraph()).assertDoesNotExist()
    }

    @Test
    fun `a finished deck offers going back, custom study, and the deck's options`() {
        val done = mutableListOf<String>()
        show {
            Congrats(
                finished,
                onClose = { done += "back" },
                onStudyMore = { done += "custom study" },
                onDeckOptions = { done += "options" },
            )
        }

        listOf(backToDecks, customStudy, deckOptions).forEach { action(it).performClick() }

        assertEquals(listOf("back", "custom study", "options"), done)
        listOf(TR.studyingUnbury(), TR.actionsRebuild(), TR.studyingEmpty()).forEach {
            composeRule.onNodeWithText(it).assertDoesNotExist()
        }
    }

    @Test
    fun `a finished filtered deck with buried cards offers unbury, rebuild, and empty instead of custom study`() {
        val done = mutableListOf<String>()
        val filtered = finished.copy(isFiltered = true, haveSchedBuried = true, secsUntilNextLearn = Int.MAX_VALUE)
        show {
            Congrats(
                filtered,
                onUnbury = { done += "unbury" },
                onRebuild = { done += "rebuild" },
                onEmpty = { done += "empty" },
            )
        }

        composeRule.onNodeWithText(TR.schedulingBuriedCardsFound(unburyThem = TR.schedulingUnburyThem()).asParagraph()).assertExists()
        listOf(TR.studyingUnbury(), TR.actionsRebuild(), TR.studyingEmpty()).forEach { action(it).performClick() }

        assertEquals(listOf("unbury", "rebuild", "empty"), done)
        composeRule.onNodeWithText(customStudy).assertDoesNotExist()
        val customStudyHint = TR.schedulingHowToCustomStudy(customStudy = TR.schedulingCustomStudy()).asParagraph()
        composeRule.onNodeWithText(customStudyHint).assertDoesNotExist()
    }

    @Test
    fun `while an operation runs the deck's actions wait, but going back does not`() {
        show { Congrats(finished.copy(haveUserBuried = true), working = true) }

        action(backToDecks).assertIsEnabled()
        listOf(TR.studyingUnbury(), customStudy, deckOptions).forEach { action(it).assertIsNotEnabled() }
    }

    @Test
    fun `an error tells why studying stopped and tries again`() {
        var retried = 0
        show { ErrorPane("The collection is closed", onRetry = { retried++ }) }

        composeRule
            .onNodeWithText(targetContext.getString(R.string.expressive_study_error_title))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        composeRule.onNodeWithText("The collection is closed").assertExists()
        action(targetContext.getString(R.string.retry)).performClick()

        assertEquals(1, retried)
    }

    @Test
    fun `the unbury dialog unburies the cards chosen`() {
        val chosen = mutableListOf<UnburyDeckRequest.Mode>()
        var dismissed = 0
        show { UnburyDialog(onUnbury = { chosen += it }, onDismiss = { dismissed++ }) }

        composeRule.onNodeWithText(TR.studyingUnbury()).assertExists()
        listOf(
            TR.studyingBuriedSiblings().toSentenceCase(targetContext, R.string.sentence_buried_siblings),
            TR.studyingManuallyBuriedCards().toSentenceCase(targetContext, R.string.sentence_manually_buried_cards),
            TR.studyingAllBuriedCards().toSentenceCase(targetContext, R.string.sentence_all_buried_cards),
        ).forEach { dialogAction(it).performClick() }
        dialogAction(targetContext.getString(R.string.dialog_cancel)).performClick()

        assertEquals(listOf(UnburyDeckRequest.Mode.SCHED_ONLY, UnburyDeckRequest.Mode.USER_ONLY, UnburyDeckRequest.Mode.ALL), chosen)
        assertEquals(1, dismissed)
    }

    @Test
    fun `the timebox dialog tells the session so far, and studies on or finishes`() {
        val done = mutableListOf<String>()
        show {
            TimeboxDialog(
                ExpressiveTimeboxInfo(seconds = 290, reviews = 12),
                onContinue = { done += "continue" },
                onFinish = { done += "finish" },
            )
        }

        composeRule.onNodeWithText(TR.studyingCardStudiedIn(12) + " " + TR.studyingMinute(5)).assertExists()
        dialogAction(TR.studyingContinue()).performClick()
        dialogAction(TR.studyingFinish()).performClick()

        assertEquals(listOf("continue", "finish"), done)
    }

    /** A phone in landscape at twice the font size: the long press menu scrolls to its last action. */
    @Test
    @Config(qualifiers = "w914dp-h411dp", fontScale = 2f)
    fun `the long press menu names each action and reaches the last in a short window`() {
        val chosen = mutableListOf<String>()
        show { GestureActionMenu(expanded = true, onAction = { chosen += it }, onDismiss = {}) }

        listOf(GestureAction.UNDO, GestureAction.FLAG).forEach { action ->
            composeRule
                .onNodeWithText(action.label(targetContext))
                .performScrollTo()
                .assertIsDisplayed()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                .performClick()
        }

        assertEquals(listOf(GestureAction.UNDO.key, GestureAction.FLAG.key), chosen)
    }

    @Test
    fun `every gesture action has a name of its own`() {
        val labels = GestureAction.entries.map { it.label(targetContext) }

        assertTrue(labels.none { it.isBlank() }, "$labels")
        assertEquals(labels.size, labels.distinct().size, "$labels")
    }

    /** A pane's button labelled [label], scrolled into view: it has the button role and a 48 dp target. */
    private fun action(label: String): SemanticsNodeInteraction = dialogAction(label).performScrollTo()

    /** A dialog's button labelled [label]: it has the button role and a 48 dp target. */
    private fun dialogAction(label: String): SemanticsNodeInteraction =
        composeRule
            .onNodeWithText(label)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertHeightIsAtLeast(48.dp)

    @Composable
    private fun Congrats(
        info: ExpressiveCongratulationsInfo,
        working: Boolean = false,
        onClose: () -> Unit = {},
        onDeckOptions: () -> Unit = {},
        onStudyMore: () -> Unit = {},
        onUnbury: () -> Unit = {},
        onRebuild: () -> Unit = {},
        onEmpty: () -> Unit = {},
    ) {
        CongratsPane(
            info = info,
            working = working,
            onClose = onClose,
            onDeckOptions = onDeckOptions,
            onStudyMore = onStudyMore,
            onUnbury = onUnbury,
            onRebuild = onRebuild,
            onEmpty = onEmpty,
        )
    }

    private fun show(content: @Composable () -> Unit) {
        composeRule.setContent { ExpressiveAnkiTheme(themeMode = "light", dynamicColor = false, content = content) }
    }
}
