// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.ui.expressive.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import kotlin.math.abs
import kotlin.math.min

/** One hue and chroma in Material's HCT color space; any tone (0 black to 100 white) can be taken from it. */
internal class TonalPalette(
    val hue: Float,
    val chroma: Float,
) {
    /** The in-gamut color closest to this hue and chroma at [tone]; chroma is reduced where sRGB requires it. */
    fun tone(tone: Int): Color = Color(ColorUtils.M3HCTToColor(hue, chroma, tone.toFloat()))
}

/** HCT of [color] as `[hue, chroma, tone]`. */
internal fun hct(color: Color): FloatArray = FloatArray(3).also { ColorUtils.colorToM3HCT(color.toArgb(), it) }

/**
 * A complete Material "tonal spot" color scheme generated from [seed], the scheme Android uses
 * for wallpaper colors: primary keeps the seed hue at chroma 36, secondary at 16, tertiary is
 * rotated by 60° at 24, and the neutrals carry a trace (6 and 8) of the seed hue. Roles take the
 * Material 3 tones, except that light on-container roles use tone 30, as M3 Expressive does.
 */
internal fun tonalSpotColorScheme(
    seed: Color,
    dark: Boolean,
): ColorScheme {
    val hue = hct(seed)[0]
    val primary = TonalPalette(hue, 36f)
    val secondary = TonalPalette(hue, 16f)
    val tertiary = TonalPalette(sanitizeDegrees(hue + 60f), 24f)
    val neutral = TonalPalette(hue, 6f)
    val neutralVariant = TonalPalette(hue, 8f)
    val error = TonalPalette(25f, 84f)
    return if (dark) {
        darkColorScheme(
            primary = primary.tone(80),
            onPrimary = primary.tone(20),
            primaryContainer = primary.tone(30),
            onPrimaryContainer = primary.tone(90),
            inversePrimary = primary.tone(40),
            secondary = secondary.tone(80),
            onSecondary = secondary.tone(20),
            secondaryContainer = secondary.tone(30),
            onSecondaryContainer = secondary.tone(90),
            tertiary = tertiary.tone(80),
            onTertiary = tertiary.tone(20),
            tertiaryContainer = tertiary.tone(30),
            onTertiaryContainer = tertiary.tone(90),
            background = neutral.tone(6),
            onBackground = neutral.tone(90),
            surface = neutral.tone(6),
            onSurface = neutral.tone(90),
            surfaceVariant = neutralVariant.tone(30),
            onSurfaceVariant = neutralVariant.tone(80),
            surfaceTint = primary.tone(80),
            inverseSurface = neutral.tone(90),
            inverseOnSurface = neutral.tone(20),
            error = error.tone(80),
            onError = error.tone(20),
            errorContainer = error.tone(30),
            onErrorContainer = error.tone(90),
            outline = neutralVariant.tone(60),
            outlineVariant = neutralVariant.tone(30),
            scrim = neutral.tone(0),
            surfaceBright = neutral.tone(24),
            surfaceContainer = neutral.tone(12),
            surfaceContainerHigh = neutral.tone(17),
            surfaceContainerHighest = neutral.tone(22),
            surfaceContainerLow = neutral.tone(10),
            surfaceContainerLowest = neutral.tone(4),
            surfaceDim = neutral.tone(6),
            primaryFixed = primary.tone(90),
            primaryFixedDim = primary.tone(80),
            onPrimaryFixed = primary.tone(10),
            onPrimaryFixedVariant = primary.tone(30),
            secondaryFixed = secondary.tone(90),
            secondaryFixedDim = secondary.tone(80),
            onSecondaryFixed = secondary.tone(10),
            onSecondaryFixedVariant = secondary.tone(30),
            tertiaryFixed = tertiary.tone(90),
            tertiaryFixedDim = tertiary.tone(80),
            onTertiaryFixed = tertiary.tone(10),
            onTertiaryFixedVariant = tertiary.tone(30),
        )
    } else {
        lightColorScheme(
            primary = primary.tone(40),
            onPrimary = primary.tone(100),
            primaryContainer = primary.tone(90),
            onPrimaryContainer = primary.tone(30),
            inversePrimary = primary.tone(80),
            secondary = secondary.tone(40),
            onSecondary = secondary.tone(100),
            secondaryContainer = secondary.tone(90),
            onSecondaryContainer = secondary.tone(30),
            tertiary = tertiary.tone(40),
            onTertiary = tertiary.tone(100),
            tertiaryContainer = tertiary.tone(90),
            onTertiaryContainer = tertiary.tone(30),
            background = neutral.tone(98),
            onBackground = neutral.tone(10),
            surface = neutral.tone(98),
            onSurface = neutral.tone(10),
            surfaceVariant = neutralVariant.tone(90),
            onSurfaceVariant = neutralVariant.tone(30),
            surfaceTint = primary.tone(40),
            inverseSurface = neutral.tone(20),
            inverseOnSurface = neutral.tone(95),
            error = error.tone(40),
            onError = error.tone(100),
            errorContainer = error.tone(90),
            onErrorContainer = error.tone(30),
            outline = neutralVariant.tone(50),
            outlineVariant = neutralVariant.tone(80),
            scrim = neutral.tone(0),
            surfaceBright = neutral.tone(98),
            surfaceContainer = neutral.tone(94),
            surfaceContainerHigh = neutral.tone(92),
            surfaceContainerHighest = neutral.tone(90),
            surfaceContainerLow = neutral.tone(96),
            surfaceContainerLowest = neutral.tone(100),
            surfaceDim = neutral.tone(87),
            primaryFixed = primary.tone(90),
            primaryFixedDim = primary.tone(80),
            onPrimaryFixed = primary.tone(10),
            onPrimaryFixedVariant = primary.tone(30),
            secondaryFixed = secondary.tone(90),
            secondaryFixedDim = secondary.tone(80),
            onSecondaryFixed = secondary.tone(10),
            onSecondaryFixedVariant = secondary.tone(30),
            tertiaryFixed = tertiary.tone(90),
            tertiaryFixedDim = tertiary.tone(80),
            onTertiaryFixed = tertiary.tone(10),
            onTertiaryFixedVariant = tertiary.tone(30),
        )
    }
}

