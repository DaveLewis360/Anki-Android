// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.ui.expressive.theme

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import com.ichi2.anki.ScreenshotTest
import com.ichi2.anki.ui.expressive.ExpressiveAccount
import com.ichi2.anki.ui.expressive.ExpressiveAccountUiState
import com.ichi2.anki.ui.expressive.UiDeck
import com.ichi2.anki.ui.expressive.components.AnswerButtons
import com.ichi2.anki.ui.expressive.components.DeckCard
import com.ichi2.anki.ui.expressive.components.TodaySummaryCard
import com.ichi2.anki.ui.expressive.screens.AccountScreen
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * The Expressive design system as the owner reviews it: color roles, answer colors, the type and
 * shape scales, Material 3 Expressive components, and a real screen, in light and dark. Dynamic
 * color is off: Robolectric has no wallpaper colors, so a dynamic scheme would only show framework
 * defaults. [ExpressiveThemeContractTest] covers that branch.
 *
 * `./gradlew :AnkiDroid:testFullDebugUnitTest -Pscreenshot -Ptheme=light,dark -Proborazzi.test.record=true
 *   --tests 'com.ichi2.anki.ui.expressive.theme.ExpressiveThemeScreenshotTest'`
 */
class ExpressiveThemeScreenshotTest : ScreenshotTest() {
    private val themeMode: String
        get() =
            when (theme) {
                ScreenshotTest.ThemeConfig.LIGHT -> "light"
                ScreenshotTest.ThemeConfig.DARK -> "dark"
                else -> "unsupported"
            }

    @Test
    fun colorRoles() {
        assumeExpressiveTheme()
        capture("colors") { ColorRoles() }
    }

    @Test
    fun typeShapesAndComponents() {
        assumeExpressiveTheme()
        capture("components") { TypeShapesAndComponents() }
    }

    @Test
    fun palettes() {
        assumeExpressiveTheme()
        capture("palettes") { Palettes(dark = themeMode == "dark") }
    }

    @Test
    fun componentsAtDoubleFontSize() {
        assumeExpressiveTheme()
        RuntimeEnvironment.setFontScale(2f)
        capture("components_font_2x") { TypeShapesAndComponents() }
    }

    @Test
    fun componentsRightToLeft() {
        assumeExpressiveTheme()
        RuntimeEnvironment.setQualifiers("+ldrtl")
        capture("components_rtl") { TypeShapesAndComponents() }
    }

    /** A real screen: a heading, default-shaped text fields, and buttons under the theme. */
    @Test
    fun accountSignIn() {
        assumeExpressiveTheme()
        capture("account_sign_in") { AccountScreenSample(ExpressiveAccountUiState(username = "someone@example.com")) }
    }

    @Test
    fun accountSignedIn() {
        assumeExpressiveTheme()
        capture("account_signed_in") {
            AccountScreenSample(ExpressiveAccountUiState(account = ExpressiveAccount(username = "someone@example.com")))
        }
    }

    /** The app's own components whose text sits on colored containers: the summary card, deck cards, and answer buttons. */
    @Test
    fun appComponents() {
        assumeExpressiveTheme()
        capture("app_components") { AppComponents() }
    }

    @Test
    fun appComponentsAtDoubleFontSize() {
        assumeExpressiveTheme()
        RuntimeEnvironment.setFontScale(2f)
        capture("app_components_font_2x") { AppComponents() }
    }

    @Test
    fun appComponentsRightToLeft() {
        assumeExpressiveTheme()
        RuntimeEnvironment.setQualifiers("+ldrtl")
        capture("app_components_rtl") { AppComponents() }
    }

    /** The Expressive theme has light and dark modes only; the Classic plain, black, and e-ink themes do not apply. */
    private fun assumeExpressiveTheme() = assumeTrue(theme == ScreenshotTest.ThemeConfig.LIGHT || theme == ScreenshotTest.ThemeConfig.DARK)

    private fun capture(
        name: String,
        content: @Composable () -> Unit,
    ) {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    ExpressiveAnkiTheme(themeMode = themeMode, paletteKey = "blue", dynamicColor = false) {
                        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) { content() }
                    }
                }
            }
            shadowOf(Looper.getMainLooper()).idle()
            captureScreen(name)
        }
    }
}

@Composable
private fun AccountScreenSample(state: ExpressiveAccountUiState) {
    AccountScreen(
        state = state,
        onUsernameChanged = {},
        onPasswordChanged = {},
        onSignIn = {},
        onRequestForget = {},
        onDismissForget = {},
        onConfirmForget = {},
        onBack = {},
    )
}

@Composable
private fun AppComponents() {
    Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TodaySummaryCard(newCount = 20, learnCount = 5, dueCount = 42, modifier = Modifier.padding(horizontal = 16.dp))
        DeckCard(deck = sampleDeck("Japanese", new = 20, learn = 5, due = 42), selected = false, onClick = {})
        DeckCard(deck = sampleDeck("Kanji", new = 10, learn = 2, due = 7), selected = true, onClick = {})
        DeckCard(deck = sampleDeck("Leeches", new = 0, learn = 0, due = 3, filtered = true), selected = false, onClick = {})
        DeckCard(deck = sampleDeck("Finished", new = 0, learn = 0, due = 0), selected = false, onClick = {})
        AnswerButtons(intervals = listOf("<1m", "6m", "10m", "4d"), onAnswer = {})
    }
}

private fun sampleDeck(
    name: String,
    new: Int,
    learn: Int,
    due: Int,
    filtered: Boolean = false,
) = UiDeck(
    did = name.hashCode().toLong(),
    name = name,
    fullDeckName = name,
    depth = 0,
    filtered = filtered,
    collapsed = false,
    newCount = new,
    learnCount = learn,
    dueCount = due,
    children = emptyList(),
)

