// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.ui.expressive.screens

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import com.ichi2.anki.CollectionManager.TR
import com.ichi2.anki.Flag
import com.ichi2.anki.R
import com.ichi2.anki.ScreenshotTest
import com.ichi2.anki.ui.expressive.components.AnswerButtons
import com.ichi2.anki.ui.expressive.components.ShowAnswerButton
import com.ichi2.anki.ui.expressive.components.StudyCardActions
import com.ichi2.anki.ui.expressive.components.StudyLayout
import com.ichi2.anki.ui.expressive.components.StudyTopAppBar
import com.ichi2.anki.ui.expressive.components.rememberStudyActionLabels
import com.ichi2.anki.ui.expressive.theme.ExpressiveAnkiTheme
import com.ichi2.anki.ui.internationalization.SentenceCase
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * The Study screen as the owner reviews it (gate G1): the question and answer sides with the real
 * app bar, card actions, and answer controls, and the card actions' menus. Robolectric does not
 * draw WebViews, so a plain Compose stand-in takes the card's place.
 *
 * `./gradlew :AnkiDroid:testFullDebugUnitTest -Pscreenshot -Ptheme=light,dark -Pdevice=phone,tablet
 *   -Proborazzi.test.record=true --tests 'com.ichi2.anki.ui.expressive.screens.StudyScreenshotTest'`
 */
class StudyScreenshotTest : ScreenshotTest() {
    /** Opens the menus: the activities are launched by [capture]. */
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val themeMode: String
        get() =
            when (theme) {
                ScreenshotTest.ThemeConfig.LIGHT -> "light"
                ScreenshotTest.ThemeConfig.DARK -> "dark"
                else -> "unsupported"
            }

    @Test
    fun question() {
        assumeExpressiveTheme()
        capture("study_question") { StudySample(answered = false) }
    }

    @Test
    fun answer() {
        assumeExpressiveTheme()
        capture("study_answer") { StudySample(answered = true) }
    }

    @Test
    fun answerAtDoubleFontSize() {
        assumeExpressiveTheme()
        RuntimeEnvironment.setFontScale(2f)
        capture("study_answer_font_2x") { StudySample(answered = true) }
    }

    @Test
    fun answerRightToLeft() {
        assumeExpressiveTheme()
        RuntimeEnvironment.setQualifiers("+ldrtl")
        capture("study_answer_rtl") { StudySample(answered = true) }
    }

    /** A phone in landscape, a short and wide window: the card actions and the answer buttons share one row. */
    @Test
    fun answerInLandscape() {
        assumeExpressiveTheme()
        assumeTrue("the tablet is in landscape already", device == ScreenshotTest.DeviceConfig.PHONE)
        RuntimeEnvironment.setQualifiers("+land")
        capture("study_answer_land") { StudySample(answered = true) }
    }

    /** A tablet in portrait, a medium-width window: the controls stop growing. */
    @Test
    fun answerInPortrait() {
        assumeExpressiveTheme()
        assumeTrue("the phone is in portrait already", device == ScreenshotTest.DeviceConfig.TABLET)
        RuntimeEnvironment.setQualifiers("+port")
        capture("study_answer_port") { StudySample(answered = true) }
    }

    /** Half of a phone's screen in split screen, a short but narrow window: the controls stay stacked. */
    @Test
    fun answerInSplitScreen() {
        assumeExpressiveTheme()
        assumeTrue("split screen is a phone's", device == ScreenshotTest.DeviceConfig.PHONE)
        RuntimeEnvironment.setQualifiers("+w411dp-h415dp")
        capture("study_answer_split_screen") { StudySample(answered = true) }
    }

    /** The flags by name, the card's own flag selected. */
    @Test
    fun flagMenu() {
        assumeExpressiveTheme()
        capture("study_flag_menu", tapping = with(targetContext) { SentenceCase.flagCard }) { StudySample(answered = true) }
    }

