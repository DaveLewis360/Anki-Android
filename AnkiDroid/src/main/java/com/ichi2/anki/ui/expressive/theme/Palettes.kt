// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.ui.expressive.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Light
val PrimaryLight = Color(0xFF00639B)
val OnPrimaryLight = Color(0xFFFFFFFF)
val PrimaryContainerLight = Color(0xFFCFE5FF)
val OnPrimaryContainerLight = Color(0xFF001D34)
val SecondaryLight = Color(0xFF51606F)
val OnSecondaryLight = Color(0xFFFFFFFF)
val SecondaryContainerLight = Color(0xFFD5E4F7)
val OnSecondaryContainerLight = Color(0xFF0E1D2A)
val TertiaryLight = Color(0xFF655586)
val OnTertiaryLight = Color(0xFFFFFFFF)
val TertiaryContainerLight = Color(0xFFE9DDFF)
val OnTertiaryContainerLight = Color(0xFF201038)
val ErrorLight = Color(0xFFB3261E)
val OnErrorLight = Color(0xFFFFFFFF)
val ErrorContainerLight = Color(0xFFF9DEDC)
val OnErrorContainerLight = Color(0xFF410E0B)
val BackgroundLight = Color(0xFFF8F9FF)
val OnBackgroundLight = Color(0xFF191C20)
val SurfaceLight = Color(0xFFF8F9FF)
val OnSurfaceLight = Color(0xFF191C20)
val SurfaceVariantLight = Color(0xFFDFE2EB)
val OnSurfaceVariantLight = Color(0xFF43474E)
val OutlineLight = Color(0xFF73777F)
val OutlineVariantLight = Color(0xFFC3C7CF)
val SurfaceContainerLowestLight = Color(0xFFFFFFFF)
val SurfaceContainerLowLight = Color(0xFFF1F3FA)
val SurfaceContainerLight = Color(0xFFEBEEF6)
val SurfaceContainerHighLight = Color(0xFFE5E8F0)
val SurfaceContainerHighestLight = Color(0xFFDFE2EB)

// Dark
val PrimaryDark = Color(0xFF99CBFF)
val OnPrimaryDark = Color(0xFF003351)
val PrimaryContainerDark = Color(0xFF004B73)
val OnPrimaryContainerDark = Color(0xFFCFE5FF)
val SecondaryDark = Color(0xFFB9C8DA)
val OnSecondaryDark = Color(0xFF243242)
val SecondaryContainerDark = Color(0xFF3B4857)
val OnSecondaryContainerDark = Color(0xFFD5E4F7)
val TertiaryDark = Color(0xFFCFC2EA)
val OnTertiaryDark = Color(0xFF36284D)
val TertiaryContainerDark = Color(0xFF4D3E65)
val OnTertiaryContainerDark = Color(0xFFE9DDFF)
val ErrorDark = Color(0xFFF2B8B5)
val OnErrorDark = Color(0xFF601410)
val ErrorContainerDark = Color(0xFF8C1D18)
val OnErrorContainerDark = Color(0xFFF9DEDC)
val BackgroundDark = Color(0xFF101418)
val OnBackgroundDark = Color(0xFFE1E2E8)
val SurfaceDark = Color(0xFF101418)
val OnSurfaceDark = Color(0xFFE1E2E8)
val SurfaceVariantDark = Color(0xFF43474E)
val OnSurfaceVariantDark = Color(0xFFC3C7CF)
val OutlineDark = Color(0xFF8D9199)
val OutlineVariantDark = Color(0xFF43474E)
val SurfaceContainerLowestDark = Color(0xFF0B0E12)
val SurfaceContainerLowDark = Color(0xFF191C21)
val SurfaceContainerDark = Color(0xFF1D2024)
val SurfaceContainerHighDark = Color(0xFF282A2F)
val SurfaceContainerHighestDark = Color(0xFF33353A)

// Semantic answer-button colors
val AgainContainerLight = Color(0xFFFFDAD6)
val OnAgainContainerLight = Color(0xFF410002)
val HardContainerLight = Color(0xFFFFDCC2)
val OnHardContainerLight = Color(0xFF4A2800)
val GoodContainerLight = Color(0xFFC7F0C9)
val OnGoodContainerLight = Color(0xFF003911)
val EasyContainerLight = Color(0xFFCFE5FF)
val OnEasyContainerLight = Color(0xFF001D34)
val AgainContainerDark = Color(0xFF93000A)
val OnAgainContainerDark = Color(0xFFFFDAD6)
val HardContainerDark = Color(0xFF6A3E00)
val OnHardContainerDark = Color(0xFFFFDCC2)
val GoodContainerDark = Color(0xFF0C5F2B)
val OnGoodContainerDark = Color(0xFFC7F0C9)
val EasyContainerDark = Color(0xFF004B73)
val OnEasyContainerDark = Color(0xFFCFE5FF)