@Composable
private fun ColorRoles() {
    val colors = MaterialTheme.colorScheme
    val answers = LocalAnswerColors.current
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("Accent roles")
        SwatchRow(
            Swatch("primary", colors.primary, colors.onPrimary),
            Swatch("primaryContainer", colors.primaryContainer, colors.onPrimaryContainer),
        )
        SwatchRow(
            Swatch("secondary", colors.secondary, colors.onSecondary),
            Swatch("secondaryContainer", colors.secondaryContainer, colors.onSecondaryContainer),
        )
        SwatchRow(
            Swatch("tertiary", colors.tertiary, colors.onTertiary),
            Swatch("tertiaryContainer", colors.tertiaryContainer, colors.onTertiaryContainer),
        )
        SwatchRow(
            Swatch("error", colors.error, colors.onError),
            Swatch("errorContainer", colors.errorContainer, colors.onErrorContainer),
        )
        SectionTitle("Surfaces")
        SwatchRow(
            Swatch("lowest", colors.surfaceContainerLowest, colors.onSurface),
            Swatch("low", colors.surfaceContainerLow, colors.onSurface),
            Swatch("container", colors.surfaceContainer, colors.onSurface),
        )
        SwatchRow(
            Swatch("high", colors.surfaceContainerHigh, colors.onSurface),
            Swatch("highest", colors.surfaceContainerHighest, colors.onSurfaceVariant),
            Swatch("inverse", colors.inverseSurface, colors.inverseOnSurface),
        )
        SectionTitle("Answer ratings, harmonized")
        SwatchRow(
            Swatch("Again", answers.againContainer, answers.onAgainContainer),
            Swatch("Hard", answers.hardContainer, answers.onHardContainer),
            Swatch("Good", answers.goodContainer, answers.onGoodContainer),
            Swatch("Easy", answers.easyContainer, answers.onEasyContainer),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TypeShapesAndComponents() {
    val type = MaterialTheme.typography
    val shapes = MaterialTheme.shapes
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle("Type scale, baseline and emphasized")
        TypeSample("Headline medium", type.headlineMedium, type.headlineMediumEmphasized)
        TypeSample("Title large", type.titleLarge, type.titleLargeEmphasized)
        TypeSample("Body large", type.bodyLarge, type.bodyLargeEmphasized)
        TypeSample("Label large", type.labelLarge, type.labelLargeEmphasized)
        SectionTitle("Shape scale")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                "XS" to shapes.extraSmall,
                "S" to shapes.small,
                "M" to shapes.medium,
                "L" to shapes.large,
                "L+" to shapes.largeIncreased,
                "XL" to shapes.extraLarge,
                "XL+" to shapes.extraLargeIncreased,
                "XXL" to shapes.extraExtraLarge,
            ).forEach { (label, shape) -> ShapeSample(label, shape) }
        }
        SectionTitle("Components")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {}, shapes = ButtonDefaults.shapes()) { Text("Show answer") }
            FilledTonalButton(onClick = {}, shapes = ButtonDefaults.shapes()) { Text("Tonal") }
            OutlinedButton(onClick = {}, shapes = ButtonDefaults.shapes()) { Text("Outlined") }
            ToggleButton(checked = true, onCheckedChange = {}) { Text("On") }
            ToggleButton(checked = false, onCheckedChange = {}) { Text("Off") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            LoadingIndicator(progress = { 0.6f })
            ContainedLoadingIndicator(progress = { 0.6f })
            // A still wave: a travelling one animates forever and never lets the frame settle.
            LinearWavyProgressIndicator(progress = { 0.6f }, modifier = Modifier.width(160.dp), waveSpeed = 0.dp)
        }
        // Components that take their corners from the scale themselves: chips (small) and snackbars (extra small).
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = true, onClick = {}, label = { Text("Due") })
            FilterChip(selected = false, onClick = {}, label = { Text("New") })
            AssistChip(onClick = {}, label = { Text("Assist") })
        }
        Snackbar { Text("Card buried") }
    }
}

@Composable
private fun Palettes(dark: Boolean) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("Palettes")
        expressivePalettes.forEach { palette ->
            val scheme = tonalSpotColorScheme(palette.seed, dark)
            Text(palette.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
            SwatchRow(
                Swatch("primary", scheme.primary, scheme.onPrimary),
                Swatch("primaryC", scheme.primaryContainer, scheme.onPrimaryContainer),
                Swatch("secondaryC", scheme.secondaryContainer, scheme.onSecondaryContainer),
                Swatch("tertiaryC", scheme.tertiaryContainer, scheme.onTertiaryContainer),
                Swatch("surfaceHigh", scheme.surfaceContainerHigh, scheme.onSurface),
            )
        }
    }
}

private class Swatch(
    val label: String,
    val color: Color,
    val onColor: Color,
)

@Composable
private fun SwatchRow(vararg swatches: Swatch) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        swatches.forEach { swatch ->
            Box(
                Modifier
                    .weight(1f)
                    .height(56.dp)
                    .background(swatch.color, MaterialTheme.shapes.medium)
                    .padding(8.dp),
            ) {
                Text(swatch.label, style = MaterialTheme.typography.labelMedium, color = swatch.onColor)
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMediumEmphasized, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun TypeSample(
    label: String,
    baseline: TextStyle,
    emphasized: TextStyle,
) {
    Column {
        Text(label, style = baseline, color = MaterialTheme.colorScheme.onSurface)
        Text("$label emphasized", style = emphasized, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun ShapeSample(
    label: String,
    shape: Shape,
) {
    Box(
        Modifier
            .size(72.dp)
            .background(MaterialTheme.colorScheme.tertiaryContainer, shape),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onTertiaryContainer)
    }
}
