// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.ui.expressive

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ichi2.anki.libanki.DeckId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ExpressiveBrowseUiState(
    val request: BrowseSearchRequest,
    val rows: List<UiCard> = emptyList(),
    val truncated: Boolean = false,
    val loading: Boolean = true,
    val working: Boolean = false,
    val error: String? = null,
    val selectionMode: Boolean = false,
    val selectedRowIds: List<BrowseRowId> = emptyList(),
    val selectionAnchor: BrowseRowId? = null,
    val previewRowId: BrowseRowId? = null,
    val dialog: ExpressiveBrowseDialog? = null,
) {
    val preview: UiCard?
        get() = rows.firstOrNull { it.rowId == previewRowId }

    val selectedCount: Int
        get() = selectedRowIds.size
}

sealed interface ExpressiveBrowseDialog {
    data class Forget(
        val defaults: ForgetOptions = ForgetOptions(),
    ) : ExpressiveBrowseDialog

    data object DueDate : ExpressiveBrowseDialog

    data class Reposition(
        val preview: RepositionPreview,
    ) : ExpressiveBrowseDialog

    data class DeckPicker(
        val purpose: DeckPickerPurpose,
        val decks: List<ExpressiveDeckChoice>,
    ) : ExpressiveBrowseDialog

    data class Tags(
        val selection: BrowseTagSelection,
    ) : ExpressiveBrowseDialog

    data object Flag : ExpressiveBrowseDialog
}

enum class DeckPickerPurpose { SEARCH_SCOPE, MOVE_SELECTION }

data class ExpressiveBrowseMessage(
    val text: String,
    val undoStep: BrowseUndoStep? = null,
)