/** Accent roles of a selectable palette. */
data class ExpressivePalette(
    val key: String,
    val label: String,
    val primaryLight: Color,
    val onPrimaryLight: Color,
    val primaryContainerLight: Color,
    val onPrimaryContainerLight: Color,
    val secondaryContainerLight: Color,
    val onSecondaryContainerLight: Color,
    val tertiaryContainerLight: Color,
    val onTertiaryContainerLight: Color,
    val primaryDark: Color,
    val onPrimaryDark: Color,
    val primaryContainerDark: Color,
    val onPrimaryContainerDark: Color,
    val secondaryContainerDark: Color,
    val onSecondaryContainerDark: Color,
    val tertiaryContainerDark: Color,
    val onTertiaryContainerDark: Color,
)

/** The six selectable color palettes (values match the XML palette overlays). */
val expressivePalettes: List<ExpressivePalette> =
    listOf(
        ExpressivePalette(
            "blue",
            "Blue",
            PrimaryLight,
            OnPrimaryLight,
            PrimaryContainerLight,
            OnPrimaryContainerLight,
            SecondaryContainerLight,
            OnSecondaryContainerLight,
            TertiaryContainerLight,
            OnTertiaryContainerLight,
            PrimaryDark,
            OnPrimaryDark,
            PrimaryContainerDark,
            OnPrimaryContainerDark,
            SecondaryContainerDark,
            OnSecondaryContainerDark,
            TertiaryContainerDark,
            OnTertiaryContainerDark,
        ),
        ExpressivePalette(
            "violet",
            "Violet",
            Color(0xFF6750A4),
            Color(0xFFFFFFFF),
            Color(0xFFEADDFF),
            Color(0xFF21005D),
            Color(0xFFE8DEF8),
            Color(0xFF1D192B),
            Color(0xFFFFD8E4),
            Color(0xFF31111D),
            Color(0xFFD0BCFF),
            Color(0xFF381E72),
            Color(0xFF4F378B),
            Color(0xFFEADDFF),
            Color(0xFF4A4458),
            Color(0xFFE8DEF8),
            Color(0xFF633B48),
            Color(0xFFFFD8E4),
        ),
        ExpressivePalette(
            "green",
            "Green",
            Color(0xFF206B34),
            Color(0xFFFFFFFF),
            Color(0xFFA7F5AD),
            Color(0xFF00210A),
            Color(0xFFC5EEC9),
            Color(0xFF0A2010),
            Color(0xFFD8E8B8),
            Color(0xFF141F04),
            Color(0xFF8BD88F),
            Color(0xFF003914),
            Color(0xFF00531F),
            Color(0xFFA7F5AD),
            Color(0xFF35543A),
            Color(0xFFC5EEC9),
            Color(0xFF4C602D),
            Color(0xFFD8E8B8),
        ),
        ExpressivePalette(
            "amber",
            "Amber",
            Color(0xFF7A5900),
            Color(0xFFFFFFFF),
            Color(0xFFFFDF9E),
            Color(0xFF261A00),
            Color(0xFFF3E0C8),
            Color(0xFF221B0E),
            Color(0xFFD8E4B8),
            Color(0xFF151F04),
            Color(0xFFF8BD48),
            Color(0xFF422C00),
            Color(0xFF5F4200),
            Color(0xFFFFDF9E),
            Color(0xFF50452D),
            Color(0xFFF3E0C8),
            Color(0xFF665F3C),
            Color(0xFFD8E4B8),
        ),
        ExpressivePalette(
            "rose",
            "Rose",
            Color(0xFF984061),
            Color(0xFFFFFFFF),
            Color(0xFFFFD9E4),
            Color(0xFF3E001D),
            Color(0xFFF4DDDF),
            Color(0xFF22191A),
            Color(0xFFE5E0B8),
            Color(0xFF1D1C0C),
            Color(0xFFFFB1C8),
            Color(0xFF5E1133),
            Color(0xFF7B2949),
            Color(0xFFFFD9E4),
            Color(0xFF514346),
            Color(0xFFF4DDDF),
            Color(0xFF6A6243),
            Color(0xFFE5E0B8),
        ),
        ExpressivePalette(
            "teal",
            "Teal",
            Color(0xFF006A60),
            Color(0xFFFFFFFF),
            Color(0xFF9EF2E4),
            Color(0xFF00201C),
            Color(0xFFC9E8E1),
            Color(0xFF051F1B),
            Color(0xFFC4EED5),
            Color(0xFF002112),
            Color(0xFF82D5C8),
            Color(0xFF003731),
            Color(0xFF005048),
            Color(0xFF9EF2E4),
            Color(0xFF334B46),
            Color(0xFFC9E8E1),
            Color(0xFF4C6357),
            Color(0xFFC4EED5),
        ),
    )

