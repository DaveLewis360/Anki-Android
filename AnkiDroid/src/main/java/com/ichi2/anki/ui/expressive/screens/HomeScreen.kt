// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.ui.expressive.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ichi2.anki.libanki.DeckId
import com.ichi2.anki.ui.expressive.ExpressiveCollection
import com.ichi2.anki.ui.expressive.UiDeck
import com.ichi2.anki.ui.expressive.components.DeckCard
import com.ichi2.anki.ui.expressive.components.GreetingHeader
import com.ichi2.anki.ui.expressive.components.TodaySummaryCard
import kotlinx.coroutines.launch

/** Home: greeting, today's summary and the REAL deck list from the collection. */
@Composable
fun HomeScreen(onDeckClick: (deckId: DeckId, deckName: String) -> Unit) {
    var decks by remember { mutableStateOf<List<UiDeck>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun reload() {
        scope.launch {
            try {
                decks = ExpressiveCollection.loadDecks()
                error = null
            } catch (e: Exception) {
                error = e.message ?: "Failed to load decks"
            }
        }
    }

    LaunchedEffect(Unit) { reload() }

    val loaded =
        decks ?: run {
            LoadingPane(error)
            return
        }

    val flat =
        remember(loaded) {
            buildList {
                loaded.forEach { deck ->
                    add(deck)
                    if (!deck.collapsed) {
                        deck.children.forEach { add(it) }
                    }
                }
            }
        }
    val totalNew = loaded.sumOf { it.newCount }
    val totalLearn = loaded.sumOf { it.learnCount }
    val totalDue = loaded.sumOf { it.dueCount }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 20.dp, bottom = 120.dp),
    ) {
        item {
            Column(Modifier.padding(horizontal = 20.dp)) {
                GreetingHeader()
                Spacer(Modifier.height(20.dp))
                if (loaded.isEmpty()) {
                    Text(
                        "No decks yet — add one from the Classic UI",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    TodaySummaryCard(
                        newCount = totalNew,
                        learnCount = totalLearn,
                        dueCount = totalDue,
                        onStartStudying = {
                            loaded
                                .firstOrNull { it.dueCount + it.learnCount + it.newCount > 0 }
                                ?.let { onDeckClick(it.did, it.name) }
                        },
                    )
                }
                Spacer(Modifier.height(24.dp))
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Decks",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "${loaded.size} decks",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
        }
        items(flat, key = { it.did }) { deck ->
            DeckCard(
                deck = deck,
                selected = false,
                onClick = {
                    if (deck.children.isNotEmpty()) {
                        scope.launch {
                            ExpressiveCollection.setCollapsed(deck.did, !deck.collapsed)
                            decks = ExpressiveCollection.loadDecks()
                        }
                    } else {
                        onDeckClick(deck.did, deck.name)
                    }
                },
            )
        }
    }
}

@Composable
private fun LoadingPane(error: String?) {
    Column(
        Modifier.fillMaxSize().padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (error == null) {
            Text(
                "Loading collection…",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                "Couldn't open the collection:\n$error",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