class ExpressiveBrowseViewModel(
    private val savedStateHandle: SavedStateHandle,
    private val gateway: ExpressiveBrowseGateway = ExpressiveCollection,
) : ViewModel() {
    private val initialRequest =
        BrowseSearchRequest(
            query = savedStateHandle[QUERY_KEY] ?: "",
            filter = enumValue(savedStateHandle[FILTER_KEY], ExpressiveBrowseFilter.ALL),
            mode = enumValue(savedStateHandle[MODE_KEY], ExpressiveBrowseMode.CARDS),
            deckId = savedStateHandle.get<Long>(DECK_KEY)?.takeIf { it > 0 },
        )
    private val initialSelection = decodeRowIds(savedStateHandle[SELECTION_KEY], initialRequest.mode)
    private val initialAnchor = decodeRowId(savedStateHandle[ANCHOR_KEY], initialRequest.mode)
    private val initialPreview = decodeRowId(savedStateHandle[PREVIEW_KEY], initialRequest.mode)
    private val _state =
        MutableStateFlow(
            ExpressiveBrowseUiState(
                request = initialRequest,
                selectionMode = savedStateHandle[SELECTION_MODE_KEY] ?: initialSelection.isNotEmpty(),
                selectedRowIds = initialSelection,
                selectionAnchor = initialAnchor,
                previewRowId = initialPreview,
            ),
        )
    val state: StateFlow<ExpressiveBrowseUiState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<ExpressiveBrowseMessage>(extraBufferCapacity = 8)
    val messages: SharedFlow<ExpressiveBrowseMessage> = _messages.asSharedFlow()
    private var searchJob: Job? = null
    private var collectionAccessEnabled = false
    private var refreshPending = true

    init {
        viewModelScope.launch {
            gateway.collectionChanges.collect { change ->
                if (change.changes.affectsExpressiveBrowser()) scheduleSearch(delayMillis = 0)
            }
        }
    }

    fun setCollectionAccessEnabled(enabled: Boolean) {
        collectionAccessEnabled = enabled
        if (!enabled) {
            searchJob?.cancel()
            refreshPending = true
        } else if (refreshPending) {
            scheduleSearch(delayMillis = 0)
        }
    }

    fun setQuery(value: String) {
        updateRequest(_state.value.request.copy(query = value), searchDelay = SEARCH_DEBOUNCE_MS)
    }

    fun setFilter(value: ExpressiveBrowseFilter) {
        updateRequest(_state.value.request.copy(filter = value))
    }

    fun setMode(value: ExpressiveBrowseMode) {
        if (value == _state.value.request.mode) return
        _state.update {
            it.copy(
                request = it.request.copy(mode = value),
                selectionMode = false,
                selectedRowIds = emptyList(),
                selectionAnchor = null,
                previewRowId = null,
                dialog = null,
            )
        }
        persistState()
        scheduleSearch(delayMillis = 0)
    }

    fun refresh() = scheduleSearch(delayMillis = 0)

    fun onEditorResult(result: ExpressiveEditorResult) {
        if (result.origin == ExpressiveEditorOrigin.BROWSE && (result.changed || result.reloadRequired)) refresh()
    }

    fun openPreview(rowId: BrowseRowId) {
        if (_state.value.selectionMode) {
            toggleSelection(rowId)
        } else {
            _state.update { it.copy(previewRowId = rowId) }
            persistState()
        }
    }

    fun closePreview() {
        _state.update { it.copy(previewRowId = null) }
        persistState()
    }

    fun onRowLongPress(rowId: BrowseRowId) {
        val current = _state.value
        if (!current.selectionMode) {
            _state.update {
                it.copy(selectionMode = true, selectedRowIds = listOf(rowId), selectionAnchor = rowId, previewRowId = null)
            }
        } else {
            val anchorIndex = current.rows.indexOfFirst { it.rowId == current.selectionAnchor }
            val rowIndex = current.rows.indexOfFirst { it.rowId == rowId }
            val additions =
                if (anchorIndex >= 0 && rowIndex >= 0) {
                    current.rows.subList(minOf(anchorIndex, rowIndex), maxOf(anchorIndex, rowIndex) + 1).map { it.rowId }
                } else {
                    listOf(rowId)
                }
            val selected = LinkedHashSet(current.selectedRowIds).apply { addAll(additions) }.toList()
            _state.update { it.copy(selectedRowIds = selected, selectionAnchor = rowId, previewRowId = null) }
        }
        persistState()
    }

    fun toggleSelection(rowId: BrowseRowId) {
        val current = _state.value
        val selected = LinkedHashSet(current.selectedRowIds)
        if (!selected.add(rowId)) selected.remove(rowId)
        _state.update {
            it.copy(
                selectionMode = selected.isNotEmpty(),
                selectedRowIds = selected.toList(),
                selectionAnchor = if (selected.isEmpty()) null else rowId,
            )
        }
        persistState()
    }

    fun selectAllLoaded() {
        _state.update {
            it.copy(
                selectionMode = true,
                selectedRowIds = it.rows.map(UiCard::rowId),
                selectionAnchor = it.rows.lastOrNull()?.rowId,
                previewRowId = null,
            )
        }
        persistState()
    }

    fun clearSelection(keepMode: Boolean = false) {
        _state.update {
            it.copy(selectionMode = keepMode, selectedRowIds = emptyList(), selectionAnchor = null, dialog = null)
        }
        persistState()
    }

    fun dismissDialog() {
        _state.update { it.copy(dialog = null) }
    }

    fun handleBack(): Boolean =
        when {
            _state.value.dialog != null -> {
                dismissDialog()
                true
            }
            _state.value.previewRowId != null -> {
                closePreview()
                true
            }
            _state.value.selectionMode -> {
                clearSelection()
                true
            }
            else -> false
        }

    fun deleteSelection() = runMutation { gateway.deleteNotes(currentSelection()) }

    fun openForget() {
        requireSelection()
        _state.update { it.copy(dialog = ExpressiveBrowseDialog.Forget()) }
    }

    fun forget(options: ForgetOptions) = runMutation { gateway.forget(currentSelection(), options) }

    fun openDueDate() {
        requireSelection()
        _state.update { it.copy(dialog = ExpressiveBrowseDialog.DueDate) }
    }

    fun setDueDate(spec: DueDateSpec) = runMutation { gateway.setDueDate(currentSelection(), spec) }

    fun openReposition() {
        requireSelection()
        runPreparation {
            _state.update { it.copy(dialog = ExpressiveBrowseDialog.Reposition(gateway.prepareReposition(currentSelection()))) }
        }
    }

    fun reposition(request: RepositionRequest) = runMutation { gateway.reposition(currentSelection(), request) }

    fun openDeckPicker(purpose: DeckPickerPurpose) {
        if (purpose == DeckPickerPurpose.MOVE_SELECTION) requireSelection()
        runPreparation {
            _state.update { it.copy(dialog = ExpressiveBrowseDialog.DeckPicker(purpose, gateway.deckChoices())) }
        }
    }

    fun chooseDeck(
        purpose: DeckPickerPurpose,
        deckId: DeckId?,
    ) {
        if (purpose == DeckPickerPurpose.SEARCH_SCOPE) {
            updateRequest(_state.value.request.copy(deckId = deckId))
            _state.update { it.copy(dialog = null) }
        } else if (deckId != null) {
            runMutation { gateway.moveToDeck(currentSelection(), deckId) }
        }
    }

    fun openTags() {
        requireSelection()
        runPreparation {
            _state.update { it.copy(dialog = ExpressiveBrowseDialog.Tags(gateway.tagSelection(currentSelection()))) }
        }
    }

    fun editTags(request: TagFinalStateRequest) = runMutation { gateway.editTags(currentSelection(), request) }

    fun toggleMark() = runMutation { gateway.toggleMark(currentSelection()) }

    fun openFlag() {
        requireSelection()
        _state.update { it.copy(dialog = ExpressiveBrowseDialog.Flag) }
    }

    fun setFlag(flag: Int) = runMutation { gateway.setFlag(currentSelection(), flag) }

    fun toggleSuspend() = runMutation { gateway.toggleSuspend(currentSelection()) }

    fun toggleBury() = runMutation { gateway.toggleBury(currentSelection()) }

    fun undo(step: BrowseUndoStep) {
        viewModelScope.launch {
            _state.update { it.copy(working = true, error = null) }
            try {
                gateway.undo(step)
                _messages.emit(ExpressiveBrowseMessage("Operation undone."))
                scheduleSearch(delayMillis = 0)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showError(e)
            } finally {
                _state.update { it.copy(working = false) }
            }
        }
    }

    fun deckIdForNewNote(): DeckId? = _state.value.request.deckId

    private fun runMutation(block: suspend () -> BrowseMutationReceipt?) {
        requireSelection()
        viewModelScope.launch {
            _state.update { it.copy(working = true, error = null) }
            try {
                val receipt = block() ?: return@launch
                clearSelection()
                _messages.emit(
                    ExpressiveBrowseMessage(
                        text = "${receipt.affectedCount} item(s) updated.",
                        undoStep = receipt.undoStep,
                    ),
                )
                scheduleSearch(delayMillis = 0)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showError(e)
            } finally {
                _state.update { it.copy(working = false) }
            }
        }
    }

    private fun runPreparation(block: suspend () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(working = true, error = null) }
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showError(e)
            } finally {
                _state.update { it.copy(working = false) }
            }
        }
    }

    private fun requireSelection() {
        if (_state.value.selectedRowIds.isEmpty()) {
            _messages.tryEmit(ExpressiveBrowseMessage("Select at least one row."))
            throw BrowseOperationUnavailableException("Select at least one row.")
        }
    }

    private fun currentSelection(): BrowseSelection = BrowseSelection(_state.value.request.mode, _state.value.selectedRowIds)

    private fun showError(error: Exception) {
        val message = error.message?.takeIf { it.isNotBlank() } ?: "Browse operation failed."
        _state.update { it.copy(error = message) }
        _messages.tryEmit(ExpressiveBrowseMessage(message))
    }

    private fun updateRequest(
        request: BrowseSearchRequest,
        searchDelay: Long = 0,
    ) {
        _state.update { it.copy(request = request, dialog = null) }
        persistState()
        scheduleSearch(searchDelay)
    }

    private fun scheduleSearch(delayMillis: Long) {
        if (!collectionAccessEnabled) {
            refreshPending = true
            return
        }
        refreshPending = false
        searchJob?.cancel()
        searchJob =
            viewModelScope.launch {
                if (delayMillis > 0) delay(delayMillis)
                _state.update { it.copy(loading = true, error = null) }
                try {
                    val result = gateway.searchBrowse(_state.value.request)
                    currentCoroutineContext().ensureActive()
                    _state.update { current ->
                        val loadedIds = result.rows.mapTo(hashSetOf()) { it.rowId }
                        val selected = current.selectedRowIds.filter { it in loadedIds }
                        val preview = current.previewRowId?.takeIf { it in loadedIds }
                        current.copy(
                            rows = result.rows,
                            truncated = result.truncated,
                            loading = false,
                            selectionMode = current.selectionMode && (selected.isNotEmpty() || current.selectedRowIds.isEmpty()),
                            selectedRowIds = selected,
                            selectionAnchor = current.selectionAnchor?.takeIf { it in loadedIds },
                            previewRowId = preview,
                        )
                    }
                    persistState()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _state.update {
                        it.copy(
                            loading = false,
                            error = e.message?.takeIf(String::isNotBlank) ?: "Search failed.",
                        )
                    }
                }
            }
    }

    private fun persistState() {
        val value = _state.value
        savedStateHandle[QUERY_KEY] = value.request.query
        savedStateHandle[FILTER_KEY] = value.request.filter.name
        savedStateHandle[MODE_KEY] = value.request.mode.name
        if (value.request.deckId == null) savedStateHandle.remove<Long>(DECK_KEY) else savedStateHandle[DECK_KEY] = value.request.deckId
        savedStateHandle[SELECTION_MODE_KEY] = value.selectionMode
        savedStateHandle[SELECTION_KEY] = encodeRowIds(value.selectedRowIds)
        saveRowId(ANCHOR_KEY, value.selectionAnchor)
        saveRowId(PREVIEW_KEY, value.previewRowId)
    }

    private fun saveRowId(
        key: String,
        rowId: BrowseRowId?,
    ) {
        if (rowId == null) savedStateHandle.remove<String>(key) else savedStateHandle[key] = encodeRowId(rowId)
    }

    companion object {
        private const val SEARCH_DEBOUNCE_MS = 300L
        private const val QUERY_KEY = "expressive.browse.query"
        private const val FILTER_KEY = "expressive.browse.filter"
        private const val MODE_KEY = "expressive.browse.mode"
        private const val DECK_KEY = "expressive.browse.deck"
        private const val SELECTION_MODE_KEY = "expressive.browse.selection-mode"
        private const val SELECTION_KEY = "expressive.browse.selection"
        private const val ANCHOR_KEY = "expressive.browse.anchor"
        private const val PREVIEW_KEY = "expressive.browse.preview"

        fun factory(gateway: ExpressiveBrowseGateway = ExpressiveCollection): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    ExpressiveBrowseViewModel(createSavedStateHandle(), gateway)
                }
            }

        private inline fun <reified T : Enum<T>> enumValue(
            value: String?,
            fallback: T,
        ): T = value?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback

        private fun encodeRowId(id: BrowseRowId): String =
            when (id) {
                is BrowseRowId.Card -> "C:${id.value}"
                is BrowseRowId.Note -> "N:${id.value}"
            }

        private fun encodeRowIds(ids: List<BrowseRowId>): String = ids.joinToString(",", transform = ::encodeRowId)

        private fun decodeRowIds(
            encoded: String?,
            mode: ExpressiveBrowseMode,
        ): List<BrowseRowId> =
            encoded
                .orEmpty()
                .split(',')
                .mapNotNull { decodeRowId(it, mode) }
                .distinct()

        private fun decodeRowId(
            encoded: String?,
            mode: ExpressiveBrowseMode,
        ): BrowseRowId? {
            val parts = encoded?.split(':', limit = 2) ?: return null
            if (parts.size != 2) return null
            val value = parts[1].toLongOrNull()?.takeIf { it > 0 } ?: return null
            return when {
                mode == ExpressiveBrowseMode.CARDS && parts[0] == "C" -> BrowseRowId.Card(value)
                mode == ExpressiveBrowseMode.NOTES && parts[0] == "N" -> BrowseRowId.Note(value)
                else -> null
            }
        }
    }
}
