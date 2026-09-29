// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.ui.expressive.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The Material 3 Expressive type scale: the baseline styles and the emphasized ones M3 Expressive
 * adds (for example `MaterialTheme.typography.titleLargeEmphasized`), sized in `sp` so that it
 * follows the system font scale. `Typography()` alone binds the constructor whose emphasized
 * styles merely repeat the baseline ones. Naming an emphasized style binds the Expressive
 * constructor instead, which takes every other style from the Material tokens, and the style
 * named here is its token too: display large at medium weight and without tracking.
 */
internal val ExpressiveTypography: Typography =
    Typography(displayLargeEmphasized = Typography().displayLarge.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.sp))

/**
 * The Material 3 Expressive shape scale: extra small 4 dp, small 8, medium 12, large 16,
 * large increased 20, extra large 28, extra large increased 32, and extra extra large 48.
 */
val ExpressiveShapes = Shapes()

/** Pill shape used for chips and buttons. */
val PillShape = RoundedCornerShape(50)

/** Whether Material You dynamic color is available (Android 12+). */
val supportsDynamicColor: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/** One instance, so that recomposing the theme never invalidates everything that reads the motion scheme. */
private val ExpressiveMotionScheme = MotionScheme.expressive()

/**
 * The Material 3 Expressive theme: expressive motion springs, the full shape scale, and the
 * emphasized type scale. Colors come from Material You dynamic color when it is available and
 * enabled, and otherwise from the selected palette's generated scheme. Answer colors are
 * harmonized with whichever scheme is active.
 */
@Composable
fun ExpressiveAnkiTheme(
    themeMode: String = "system",
    paletteKey: String = "blue",
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val dark = isExpressiveDark(themeMode)
    val context = LocalContext.current
    val colors =
        if (dynamicColor && supportsDynamicColor) {
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else {
            val palette = expressivePalette(paletteKey)
            remember(palette, dark) { tonalSpotColorScheme(palette.seed, dark) }
        }
    val answerColors = remember(colors.primary, dark) { harmonizedAnswerColors(colors.primary, dark) }
    CompositionLocalProvider(LocalAnswerColors provides answerColors) {
        MaterialExpressiveTheme(
            colorScheme = colors,
            motionScheme = ExpressiveMotionScheme,
            shapes = ExpressiveShapes,
            typography = ExpressiveTypography,
            content = content,
        )
    }
}

/** Resolves the effective dark flag the same way the theme does. */
@Composable
fun isExpressiveDark(themeMode: String): Boolean =
    when (themeMode) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
