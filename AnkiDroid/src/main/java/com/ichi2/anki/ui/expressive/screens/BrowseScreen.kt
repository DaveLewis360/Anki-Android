// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.ui.expressive.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ichi2.anki.R
import com.ichi2.anki.libanki.CardId
import com.ichi2.anki.libanki.DeckId
import com.ichi2.anki.ui.expressive.BrowseRowId
import com.ichi2.anki.ui.expressive.BrowseTagSelection
import com.ichi2.anki.ui.expressive.DeckPickerPurpose
import com.ichi2.anki.ui.expressive.DueDateSpec
import com.ichi2.anki.ui.expressive.ExpressiveBrowseDialog
import com.ichi2.anki.ui.expressive.ExpressiveBrowseFilter
import com.ichi2.anki.ui.expressive.ExpressiveBrowseMode
import com.ichi2.anki.ui.expressive.ExpressiveBrowseViewModel
import com.ichi2.anki.ui.expressive.ForgetOptions
import com.ichi2.anki.ui.expressive.RepositionPreview
import com.ichi2.anki.ui.expressive.RepositionRequest
import com.ichi2.anki.ui.expressive.TagFinalStateRequest
import com.ichi2.anki.ui.expressive.UiCard
import com.ichi2.anki.ui.expressive.UiCardState
import com.ichi2.anki.ui.expressive.theme.PillShape

