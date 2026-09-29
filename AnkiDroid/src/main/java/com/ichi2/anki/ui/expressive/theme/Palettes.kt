// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.ui.expressive.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * A selectable color palette. Its complete light and dark color schemes are generated from
 * [seed] (see [tonalSpotColorScheme]).
 */
data class ExpressivePalette(
    val key: String,
    val label: String,
    val seed: Color,
)

/** The six selectable palettes. Each seed is the primary accent of the matching Classic XML palette overlay. */
val expressivePalettes: List<ExpressivePalette> =
    listOf(
        ExpressivePalette("blue", "Blue", Color(0xFF00639B)),
        ExpressivePalette("violet", "Violet", Color(0xFF6750A4)),
        ExpressivePalette("green", "Green", Color(0xFF206B34)),
        ExpressivePalette("amber", "Amber", Color(0xFF7A5900)),
        ExpressivePalette("rose", "Rose", Color(0xFF984061)),
        ExpressivePalette("teal", "Teal", Color(0xFF006A60)),
    )

/** The palette for [key]; unknown keys fall back to the default blue palette. */
internal fun expressivePalette(key: String): ExpressivePalette =
    expressivePalettes.firstOrNull { it.key == key } ?: expressivePalettes.first()

/** Hue families of the answer ratings, before harmonization with the active scheme. */
internal val AgainRating = Color(0xFFBA1A1A)
internal val HardRating = Color(0xFF8B5000)
internal val GoodRating = Color(0xFF1B6D2F)
internal val EasyRating = Color(0xFF0061A4)

/** Semantic answer-button colors (Again/Hard/Good/Easy) for the current scheme. */
data class AnswerColors(
    val againContainer: Color,
    val onAgainContainer: Color,
    val hardContainer: Color,
    val onHardContainer: Color,
    val goodContainer: Color,
    val onGoodContainer: Color,
    val easyContainer: Color,
    val onEasyContainer: Color,
)

/** Provides scheme-harmonized answer colors anywhere below the theme. */
val LocalAnswerColors =
    staticCompositionLocalOf {
        harmonizedAnswerColors(expressivePalettes.first().seed, dark = false)
    }
