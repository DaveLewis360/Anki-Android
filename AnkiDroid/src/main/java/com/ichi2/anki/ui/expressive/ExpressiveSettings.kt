// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.ui.expressive

import android.content.Context

/** User-visible settings of the expressive UI, persisted in a private prefs file. */
data class ExpressiveSettings(
    val themeMode: String = "system", // system | light | dark
    val paletteKey: String = "blue",
    val dynamicColor: Boolean = true,
    // Study-screen gestures (GestureAction keys)
    val gestureTapQuestion: String = "show_answer",
    val gestureTapAnswer: String = "good",
    val gestureSwipeLeft: String = "again",
    val gestureSwipeRight: String = "good",
    val gestureDoubleTap: String = "mark",
    val gestureLongPress: String = "menu",
) {
    companion object {
        private const val FILE = "expressive_ui"
        private const val KEY_THEME = "themeMode"
        private const val KEY_PALETTE = "paletteKey"
        private const val KEY_DYNAMIC = "dynamicColor"
        private const val KEY_G_TAP_Q = "gestureTapQuestion"
        private const val KEY_G_TAP_A = "gestureTapAnswer"
        private const val KEY_G_SWIPE_L = "gestureSwipeLeft"
        private const val KEY_G_SWIPE_R = "gestureSwipeRight"
        private const val KEY_G_DOUBLE = "gestureDoubleTap"
        private const val KEY_G_LONG = "gestureLongPress"

        fun load(context: Context): ExpressiveSettings {
            val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            return ExpressiveSettings(
                themeMode = prefs.getString(KEY_THEME, "system") ?: "system",
                paletteKey = prefs.getString(KEY_PALETTE, "blue") ?: "blue",
                dynamicColor = prefs.getBoolean(KEY_DYNAMIC, true),
                gestureTapQuestion = prefs.getString(KEY_G_TAP_Q, "show_answer") ?: "show_answer",
                gestureTapAnswer = prefs.getString(KEY_G_TAP_A, "good") ?: "good",
                gestureSwipeLeft = prefs.getString(KEY_G_SWIPE_L, "again") ?: "again",
                gestureSwipeRight = prefs.getString(KEY_G_SWIPE_R, "good") ?: "good",
                gestureDoubleTap = prefs.getString(KEY_G_DOUBLE, "mark") ?: "mark",
                gestureLongPress = prefs.getString(KEY_G_LONG, "menu") ?: "menu",
            )
        }
    }

    fun save(context: Context) {
        context
            .getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME, themeMode)
            .putString(KEY_PALETTE, paletteKey)
            .putBoolean(KEY_DYNAMIC, dynamicColor)
            .putString(KEY_G_TAP_Q, gestureTapQuestion)
            .putString(KEY_G_TAP_A, gestureTapAnswer)
            .putString(KEY_G_SWIPE_L, gestureSwipeLeft)
            .putString(KEY_G_SWIPE_R, gestureSwipeRight)
            .putString(KEY_G_DOUBLE, gestureDoubleTap)
            .putString(KEY_G_LONG, gestureLongPress)
            .apply()
    }

    /**
     * Mirrors these appearance settings into the classic XML theme preferences so
     * legacy screens (note editor, reviewer, classic dialogs) render with the same
     * palette and light/dark mode as the expressive Compose UI.
     */
    fun syncToClassicThemePrefs() {
        com.ichi2.anki.settings.Prefs.colorPalette = paletteKey
        com.ichi2.anki.settings.Prefs.appTheme =
            when (themeMode) {
                "system" -> com.ichi2.anki.settings.enums.AppTheme.FOLLOW_SYSTEM
                "dark" -> com.ichi2.anki.settings.enums.AppTheme.NIGHT
                else -> com.ichi2.anki.settings.enums.AppTheme.DAY
            }
        // The expressive dark scheme is the charcoal (#101418) variant — never pure
        // black — so legacy screens match the Compose UI in night mode.
        com.ichi2.anki.settings.Prefs.nightTheme =
            com.ichi2.anki.settings.enums.NightTheme.DARK
        if (themeMode == "light") {
            com.ichi2.anki.settings.Prefs.dayTheme =
                com.ichi2.anki.settings.enums.DayTheme.LIGHT
        }
    }
}
