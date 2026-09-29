// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.ui.expressive.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.RobolectricTest
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class TonalColorSchemeTest : RobolectricTest() {
    private val modes = listOf(false, true)

    @Test
    fun `every palette yields accent roles at the Material tones and on the seed hue`() {
        forEachScheme { palette, dark, scheme ->
            val name = "${palette.key} dark=$dark"
            assertTone(if (dark) 80f else 40f, scheme.primary, "$name primary")
            assertTone(if (dark) 30f else 90f, scheme.primaryContainer, "$name primaryContainer")
            assertTone(if (dark) 90f else 30f, scheme.onPrimaryContainer, "$name onPrimaryContainer")
            assertTone(if (dark) 6f else 98f, scheme.surface, "$name surface")
            val seedHue = hct(palette.seed)[0]
            assertTrue(differenceDegrees(hct(scheme.primary)[0], seedHue) < 3f, "$name keeps the seed hue")
            assertTrue(differenceDegrees(hct(scheme.tertiary)[0], seedHue) > 45f, "$name tertiary is a distinct hue")
        }
    }

    @Test
    fun `text roles meet WCAG AA contrast on their containers in every scheme`() {
        forEachScheme { palette, dark, scheme ->
            val pairs =
                mapOf(
                    "onPrimary/primary" to (scheme.onPrimary to scheme.primary),
                    "onPrimaryContainer/primaryContainer" to (scheme.onPrimaryContainer to scheme.primaryContainer),
                    "onSecondary/secondary" to (scheme.onSecondary to scheme.secondary),
                    "onSecondaryContainer/secondaryContainer" to (scheme.onSecondaryContainer to scheme.secondaryContainer),
                    "onTertiary/tertiary" to (scheme.onTertiary to scheme.tertiary),
                    "onTertiaryContainer/tertiaryContainer" to (scheme.onTertiaryContainer to scheme.tertiaryContainer),
                    "onError/error" to (scheme.onError to scheme.error),
                    "onErrorContainer/errorContainer" to (scheme.onErrorContainer to scheme.errorContainer),
                    "onSurface/surface" to (scheme.onSurface to scheme.surface),
                    "onSurface/surfaceContainerHighest" to (scheme.onSurface to scheme.surfaceContainerHighest),
                    "onSurfaceVariant/surfaceVariant" to (scheme.onSurfaceVariant to scheme.surfaceVariant),
                    "onSurfaceVariant/surfaceContainerHighest" to (scheme.onSurfaceVariant to scheme.surfaceContainerHighest),
                    "inverseOnSurface/inverseSurface" to (scheme.inverseOnSurface to scheme.inverseSurface),
                    "onPrimaryFixed/primaryFixed" to (scheme.onPrimaryFixed to scheme.primaryFixed),
                )
            pairs.forEach { (roles, colors) ->
                val ratio = contrast(colors.first, colors.second)
                assertTrue(ratio >= 4.5, "${palette.key} dark=$dark $roles contrast $ratio")
            }
            // Non-text component contrast (WCAG 1.4.11) for the outline on the page surface.
            assertTrue(contrast(scheme.outline, scheme.surface) >= 3.0, "${palette.key} dark=$dark outline")
        }
    }

    @Test
    fun `surface containers step toward the text color in order`() {
        forEachScheme { palette, dark, scheme ->
            val tones =
                listOf(
                    scheme.surfaceContainerLowest,
                    scheme.surfaceContainerLow,
                    scheme.surfaceContainer,
                    scheme.surfaceContainerHigh,
                    scheme.surfaceContainerHighest,
                ).map { hct(it)[2] }
            val ordered = if (dark) tones.zipWithNext().all { (a, b) -> a < b } else tones.zipWithNext().all { (a, b) -> a > b }
            assertTrue(ordered, "${palette.key} dark=$dark container tones $tones")
        }
    }

    @Test
    fun `answer colors stay in their hue family, stay balanced, and stay readable with every primary`() {
        // Again stays red and Hard stays orange: full turns would make Again rust or bring the two together.
        val ratings =
            listOf(
                Triple("again", AgainRating, AGAIN_AND_HARD_MAX_ROTATION),
                Triple("hard", HardRating, AGAIN_AND_HARD_MAX_ROTATION),
                Triple("good", GoodRating, MAX_HARMONIZE_ROTATION),
                Triple("easy", EasyRating, MAX_HARMONIZE_ROTATION),
            )
        forEachScheme { palette, dark, scheme ->
            val label = "${palette.key} dark=$dark"
            val palettes = harmonizedAnswerPalettes(scheme.primary)
            ratings.zip(palettes).forEach { (rating, harmonized) ->
                val (name, source, limit) = rating
                val shift = differenceDegrees(harmonized.hue, hct(source)[0])
                assertTrue(shift <= limit + 0.001f, "$label $name turned $shift°")
            }
            assertTrue(differenceDegrees(palettes[0].hue, palettes[2].hue) > 60f, "$label again and good stay distinguishable")

            val answers = harmonizedAnswerColors(scheme.primary, dark)
            val pairs =
                mapOf(
                    "again" to (answers.onAgainContainer to answers.againContainer),
                    "hard" to (answers.onHardContainer to answers.hardContainer),
                    "good" to (answers.onGoodContainer to answers.goodContainer),
                    "easy" to (answers.onEasyContainer to answers.easyContainer),
                )
            pairs.forEach { (name, colors) ->
                val ratio = contrast(colors.first, colors.second)
                assertTrue(ratio >= 4.5, "$label $name contrast $ratio")
                val chroma = hct(colors.second)[1]
                assertTrue(chroma >= 8f, "$label $name container keeps its color")
                if (!dark) {
                    // Rounding to 8-bit sRGB can add a fraction of a chroma unit.
                    assertTrue(chroma <= LIGHT_ANSWER_CONTAINER_CHROMA + 1f, "$label $name container chroma $chroma")
                }
            }
        }
    }

    @Test
    fun `neighbouring answer hues stay apart whatever the primary hue, dynamic color included`() {
        // Every hue a wallpaper can yield, at the tone and chroma of a tonal-spot primary.
        (0 until 360 step 5).forEach { primaryHue ->
            val primary = TonalPalette(primaryHue.toFloat(), 36f).tone(40)
            val (again, hard, good, easy) = harmonizedAnswerPalettes(primary).map { it.hue }
            val gaps =
                mapOf(
                    "again/hard" to differenceDegrees(again, hard),
                    "hard/good" to differenceDegrees(hard, good),
                    "good/easy" to differenceDegrees(good, easy),
                )
            gaps.forEach { (pair, gap) -> assertTrue(gap >= 25f, "primary hue $primaryHue: $pair $gap°") }
        }
    }

    @Test
    fun `harmonization turns half way toward the source hue, at most 15 degrees, across 0 degrees`() {
        assertEquals(10f, harmonizeHue(0f, 20f), 0.001f)
        assertEquals(15f, harmonizeHue(0f, 100f), 0.001f)
        assertEquals(0f, harmonizeHue(350f, 10f), 0.001f)
        assertEquals(0f, harmonizeHue(10f, 350f), 0.001f)
        assertEquals(345f, harmonizeHue(0f, 200f), 0.001f)
        assertEquals(5f, harmonizeHue(0f, 100f, maxRotation = 5f), 0.001f)
        assertEquals(355f, harmonizeHue(0f, 200f, maxRotation = 5f), 0.001f)
        assertEquals(20f, differenceDegrees(350f, 10f), 0.001f)
    }

    @Test
    fun `unknown palette keys fall back to the default palette`() {
        assertEquals(expressivePalettes.first(), expressivePalette("no-such-palette"))
        assertEquals("rose", expressivePalette("rose").key)
    }

    private fun forEachScheme(block: (ExpressivePalette, Boolean, ColorScheme) -> Unit) {
        expressivePalettes.forEach { palette ->
            modes.forEach { dark -> block(palette, dark, tonalSpotColorScheme(palette.seed, dark)) }
        }
    }

    private fun assertTone(
        expected: Float,
        color: Color,
        name: String,
    ) {
        val tone = hct(color)[2]
        assertTrue(abs(tone - expected) <= 1.5f, "$name tone $tone, expected $expected")
    }

    private fun contrast(
        foreground: Color,
        background: Color,
    ): Double = ColorUtils.calculateContrast(foreground.toArgb(), background.toArgb())
}
