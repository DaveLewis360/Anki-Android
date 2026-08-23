// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.ui.expressive.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ichi2.anki.R
import com.ichi2.anki.common.ui.TransitionDirection
import com.ichi2.anki.noteeditor.NoteEditorLauncher
import com.ichi2.anki.ui.expressive.ExpressiveCollection
import com.ichi2.anki.ui.expressive.UiCard
import com.ichi2.anki.ui.expressive.UiCardState
import com.ichi2.anki.ui.expressive.theme.PillShape
import kotlinx.coroutines.delay

private val filters = listOf("All", "Due", "New", "Suspended", "Marked")

/** Browse: real collection search, filter chips, card rows with preview + edit. */
@Composable
fun BrowseScreen() {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("All") }
    var cards by remember { mutableStateOf<List<UiCard>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var preview by remember { mutableStateOf<UiCard?>(null) }

    LaunchedEffect(query, filter) {
        delay(300)
        loading = true
        cards =
            try {
                ExpressiveCollection.searchCards(query, filter)
            } catch (e: Exception) {
                emptyList()
            }
        loading = false
    }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding =
                androidx.compose.foundation.layout
                    .PaddingValues(top = 20.dp, bottom = 120.dp),
        ) {
            item {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    Text(
                        "Browse",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Search cards…") },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_search_white), contentDescription = null) },
                        shape = PillShape,
                        singleLine = true,
                        colors =
                            OutlinedTextFieldDefaults.colors(
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                unfocusedBorderColor = Color.Transparent,
                                focusedBorderColor = Color.Transparent,
                            ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        filters.forEach { f ->
                            val selected = f == filter
                            Surface(
                                shape = PillShape,
                                color =
                                    if (selected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerHigh
                                    },
                                contentColor =
                                    if (selected) {
                                        MaterialTheme.colorScheme.onPrimary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                modifier =
                                    Modifier.clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                    ) { filter = f },
                            ) {
                                Text(
                                    f,
                                    style = MaterialTheme.typography.labelLarge,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
            }
            when {
                loading ->
                    item {
                        Column(
                            Modifier.fillMaxWidth().padding(top = 60.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                cards.isEmpty() ->
                    item {
                        Text(
                            if (query.isBlank() && filter == "All") "No cards in the collection yet." else "No cards match.",
                            Modifier.fillMaxWidth().padding(top = 60.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                else ->
                    items(cards, key = { it.cardId }) { card ->
                        BrowseRow(card = card, onClick = { preview = card })
                    }
            }
        }
    }

    preview?.let { card ->
        CardPreviewDialog(
            card = card,
            onDismiss = { preview = null },
            onEdit = {
                val intent =
                    NoteEditorLauncher
                        .EditSelection(
                            cardIds = listOf(card.cardId),
                            animation = TransitionDirection.FADE,
                        ).toIntent(context)
                context.startActivity(intent)
                preview = null
            },
        )
    }
}

@Composable
private fun BrowseRow(
    card: UiCard,
    onClick: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 5.dp)
                .clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(card.state.color()),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    card.question.ifBlank { card.answer },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    card.deckName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(12.dp))
            Surface(shape = PillShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                Text(
                    card.dueLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun CardPreviewDialog(
    card: UiCard,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(card.deckName, style = MaterialTheme.typography.titleMedium)
        },
        text = {
            Column {
                Text(
                    card.question,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    card.answer,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onEdit) { Text("Edit") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

private fun UiCardState.color(): Color =
    when (this) {
        UiCardState.NEW -> Color(0xFF7DD3FC)
        UiCardState.LEARNING -> Color(0xFFFDBA74)
        UiCardState.REVIEW -> Color(0xFF86EFAC)
        UiCardState.SUSPENDED -> Color(0xFFCBD5E1)
    }
