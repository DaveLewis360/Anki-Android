// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.ui.expressive.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.RobolectricTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** What every Expressive screen can rely on below [ExpressiveAnkiTheme]. */
@RunWith(AndroidJUnit4::class)
class ExpressiveThemeContractTest : RobolectricTest() {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `the theme provides expressive motion and the full shape scale`() {
        val provided = capture(ThemeConfig(themeMode = "light", paletteKey = "blue", dynamicColor = false)).single()

        assertEquals(MotionScheme.expressive().defaultSpatialSpec<Float>(), provided.spatial)
        assertNotEquals(MotionScheme.standard().defaultSpatialSpec<Float>(), provided.spatial)
        val shapes = provided.shapes
        assertEquals(RoundedCornerShape(4.dp), shapes.extraSmall)
        assertEquals(RoundedCornerShape(16.dp), shapes.large)
        assertEquals(RoundedCornerShape(20.dp), shapes.largeIncreased)
        assertEquals(RoundedCornerShape(28.dp), shapes.extraLarge)
        assertEquals(RoundedCornerShape(32.dp), shapes.extraLargeIncreased)
        assertEquals(RoundedCornerShape(48.dp), shapes.extraExtraLarge)
    }

    @Test
    fun `the type scale is the Material 3 Expressive token scale, emphasized styles included`() {
        val type = capture(ThemeConfig(themeMode = "light", paletteKey = "blue", dynamicColor = false)).single().typography

        val tokens = Typography()
        listOf(
            type.displayLarge to tokens.displayLarge,
            type.displayMedium to tokens.displayMedium,
            type.displaySmall to tokens.displaySmall,
            type.headlineLarge to tokens.headlineLarge,
            type.headlineMedium to tokens.headlineMedium,
            type.headlineSmall to tokens.headlineSmall,
            type.titleLarge to tokens.titleLarge,
            type.titleMedium to tokens.titleMedium,
            type.titleSmall to tokens.titleSmall,
            type.bodyLarge to tokens.bodyLarge,
            type.bodyMedium to tokens.bodyMedium,
            type.bodySmall to tokens.bodySmall,
            type.labelLarge to tokens.labelLarge,
            type.labelMedium to tokens.labelMedium,
            type.labelSmall to tokens.labelSmall,
        ).forEach { (provided, token) -> assertEquals(token, provided) }
        // The emphasized tokens of material3 1.5.0-alpha18 (TypeScaleTokens): weight, size, line height, and tracking.
        listOf(
            Triple("displayLarge", type.displayLargeEmphasized, TypeToken(FontWeight.Medium, 57, 64, 0f)),
            Triple("displayMedium", type.displayMediumEmphasized, TypeToken(FontWeight.Medium, 45, 52, 0f)),
            Triple("displaySmall", type.displaySmallEmphasized, TypeToken(FontWeight.Medium, 36, 44, 0f)),
            Triple("headlineLarge", type.headlineLargeEmphasized, TypeToken(FontWeight.Medium, 32, 40, 0f)),
            Triple("headlineMedium", type.headlineMediumEmphasized, TypeToken(FontWeight.Medium, 28, 36, 0f)),
            Triple("headlineSmall", type.headlineSmallEmphasized, TypeToken(FontWeight.Medium, 24, 32, 0f)),
            Triple("titleLarge", type.titleLargeEmphasized, TypeToken(FontWeight.Medium, 22, 28, 0f)),
            Triple("titleMedium", type.titleMediumEmphasized, TypeToken(FontWeight.Bold, 16, 24, 0.15f)),
            Triple("titleSmall", type.titleSmallEmphasized, TypeToken(FontWeight.Bold, 14, 20, 0.1f)),
            Triple("bodyLarge", type.bodyLargeEmphasized, TypeToken(FontWeight.Medium, 16, 24, 0.15f)),
            Triple("bodyMedium", type.bodyMediumEmphasized, TypeToken(FontWeight.Medium, 14, 20, 0.25f)),
            Triple("bodySmall", type.bodySmallEmphasized, TypeToken(FontWeight.Medium, 12, 16, 0.4f)),
            Triple("labelLarge", type.labelLargeEmphasized, TypeToken(FontWeight.Bold, 14, 20, 0.1f)),
            Triple("labelMedium", type.labelMediumEmphasized, TypeToken(FontWeight.Bold, 12, 16, 0.5f)),
            Triple("labelSmall", type.labelSmallEmphasized, TypeToken(FontWeight.Bold, 11, 16, 0.5f)),
        ).forEach { (name, style, token) ->
            assertEquals(token.weight, style.fontWeight, "$name emphasized weight")
            assertEquals(token.size.sp, style.fontSize, "$name emphasized size")
            assertEquals(token.lineHeight.sp, style.lineHeight, "$name emphasized line height")
            assertEquals(token.tracking.sp, style.letterSpacing, "$name emphasized tracking")
            assertEquals(tokens.displayLarge.fontFamily, style.fontFamily, "$name emphasized font family")
        }
    }

    @Test
    fun `a static palette provides its generated scheme and answer colors harmonized with it`() {
        val (light, dark) =
            capture(
                ThemeConfig(themeMode = "light", paletteKey = "rose", dynamicColor = false),
                ThemeConfig(themeMode = "dark", paletteKey = "rose", dynamicColor = false),
            )

        listOf(light to false, dark to true).forEach { (provided, isDark) ->
            val expected = tonalSpotColorScheme(expressivePalette("rose").seed, isDark)
            assertEquals(expected.primary, provided.colors.primary)
            assertEquals(expected.surfaceContainerHigh, provided.colors.surfaceContainerHigh)
            assertEquals(harmonizedAnswerColors(expected.primary, isDark), provided.answers)
        }
    }

    @Test
    fun `answer colors follow the dynamic scheme when dynamic color is on`() {
        val provided = capture(ThemeConfig(themeMode = "dark", paletteKey = "rose", dynamicColor = true)).single()

        if (supportsDynamicColor) {
            assertNotEquals(tonalSpotColorScheme(expressivePalette("rose").seed, dark = true).primary, provided.colors.primary)
        }
        assertEquals(harmonizedAnswerColors(provided.colors.primary, dark = true), provided.answers)
    }

    private data class ThemeConfig(
        val themeMode: String,
        val paletteKey: String,
        val dynamicColor: Boolean,
    )

    /** One type-scale token: weight, and size, line height, and tracking in sp. */
    private data class TypeToken(
        val weight: FontWeight,
        val size: Int,
        val lineHeight: Int,
        val tracking: Float,
    )

    private class Provided(
        val colors: ColorScheme,
        val shapes: Shapes,
        val typography: Typography,
        val spatial: FiniteAnimationSpec<Float>,
        val answers: AnswerColors,
    )

    /** A compose rule takes content once: every configuration is composed side by side. */
    private fun capture(vararg configs: ThemeConfig): List<Provided> {
        val provided = arrayOfNulls<Provided>(configs.size)
        composeRule.setContent {
            configs.forEachIndexed { index, config ->
                ExpressiveAnkiTheme(themeMode = config.themeMode, paletteKey = config.paletteKey, dynamicColor = config.dynamicColor) {
                    provided[index] = read()
                }
            }
        }
        composeRule.waitForIdle()
        return provided.map { requireNotNull(it) }
    }

    @Composable
    private fun read(): Provided =
        Provided(
            colors = MaterialTheme.colorScheme,
            shapes = MaterialTheme.shapes,
            typography = MaterialTheme.typography,
            spatial = MaterialTheme.motionScheme.defaultSpatialSpec(),
            answers = LocalAnswerColors.current,
        )
}
