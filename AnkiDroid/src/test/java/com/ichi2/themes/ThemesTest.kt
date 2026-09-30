// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.themes

import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.ContextThemeWrapper
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.color.DynamicColors
import com.ichi2.anki.AnkiActivity
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.StudyOptionsActivity
import com.ichi2.anki.settings.PrefsRepository
import com.ichi2.anki.settings.enums.AppTheme
import com.ichi2.anki.settings.enums.DayTheme
import com.ichi2.anki.settings.enums.NightTheme
import com.ichi2.anki.ui.expressive.ExpressiveSettings
import com.ichi2.compose.theme.toMaterial3ColorScheme
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import kotlin.test.assertFailsWith
import com.google.android.material.R as MaterialR

@RunWith(AndroidJUnit4::class)
class ThemesTest : RobolectricTest() {
    /**
     * If the decor view is created before [Themes.setTheme] applies the user's theme, the theme's
     * windowBackground is not applied and night mode renders a white background (issue 21520).
     * Ensure this fails fast in debug builds rather than rendering incorrectly.
     */
    @Test
    fun `decor view access before setTheme fails fast`() {
        val exception =
            assertFailsWith<IllegalStateException> {
                Robolectric.buildActivity(EarlyDecorViewInitActivity::class.java).create()
            }
        assertThat(exception.message, containsString("setTheme"))
    }

    @Test
    fun `window background follows the night theme - issue 21520`() {
        RuntimeEnvironment.setQualifiers("+night")
        PrefsRepository(targetContext).apply {
            appTheme = AppTheme.NIGHT
            nightTheme = NightTheme.DARK
        }
        val activity =
            startActivityNormallyOpenCollectionWithIntent(
                StudyOptionsActivity::class.java,
                Intent(),
            )

        val tv = TypedValue()
        activity.theme.resolveAttribute(android.R.attr.windowBackground, tv, true)
        assertThat(
            "the theme's windowBackground is expected to be a plain color",
            tv.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT,
            equalTo(true),
        )
        val decorBackground = activity.window.decorView.background
        assertThat((decorBackground as ColorDrawable).color, equalTo(tv.data))
    }

    /**
     * When an activity is relaunched (e.g. after a day/night theme change), the framework may
     * preserve the window: the decor view already exists before `super.onCreate`, and the
     * framework refreshes its `windowBackground` itself, so [Themes.setTheme] should not fail
     * (issue 21548).
     */
    @Test
    fun `recreated activity with an existing decor view does not fail - issue 21548`() {
        Robolectric.buildActivity(EarlyDecorViewInitActivity::class.java).create(Bundle())
    }

    /** simulates e.g. an [androidx.activity.enableEdgeToEdge] call before `super.onCreate` */
    class EarlyDecorViewInitActivity : AnkiActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            window.decorView
            super.onCreate(savedInstanceState)
        }
    }

    /**
     * A palette picked while dynamic color was off stays saved when dynamic color is switched back
     * on. Wallpaper colors are applied after it, so classic screens follow the wallpaper, as the
     * Compose UI does.
     */
    @Test
    fun `dynamic color wins over a saved static palette`() {
        assumeTrue("wallpaper colors need Android 12 or later", DynamicColors.isDynamicColorAvailable())
        PrefsRepository(targetContext).apply {
            appTheme = AppTheme.DAY
            dayTheme = DayTheme.LIGHT
            colorPalette = "rose"
        }
        ExpressiveSettings.load(targetContext).copy(dynamicColor = true).save(targetContext)

        val activity = Robolectric.buildActivity(ThemedActivity::class.java).create().get()

        val wallpaper = ContextCompat.getColor(activity, MaterialR.color.m3_sys_color_dynamic_light_primary)
        val rose = ContextCompat.getColor(activity, R.color.palette_rose_primary_light)
        assumeTrue("the wallpaper primary differs from the palette primary", wallpaper != rose)
        assertThat(activity.toMaterial3ColorScheme().primary.toArgb(), equalTo(wallpaper))

        // Dialogs such as the TTS voices dialog apply the theme to their host activity again.
        Themes.setTheme(activity)
        assertThat(activity.toMaterial3ColorScheme().primary.toArgb(), equalTo(wallpaper))
    }

    /**
     * An activity that starts from the launcher splash theme, as DeckPicker does, shows the user's
     * window background, even when its window style was read while the splash theme was active.
     * With dynamic color on, the replacement also has to come after the wallpaper colors.
     */
    @Test
    fun `the launcher splash background is replaced by the user's theme background`() {
        PrefsRepository(targetContext).apply {
            appTheme = AppTheme.DAY
            dayTheme = DayTheme.LIGHT
        }
        ExpressiveSettings.load(targetContext).copy(dynamicColor = true).save(targetContext)

        val activity = Robolectric.buildActivity(LauncherThemedActivity::class.java).create().get()

        val tv = TypedValue()
        activity.theme.resolveAttribute(android.R.attr.windowBackground, tv, true)
        val background = activity.window.decorView.background
        assertThat("the splash drawable is replaced by a plain color, was $background", background is ColorDrawable, equalTo(true))
        assertThat((background as ColorDrawable).color, equalTo(tv.data))

        // The replacement also comes after the wallpaper colors.
        if (DynamicColors.isDynamicColorAvailable()) {
            val wallpaper = ContextCompat.getColor(activity, MaterialR.color.m3_sys_color_dynamic_light_background)
            val static = TypedValue()
            ContextThemeWrapper(targetContext, R.style.Theme_Light).theme.resolveAttribute(android.R.attr.windowBackground, static, true)
            assumeTrue("the wallpaper background differs from the static one", wallpaper != static.data)
            assertThat("the window background follows the wallpaper", background.color, equalTo(wallpaper))
        }
    }

    class ThemedActivity : AnkiActivity()

    /** Starts from the launcher splash theme and reads its window style, as the platform may do early. */
    class LauncherThemedActivity : AnkiActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_Dark_Launcher)
            window.windowStyle
            super.onCreate(savedInstanceState)
        }
    }
}
