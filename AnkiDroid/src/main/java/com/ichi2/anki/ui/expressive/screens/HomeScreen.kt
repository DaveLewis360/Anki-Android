// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.ui.expressive.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import anki.scheduler.CustomStudyRequest.Cram.CramKind
import com.ichi2.anki.R
import com.ichi2.anki.libanki.DeckId
import com.ichi2.anki.ui.expressive.ExpressiveCollection
import com.ichi2.anki.ui.expressive.ExpressiveCustomStudyAction
import com.ichi2.anki.ui.expressive.ExpressiveCustomStudyDefaults
import com.ichi2.anki.ui.expressive.ExpressiveDeckDeletePreview
import com.ichi2.anki.ui.expressive.ExpressiveDeckGateway
import com.ichi2.anki.ui.expressive.ExpressiveMaintenanceMode
import com.ichi2.anki.ui.expressive.UiDeck
import com.ichi2.anki.ui.expressive.components.DeckCard
import com.ichi2.anki.ui.expressive.components.ExpressiveFab
import com.ichi2.anki.ui.expressive.components.GreetingHeader
import com.ichi2.anki.ui.expressive.components.TodaySummaryCard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Native deck list, hierarchy management, filtered-deck actions, and Custom Study. */
@Composable
fun HomeScreen(
    onDeckClick: (deckId: DeckId, deckName: String) -> Unit,
    onAddCard: (deckId: DeckId?) -> Unit,
    onDeckOptions: (deckId: DeckId, isFiltered: Boolean) -> Unit,
    onImport: () -> Unit = {},
    onMaintenance: (ExpressiveMaintenanceMode) -> Unit = {},
    onExportDeck: (deckId: DeckId, deckName: String) -> Unit = { _, _ -> },
    refreshKey: Int = 0,
    deckGateway: ExpressiveDeckGateway = ExpressiveCollection,
) {
    var decks by remember { mutableStateOf<List<UiDeck>?>(null) }
    var selectedDeckId by remember { mutableStateOf<DeckId?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var operationError by remember { mutableStateOf<String?>(null) }
    var menuDeck by remember { mutableStateOf<UiDeck?>(null) }
    var renameDeck by remember { mutableStateOf<UiDeck?>(null) }
    var deleteCandidate by remember { mutableStateOf<DeleteCandidate?>(null) }
    var customStudyDeck by remember { mutableStateOf<UiDeck?>(null) }
    var addDeckParent by remember { mutableStateOf<UiDeck?>(null) }
    var showAddDeck by remember { mutableStateOf(false) }
    var showFabMenu by remember { mutableStateOf(false) }
    var workingDeckId by remember { mutableStateOf<DeckId?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    fun reload() {
        scope.launch {
            try {
                decks = deckGateway.loadDecks()
                selectedDeckId = deckGateway.selectedDeckId()
                loadError = null
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                loadError = failure.message ?: "Failed to load decks"
            }
        }
    }

    fun reportOperationFailure(failure: Exception) {
        if (failure is CancellationException) throw failure
        operationError = failure.message ?: "The deck operation could not be completed."
    }

    LaunchedEffect(Unit) { reload() }
    LaunchedEffect(refreshKey) { if (refreshKey > 0) reload() }
    LaunchedEffect(deckGateway) {
        deckGateway.collectionChanges.collectLatest { change ->
            val changes = change.changes
            if (changes.deck || changes.card || changes.note || changes.studyQueues) reload()
        }
    }

    val loaded =
        decks ?: run {
            LoadingPane(loadError)
            return
        }
    val flat = remember(loaded) { loaded.flattenVisible() }
    val totalNew = loaded.sumOf(UiDeck::newCount)
    val totalLearn = loaded.sumOf(UiDeck::learnCount)
    val totalDue = loaded.sumOf(UiDeck::dueCount)

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 20.dp, bottom = 140.dp),
        ) {
            item {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    GreetingHeader()
                    Spacer(Modifier.height(20.dp))
                    if (loaded.isEmpty()) {
                        Text(
                            "No decks yet — create one or import an Anki package.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = onImport, modifier = Modifier.height(48.dp)) {
                            Text("Import")
                        }
                    } else {
                        TodaySummaryCard(
                            newCount = totalNew,
                            learnCount = totalLearn,
                            dueCount = totalDue,
                            onStartStudying = {
                                flat
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
                            "${loaded.countAll()} decks",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(
                        onClick = { onMaintenance(ExpressiveMaintenanceMode.BACKUPS) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(stringResource(R.string.expressive_maintenance_home_entry)) }
                    Spacer(Modifier.height(8.dp))
                }
            }
            items(flat, key = UiDeck::did) { deck ->
                Box {
                    DeckCard(
                        deck = deck,
                        selected = selectedDeckId == deck.did,
                        onClick = { onDeckClick(deck.did, deck.name) },
                        onToggleExpanded = {
                            if (workingDeckId == null) {
                                workingDeckId = deck.did
                                scope.launch {
                                    try {
                                        deckGateway.setCollapsed(deck.did, !deck.collapsed)
                                        reload()
                                    } catch (failure: Exception) {
                                        reportOperationFailure(failure)
                                    } finally {
                                        workingDeckId = null
                                    }
                                }
                            }
                        },
                        onLongClick = { if (workingDeckId == null) menuDeck = deck },
                    )
                    DeckContextMenu(
                        deck = deck,
                        expanded = menuDeck?.did == deck.did,
                        onDismiss = { menuDeck = null },
                        onCreateSubdeck = {
                            addDeckParent = deck
                            showAddDeck = true
                            menuDeck = null
                        },
                        onRename = {
                            renameDeck = deck
                            menuDeck = null
                        },
                        onDelete = {
                            menuDeck = null
                            workingDeckId = deck.did
                            scope.launch {
                                try {
                                    deleteCandidate = DeleteCandidate(deck, deckGateway.deletePreview(deck.did))
                                } catch (failure: Exception) {
                                    reportOperationFailure(failure)
                                } finally {
                                    workingDeckId = null
                                }
                            }
                        },
                        onOptions = {
                            onDeckOptions(deck.did, deck.filtered)
                            menuDeck = null
                        },
                        onExport = {
                            onExportDeck(deck.did, deck.fullDeckName)
                            menuDeck = null
                        },
                        onCustomStudy = {
                            customStudyDeck = deck
                            menuDeck = null
                        },
                        onRebuild = {
                            menuDeck = null
                            workingDeckId = deck.did
                            scope.launch {
                                try {
                                    deckGateway.rebuildFilteredDeck(deck.did)
                                    reload()
                                } catch (failure: Exception) {
                                    reportOperationFailure(failure)
                                } finally {
                                    workingDeckId = null
                                }
                            }
                        },
                        onEmpty = {
                            menuDeck = null
                            workingDeckId = deck.did
                            scope.launch {
                                try {
                                    deckGateway.emptyFilteredDeck(deck.did)
                                    reload()
                                } catch (failure: Exception) {
                                    reportOperationFailure(failure)
                                } finally {
                                    workingDeckId = null
                                }
                            }
                        },
                    )
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 16.dp, vertical = 96.dp),
        )
        ExpressiveFab(
            expanded = showFabMenu,
            onToggle = { showFabMenu = !showFabMenu },
            onAddCard = {
                showFabMenu = false
                onAddCard(selectedDeckId)
            },
            onAddDeck = {
                showFabMenu = false
                addDeckParent = null
                showAddDeck = true
            },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
        )
    }

    renameDeck?.let { deck ->
        RenameDeckDialog(
            currentName = deck.fullDeckName,
            enabled = workingDeckId == null,
            onDismiss = { if (workingDeckId == null) renameDeck = null },
            onRename = { newName ->
                workingDeckId = deck.did
                scope.launch {
                    try {
                        deckGateway.renameDeck(deck.did, newName)
                        renameDeck = null
                        reload()
                    } catch (failure: Exception) {
                        reportOperationFailure(failure)
                    } finally {
                        workingDeckId = null
                    }
                }
            },
        )
    }

    deleteCandidate?.let { candidate ->
        AlertDialog(
            onDismissRequest = { if (workingDeckId == null) deleteCandidate = null },
            title = { Text("Delete deck") },
            text = {
                Text(
                    if (candidate.preview.filtered) {
                        "Delete filtered deck “${candidate.deck.fullDeckName}”? Its cards return to their original decks."
                    } else {
                        "Delete “${candidate.deck.fullDeckName}”, its subdecks, and ${candidate.preview.cardCount} cards?"
                    },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = workingDeckId == null,
                    onClick = {
                        workingDeckId = candidate.deck.did
                        scope.launch {
                            try {
                                val deletion = deckGateway.deleteDeck(candidate.deck.did)
                                deleteCandidate = null
                                reload()
                                val result =
                                    snackbarHostState.showSnackbar(
                                        message = "Deleted ${deletion.deletedCards} cards",
                                        actionLabel = if (deletion.undoStep != null) "Undo" else null,
                                        withDismissAction = true,
                                    )
                                if (result == SnackbarResult.ActionPerformed && deletion.undoStep != null) {
                                    if (!deckGateway.undoDeckDeletion(deletion.undoStep)) {
                                        operationError = "Another operation replaced the deck deletion in undo history."
                                    }
                                    reload()
                                }
                            } catch (failure: Exception) {
                                reportOperationFailure(failure)
                            } finally {
                                workingDeckId = null
                            }
                        }
                    },
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(enabled = workingDeckId == null, onClick = { deleteCandidate = null }) { Text("Cancel") }
            },
        )
    }

    if (showAddDeck) {
        val initialName = ""
        AddDeckDialog(
            initialName = initialName,
            enabled = workingDeckId == null,
            title = addDeckParent?.let { "New subdeck in ${it.fullDeckName}" } ?: "New deck",
            onDismiss = {
                if (workingDeckId == null) {
                    showAddDeck = false
                    addDeckParent = null
                }
            },
            onCreate = { name ->
                workingDeckId = addDeckParent?.did ?: 0L
                scope.launch {
                    try {
                        deckGateway.createDeck(name, parentDid = addDeckParent?.did)
                        showAddDeck = false
                        addDeckParent = null
                        reload()
                    } catch (failure: Exception) {
                        reportOperationFailure(failure)
                    } finally {
                        workingDeckId = null
                    }
                }
            },
        )
    }

    customStudyDeck?.let { deck ->
        CustomStudyDialog(
            deckId = deck.did,
            deckName = deck.fullDeckName,
            deckGateway = deckGateway,
            onDismiss = { customStudyDeck = null },
            onDone = { sessionDeckId ->
                customStudyDeck = null
                scope.launch {
                    try {
                        val current = deckGateway.loadDecks()
                        decks = current
                        selectedDeckId = deckGateway.selectedDeckId()
                        sessionDeckId?.let { did ->
                            current.findDeck(did)?.let { session -> onDeckClick(session.did, session.name) }
                                ?: run { operationError = "The Custom Study session was created but could not be opened." }
                        }
                    } catch (failure: Exception) {
                        reportOperationFailure(failure)
                    }
                }
            },
        )
    }

    operationError?.let { message ->
        AlertDialog(
            onDismissRequest = { operationError = null },
            title = { Text("Deck operation failed") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { operationError = null }) { Text("OK") } },
        )
    }
}

private data class DeleteCandidate(
    val deck: UiDeck,
    val preview: ExpressiveDeckDeletePreview,
)

private enum class CustomStudyMode(
    val label: String,
) {
    EXTEND_NEW("Increase today's new card limit"),
    EXTEND_REVIEW("Increase today's review limit"),
    FORGOTTEN("Review forgotten cards"),
    AHEAD("Review ahead"),
    PREVIEW("Preview new cards"),
    CRAM("Study by card state or tag"),
}

private val cramKinds =
    listOf(
        CramKind.CRAM_KIND_NEW to "New cards only",
        CramKind.CRAM_KIND_DUE to "Due cards only",
        CramKind.CRAM_KIND_REVIEW to "All review cards in random order",
        CramKind.CRAM_KIND_ALL to "All cards in random order",
    )

@Composable
internal fun CustomStudyDialog(
    deckId: DeckId,
    deckName: String,
    deckGateway: ExpressiveDeckGateway,
    onDismiss: () -> Unit,
    onDone: (sessionDeckId: DeckId?) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var defaults by remember { mutableStateOf<ExpressiveCustomStudyDefaults?>(null) }
    var modeName by rememberSaveable(deckId) { mutableStateOf(CustomStudyMode.EXTEND_NEW.name) }
    val mode = CustomStudyMode.valueOf(modeName)
    var amount by rememberSaveable(deckId) { mutableStateOf("") }
    var includeTags by rememberSaveable(deckId) { mutableStateOf("") }
    var excludeTags by rememberSaveable(deckId) { mutableStateOf("") }
    var cramKindName by rememberSaveable(deckId) { mutableStateOf(CramKind.CRAM_KIND_NEW.name) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(deckId) {
        try {
            defaults = deckGateway.customStudyDefaults(deckId)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            error = failure.message ?: "Custom Study defaults could not be loaded."
        }
    }
    LaunchedEffect(mode, defaults) {
        amount =
            when (mode) {
                CustomStudyMode.EXTEND_NEW -> defaults?.extendNew?.toString().orEmpty()
                CustomStudyMode.EXTEND_REVIEW -> defaults?.extendReview?.toString().orEmpty()
                CustomStudyMode.FORGOTTEN,
                CustomStudyMode.AHEAD,
                CustomStudyMode.PREVIEW,
                -> "1"
                CustomStudyMode.CRAM -> "100"
            }
    }

    val parsedAmount = amount.toIntOrNull()
    val canSubmit = defaults != null && parsedAmount != null && parsedAmount != 0 && !busy
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Custom study") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item {
                    Text(
                        deckName,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (defaults == null && error == null) {
                    item { CircularProgressIndicator() }
                }
                error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
                if (defaults != null) {
                    items(CustomStudyMode.entries) { candidate ->
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = mode == candidate, onClick = { modeName = candidate.name })
                            Text(candidate.label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    item {
                        defaults?.let { loaded ->
                            val availability =
                                when (mode) {
                                    CustomStudyMode.EXTEND_NEW ->
                                        "Available: ${loaded.availableNew} (${loaded.availableNewInChildren} in subdecks)"
                                    CustomStudyMode.EXTEND_REVIEW ->
                                        "Available: ${loaded.availableReview} (${loaded.availableReviewInChildren} in subdecks)"
                                    else -> null
                                }
                            availability?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        OutlinedTextField(
                            value = amount,
                            onValueChange = { value ->
                                amount =
                                    value.filterIndexed { index, character ->
                                        val signAtStart = character == '-' && index == 0
                                        val signedMode =
                                            mode == CustomStudyMode.EXTEND_NEW ||
                                                mode == CustomStudyMode.EXTEND_REVIEW
                                        character.isDigit() || (signAtStart && signedMode)
                                    }
                            },
                            label = {
                                Text(
                                    if (
                                        mode == CustomStudyMode.FORGOTTEN ||
                                        mode == CustomStudyMode.AHEAD ||
                                        mode == CustomStudyMode.PREVIEW
                                    ) {
                                        "Days"
                                    } else {
                                        "Cards"
                                    },
                                )
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (mode == CustomStudyMode.CRAM) {
                        items(cramKinds) { (kind, label) ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = cramKindName == kind.name, onClick = { cramKindName = kind.name })
                                Text(label, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        item {
                            OutlinedTextField(
                                value = includeTags,
                                onValueChange = { includeTags = it },
                                label = { Text("Include tags (comma-separated)") },
                                supportingText = { Text("Known tags: ${defaults?.tags?.take(8)?.joinToString().orEmpty()}") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = excludeTags,
                                onValueChange = { excludeTags = it },
                                label = { Text("Exclude tags (comma-separated)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSubmit,
                onClick = {
                    val count = parsedAmount ?: return@TextButton
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            val action =
                                when (mode) {
                                    CustomStudyMode.EXTEND_NEW -> ExpressiveCustomStudyAction.ExtendNew(count)
                                    CustomStudyMode.EXTEND_REVIEW -> ExpressiveCustomStudyAction.ExtendReview(count)
                                    CustomStudyMode.FORGOTTEN -> ExpressiveCustomStudyAction.Forgotten(count)
                                    CustomStudyMode.AHEAD -> ExpressiveCustomStudyAction.ReviewAhead(count)
                                    CustomStudyMode.PREVIEW -> ExpressiveCustomStudyAction.PreviewNew(count)
                                    CustomStudyMode.CRAM ->
                                        ExpressiveCustomStudyAction.Cram(
                                            amount = count,
                                            kind = CramKind.valueOf(cramKindName),
                                            includeTags = includeTags.asTagList(),
                                            excludeTags = excludeTags.asTagList(),
                                        )
                                }
                            val result = deckGateway.customStudy(deckId, action)
                            onDone(result.sessionDeckId)
                        } catch (failure: CancellationException) {
                            throw failure
                        } catch (failure: Exception) {
                            error = failure.message ?: "Custom Study could not be created."
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text(if (busy) "Working…" else "Start") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun DeckContextMenu(
    deck: UiDeck,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onCreateSubdeck: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onOptions: () -> Unit,
    onExport: () -> Unit,
    onCustomStudy: () -> Unit,
    onRebuild: () -> Unit,
    onEmpty: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text("Deck options") }, onClick = onOptions)
        DropdownMenuItem(text = { Text("Export deck") }, onClick = onExport)
        if (deck.filtered) {
            DropdownMenuItem(text = { Text("Rebuild") }, onClick = onRebuild)
            DropdownMenuItem(text = { Text("Empty") }, onClick = onEmpty)
        } else {
            DropdownMenuItem(text = { Text("Custom study") }, onClick = onCustomStudy)
            DropdownMenuItem(text = { Text("Create subdeck") }, onClick = onCreateSubdeck)
        }
        DropdownMenuItem(text = { Text("Rename") }, onClick = onRename)
        DropdownMenuItem(text = { Text("Delete") }, onClick = onDelete)
    }
}

@Composable
private fun RenameDeckDialog(
    currentName: String,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
) {
    var name by rememberSaveable(currentName) { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename deck") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                enabled = enabled,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                enabled = enabled && name.isNotBlank() && name.trim() != currentName,
                onClick = { onRename(name.trim()) },
            ) { Text("Rename") }
        },
        dismissButton = { TextButton(enabled = enabled, onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun AddDeckDialog(
    initialName: String,
    title: String,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var name by rememberSaveable(initialName) { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                enabled = enabled,
                placeholder = { Text("Deck name (use :: for subdecks)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(enabled = enabled && name.isNotBlank(), onClick = { onCreate(name.trim()) }) {
                Text("Create")
            }
        },
        dismissButton = { TextButton(enabled = enabled, onClick = onDismiss) { Text("Cancel") } },
    )
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

private fun List<UiDeck>.flattenVisible(): List<UiDeck> =
    buildList {
        fun walk(deck: UiDeck) {
            add(deck)
            if (!deck.collapsed) deck.children.forEach(::walk)
        }
        this@flattenVisible.forEach(::walk)
    }

private fun List<UiDeck>.countAll(): Int = sumOf { 1 + it.children.countAll() }

private fun List<UiDeck>.findDeck(did: DeckId): UiDeck? =
    firstOrNull { it.did == did } ?: firstNotNullOfOrNull { it.children.findDeck(did) }

private fun String.asTagList(): List<String> =
    split(',')
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
        .take(100)
