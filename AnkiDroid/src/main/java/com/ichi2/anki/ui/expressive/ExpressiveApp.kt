// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.ui.expressive

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import com.ichi2.anki.pages.toIntent
import com.ichi2.anki.ui.expressive.components.ExpressiveBottomBar
import com.ichi2.anki.ui.expressive.screens.BrowseScreen
import com.ichi2.anki.ui.expressive.screens.HomeScreen
import com.ichi2.anki.ui.expressive.screens.MoreScreen
import com.ichi2.anki.ui.expressive.screens.SettingsScreen
import com.ichi2.anki.ui.expressive.screens.StatsScreen
import com.ichi2.anki.ui.expressive.screens.StudyScreen

/**
 * Root composable: tabbed shell with a full-screen study overlay.
 *
 * All tabs stay composed (state preserved: scroll, deck expansion, search)
 * and switch with a soft fade + horizontal shift; the selected tab is raised
 * above the others so only it receives input.
 */
@Composable
fun ExpressiveApp(
    onOpenClassicUi: () -> Unit,
    settings: ExpressiveSettings,
    onSettingsChanged: (ExpressiveSettings) -> Unit,
    onSync: () -> Unit = {},
    initialDeck: Pair<com.ichi2.anki.libanki.DeckId, String>? = null,
) {
    var tab by remember { mutableIntStateOf(0) }
    var studyDeck by remember { mutableStateOf(initialDeck) }
    var showSettings by remember { mutableStateOf(false) }
    var homeRefresh by remember { mutableIntStateOf(0) }
    val context = androidx.compose.ui.platform.LocalContext.current
    BackHandler(enabled = showSettings) { showSettings = false }
    BackHandler(enabled = studyDeck != null) { studyDeck = null }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .clipToBounds(),
            ) {
                TabLayer(tabIndex = 0, currentTab = tab) {
                    HomeScreen(
                        onDeckClick = { did, name -> studyDeck = did to name },
                        onAddCard = {
                            context.startActivity(
                                com.ichi2.anki.noteeditor.NoteEditorLauncher
                                    .AddNote()
                                    .toIntent(context),
                            )
                        },
                        onDeckOptions = { did, filtered ->
                            context.startActivity(
                                com.ichi2.anki.common.destinations
                                    .DeckOptionsDestination(
                                        deckId = did,
                                        isFiltered = filtered,
                                    ).toIntent(context),
                            )
                        },
                        refreshKey = homeRefresh,
                    )
                }
                TabLayer(tabIndex = 1, currentTab = tab) {
                    BrowseScreen()
                }
                TabLayer(tabIndex = 2, currentTab = tab) {
                    StatsScreen()
                }
                TabLayer(tabIndex = 3, currentTab = tab) {
                    MoreScreen(
                        onOpenSettings = { showSettings = true },
                        onOpenClassicUi = onOpenClassicUi,
                        onSync = onSync,
                    )
                }
            }
            ExpressiveBottomBar(selectedIndex = tab, onSelect = { tab = it })
        }

        // Settings overlay slides up over everything
        AnimatedVisibility(
            visible = showSettings,
            enter = slideInVertically(tween(280)) { it } + fadeIn(tween(220)),
            exit = slideOutVertically(tween(220)) { it } + fadeOut(tween(160)),
        ) {
            Surface(color = MaterialTheme.colorScheme.background) {
                SettingsScreen(
                    settings = settings,
                    onChange = onSettingsChanged,
                    onBack = { showSettings = false },
                )
            }
        }

        // Study overlay slides over everything — opaque, above the nav bar
        AnimatedVisibility(
            visible = studyDeck != null,
            enter = scaleIn(tween(260), initialScale = 0.94f) + fadeIn(tween(200)),
            exit = fadeOut(tween(180)),
        ) {
            studyDeck?.let { (deckId, deckName) ->
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    StudyScreen(
                        deckId = deckId,
                        deckName = deckName,
                        onClose = {
                            studyDeck = null
                            homeRefresh++
                        },
                    )
                }
            }
        }
    }
}

/** One always-composed tab page; fades and shifts instead of being recreated. */
@Composable
private fun TabLayer(
    tabIndex: Int,
    currentTab: Int,
    content: @Composable () -> Unit,
) {
    val selected = tabIndex == currentTab
    val transition = updateTransition(targetState = selected, label = "tab$tabIndex")
    val alpha by transition.animateFloat(
        transitionSpec = { tween(if (targetState) 220 else 150) },
        label = "alpha$tabIndex",
    ) { if (it) 1f else 0f }
    val shiftFraction by transition.animateFloat(
        transitionSpec = { tween(240) },
        label = "shift$tabIndex",
    ) { visible ->
        if (visible) 0f else ((tabIndex - currentTab).coerceIn(-1, 1)).toFloat()
    }
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                this.alpha = alpha
                translationX = shiftFraction * size.width * 0.12f
            }.zIndex(if (selected) 1f else 0f),
    ) {
        content()
    }
}
