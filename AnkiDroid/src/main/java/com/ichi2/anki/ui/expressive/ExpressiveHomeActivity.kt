// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.ui.expressive

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.lifecycleScope
import com.ichi2.anki.DeckPicker
import com.ichi2.anki.account.AccountActivity
import com.ichi2.anki.isLoggedIn
import com.ichi2.anki.shouldFetchMedia
import com.ichi2.anki.syncAuth
import com.ichi2.anki.ui.expressive.theme.ExpressiveAnkiTheme
import com.ichi2.anki.ui.expressive.theme.isExpressiveDark
import com.ichi2.anki.worker.SyncWorker
import kotlinx.coroutines.launch

/**
 * Hosts the from-scratch Material 3 Expressive Compose UI.
 * Data shown is sample data until the collection is wired in.
 */
class ExpressiveHomeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val initialSettings = ExpressiveSettings.load(this@ExpressiveHomeActivity)
        initialSettings.syncToClassicThemePrefs()
        setContent {
            var settings =
                remember {
                    mutableStateOf(initialSettings)
                } // Debug overrides for quick theme verification (e.g. adb --es palette rose --ez dynamic false)
            intent.getStringExtra("palette")?.let { settings.value = settings.value.copy(paletteKey = it) }
            if (intent.hasExtra("dynamic")) {
                settings.value = settings.value.copy(dynamicColor = intent.getBooleanExtra("dynamic", true))
            }
            if (intent.hasExtra("theme")) {
                settings.value = settings.value.copy(themeMode = intent.getStringExtra("theme") ?: "system")
            }
            ExpressiveAnkiTheme(
                themeMode = settings.value.themeMode,
                paletteKey = settings.value.paletteKey,
                dynamicColor = settings.value.dynamicColor,
            ) {
                val effectiveDark = isExpressiveDark(settings.value.themeMode)
                LaunchedEffect(effectiveDark) {
                    enableEdgeToEdge(
                        statusBarStyle =
                            if (effectiveDark) {
                                SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                            } else {
                                SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                            },
                        navigationBarStyle =
                            if (effectiveDark) {
                                SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                            } else {
                                SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                            },
                    )
                }
                ExpressiveApp(
                    initialDeck = null,
                    settings = settings.value,
                    onSettingsChanged = {
                        it.save(this)
                        it.syncToClassicThemePrefs()
                        settings.value = it
                    },
                    onOpenClassicUi = {
                        startActivity(
                            Intent(this, DeckPicker::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            },
                        )
                    },
                    onSync = {
                        lifecycleScope.launch {
                            try {
                                if (!isLoggedIn()) {
                                    startActivity(AccountActivity.getIntent(this@ExpressiveHomeActivity))
                                    Toast.makeText(this@ExpressiveHomeActivity, "Sign in to AnkiWeb first", Toast.LENGTH_SHORT).show()
                                } else {
                                    val auth = syncAuth()
                                    if (auth == null) {
                                        Toast
                                            .makeText(
                                                this@ExpressiveHomeActivity,
                                                "Sync authentication unavailable",
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                    } else {
                                        SyncWorker.start(applicationContext, auth, shouldFetchMedia())
                                        Toast.makeText(this@ExpressiveHomeActivity, "Sync started", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            } catch (e: Exception) {
                                Toast.makeText(this@ExpressiveHomeActivity, "Sync failed: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                )
            }
        }
    }
}