/** The full base schemes, exposed so palette selection can restyle accent roles. */
internal fun buildLightScheme(): ColorScheme =
    lightColorScheme(
        primary = PrimaryLight,
        onPrimary = OnPrimaryLight,
        primaryContainer = PrimaryContainerLight,
        onPrimaryContainer = OnPrimaryContainerLight,
        secondary = SecondaryLight,
        onSecondary = OnSecondaryLight,
        secondaryContainer = SecondaryContainerLight,
        onSecondaryContainer = OnSecondaryContainerLight,
        tertiary = TertiaryLight,
        onTertiary = OnTertiaryLight,
        tertiaryContainer = TertiaryContainerLight,
        onTertiaryContainer = OnTertiaryContainerLight,
        error = ErrorLight,
        onError = OnErrorLight,
        errorContainer = ErrorContainerLight,
        onErrorContainer = OnErrorContainerLight,
        background = BackgroundLight,
        onBackground = OnBackgroundLight,
        surface = SurfaceLight,
        onSurface = OnSurfaceLight,
        surfaceVariant = SurfaceVariantLight,
        onSurfaceVariant = OnSurfaceVariantLight,
        outline = OutlineLight,
        outlineVariant = OutlineVariantLight,
        surfaceContainerLowest = SurfaceContainerLowestLight,
        surfaceContainerLow = SurfaceContainerLowLight,
        surfaceContainer = SurfaceContainerLight,
        surfaceContainerHigh = SurfaceContainerHighLight,
        surfaceContainerHighest = SurfaceContainerHighestLight,
    )

internal fun buildDarkScheme(): ColorScheme =
    darkColorScheme(
        primary = PrimaryDark,
        onPrimary = OnPrimaryDark,
        primaryContainer = PrimaryContainerDark,
        onPrimaryContainer = OnPrimaryContainerDark,
        secondary = SecondaryDark,
        onSecondary = OnSecondaryDark,
        secondaryContainer = SecondaryContainerDark,
        onSecondaryContainer = OnSecondaryContainerDark,
        tertiary = TertiaryDark,
        onTertiary = OnTertiaryDark,
        tertiaryContainer = TertiaryContainerDark,
        onTertiaryContainer = OnTertiaryContainerDark,
        error = ErrorDark,
        onError = OnErrorDark,
        errorContainer = ErrorContainerDark,
        onErrorContainer = OnErrorContainerDark,
        background = BackgroundDark,
        onBackground = OnBackgroundDark,
        surface = SurfaceDark,
        onSurface = OnSurfaceDark,
        surfaceVariant = SurfaceVariantDark,
        onSurfaceVariant = OnSurfaceVariantDark,
        outline = OutlineDark,
        outlineVariant = OutlineVariantDark,
        surfaceContainerLowest = SurfaceContainerLowestDark,
        surfaceContainerLow = SurfaceContainerLowDark,
        surfaceContainer = SurfaceContainerDark,
        surfaceContainerHigh = SurfaceContainerHighDark,
        surfaceContainerHighest = SurfaceContainerHighestDark,
    )

/** Applies a palette's accent roles on top of the neutral base scheme. */
internal fun ColorScheme.withPalette(
    p: ExpressivePalette,
    dark: Boolean,
): ColorScheme =
    if (dark) {
        copy(
            primary = p.primaryDark,
            onPrimary = p.onPrimaryDark,
            primaryContainer = p.primaryContainerDark,
            onPrimaryContainer = p.onPrimaryContainerDark,
            secondary = p.primaryDark,
            onSecondary = Color.Black,
            secondaryContainer = p.secondaryContainerDark,
            onSecondaryContainer = p.onSecondaryContainerDark,
            tertiary = p.primaryDark,
            onTertiary = Color.Black,
            tertiaryContainer = p.tertiaryContainerDark,
            onTertiaryContainer = p.onTertiaryContainerDark,
        )
    } else {
        copy(
            primary = p.primaryLight,
            onPrimary = p.onPrimaryLight,
            primaryContainer = p.primaryContainerLight,
            onPrimaryContainer = p.onPrimaryContainerLight,
            secondary = p.primaryLight,
            onSecondary = Color.White,
            secondaryContainer = p.secondaryContainerLight,
            onSecondaryContainer = p.onSecondaryContainerLight,
            tertiary = p.primaryLight,
            onTertiary = Color.White,
            tertiaryContainer = p.tertiaryContainerLight,
            onTertiaryContainer = p.onTertiaryContainerLight,
        )
    }

/** Semantic answer-button colors (Again/Hard/Good/Easy) for the current mode. */
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

private val AnswerColorsLightSet =
    AnswerColors(
        AgainContainerLight,
        OnAgainContainerLight,
        HardContainerLight,
        OnHardContainerLight,
        GoodContainerLight,
        OnGoodContainerLight,
        EasyContainerLight,
        OnEasyContainerLight,
    )

private val AnswerColorsDarkSet =
    AnswerColors(
        AgainContainerDark,
        OnAgainContainerDark,
        HardContainerDark,
        OnHardContainerDark,
        GoodContainerDark,
        OnGoodContainerDark,
        EasyContainerDark,
        OnEasyContainerDark,
    )

/** Provides mode-correct answer colors anywhere below the theme. */
val LocalAnswerColors =
    staticCompositionLocalOf {
        AnswerColorsLightSet
    }

internal fun answerColorsFor(dark: Boolean): AnswerColors = if (dark) AnswerColorsDarkSet else AnswerColorsLightSet
