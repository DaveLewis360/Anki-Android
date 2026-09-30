// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.ui.expressive.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.Flag
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.ui.expressive.theme.ExpressiveAnkiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the Study screen's chrome tells TalkBack, and which action each control starts. Native
 * graphics measure text as a device does, which the layout checks need.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StudyComponentsTest : RobolectricTest() {
    @get:Rule
    val composeRule = createComposeRule()

    private val labels =
        StudyActionLabels(
            markNote = "Mark note",
            flagCard = "Flag card",
            buryCard = "Bury card",
            buryNote = "Bury note",
            suspendCard = "Suspend card",
            suspendNote = "Suspend note",
        )

    private val flagNames = Flag.entries.associateWith { "flag ${it.code}" }

    @Test
    fun `each answer button reads its rating and interval and answers with that rating`() {
        val answered = mutableListOf<Int>()
        show { AnswerButtons(intervals = listOf("<1m", "6m", "10m", "4d"), onAnswer = { answered += it }) }

        val ratings =
            listOf(R.string.ease_button_again, R.string.ease_button_hard, R.string.ease_button_good, R.string.ease_button_easy)
                .map { targetContext.getString(it) }
        ratings.zip(listOf("<1m", "6m", "10m", "4d")).forEach { (rating, interval) ->
            composeRule
                .onNodeWithContentDescription(targetContext.getString(R.string.expressive_study_answer_with_interval, rating, interval))
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                .assertHasClickAction()
                .assertIsEnabled()
                .assertHeightIsAtLeast(48.dp)
        }
        composeRule.onNodeWithContentDescription(answerLabel(ratings[2], "10m")).performClick()
        composeRule.onNodeWithContentDescription(answerLabel(ratings[0], "<1m")).performClick()

        assertEquals(listOf(2, 0), answered)
    }

    @Test
    fun `answer buttons report that they wait while the previous answer is saved`() {
        show { AnswerButtons(intervals = listOf("<1m", "6m", "10m", "4d"), onAnswer = {}, enabled = false) }

        val good = targetContext.getString(R.string.ease_button_good)
        composeRule.onNodeWithContentDescription(answerLabel(good, "10m")).assertIsNotEnabled()
    }

    private fun answerLabel(
        rating: String,
        interval: String,
    ): String = targetContext.getString(R.string.expressive_study_answer_with_interval, rating, interval)

    @Test
    fun `at the default font size the four ratings share one row`() {
        show { Box(Modifier.width(360.dp)) { AnswerButtons(intervals = listOf("<1m", "6m", "10m", "4d"), onAnswer = {}) } }

        val tops = ratingLabels().map { it.getUnclippedBoundsInRoot().top }
        assertEquals(1, tops.distinct().size, "tops $tops")
        ratingLabels().forEach {
            val layout = it.textLayout()
            assertFalse(layout.isLineEllipsized(layout.lineCount - 1), "a label is cut")
        }
    }

    @Test
    fun `at twice the font size the ratings wrap into rows instead of cutting their labels`() {
        show {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f)) {
                Box(Modifier.width(360.dp)) { AnswerButtons(intervals = listOf("<1m", "6m", "10m", "4d"), onAnswer = {}) }
            }
        }

        val (again, _, good, _) = ratingLabels()
        assertTrue(good.getUnclippedBoundsInRoot().top > again.getUnclippedBoundsInRoot().bottom, "Good is on a row below Again")
        ratingLabels().forEach {
            val layout = it.textLayout()
            assertFalse(layout.isLineEllipsized(layout.lineCount - 1), "a label is cut")
        }
    }

    @Test
    fun `a rating alone in a row too narrow for its label wraps the label instead of cutting it`() {
        show {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f)) {
                Box(Modifier.width(80.dp)) { AnswerButtons(intervals = listOf("<1m", "6m", "10m", "4d"), onAnswer = {}) }
            }
        }

        val again = ratingLabels().first().textLayout()
        assertTrue(again.lineCount > 1, "Again wraps")
        ratingLabels().forEach {
            val layout = it.textLayout()
            assertFalse(layout.isLineEllipsized(layout.lineCount - 1), "a label is cut")
        }
    }

    private fun ratingLabels(): List<SemanticsNodeInteraction> =
        listOf(R.string.ease_button_again, R.string.ease_button_hard, R.string.ease_button_good, R.string.ease_button_easy)
            .map { composeRule.onNodeWithText(targetContext.getString(it), useUnmergedTree = true) }

    private fun SemanticsNodeInteraction.textLayout(): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        return layouts.single()
    }

    @Test
    fun `show answer is a button with the translated label`() {
        var shown = 0
        show { ShowAnswerButton(onClick = { shown++ }) }

        composeRule
            .onNodeWithText(targetContext.getString(R.string.show_answer))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertHeightIsAtLeast(48.dp)
            .performClick()

        assertEquals(1, shown)
    }

    @Test
    fun `mark is a toggle that reports its state`() {
        var marked by mutableStateOf(false)
        show { CardActions(marked = marked, onToggleMark = { marked = !marked }) }

        composeRule.onNodeWithContentDescription(labels.markNote).assertIsOff().performClick()

        composeRule.onNodeWithContentDescription(labels.markNote).assertIsOn()
    }

    @Test
    fun `a flag is chosen by name, and the set flag is read with the button`() {
        val chosen = mutableListOf<Flag>()
        var flag by mutableStateOf(Flag.NONE)
        show {
            CardActions(
                flag = flag,
                onSetFlag = {
                    chosen += it
                    flag = it
                },
            )
        }

        composeRule.onNodeWithContentDescription(labels.flagCard).performClick()
        composeRule.onNodeWithText("flag 1").performClick()

        assertEquals(listOf(Flag.RED), chosen)
        composeRule
            .onNodeWithContentDescription(labels.flagCard)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "flag 1"))
    }

    /** A phone in landscape at twice the font size: the eight flags are taller than the window. */
    @Test
    @Config(qualifiers = "w914dp-h411dp", fontScale = 2f)
    fun `in a short window the flag menu scrolls to its last flag`() {
        val chosen = mutableListOf<Flag>()
        show { CardActions(onSetFlag = { chosen += it }) }

        composeRule.onNodeWithContentDescription(labels.flagCard).performClick()
        composeRule
            .onNodeWithText("flag 7")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()

        assertEquals(listOf(Flag.PURPLE), chosen)
    }

    @Test
    fun `the flag menu reads which flag is set`() {
        show { CardActions(flag = Flag.GREEN) }

        composeRule.onNodeWithContentDescription(labels.flagCard).performClick()

        composeRule
            .onNodeWithText("flag 3")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .assertIsSelected()
        listOf("flag 0", "flag 1").forEach { composeRule.onNodeWithText(it).assertIsNotSelected() }
    }

    @Test
    fun `without the flag names the flag button steps to the next flag`() {
        val chosen = mutableListOf<Flag>()
        var flag by mutableStateOf(Flag.TURQUOISE)
        show {
            CardActions(
                flag = flag,
                names = emptyMap(),
                onSetFlag = {
                    chosen += it
                    flag = it
                },
            )
        }

        repeat(3) { composeRule.onNodeWithContentDescription(labels.flagCard).performClick() }

        assertEquals(listOf(Flag.PURPLE, Flag.NONE, Flag.RED), chosen)
    }

    @Test
    fun `answering gives haptic feedback, a distinct one for Again`() {
        val haptics = RecordingHaptics()
        show {
            CompositionLocalProvider(LocalHapticFeedback provides haptics) {
                AnswerButtons(intervals = listOf("<1m", "6m", "10m", "4d"), onAnswer = {})
            }
        }

        val again = targetContext.getString(R.string.ease_button_again)
        val good = targetContext.getString(R.string.ease_button_good)
        composeRule.onNodeWithContentDescription(answerLabel(again, "<1m")).performClick()
        composeRule.onNodeWithContentDescription(answerLabel(good, "10m")).performClick()

        assertEquals(listOf(HapticFeedbackType.Reject, HapticFeedbackType.Confirm), haptics.performed)
    }

    @Test
    fun `marking and unmarking give toggle haptics`() {
        val haptics = RecordingHaptics()
        var marked by mutableStateOf(false)
        show {
            CompositionLocalProvider(LocalHapticFeedback provides haptics) {
                CardActions(marked = marked, onToggleMark = { marked = !marked })
            }
        }

        repeat(2) { composeRule.onNodeWithContentDescription(labels.markNote).performClick() }

        assertEquals(listOf(HapticFeedbackType.ToggleOn, HapticFeedbackType.ToggleOff), haptics.performed)
    }

    @Test
    fun `in a short, wide window the card actions and the answer controls share one row`() {
        show { Box(Modifier.requiredSize(914.dp, 411.dp)) { LayoutSample() } }

        val actions = composeRule.onNodeWithTag(CARD_ACTIONS).getUnclippedBoundsInRoot()
        val answers = composeRule.onNodeWithTag(ANSWER_CONTROLS).getUnclippedBoundsInRoot()
        assertTrue(answers.left >= actions.right, "the answers $answers follow the actions $actions")
        assertTrue(answers.top < actions.bottom, "the answers $answers are beside the actions $actions")
    }

    @Test
    fun `in a short, narrow window, as in split screen, the controls stay stacked`() {
        show { Box(Modifier.requiredSize(411.dp, 415.dp)) { LayoutSample() } }

        val actions = composeRule.onNodeWithTag(CARD_ACTIONS).getUnclippedBoundsInRoot()
        val answers = composeRule.onNodeWithTag(ANSWER_CONTROLS).getUnclippedBoundsInRoot()
        assertTrue(answers.top >= actions.bottom, "the answers $answers are below the actions $actions")
        assertEquals(411f - 32f, (answers.right - answers.left).value, 1f, "the answers take the window's width, less its margins")
    }

    /** Stand-ins of the phone's toolbar and answer row, at their real sizes. */
    @Composable
    private fun LayoutSample() {
        StudyLayout(
            topBar = {},
            cardActions = { Box(Modifier.size(width = 255.dp, height = 64.dp).testTag(CARD_ACTIONS)) },
            answerControls = { Box(Modifier.fillMaxWidth().height(56.dp).testTag(ANSWER_CONTROLS)) },
            snackbarHost = {},
        ) { Box(Modifier.fillMaxSize()) }
    }

    @Test
    fun `burying and suspending are behind more card actions`() {
        val done = mutableListOf<String>()
        show {
            CardActions(
                onBuryCard = { done += "bury card" },
                onBuryNote = { done += "bury note" },
                onSuspendCard = { done += "suspend card" },
                onSuspendNote = { done += "suspend note" },
            )
        }

        listOf(labels.buryNote, labels.suspendCard).forEach { action ->
            composeRule.onNodeWithContentDescription(targetContext.getString(R.string.expressive_study_more_card_actions)).performClick()
            composeRule.onNodeWithText(action).performClick()
        }

        assertEquals(listOf("bury note", "suspend card"), done)
    }

    @Test
    fun `card actions are disabled while an operation runs`() {
        show { CardActions(enabled = false) }

        listOf(labels.markNote, labels.flagCard, targetContext.getString(R.string.replay_media)).forEach {
            composeRule.onNodeWithContentDescription(it).assertIsNotEnabled()
        }
    }

    @Test
    fun `the app bar reads the deck, the cards left, and the time on this card`() {
        var closed = 0
        var added = 0
        show {
            StudyTopAppBar(
                deckName = "Japanese",
                remaining = 12,
                progress = 0.5f,
                timerSeconds = 65,
                canUndo = false,
                canAddNote = true,
                onClose = { closed++ },
                onUndo = {},
                onAddNote = { added++ },
                movingWave = false,
            )
        }

        composeRule.onNodeWithText("Japanese").assertExists()
        composeRule.onNodeWithText(targetContext.resources.getQuantityString(R.plurals.expressive_study_cards_left, 12, 12)).assertExists()
        composeRule.onNodeWithContentDescription(targetContext.getString(R.string.expressive_study_time_on_card, "1:05")).assertExists()
        composeRule.onNodeWithContentDescription(targetContext.getString(R.string.undo)).assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(targetContext.getString(R.string.close)).performClick()
        composeRule.onNodeWithContentDescription(targetContext.getString(R.string.menu_add_note)).performClick()

        assertEquals(1, closed)
        assertEquals(1, added)
    }

    @Composable
    private fun CardActions(
        marked: Boolean = false,
        flag: Flag = Flag.NONE,
        names: Map<Flag, String> = flagNames,
        enabled: Boolean = true,
        onToggleMark: () -> Unit = {},
        onSetFlag: (Flag) -> Unit = {},
        onBuryCard: () -> Unit = {},
        onBuryNote: () -> Unit = {},
        onSuspendCard: () -> Unit = {},
        onSuspendNote: () -> Unit = {},
    ) {
        StudyCardActions(
            marked = marked,
            flag = flag,
            flagNames = names,
            labels = labels,
            enabled = enabled,
            onReplay = {},
            onToggleMark = onToggleMark,
            onSetFlag = onSetFlag,
            onEdit = {},
            onBuryCard = onBuryCard,
            onBuryNote = onBuryNote,
            onSuspendCard = onSuspendCard,
            onSuspendNote = onSuspendNote,
        )
    }

    private fun show(content: @Composable () -> Unit) {
        composeRule.setContent { ExpressiveAnkiTheme(themeMode = "light", dynamicColor = false, content = content) }
    }

    /** Records the haptics a component asks for, in order. */
    private class RecordingHaptics : HapticFeedback {
        val performed = mutableListOf<HapticFeedbackType>()

        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
            performed += hapticFeedbackType
        }
    }

    private companion object {
        const val CARD_ACTIONS = "card actions"
        const val ANSWER_CONTROLS = "answer controls"
    }
}
