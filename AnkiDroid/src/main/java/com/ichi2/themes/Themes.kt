// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: Copyright (c) 2011 Norbert Nagold <norbert.nagold@gmail.com>
// SPDX-FileCopyrightText: Copyright (c) 2015 Timothy Rae <perceptualchaos2@gmail.com>
// SPDX-FileCopyrightText: Copyright (c) 2021 Akshay Jadhav <jadhavakshay0701@gmail.com>

package com.ichi2.themes

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import com.google.android.material.color.DynamicColors
import com.ichi2.anki.BuildConfig
import com.ichi2.anki.R
import com.ichi2.anki.common.utils.android.systemIsInNightMode
import com.ichi2.anki.settings.Prefs
import com.ichi2.anki.settings.PrefsRepository
import com.ichi2.anki.settings.enums.AppTheme
import com.ichi2.anki.settings.enums.DayTheme
import com.ichi2.anki.settings.enums.NightTheme
import com.ichi2.anki.settings.enums.Theme
import com.ichi2.anki.ui.expressive.ExpressiveSettings
import com.ichi2.themes.Themes.currentTheme
import timber.log.Timber

/**
 * Helper methods to configure things related to AnkiDroid's themes
 */
object Themes {
    const val ALPHA_ICON_ENABLED_LIGHT = 255 // 100%
    const val ALPHA_ICON_DISABLED_LIGHT = 76 // 31%

    var currentTheme: Theme = DayTheme.LIGHT
    val isNightTheme: Boolean get() = currentTheme is NightTheme

    fun setTheme(context: Context) {
        updateCurrentTheme(context)
        context.setTheme(currentTheme.styleResId)
        applyColorPalette(context)
    }

    /**
     * Applies the user-selected Material 3 color palette on top of the base
     * theme. The default palette ("blue") needs no overlay.
     */
    private fun applyColorPalette(context: Context) {
        if (currentTheme == DayTheme.EINK) return // keep e-ink monochrome
        val overlayResId =
            when (Prefs.colorPalette to isNightTheme) {
                "violet" to false -> R.style.ThemeOverlay_Palette_Violet_light
                "violet" to true -> R.style.ThemeOverlay_Palette_Violet_dark
                "green" to false -> R.style.ThemeOverlay_Palette_Green_light
                "green" to true -> R.style.ThemeOverlay_Palette_Green_dark
                "amber" to false -> R.style.ThemeOverlay_Palette_Amber_light
                "amber" to true -> R.style.ThemeOverlay_Palette_Amber_dark
                "rose" to false -> R.style.ThemeOverlay_Palette_Rose_light
                "rose" to true -> R.style.ThemeOverlay_Palette_Rose_dark
                "teal" to false -> R.style.ThemeOverlay_Palette_Teal_light
                "teal" to true -> R.style.ThemeOverlay_Palette_Teal_dark
                else -> return
            }
        context.theme.applyStyle(overlayResId, true)
    }

    /**
     * Applies Material You wallpaper colors on top of the base theme and palette when
     * the expressive UI's dynamic-color setting is enabled. Runs LAST so wallpaper
     * colors win over the static palette for legacy (View-based) screens.
     */
    private fun applyDynamicColor(context: Context) {
        if (currentTheme == DayTheme.EINK) return
        if (context !is Activity) return
        if (!ExpressiveSettings.load(context).dynamicColor) return
        val overlayRes =
            if (isNightTheme) {
                com.google.android.material.R.style.ThemeOverlay_Material3_DynamicColors_Dark
            } else {
                com.google.android.material.R.style.ThemeOverlay_Material3_DynamicColors_Light
            }
        DynamicColors.applyToActivityIfAvailable(
            context,
            com.google.android.material.color.DynamicColorsOptions
                .Builder()
                .setThemeOverlay(overlayRes)
                .build(),
        )
    }

    /**
     * @param savedInstanceState the bundle provided to [Activity.onCreate]
     */
    fun setTheme(
        activity: Activity,
        savedInstanceState: Bundle?,
    ) {
        setTheme(activity)
        applyDynamicColor(activity)
        val tv = TypedValue()
        activity.theme.resolveAttribute(android.R.attr.windowBackground, tv, true)
        val hadLauncherSplash = tv.resourceId == R.drawable.launch_screen

        // If the decor view already exists, `windowBackground` can no longer be updated by setTheme
        // `hadLauncherSplash` is exempt: its window background is replaced below.
        // Exclude recreation: the decor view can exist before `onCreate`, but the framework
        // refreshes `windowBackground`.
        val isRecreation = savedInstanceState != null
        if (!isRecreation && !hadLauncherSplash && activity.window.peekDecorView() != null) {
            val message =
                "Decor view was initialized before setTheme(): windowBackground is stale. " +
                    "Move window access (e.g. enableEdgeToEdge()) after super.onCreate()"
            if (BuildConfig.DEBUG) throw IllegalStateException(message) else Timber.w(message)
        }

        setTheme(activity as Context)

        if (hadLauncherSplash) {
            activity.theme.resolveAttribute(android.R.attr.windowBackground, tv, true)
            val replacement =
                if (tv.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT) {
                    tv.data.toDrawable()
                } else {
                    AppCompatResources.getDrawable(activity, tv.resourceId)
                }
            activity.window.setBackgroundDrawable(replacement)
        }
    }

    /**
     * Updates [currentTheme] value based on preferences.
     * If `Follow system` is selected, it's updated to the theme set
     * on `Day` or `Night` theme according to system's current mode
     * Otherwise, updates to the selected theme.
     */
    fun updateCurrentTheme(context: Context) {
        val prefs = PrefsRepository(context)
        val appTheme = prefs.appTheme

        val themeIsDark = (appTheme == AppTheme.FOLLOW_SYSTEM && systemIsInNightMode(context)) || appTheme == AppTheme.NIGHT
        currentTheme =
            if (themeIsDark) {
                prefs.nightTheme
            } else {
                prefs.dayTheme
            }
        val defaultNightMode =
            when (appTheme) {
                AppTheme.FOLLOW_SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                AppTheme.DAY -> AppCompatDelegate.MODE_NIGHT_NO
                AppTheme.NIGHT -> AppCompatDelegate.MODE_NIGHT_YES
            }
        AppCompatDelegate.setDefaultNightMode(defaultNightMode)
    }
}

@Suppress("deprecation", "API35 properly handle edge-to-edge")
fun FragmentActivity.setTransparentStatusBar() {
    WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars =
        Themes.currentTheme !is NightTheme
    window.statusBarColor = Color.TRANSPARENT
}

fun FragmentActivity.setTransparentBackground() {
    window.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
}