    /** A phone in landscape at twice the font size, the shortest window: the flag menu scrolls within it. */
    @Test
    fun flagMenuInLandscapeAtDoubleFontSize() {
        assumeExpressiveTheme()
        assumeTrue("a phone's landscape window is the shortest", device == ScreenshotTest.DeviceConfig.PHONE)
        RuntimeEnvironment.setQualifiers("+land")
        RuntimeEnvironment.setFontScale(2f)
        capture("study_flag_menu_land_font_2x", tapping = with(targetContext) { SentenceCase.flagCard }) { StudySample(answered = true) }
    }

    /** Burying and suspending, in two groups. */
    @Test
    fun moreCardActions() {
        assumeExpressiveTheme()
        capture("study_more_card_actions", tapping = targetContext.getString(R.string.expressive_study_more_card_actions)) {
            StudySample(answered = true)
        }
    }

    /** The Expressive theme has light and dark modes only; the Classic plain, black, and e-ink themes do not apply. */
    private fun assumeExpressiveTheme() = assumeTrue(theme == ScreenshotTest.ThemeConfig.LIGHT || theme == ScreenshotTest.ThemeConfig.DARK)

    /** Captures [content] as [name], after tapping the control described as [tapping], if any. */
    private fun capture(
        name: String,
        tapping: String? = null,
        content: @Composable () -> Unit,
    ) {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    ExpressiveAnkiTheme(themeMode = themeMode, paletteKey = "blue", dynamicColor = false, content = content)
                }
            }
            if (tapping != null) composeRule.onNodeWithContentDescription(tapping).performClick()
            composeRule.waitForIdle()
            shadowOf(Looper.getMainLooper()).idle()
            captureScreen(name)
        }
    }
}

/** Anki's own names of the flags, as a new collection has them. */
private fun defaultFlagNames(): Map<Flag, String> =
    mapOf(
        Flag.NONE to TR.browsingNoFlag(),
        Flag.RED to TR.actionsFlagRed(),
        Flag.ORANGE to TR.actionsFlagOrange(),
        Flag.GREEN to TR.actionsFlagGreen(),
        Flag.BLUE to TR.actionsFlagBlue(),
        Flag.PINK to TR.actionsFlagPink(),
        Flag.TURQUOISE to TR.actionsFlagTurquoise(),
        Flag.PURPLE to TR.actionsFlagPurple(),
    )

@Composable
private fun StudySample(answered: Boolean) {
    StudyLayout(
        topBar = {
            StudyTopAppBar(
                deckName = "Vocabulary",
                remaining = 42,
                progress = 0.35f,
                timerSeconds = 12,
                canUndo = true,
                canAddNote = true,
                onClose = {},
                onUndo = {},
                onAddNote = {},
                movingWave = false,
            )
        },
        cardActions = {
            StudyCardActions(
                marked = true,
                flag = Flag.RED,
                flagNames = defaultFlagNames(),
                labels = rememberStudyActionLabels(),
                enabled = true,
                onReplay = {},
                onToggleMark = {},
                onSetFlag = {},
                onEdit = {},
                onBuryCard = {},
                onBuryNote = {},
                onSuspendCard = {},
                onSuspendNote = {},
            )
        },
        answerControls = {
            if (answered) {
                AnswerButtons(intervals = listOf("<1m", "6m", "10m", "4d"), onAnswer = {})
            } else {
                ShowAnswerButton(onClick = {})
            }
        },
        snackbarHost = {},
    ) { padding ->
        SampleCard(answered, Modifier.padding(padding))
    }
}

/** Stands in for the card WebView: a Basic note's front, and its back below the answer line. */
@Composable
private fun SampleCard(
    answered: Boolean,
    modifier: Modifier,
) {
    Column(
        modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("le chien", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
        if (answered) {
            HorizontalDivider()
            Text("the dog", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
        }
    }
}