/** Full native card/note browser with typed selection and backend-owned bulk operations. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BrowseScreen(
    viewModel: ExpressiveBrowseViewModel,
    refreshKey: Int = 0,
    backHandlingEnabled: Boolean = true,
    onAddNote: (searchTerms: String, deckId: DeckId?) -> Unit = { _, _ -> },
    onEditCard: (CardId) -> Unit = {},
    onExportSelection: (com.ichi2.anki.ui.expressive.BrowseSelection) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    DisposableEffect(viewModel) {
        viewModel.setCollectionAccessEnabled(true)
        onDispose { viewModel.setCollectionAccessEnabled(false) }
    }
    LaunchedEffect(refreshKey) { viewModel.refresh() }
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            val result =
                snackbarHostState.showSnackbar(
                    message = message.text,
                    actionLabel = if (message.undoStep != null) "Undo" else null,
                )
            if (result == SnackbarResult.ActionPerformed) message.undoStep?.let(viewModel::undo)
        }
    }
    BackHandler(
        enabled =
            backHandlingEnabled &&
                (state.dialog != null || state.previewRowId != null || state.selectionMode),
        onBack = { viewModel.handleBack() },
    )

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 20.dp, bottom = 120.dp),
        ) {
            item {
                BrowseHeader(
                    state = state,
                    onQuery = viewModel::setQuery,
                    onMode = viewModel::setMode,
                    onFilter = viewModel::setFilter,
                    onDeckScope = { viewModel.openDeckPicker(DeckPickerPurpose.SEARCH_SCOPE) },
                    onAddNote = { onAddNote(state.request.query, viewModel.deckIdForNewNote()) },
                    onSelectAll = viewModel::selectAllLoaded,
                    onClearSelection = { viewModel.clearSelection() },
                )
            }
            if (state.selectionMode) {
                item {
                    BrowseActions(
                        viewModel = viewModel,
                        enabled = state.selectedCount > 0 && !state.working,
                        onExport = {
                            onExportSelection(
                                com.ichi2.anki.ui.expressive
                                    .BrowseSelection(state.request.mode, state.selectedRowIds),
                            )
                        },
                    )
                }
            }
            state.error?.let { error ->
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(error, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                        TextButton(onClick = viewModel::refresh) { Text("Retry") }
                    }
                }
            }
            when {
                state.loading && state.rows.isEmpty() ->
                    item {
                        Box(Modifier.fillMaxWidth().padding(top = 60.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                state.rows.isEmpty() && state.error == null ->
                    item {
                        Text(
                            if (state.request.query.isBlank() && state.request.filter == ExpressiveBrowseFilter.ALL) {
                                "No ${state.request.mode.name.lowercase()} in the collection yet."
                            } else {
                                "No results match."
                            },
                            Modifier.fillMaxWidth().padding(top = 60.dp),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                else -> {
                    items(state.rows, key = { "${it.rowId::class.simpleName}:${it.rowId.value}" }) { row ->
                        BrowseRow(
                            row = row,
                            selected = row.rowId in state.selectedRowIds,
                            onClick = { viewModel.openPreview(row.rowId) },
                            onLongClick = { viewModel.onRowLongPress(row.rowId) },
                        )
                    }
                    if (state.truncated) {
                        item {
                            Text(
                                "Showing the first ${state.rows.size} results. Refine the search to see others.",
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        if (state.working) {
            CircularProgressIndicator(Modifier.align(Alignment.TopCenter).padding(top = 8.dp).size(24.dp))
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 90.dp),
        )
    }

    state.preview?.let { row ->
        CardPreviewDialog(
            row = row,
            onDismiss = viewModel::closePreview,
            onEdit = if (row.cardId > 0) ({ onEditCard(row.cardId) }) else null,
        )
    }
    BrowseOperationDialog(state.dialog, viewModel)
}

@Composable
private fun BrowseHeader(
    state: com.ichi2.anki.ui.expressive.ExpressiveBrowseUiState,
    onQuery: (String) -> Unit,
    onMode: (ExpressiveBrowseMode) -> Unit,
    onFilter: (ExpressiveBrowseFilter) -> Unit,
    onDeckScope: () -> Unit,
    onAddNote: () -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
) {
    Column(Modifier.padding(horizontal = 20.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.selectionMode) {
                Text(
                    "${state.selectedCount} selected",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Row {
                    TextButton(onClick = onSelectAll) { Text("All") }
                    TextButton(onClick = onClearSelection) { Text("Close") }
                }
            } else {
                Text(
                    "Browse",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                TextButton(onClick = onAddNote) { Text("Add note") }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ExpressiveBrowseMode.entries.forEach { mode ->
                FilterChip(
                    selected = state.request.mode == mode,
                    onClick = { onMode(mode) },
                    label = { Text(if (mode == ExpressiveBrowseMode.CARDS) "Cards" else "Notes") },
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = state.request.query,
            onValueChange = onQuery,
            placeholder = { Text("Search ${state.request.mode.name.lowercase()}…") },
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
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = state.request.deckId != null,
                onClick = onDeckScope,
                label = { Text(state.request.deckId?.let { "Deck #$it" } ?: "All decks") },
            )
            ExpressiveBrowseFilter.entries.forEach { filter ->
                FilterChip(
                    selected = state.request.filter == filter,
                    onClick = { onFilter(filter) },
                    label = { Text(filter.name.lowercase().replaceFirstChar(Char::uppercase)) },
                )
            }
        }
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun BrowseActions(
    viewModel: ExpressiveBrowseViewModel,
    enabled: Boolean,
    onExport: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Action("Export", enabled, onExport)
        Action("Delete", enabled, viewModel::deleteSelection)
        Action("Reset", enabled, viewModel::openForget)
        Action("Set due", enabled, viewModel::openDueDate)
        Action("Reposition", enabled, viewModel::openReposition)
        Action("Deck", enabled) { viewModel.openDeckPicker(DeckPickerPurpose.MOVE_SELECTION) }
        Action("Tags", enabled, viewModel::openTags)
        Action("Mark", enabled, viewModel::toggleMark)
        Action("Flag", enabled, viewModel::openFlag)
        Action("Suspend", enabled, viewModel::toggleSuspend)
        Action("Bury", enabled, viewModel::toggleBury)
    }
}

@Composable
private fun Action(
    label: String,
    enabled: Boolean,
    action: () -> Unit,
) {
    TextButton(onClick = action, enabled = enabled) { Text(label) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BrowseRow(
    row: UiCard,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color =
            if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 5.dp)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(row.state.color()))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    row.question.ifBlank { row.answer },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    buildString {
                        append(row.deckName)
                        if (row.rowId is BrowseRowId.Note && row.siblingCount != 1) append(" · ${row.siblingCount} cards")
                        if (row.marked) append(" · marked")
                        if (row.flag > 0) append(" · flag ${row.flag}")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(12.dp))
            Surface(shape = PillShape, color = MaterialTheme.colorScheme.tertiaryContainer) {
                Text(
                    row.dueLabel,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun CardPreviewDialog(
    row: UiCard,
    onDismiss: () -> Unit,
    onEdit: (() -> Unit)?,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(row.deckName, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                Text(row.question, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(10.dp))
                Text(row.answer, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            if (onEdit != null) {
                TextButton(
                    onClick = {
                        onEdit()
                        onDismiss()
                    },
                ) { Text("Edit") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun BrowseOperationDialog(
    dialog: ExpressiveBrowseDialog?,
    viewModel: ExpressiveBrowseViewModel,
) {
    when (dialog) {
        null -> Unit
        is ExpressiveBrowseDialog.Forget -> ForgetDialog(dialog.defaults, viewModel)
        ExpressiveBrowseDialog.DueDate -> DueDateDialog(viewModel)
        is ExpressiveBrowseDialog.Reposition -> RepositionDialog(dialog.preview, viewModel)
        is ExpressiveBrowseDialog.DeckPicker ->
            DeckPickerDialog(dialog, viewModel)
        is ExpressiveBrowseDialog.Tags -> TagsDialog(dialog.selection, viewModel)
        ExpressiveBrowseDialog.Flag -> FlagDialog(viewModel)
    }
}

@Composable
private fun ForgetDialog(
    defaults: ForgetOptions,
    viewModel: ExpressiveBrowseViewModel,
) {
    var restorePosition by rememberSaveable { mutableStateOf(defaults.restorePosition) }
    var resetCounts by rememberSaveable { mutableStateOf(defaults.resetCounts) }
    AlertDialog(
        onDismissRequest = viewModel::dismissDialog,
        title = { Text("Reset cards") },
        text = {
            Column {
                ToggleRow("Restore original position", restorePosition) { restorePosition = it }
                ToggleRow("Reset review and lapse counts", resetCounts) { resetCounts = it }
            }
        },
        confirmButton = {
            TextButton(onClick = { viewModel.forget(ForgetOptions(restorePosition, resetCounts)) }) { Text("Reset") }
        },
        dismissButton = { TextButton(onClick = viewModel::dismissDialog) { Text("Cancel") } },
    )
}

@Composable
private fun DueDateDialog(viewModel: ExpressiveBrowseViewModel) {
    var start by rememberSaveable { mutableStateOf("0") }
    var end by rememberSaveable { mutableStateOf("") }
    var updateInterval by rememberSaveable { mutableStateOf(false) }
    var validation by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = viewModel::dismissDialog,
        title = { Text("Set due date") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("Days from today", start) { start = it }
                NumberField("End day (optional)", end) { end = it }
                ToggleRow("Update interval", updateInterval) { updateInterval = it }
                validation?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val first = start.toIntOrNull()
                    val last = end.takeIf(String::isNotBlank)?.toIntOrNull()
                    val spec = runCatching { first?.let { DueDateSpec(it, last, updateInterval) } }.getOrNull()
                    if (spec == null) validation = "Enter a valid nonnegative range." else viewModel.setDueDate(spec)
                },
            ) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = viewModel::dismissDialog) { Text("Cancel") } },
    )
}

@Composable
private fun RepositionDialog(
    preview: RepositionPreview,
    viewModel: ExpressiveBrowseViewModel,
) {
    var position by rememberSaveable { mutableStateOf((preview.minimumPosition ?: 0).toString()) }
    var step by rememberSaveable { mutableStateOf("1") }
    var random by rememberSaveable { mutableStateOf(preview.random) }
    var shift by rememberSaveable { mutableStateOf(preview.shift) }
    var validation by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = viewModel::dismissDialog,
        title = { Text("Reposition new cards") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${preview.eligibleCount} eligible; ${preview.unsupportedCount} unsupported")
                preview.maximumPosition?.let { Text("Current positions: ${preview.minimumPosition ?: 0}–$it") }
                NumberField("Start position", position) { position = it }
                NumberField("Step", step) { step = it }
                ToggleRow("Randomize order", random) { random = it }
                ToggleRow("Shift existing cards", shift) { shift = it }
                validation?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val request =
                        runCatching {
                            RepositionRequest(
                                position = position.toInt(),
                                step = step.toInt(),
                                random = random,
                                shift = shift,
                            )
                        }.getOrNull()
                    if (request == null) validation = "Enter a nonnegative position and positive step." else viewModel.reposition(request)
                },
            ) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = viewModel::dismissDialog) { Text("Cancel") } },
    )
}

@Composable
private fun DeckPickerDialog(
    dialog: ExpressiveBrowseDialog.DeckPicker,
    viewModel: ExpressiveBrowseViewModel,
) {
    AlertDialog(
        onDismissRequest = viewModel::dismissDialog,
        title = { Text(if (dialog.purpose == DeckPickerPurpose.SEARCH_SCOPE) "Filter by deck" else "Move to deck") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                if (dialog.purpose == DeckPickerPurpose.SEARCH_SCOPE) {
                    item {
                        TextButton(onClick = { viewModel.chooseDeck(dialog.purpose, null) }) { Text("All decks") }
                    }
                }
                items(dialog.decks, key = { it.deckId }) { deck ->
                    TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { viewModel.chooseDeck(dialog.purpose, deck.deckId) },
                    ) {
                        Text(deck.name, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = viewModel::dismissDialog) { Text("Cancel") } },
    )
}

@Composable
private fun TagsDialog(
    selection: BrowseTagSelection,
    viewModel: ExpressiveBrowseViewModel,
) {
    var selected by rememberSaveable { mutableStateOf(selection.selectedTags.joinToString(" ")) }
    var unchangedMixed by rememberSaveable { mutableStateOf(selection.indeterminateTags.joinToString(" ")) }
    AlertDialog(
        onDismissRequest = viewModel::dismissDialog,
        title = { Text("Edit tags") },
        text = {
            Column {
                OutlinedTextField(
                    value = selected,
                    onValueChange = { selected = it },
                    label = { Text("Selected tags") },
                    supportingText = { Text("Every selected note will have these tags.") },
                )
                if (selection.indeterminateTags.isNotEmpty()) {
                    OutlinedTextField(
                        value = unchangedMixed,
                        onValueChange = { unchangedMixed = it },
                        label = { Text("Mixed tags to keep unchanged") },
                        supportingText = { Text("Remove a tag here to remove it from every selected note.") },
                    )
                }
                if (selection.allTags.isNotEmpty()) {
                    Text(
                        "Available: ${selection.allTags.take(12).joinToString()}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    viewModel.editTags(
                        TagFinalStateRequest(
                            selectedTags = selected.split(Regex("[,\\s]+")).filter(String::isNotBlank).toSet(),
                            indeterminateTags =
                                unchangedMixed
                                    .split(Regex("[,\\s]+"))
                                    .filter(String::isNotBlank)
                                    .toSet(),
                        ),
                    )
                },
            ) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = viewModel::dismissDialog) { Text("Cancel") } },
    )
}

@Composable
private fun FlagDialog(viewModel: ExpressiveBrowseViewModel) {
    AlertDialog(
        onDismissRequest = viewModel::dismissDialog,
        title = { Text("Set flag") },
        text = {
            Column {
                (0..7).chunked(4).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        row.forEach { flag ->
                            TextButton(onClick = { viewModel.setFlag(flag) }) {
                                Text(if (flag == 0) "Clear" else flag.toString())
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = viewModel::dismissDialog) { Text("Cancel") } },
    )
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChecked)
        Text(label)
    }
}

@Composable
private fun NumberField(
    label: String,
    value: String,
    onValue: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { candidate -> if (candidate.all(Char::isDigit)) onValue(candidate) },
        label = { Text(label) },
        singleLine = true,
    )
}

private fun UiCardState.color(): Color =
    when (this) {
        UiCardState.NEW -> Color(0xFF7DD3FC)
        UiCardState.LEARNING -> Color(0xFFFDBA74)
        UiCardState.REVIEW -> Color(0xFF86EFAC)
        UiCardState.SUSPENDED -> Color(0xFFCBD5E1)
        UiCardState.BURIED -> Color(0xFFC4B5FD)
    }
