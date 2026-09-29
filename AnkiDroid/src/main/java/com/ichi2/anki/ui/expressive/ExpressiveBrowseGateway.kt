// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.ui.expressive

import anki.collection.OpChanges
import com.ichi2.anki.collection.CollectionChange
import com.ichi2.anki.libanki.CardId
import com.ichi2.anki.libanki.DeckId
import com.ichi2.anki.libanki.NoteId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

enum class ExpressiveBrowseMode { CARDS, NOTES }

enum class ExpressiveBrowseFilter { ALL, DUE, NEW, SUSPENDED, MARKED }

sealed interface BrowseRowId {
    val value: Long

    data class Card(
        override val value: CardId,
    ) : BrowseRowId

    data class Note(
        override val value: NoteId,
    ) : BrowseRowId
}

data class BrowseSearchRequest(
    val query: String = "",
    val filter: ExpressiveBrowseFilter = ExpressiveBrowseFilter.ALL,
    val mode: ExpressiveBrowseMode = ExpressiveBrowseMode.CARDS,
    val deckId: DeckId? = null,
    val limit: Int = 60,
)

data class BrowseSearchResult(
    val rows: List<UiCard>,
    /** True when at least one result exists after [rows]. */
    val truncated: Boolean,
)

data class BrowseSelection(
    val mode: ExpressiveBrowseMode,
    val rowIds: List<BrowseRowId>,
) {
    val isEmpty: Boolean
        get() = rowIds.isEmpty()

    fun isValid(): Boolean =
        rowIds.all {
            when (mode) {
                ExpressiveBrowseMode.CARDS -> it is BrowseRowId.Card
                ExpressiveBrowseMode.NOTES -> it is BrowseRowId.Note
            }
        }
}

enum class BrowseMutation {
    DELETE_NOTES,
    FORGET,
    SET_DUE_DATE,
    REPOSITION,
    MOVE_DECK,
    EDIT_TAGS,
    MARK,
    FLAG,
    SUSPEND,
    BURY,
}

data class BrowseUndoStep(
    val backendStep: Int,
    val mutation: BrowseMutation,
)

data class BrowseMutationReceipt(
    val mutation: BrowseMutation,
    val affectedCount: Int,
    val undoStep: BrowseUndoStep?,
)

data class ForgetOptions(
    val restorePosition: Boolean = true,
    val resetCounts: Boolean = false,
)

data class DueDateSpec(
    val startDay: Int,
    val endDay: Int? = null,
    val updateInterval: Boolean = false,
) {
    init {
        require(startDay >= 0) { "The due date cannot be negative." }
        require(endDay == null || endDay >= startDay) { "The end day cannot precede the start day." }
    }

    fun backendValue(forceUpdateInterval: Boolean): String {
        val range = endDay?.takeIf { it != startDay }?.let { "$startDay-$it" } ?: startDay.toString()
        return if (updateInterval || forceUpdateInterval) "$range!" else range
    }
}

data class RepositionPreview(
    val minimumPosition: Int?,
    val maximumPosition: Int?,
    val random: Boolean,
    val shift: Boolean,
    val eligibleCount: Int,
    val unsupportedCount: Int,
)

data class RepositionRequest(
    val position: Int,
    val step: Int = 1,
    val random: Boolean = false,
    val shift: Boolean = false,
) {
    init {
        require(position >= 0) { "Position cannot be negative." }
        require(step > 0) { "Step must be positive." }
    }
}

data class ExpressiveDeckChoice(
    val deckId: DeckId,
    val name: String,
)

data class BrowseTagSelection(
    val allTags: List<String>,
    val selectedTags: Set<String>,
    val indeterminateTags: Set<String>,
)

data class TagFinalStateRequest(
    val selectedTags: Set<String>,
    /** Tags in this set retain their per-note state. */
    val indeterminateTags: Set<String>,
)

class BrowseOperationUnavailableException(
    message: String,
) : IllegalStateException(message)

/** Collection capabilities used by the expressive card browser. */
interface ExpressiveBrowseGateway {
    /** Collection invalidations that may require the current search to be run again. */
    val collectionChanges: Flow<CollectionChange>
        get() = emptyFlow()

    /** Compatibility contract retained for existing lightweight gateway fakes. */
    suspend fun searchCards(
        query: String,
        filter: String,
        limit: Int = 60,
    ): List<UiCard>

    suspend fun searchBrowse(request: BrowseSearchRequest): BrowseSearchResult {
        val rows = searchCards(request.query, request.filter.name, request.limit + 1)
        return BrowseSearchResult(rows = rows.take(request.limit), truncated = rows.size > request.limit)
    }

    suspend fun deckChoices(): List<ExpressiveDeckChoice> = emptyList()

    suspend fun tagSelection(selection: BrowseSelection): BrowseTagSelection = BrowseTagSelection(emptyList(), emptySet(), emptySet())

    suspend fun deleteNotes(selection: BrowseSelection): BrowseMutationReceipt? = unsupported()

    suspend fun forget(
        selection: BrowseSelection,
        options: ForgetOptions,
    ): BrowseMutationReceipt? = unsupported()

    suspend fun setDueDate(
        selection: BrowseSelection,
        spec: DueDateSpec,
    ): BrowseMutationReceipt? = unsupported()

    suspend fun prepareReposition(selection: BrowseSelection): RepositionPreview = unsupported()

    suspend fun reposition(
        selection: BrowseSelection,
        request: RepositionRequest,
    ): BrowseMutationReceipt? = unsupported()

    suspend fun moveToDeck(
        selection: BrowseSelection,
        deckId: DeckId,
    ): BrowseMutationReceipt? = unsupported()

    suspend fun editTags(
        selection: BrowseSelection,
        request: TagFinalStateRequest,
    ): BrowseMutationReceipt? = unsupported()

    suspend fun toggleMark(selection: BrowseSelection): BrowseMutationReceipt? = unsupported()

    suspend fun setFlag(
        selection: BrowseSelection,
        flag: Int,
    ): BrowseMutationReceipt? = unsupported()

    suspend fun toggleSuspend(selection: BrowseSelection): BrowseMutationReceipt? = unsupported()

    suspend fun toggleBury(selection: BrowseSelection): BrowseMutationReceipt? = unsupported()

    suspend fun undo(step: BrowseUndoStep): Boolean = false

    private fun <T> unsupported(): T = throw UnsupportedOperationException("Browse mutations are unavailable.")
}

/** Whether an operation can change a Browse result row, ordering, filter, or deck label. */
internal fun OpChanges.affectsExpressiveBrowser(): Boolean = browserTable || noteText || card || note || deck || studyQueues
