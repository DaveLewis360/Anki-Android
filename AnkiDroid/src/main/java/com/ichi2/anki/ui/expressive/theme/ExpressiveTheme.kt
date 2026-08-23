// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.ui.expressive.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Expressive typography: generous, confident sizes with emphasized weights. */
private val ExpressiveTypography =
    Typography(
        displayLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 52.sp, lineHeight = 58.sp, letterSpacing = (-0.5).sp),
        displayMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 42.sp, lineHeight = 48.sp, letterSpacing = (-0.4).sp),
        headlineLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 36.sp),
        headlineMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 32.sp),
        headlineSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
        titleLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 20.sp, lineHeight = 26.sp),
        titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 17.sp, lineHeight = 24.sp, letterSpacing = 0.1.sp),
        titleSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
        bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 17.sp, lineHeight = 24.sp, letterSpacing = 0.2.sp),
        bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
        bodySmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
        labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp),
        labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
        labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp),
    )

/** Expressive shape scale: big, friendly, rounded corners. */
val ExpressiveShapes =
    Shapes(
        extraSmall = RoundedCornerShape(10.dp),
        small = RoundedCornerShape(14.dp),
        medium = RoundedCornerShape(18.dp),
        large = RoundedCornerShape(26.dp),
        extraLarge = RoundedCornerShape(34.dp),
    )

/** Pill shape used for chips and buttons. */
val PillShape = RoundedCornerShape(50)

/** Whether Material You dynamic color is available (Android 12+). */
val supportsDynamicColor: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * The expressive theme. Colors resolve in this priority:
 * Material You dynamic color (when available & enabled) → selected palette → blue base.
 * Motion is provided by spring animations inside the expressive components.
 */
@Composable
fun ExpressiveAnkiTheme(
    themeMode: String = "system",
    paletteKey: String = "blue",
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val dark =
        when (themeMode) {
            "light" -> false
            "dark" -> true
            else -> isSystemInDarkTheme()
        }
    val context = LocalContext.current
    val colors =
        if (dynamicColor && supportsDynamicColor) {
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else {
            val base = if (dark) buildDarkScheme() else buildLightScheme()
            val palette = expressivePalettes.firstOrNull { it.key == paletteKey }
            if (palette == null || palette.key == "blue") base else base.withPalette(palette, dark)
        }
    CompositionLocalProvider(LocalAnswerColors provides answerColorsFor(dark)) {
        MaterialTheme(
            colorScheme = colors,
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