/** How far Material color harmonization turns a hue toward the source hue at most. */
internal const val MAX_HARMONIZE_ROTATION = 15f

/**
 * How far Again and Hard turn at most. Red and orange sit only about 39° apart: full turns could
 * bring them within 20° of each other, and make Again rust next to a green, teal, or amber primary.
 */
internal const val AGAIN_AND_HARD_MAX_ROTATION = 5f

/**
 * The chroma light answer containers are capped at: at tone 90, green holds chroma 49 while red and
 * orange reach only about 14, and an uncapped Good button would outshine the other three.
 */
internal const val LIGHT_ANSWER_CONTAINER_CHROMA = 24f

/**
 * Answer-rating colors harmonized with [primary]: each rating keeps its own hue family, rotated
 * toward the scheme's primary hue so that it sits well next to any wallpaper or palette. The
 * container and on-container tones mirror the scheme's own container roles, and light containers
 * are capped at [LIGHT_ANSWER_CONTAINER_CHROMA].
 */
internal fun harmonizedAnswerColors(
    primary: Color,
    dark: Boolean,
): AnswerColors {
    val (again, hard, good, easy) = harmonizedAnswerPalettes(primary)

    fun TonalPalette.container(): Color = if (dark) tone(30) else TonalPalette(hue, min(chroma, LIGHT_ANSWER_CONTAINER_CHROMA)).tone(90)

    fun TonalPalette.onContainer(): Color = tone(if (dark) 90 else 30)
    return AnswerColors(
        againContainer = again.container(),
        onAgainContainer = again.onContainer(),
        hardContainer = hard.container(),
        onHardContainer = hard.onContainer(),
        goodContainer = good.container(),
        onGoodContainer = good.onContainer(),
        easyContainer = easy.container(),
        onEasyContainer = easy.onContainer(),
    )
}

/**
 * The Again, Hard, Good, and Easy palettes, each rating's hue harmonized with [primary]. Again
 * and Hard turn at most [AGAIN_AND_HARD_MAX_ROTATION]; Good and Easy as far as Material
 * harmonization does.
 */
internal fun harmonizedAnswerPalettes(primary: Color): List<TonalPalette> {
    val sourceHue = hct(primary)[0]
    return listOf(
        AgainRating to AGAIN_AND_HARD_MAX_ROTATION,
        HardRating to AGAIN_AND_HARD_MAX_ROTATION,
        GoodRating to MAX_HARMONIZE_ROTATION,
        EasyRating to MAX_HARMONIZE_ROTATION,
    ).map { (rating, maxRotation) ->
        val (hue, chroma) = hct(rating)
        TonalPalette(harmonizeHue(hue, sourceHue, maxRotation), chroma)
    }
}

/** Material color harmonization: [hue] turns toward [sourceHue] by half their difference, at most [maxRotation]. */
internal fun harmonizeHue(
    hue: Float,
    sourceHue: Float,
    maxRotation: Float = MAX_HARMONIZE_ROTATION,
): Float {
    val rotation = min(differenceDegrees(hue, sourceHue) * 0.5f, maxRotation)
    val direction = if (sanitizeDegrees(sourceHue - hue) <= 180f) 1f else -1f
    return sanitizeDegrees(hue + rotation * direction)
}

/** The shortest angle between two hues, 0 to 180. */
internal fun differenceDegrees(
    a: Float,
    b: Float,
): Float = 180f - abs(abs(a - b) - 180f)

private fun sanitizeDegrees(degrees: Float): Float = ((degrees % 360f) + 360f) % 360f
