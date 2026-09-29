// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.ui.expressive

import android.content.Intent
import android.util.TypedValue
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.settings.PrefsRepository
import com.ichi2.anki.settings.enums.AppTheme
import com.ichi2.anki.settings.enums.NightTheme
import com.ichi2.compose.theme.toMaterial3ColorScheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class ExpressiveImportPageActivityTest : RobolectricTest() {
    @Test
    fun `the import page host shows the user's dark theme, not the application or launcher theme`() {
        // The application theme is light: only the user's theme, applied in onCreate, is dark.
        PrefsRepository(targetContext).apply {
            appTheme = AppTheme.NIGHT
            nightTheme = NightTheme.DARK
        }
        ExpressiveSettings.load(targetContext).copy(dynamicColor = false).save(targetContext)

        // Without an owned file the host finishes cancelled, after its theme is applied.
        val activity =
            Robolectric
                .buildActivity(ExpressiveImportPageActivity::class.java, Intent(targetContext, ExpressiveImportPageActivity::class.java))
                .create()
                .get()

        assertTrue(activity.isFinishing)
        assertEquals(ContextCompat.getColor(targetContext, R.color.m3_primary_dark), activity.toMaterial3ColorScheme().primary.toArgb())
        val background = TypedValue()
        activity.theme.resolveAttribute(android.R.attr.windowBackground, background, true)
        assertTrue(background.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT, "a plain window background")
    }
}
