// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.ui.expressive

import android.content.Context
import android.net.Uri
import anki.collection.opChanges
import anki.decks.SetDeckCollapsedRequest
import anki.scheduler.CardAnswer.Rating
import anki.scheduler.UnburyDeckRequest
import anki.scheduler.copy
import anki.scheduler.customStudyRequest
import com.ichi2.anki.cardviewer.AndroidCardRenderContext
import com.ichi2.anki.cardviewer.SingleCardSide
import com.ichi2.anki.cardviewer.TypeAnswer
import com.ichi2.anki.collection.CollectionRepository
import com.ichi2.anki.collection.CollectionWriteResult
import com.ichi2.anki.collection.DefaultCollectionRepository
import com.ichi2.anki.common.annotations.NeedsTest
import com.ichi2.anki.common.preferences.sharedPrefs
import com.ichi2.anki.libanki.Card
import com.ichi2.anki.libanki.CardId
import com.ichi2.anki.libanki.CardType
import com.ichi2.anki.libanki.Collection
import com.ichi2.anki.libanki.DeckId
import com.ichi2.anki.libanki.NoteId
import com.ichi2.anki.libanki.QueueType
import com.ichi2.anki.libanki.notesOfCards
import com.ichi2.anki.libanki.sched.CurrentQueueState
import com.ichi2.anki.libanki.sched.DeckNode
import com.ichi2.anki.libanki.sched.SetDueDateDays
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/** Display model of a deck for the expressive home screen. */
data class UiDeck(
    val did: DeckId,
    val name: String,
    val fullDeckName: String,
    val depth: Int,
    val filtered: Boolean,
    val collapsed: Boolean,
    val newCount: Int,
    val learnCount: Int,
    val dueCount: Int,
    val children: List<UiDeck>,
)

/** One rendered card side pair, ready for the WebView. */
data class StudyCardUi(
    val queueState: CurrentQueueState,
    val frontHtml: String,
    val baseUrl: String,
    val autoplay: Boolean,
    val marked: Boolean,
    val flag: Int,
    /** Non-null when the card expects a typed answer. */
    val typeCorrect: String?,
    val typeUseInputTag: Boolean,
    /** In-memory backend reconstruction identity; never persisted across processes. */
    val renderRevision: Long = 0,
)

internal const val EXPRESSIVE_CARD_ORIGIN = "https://appassets.androidplatform.net/"
internal const val EXPRESSIVE_CARD_ORIGIN_HOST = "appassets.androidplatform.net"
internal const val EXPRESSIVE_ANDROID_ASSET_ORIGIN = "${EXPRESSIVE_CARD_ORIGIN}_anki/android_asset/"
private const val ANDROID_ASSET_FILE_ORIGIN = "file:///android_asset/"

internal fun mediaDirectoryBaseUrl(directory: File): String {
    val encoded = Uri.fromFile(directory).toString()
    return if (encoded.endsWith('/')) encoded else "$encoded/"
}

internal fun rewriteCardMediaOrigin(
    html: String,
    mediaDirectory: File,
): String =
    html
        .replace(mediaDirectoryBaseUrl(mediaDirectory), EXPRESSIVE_CARD_ORIGIN)
        .replace(ANDROID_ASSET_FILE_ORIGIN, EXPRESSIVE_ANDROID_ASSET_ORIGIN)

enum class UiCardState { NEW, LEARNING, REVIEW, SUSPENDED, BURIED }

/** A browsed card or representative note row flattened to plain text for list display. */
data class UiCard(
    val cardId: CardId,
    val question: String,
    val answer: String,
    val deckName: String,
    val state: UiCardState,
    val dueLabel: String,
    val rowId: BrowseRowId = BrowseRowId.Card(cardId),
    val noteId: NoteId = 0,
    val deckId: DeckId = 0,
    val flag: Int = 0,
    val marked: Boolean = false,
    val buried: Boolean = false,
    val siblingCount: Int = 1,
)

/** Review count for one concrete scheduler day. */
data class DailyReviewCount(
    val date: LocalDate,
    val count: Int,
)

/** Real statistics for the Stats tab. */
data class ExpressiveStats(
    val reviewsToday: Int,
    val minutesToday: Int,
    val againToday: Int,
    val retentionToday: Int,
    val dueToday: Int,
    val newLeftToday: Int,
    val streak: Int,
    val dailyReviews: List<DailyReviewCount>,
    val totalCards: Int,
    val totalNotes: Int,
    val deckCount: Int,
    val totalReviews: Int,
    val mixNew: Int,
    val mixLearn: Int,
    val mixReview: Int,
    val mixSuspended: Int,
)

/** Bridges the real collection to the expressive UI. */
object ExpressiveCollection :
    ExpressiveDeckGateway,
    ExpressiveStudyGateway,
    InitiatedExpressiveStudyGateway,
    ExpressiveBrowseGateway,
    ExpressiveStatsGateway {
    private val repository: CollectionRepository = DefaultCollectionRepository

    override val collectionChanges = repository.changes

    /** Real deck tree (counts include subdecks, matching the classic deck picker). */
    @NeedsTest("empty collection yields empty list")
    override suspend fun loadDecks(): List<UiDeck> =
        repository.read {
            sched.deckDueTree().children.map { it.toUiDeck(0) }
        }

    private fun DeckNode.toUiDeck(depth: Int): UiDeck =
        UiDeck(
            did = did,
            name = lastDeckNameComponent,
            fullDeckName = fullDeckName,
            depth = depth,
            filtered = filtered,
            collapsed = collapsed,
            newCount = newCount,
            learnCount = lrnCount,
            dueCount = revCount,
            children = children.map { it.toUiDeck(depth + 1) },
        )

    override suspend fun setCollapsed(
        did: DeckId,
        collapsed: Boolean,
    ) = repository.write(initiator = ExpressiveCollection) {
        val changes = decks.setCollapsed(did, collapsed, SetDeckCollapsedRequest.Scope.REVIEWER)
        CollectionWriteResult(value = changes, changes = changes)
    }

    override suspend fun selectedDeckId(): DeckId = repository.read { decks.selected() }

    override suspend fun renameDeck(
        did: DeckId,
        newName: String,
    ) = repository.write(initiator = ExpressiveCollection) {
        val normalized = newName.trim()
        require(normalized.isNotEmpty()) { "Deck name cannot be empty." }
        val duplicate =
            decks
                .allNamesAndIds(skipEmptyDefault = false, includeFiltered = true)
                .any { it.id != did && it.name.equals(normalized, ignoreCase = true) }
        require(!duplicate) { "A deck with that name already exists." }
        val changes = decks.rename(did, normalized)
        CollectionWriteResult(value = changes, changes = changes)
    }

    override suspend fun deletePreview(did: DeckId): ExpressiveDeckDeletePreview =
        repository.read {
            ExpressiveDeckDeletePreview(
                cardCount = decks.cardCount(did, includeSubdecks = true),
                filtered = decks.isFiltered(did),
            )
        }

    override suspend fun deleteDeck(did: DeckId): ExpressiveDeckDeletion =
        repository.write(initiator = ExpressiveCollection) {
            val result = decks.remove(listOf(did))
            val undoStatus = undoStatus()
            CollectionWriteResult(
                value =
                    ExpressiveDeckDeletion(
                        deletedCards = result.count,
                        undoStep = undoStatus.undo?.let { undoStatus.lastStep },
                    ),
                changes = result.changes,
            )
        }

    override suspend fun undoDeckDeletion(expectedUndoStep: Int): Boolean =
        repository.write(initiator = ExpressiveCollection) {
            val undoStatus = undoStatus()
            if (undoStatus.undo == null || undoStatus.lastStep != expectedUndoStep) {
                CollectionWriteResult(value = false, changes = opChanges {})
            } else {
                val result = undo()
                CollectionWriteResult(value = true, changes = result.changes)
            }
        }

    override suspend fun createDeck(
        name: String,
        parentDid: DeckId?,
    ): DeckId =
        repository.write(initiator = ExpressiveCollection) {
            val relativeName = name.trim()
            require(relativeName.isNotEmpty()) { "Deck name cannot be empty." }
            val normalized =
                parentDid?.let { parent ->
                    requireNotNull(decks.getSubdeckName(parent, relativeName)) {
                        "The parent deck is no longer available."
                    }
                } ?: relativeName
            val duplicate =
                decks
                    .allNamesAndIds(skipEmptyDefault = false, includeFiltered = true)
                    .any { it.name.equals(normalized, ignoreCase = true) }
            require(!duplicate) { "A deck with that name already exists." }
            val result = decks.addNormalDeckWithName(normalized)
            CollectionWriteResult(value = result.id, changes = result.changes)
        }

    override suspend fun customStudyDefaults(did: DeckId): ExpressiveCustomStudyDefaults =
        repository.read {
            val defaults = sched.customStudyDefaults(did)
            ExpressiveCustomStudyDefaults(
                extendNew = defaults.extendNew,
                extendReview = defaults.extendReview,
                availableNew = defaults.availableNew,
                availableNewInChildren = defaults.availableNewInChildren,
                availableReview = defaults.availableReview,
                availableReviewInChildren = defaults.availableReviewInChildren,
                tags = defaults.tagsList.map { it.name },
            )
        }

    /** Extends limits or creates/rebuilds the backend-owned Custom Study filtered deck. */
    override suspend fun customStudy(
        did: DeckId,
        action: ExpressiveCustomStudyAction,
    ): ExpressiveCustomStudyResult =
        repository.write(initiator = ExpressiveCollection) {
            require(action.amount != 0) { "Amount must not be zero." }
            val request =
                customStudyRequest {
                    deckId = did
                    when (action) {
                        is ExpressiveCustomStudyAction.ExtendNew -> newLimitDelta = action.amount
                        is ExpressiveCustomStudyAction.ExtendReview -> reviewLimitDelta = action.amount
                        is ExpressiveCustomStudyAction.Forgotten -> forgotDays = action.amount
                        is ExpressiveCustomStudyAction.ReviewAhead -> reviewAheadDays = action.amount
                        is ExpressiveCustomStudyAction.PreviewNew -> previewDays = action.amount
                        is ExpressiveCustomStudyAction.Cram ->
                            cram =
                                cram.copy {
                                    kind = action.kind
                                    cardLimit = action.amount
                                    tagsToInclude.addAll(action.includeTags)
                                    tagsToExclude.addAll(action.excludeTags)
                                }
                    }
                }
            val changes = sched.customStudy(request)
            CollectionWriteResult(
                value = ExpressiveCustomStudyResult(sessionDeckId = if (action.createsSession) decks.selected() else null),
                changes = changes,
            )
        }

    /** Compatibility entry point for existing contract callers that already own a backend request. */
    suspend fun customStudy(request: anki.scheduler.CustomStudyRequest) =
        repository.write(initiator = ExpressiveCollection) {
            val changes = sched.customStudy(request)
            CollectionWriteResult(value = changes, changes = changes)
        }

    override suspend fun rebuildFilteredDeck(did: DeckId) =
        repository.write(initiator = ExpressiveCollection) {
            val result = sched.rebuildFilteredDeck(did)
            CollectionWriteResult(value = Unit, changes = result.changes)
        }

    override suspend fun emptyFilteredDeck(did: DeckId) =
        repository.write(initiator = ExpressiveCollection) {
            val changes = sched.emptyFilteredDeck(did)
            CollectionWriteResult(value = Unit, changes = changes)
        }

    override suspend fun selectDeck(did: DeckId) = selectDeck(did, expressiveStudyWriteInitiatorOr(ExpressiveCollection))

    override suspend fun selectDeck(
        did: DeckId,
        initiator: Any,
    ) = repository.write(initiator = initiator) {
        decks.select(did)
        CollectionWriteResult(
            value = Unit,
            changes = opChanges { studyQueues = true },
        )
    }

    override suspend fun startTimebox() {
        repository.read { this.startTimebox() }
    }

    override suspend fun congratulationsInfo(deckId: DeckId): ExpressiveCongratulationsInfo =
        repository.read {
            val info = sched.congratulationsInfo()
            ExpressiveCongratulationsInfo(
                deckId = deckId,
                isFiltered = decks.isFiltered(deckId),
                secsUntilNextLearn = info.secsUntilNextLearn,
                haveUserBuried = info.haveUserBuried,
                haveSchedBuried = info.haveSchedBuried,
            )
        }

    /** Renders the current card's question side; null when the queue is empty. */
    override suspend fun currentCard(context: Context): StudyCardUi? =
        repository.read {
            val state = sched.currentQueueState() ?: return@read null
            val renderContext = renderContext(context)
            renderContext.prepareTypeAnswer(this, state.topCard, context.resources)
            val (typeEnabled, typeCorrect) = renderContext.typeAnswerInfo()
            val mediaDirectory = media.dir
            val frontHtml = renderContext.renderCard(this, state.topCard, SingleCardSide.FRONT).html
            StudyCardUi(
                queueState = state,
                frontHtml = rewriteCardMediaOrigin(frontHtml, mediaDirectory),
                baseUrl = EXPRESSIVE_CARD_ORIGIN,
                autoplay = state.topCard.autoplay(this),
                marked = state.topCard.note(this).hasTag(this, "marked"),
                flag = state.topCard.userFlag(),
                typeCorrect = typeCorrect,
                typeUseInputTag = typeEnabled && renderContext.typeAnswerUseInputTag,
            )
        }

    /** Renders the answer side and the four next-interval labels.
     *  [typedInput] is the learner's typed answer for type-answer cards. */
    override suspend fun revealCard(
        context: Context,
        state: CurrentQueueState,
        typedInput: String?,
    ): Pair<String, List<String>> =
        repository.read {
            val renderContext = renderContext(context)
            renderContext.prepareTypeAnswer(this, state.topCard, context.resources)
            if (typedInput != null) {
                renderContext.setTypeAnswerInput(typedInput)
            }
            val back = renderContext.renderCard(this, state.topCard, SingleCardSide.BACK).html
            rewriteCardMediaOrigin(back, media.dir) to sched.describeNextStates(state.states)
        }

    override suspend fun answer(
        state: CurrentQueueState,
        rating: Rating,
    ): ReviewAnswerResult = answer(state, rating, expressiveStudyWriteInitiatorOr(ExpressiveCollection))

    override suspend fun answer(
        state: CurrentQueueState,
        rating: Rating,
        initiator: Any,
    ): ReviewAnswerResult =
        repository.write(initiator = initiator) {
            val wasLeech = sched.stateIsLeech(sched.buildAnswer(state.topCard, state.states, rating).newState)
            val changes = sched.answerCard(state, rating)
            val leech =
                if (wasLeech) {
                    ExpressiveLeechOutcome(suspended = getCard(state.topCard.id).queue.code < 0)
                } else {
                    null
                }
            val undoStatus = undoStatus()
            val result =
                ReviewAnswerResult(
                    undoToken =
                        undoStatus.undo?.let {
                            ReviewUndoToken(
                                lastStep = undoStatus.lastStep,
                                cardId = state.topCard.id,
                            )
                        },
                    leech = leech,
                )
            CollectionWriteResult(value = result, changes = changes)
        }

    /** Toggles the "marked" tag on the current card; returns the new marked state. */
    override suspend fun toggleMark(state: CurrentQueueState): Boolean =
        toggleMark(state, expressiveStudyWriteInitiatorOr(ExpressiveCollection))

    override suspend fun toggleMark(
        state: CurrentQueueState,
        initiator: Any,
    ): Boolean =
        repository.write(initiator = initiator) {
            // Re-read the note: the cached queue card's tags go stale after the
            // first toggle, which would make every second tap a no-op.
            val note = getCard(state.topCard.id).note(this)
            if (note.hasTag(this, "marked")) note.removeTag("marked") else note.addTag("marked")
            val changes = updateNote(note)
            CollectionWriteResult(value = note.hasTag(this, "marked"), changes = changes)
        }

    /** Cycles the card flag 0 -> 1 .. 7 -> 0; returns the new flag. */
    override suspend fun cycleFlag(state: CurrentQueueState): Int = cycleFlag(state, expressiveStudyWriteInitiatorOr(ExpressiveCollection))

    override suspend fun cycleFlag(
        state: CurrentQueueState,
        initiator: Any,
    ): Int =
        repository.write(initiator = initiator) {
            // Fresh read for the same reason as in toggleMark.
            val card = getCard(state.topCard.id)
            val next = (card.userFlag() + 1) % 8
            val result = setUserFlagForCards(listOf(card.id), next)
            CollectionWriteResult(value = next, changes = result.changes)
        }

    /** Sets the current card flag directly; 0 clears it and 1..7 select a color. */
    override suspend fun setFlag(
        state: CurrentQueueState,
        flag: Int,
    ): Int = setFlag(state, flag, expressiveStudyWriteInitiatorOr(ExpressiveCollection))

    override suspend fun setFlag(
        state: CurrentQueueState,
        flag: Int,
        initiator: Any,
    ): Int =
        repository.write(initiator = initiator) {
            require(flag in 0..7) { "Flag must be between 0 and 7." }
            val cardId = state.topCard.id
            val result = setUserFlagForCards(listOf(cardId), flag)
            CollectionWriteResult(value = flag, changes = result.changes)
        }

    override suspend fun buryCurrent(state: CurrentQueueState) = buryCurrent(state, expressiveStudyWriteInitiatorOr(ExpressiveCollection))

    override suspend fun buryCurrent(
        state: CurrentQueueState,
        initiator: Any,
    ) = repository.write(initiator = initiator) {
        val result = sched.buryCards(listOf(state.topCard.id))
        CollectionWriteResult(value = result, changes = result.changes)
    }

    override suspend fun buryNote(state: CurrentQueueState) = buryNote(state, expressiveStudyWriteInitiatorOr(ExpressiveCollection))

    override suspend fun buryNote(
        state: CurrentQueueState,
        initiator: Any,
    ) = repository.write(initiator = initiator) {
        val result = sched.buryNotes(listOf(state.topCard.nid))
        CollectionWriteResult(value = result, changes = result.changes)
    }

    override suspend fun suspendCurrent(state: CurrentQueueState) =
        suspendCurrent(state, expressiveStudyWriteInitiatorOr(ExpressiveCollection))

    override suspend fun suspendCurrent(
        state: CurrentQueueState,
        initiator: Any,
    ) = repository.write(initiator = initiator) {
        val result = sched.suspendCards(listOf(state.topCard.id))
        CollectionWriteResult(value = result, changes = result.changes)
    }

    override suspend fun suspendNote(state: CurrentQueueState) = suspendNote(state, expressiveStudyWriteInitiatorOr(ExpressiveCollection))

    override suspend fun suspendNote(
        state: CurrentQueueState,
        initiator: Any,
    ) = repository.write(initiator = initiator) {
        val result = sched.suspendNotes(listOf(state.topCard.nid))
        CollectionWriteResult(value = result, changes = result.changes)
    }

    override suspend fun undoReview(expectedToken: ReviewUndoToken): UndoReviewResult =
        undoReview(expectedToken, expressiveStudyWriteInitiatorOr(ExpressiveCollection))

    override suspend fun undoReview(
        expectedToken: ReviewUndoToken,
        initiator: Any,
    ): UndoReviewResult =
        repository.write(initiator = initiator) {
            val undoStatus = undoStatus()
            if (undoStatus.undo == null || undoStatus.lastStep != expectedToken.lastStep) {
                CollectionWriteResult(
                    value = UndoReviewResult.NotOwnedOrNoLongerTop,
                    changes = opChanges {},
                )
            } else {
                val result = undo()
                CollectionWriteResult(value = UndoReviewResult.Undone, changes = result.changes)
            }
        }

    override suspend fun unburyDeck(
        deckId: DeckId,
        mode: UnburyDeckRequest.Mode,
    ) = repository.write(initiator = expressiveStudyWriteInitiatorOr(ExpressiveCollection)) {
        val changes = sched.unburyDeck(deckId, mode)
        CollectionWriteResult(value = changes, changes = changes)
    }

    override suspend fun rebuildStudyDeck(deckId: DeckId) =
        repository.write(initiator = expressiveStudyWriteInitiatorOr(ExpressiveCollection)) {
            val result = sched.rebuildFilteredDeck(deckId)
            CollectionWriteResult(value = result.changes, changes = result.changes)
        }

    override suspend fun emptyStudyDeck(deckId: DeckId) =
        repository.write(initiator = expressiveStudyWriteInitiatorOr(ExpressiveCollection)) {
            val changes = sched.emptyFilteredDeck(deckId)
            CollectionWriteResult(value = changes, changes = changes)
        }

    /** Real statistics for the Stats tab, computed from the collection. */
    override suspend fun loadStats(): ExpressiveStats =
        repository.read {
            val cutoff = sched.dayCutoff
            val zoneId = ZoneId.systemDefault()
            val dailyBuckets = expressiveStatsDayBuckets(cutoff, zoneId, numberOfDays = 7)
            val today = dailyBuckets.last()
            val reviewsToday =
                db.queryScalar(
                    "SELECT count() FROM revlog WHERE id >= ? AND id < ?",
                    today.startMillis,
                    today.endMillis,
                )
            val msToday =
                db.queryScalar(
                    "SELECT COALESCE(sum(time), 0) FROM revlog WHERE id >= ? AND id < ?",
                    today.startMillis,
                    today.endMillis,
                )
            val againToday =
                db.queryScalar(
                    "SELECT count() FROM revlog WHERE id >= ? AND id < ? AND ease = 1",
                    today.startMillis,
                    today.endMillis,
                )
            val retentionToday =
                if (reviewsToday > 0) (reviewsToday - againToday) * 100 / reviewsToday else 100

            val tree = sched.deckDueTree()

            val dailyCounts = IntArray(dailyBuckets.size)
            db
                .query(
                    "SELECT id FROM revlog WHERE id >= ? AND id < ?",
                    dailyBuckets.first().startMillis,
                    dailyBuckets.last().endMillis,
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val index = dailyBuckets.indexOfTimestamp(cursor.getLong(0))
                        if (index >= 0) dailyCounts[index]++
                    }
                }
            val dailyReviews =
                dailyBuckets.mapIndexed { index, bucket ->
                    DailyReviewCount(date = bucket.date, count = dailyCounts[index])
                }

            val streakBuckets = expressiveStatsDayBuckets(cutoff, zoneId, numberOfDays = 401)
            val reviewedDays = HashSet<Int>()
            db
                .query(
                    "SELECT id FROM revlog WHERE id >= ? AND id < ?",
                    streakBuckets.first().startMillis,
                    streakBuckets.last().endMillis,
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val index = streakBuckets.indexOfTimestamp(cursor.getLong(0))
                        if (index >= 0) reviewedDays.add(index)
                    }
                }
            val streak = currentReviewStreak(reviewedDays, streakBuckets.lastIndex)

            val totalCards = cardCount()
            val mixNew = db.queryScalar("SELECT count() FROM cards WHERE queue = 0")
            val mixLearn = db.queryScalar("SELECT count() FROM cards WHERE queue IN (1, 3)")
            val mixSuspended = db.queryScalar("SELECT count() FROM cards WHERE queue = -1")
            val mixReview = (totalCards - mixNew - mixLearn - mixSuspended).coerceAtLeast(0)

            ExpressiveStats(
                reviewsToday = reviewsToday,
                minutesToday = msToday / 60_000,
                againToday = againToday,
                retentionToday = retentionToday,
                dueToday = tree.revCount + tree.lrnCount,
                newLeftToday = tree.newCount,
                streak = streak,
                dailyReviews = dailyReviews,
                totalCards = totalCards,
                totalNotes = db.queryScalar("SELECT count() FROM notes"),
                deckCount = decks.count() - 1,
                totalReviews = db.queryScalar("SELECT count() FROM revlog"),
                mixNew = mixNew,
                mixLearn = mixLearn,
                mixReview = mixReview,
                mixSuspended = mixSuspended,
            )
        }

    /** Legacy card-only search retained for compatibility callers and gateway test doubles. */
    override suspend fun searchCards(
        query: String,
        filter: String,
        limit: Int,
    ): List<UiCard> =
        searchBrowse(
            BrowseSearchRequest(
                query = query,
                filter =
                    runCatching { ExpressiveBrowseFilter.valueOf(filter.uppercase()) }
                        .getOrDefault(ExpressiveBrowseFilter.ALL),
                limit = limit,
            ),
        ).rows

    /** Typed card/note search with an explicit truncation marker. */
    override suspend fun searchBrowse(request: BrowseSearchRequest): BrowseSearchResult =
        repository.read {
            require(request.limit > 0) { "Browse limit must be positive." }
            val fullQuery = buildBrowseQuery(request)
            val ids =
                when (request.mode) {
                    ExpressiveBrowseMode.CARDS -> findCards(fullQuery)
                    ExpressiveBrowseMode.NOTES -> findNotes(fullQuery)
                }
            val truncated = ids.size > request.limit
            val rows =
                ids.take(request.limit).map { id ->
                    when (request.mode) {
                        ExpressiveBrowseMode.CARDS -> {
                            val card = getCard(id)
                            cardToBrowseRow(card, BrowseRowId.Card(id), siblingCount = 1)
                        }
                        ExpressiveBrowseMode.NOTES -> noteToBrowseRow(id)
                    }
                }
            BrowseSearchResult(rows = rows, truncated = truncated)
        }

    override suspend fun deckChoices(): List<ExpressiveDeckChoice> =
        repository.read {
            decks
                .allNamesAndIds(skipEmptyDefault = false, includeFiltered = false)
                .map { ExpressiveDeckChoice(deckId = it.id, name = it.name) }
                .sortedBy { it.name.lowercase() }
        }

    override suspend fun tagSelection(selection: BrowseSelection): BrowseTagSelection {
        validateSelection(selection)
        if (selection.isEmpty) return BrowseTagSelection(emptyList(), emptySet(), emptySet())
        return repository.read {
            val noteIds = normalizeSelection(selection).noteIds
            if (noteIds.isEmpty()) return@read BrowseTagSelection(tags.all(), emptySet(), emptySet())
            val notes = noteIds.map(::getNote)
            val canonicalNames = linkedMapOf<String, String>()
            val occurrences = mutableMapOf<String, Int>()
            notes.forEach { note ->
                note.tags.distinctBy { it.lowercase() }.forEach { tag ->
                    val key = tag.lowercase()
                    canonicalNames.putIfAbsent(key, tag)
                    occurrences[key] = occurrences.getOrDefault(key, 0) + 1
                }
            }
            val selected =
                occurrences.filterValues { it == notes.size }.keys.mapTo(linkedSetOf()) { canonicalNames.getValue(it) }
            val indeterminate =
                occurrences
                    .filterValues { it in 1 until notes.size }
                    .keys
                    .mapTo(linkedSetOf()) { canonicalNames.getValue(it) }
            BrowseTagSelection(tags.all(), selected, indeterminate)
        }
    }

    override suspend fun deleteNotes(selection: BrowseSelection): BrowseMutationReceipt? {
        validateSelection(selection)
        if (selection.isEmpty) return null
        return repository.write(initiator = ExpressiveCollection) {
            val normalized = normalizeSelection(selection)
            val result =
                when (selection.mode) {
                    ExpressiveBrowseMode.CARDS -> removeNotes(cardIds = normalized.cardIds)
                    ExpressiveBrowseMode.NOTES -> removeNotes(noteIds = normalized.noteIds)
                }
            CollectionWriteResult(
                value = mutationReceipt(BrowseMutation.DELETE_NOTES, result.count),
                changes = result.changes,
            )
        }
    }

    override suspend fun forget(
        selection: BrowseSelection,
        options: ForgetOptions,
    ): BrowseMutationReceipt? {
        validateSelection(selection)
        if (selection.isEmpty) return null
        return repository.write(initiator = ExpressiveCollection) {
            val cardIds = normalizeSelection(selection).requireCards()
            val changes = sched.forgetCards(cardIds, options.restorePosition, options.resetCounts)
            CollectionWriteResult(
                value = mutationReceipt(BrowseMutation.FORGET, cardIds.size),
                changes = changes,
            )
        }
    }

    override suspend fun setDueDate(
        selection: BrowseSelection,
        spec: DueDateSpec,
    ): BrowseMutationReceipt? {
        validateSelection(selection)
        if (selection.isEmpty) return null
        return repository.write(initiator = ExpressiveCollection) {
            val cardIds = normalizeSelection(selection).requireCards()
            val forceInterval = config.get<Boolean>("fsrs", false) == true
            val changes = sched.setDueDate(cardIds, SetDueDateDays(spec.backendValue(forceInterval)))
            CollectionWriteResult(
                value = mutationReceipt(BrowseMutation.SET_DUE_DATE, cardIds.size),
                changes = changes,
            )
        }
    }

    override suspend fun prepareReposition(selection: BrowseSelection): RepositionPreview {
        validateSelection(selection)
        if (selection.isEmpty) throw BrowseOperationUnavailableException("Select at least one row.")
        return repository.read {
            val cards = normalizeSelection(selection).requireCards().map(::getCard)
            val eligibleCount = cards.count { it.canReposition() }
            if (eligibleCount == 0) {
                throw BrowseOperationUnavailableException("None of the selected cards can be repositioned.")
            }
            val bounds =
                db
                    .query("select min(due), max(due) from cards where type=? and odid=0", CardType.New.code)
                    .use { cursor ->
                        if (cursor.moveToNext()) {
                            maxOf(0, cursor.getInt(0)) to cursor.getInt(1)
                        } else {
                            null
                        }
                    }
            val defaults = sched.repositionDefaults()
            RepositionPreview(
                minimumPosition = bounds?.first,
                maximumPosition = bounds?.second,
                random = defaults.random,
                shift = defaults.shift,
                eligibleCount = eligibleCount,
                unsupportedCount = cards.size - eligibleCount,
            )
        }
    }

    override suspend fun reposition(
        selection: BrowseSelection,
        request: RepositionRequest,
    ): BrowseMutationReceipt? {
        validateSelection(selection)
        if (selection.isEmpty) return null
        return repository.write(initiator = ExpressiveCollection) {
            val cardIds = normalizeSelection(selection).requireCards()
            if (cardIds.none { getCard(it).canReposition() }) {
                throw BrowseOperationUnavailableException("None of the selected cards can be repositioned.")
            }
            val result =
                sched.sortCards(
                    cids = cardIds,
                    start = request.position,
                    step = request.step,
                    shuffle = request.random,
                    shift = request.shift,
                )
            CollectionWriteResult(
                value = mutationReceipt(BrowseMutation.REPOSITION, result.count),
                changes = result.changes,
            )
        }
    }

    override suspend fun moveToDeck(
        selection: BrowseSelection,
        deckId: DeckId,
    ): BrowseMutationReceipt? {
        validateSelection(selection)
        require(deckId > 0) { "A destination deck is required." }
        if (selection.isEmpty) return null
        return repository.write(initiator = ExpressiveCollection) {
            if (decks.isFiltered(deckId)) {
                throw BrowseOperationUnavailableException("Cards cannot be moved into a filtered deck.")
            }
            val result = setDeck(normalizeSelection(selection).requireCards(), deckId)
            CollectionWriteResult(
                value = mutationReceipt(BrowseMutation.MOVE_DECK, result.count),
                changes = result.changes,
            )
        }
    }

    override suspend fun editTags(
        selection: BrowseSelection,
        request: TagFinalStateRequest,
    ): BrowseMutationReceipt? {
        validateSelection(selection)
        if (selection.isEmpty) return null
        return repository.write(initiator = ExpressiveCollection) {
            val noteIds = normalizeSelection(selection).noteIds
            if (noteIds.isEmpty()) throw BrowseOperationUnavailableException("The selection contains no notes.")
            val selected = request.selectedTags.normalizedTags()
            val indeterminate = request.indeterminateTags.normalizedTags()
            val changedNotes =
                noteIds.map(::getNote).filter { note ->
                    val previous = note.tags.toList()
                    val updated =
                        buildList {
                            previous.forEach { tag ->
                                val key = tag.lowercase()
                                if (key in indeterminate || key in selected) add(tag)
                            }
                            selected.forEach { (_, tag) ->
                                if (none { it.equals(tag, ignoreCase = true) }) add(tag)
                            }
                        }
                    if (previous.map { it.lowercase() } == updated.map { it.lowercase() }) {
                        false
                    } else {
                        note.tags = updated.toMutableList()
                        true
                    }
                }
            if (changedNotes.isEmpty()) throw BrowseOperationUnavailableException("The selected tags are already applied.")
            val changes = updateNotes(changedNotes)
            CollectionWriteResult(
                value = mutationReceipt(BrowseMutation.EDIT_TAGS, changedNotes.size),
                changes = changes,
            )
        }
    }

    override suspend fun toggleMark(selection: BrowseSelection): BrowseMutationReceipt? {
        validateSelection(selection)
        if (selection.isEmpty) return null
        return repository.write(initiator = ExpressiveCollection) {
            val notes = normalizeSelection(selection).noteIds.map(::getNote)
            if (notes.isEmpty()) throw BrowseOperationUnavailableException("The selection contains no notes.")
            val removeMark = notes.all { it.hasTag(this, "marked") }
            notes.forEach { note ->
                if (removeMark) {
                    note.removeTag("marked")
                } else if (!note.hasTag(this, "marked")) {
                    note.addTag("marked")
                }
            }
            val changes = updateNotes(notes)
            CollectionWriteResult(
                value = mutationReceipt(BrowseMutation.MARK, notes.size),
                changes = changes,
            )
        }
    }

    override suspend fun setFlag(
        selection: BrowseSelection,
        flag: Int,
    ): BrowseMutationReceipt? {
        validateSelection(selection)
        require(flag in 0..7) { "Flag must be between 0 and 7." }
        if (selection.isEmpty) return null
        return repository.write(initiator = ExpressiveCollection) {
            val result = setUserFlagForCards(normalizeSelection(selection).requireCards(), flag)
            CollectionWriteResult(
                value = mutationReceipt(BrowseMutation.FLAG, result.count),
                changes = result.changes,
            )
        }
    }

    override suspend fun toggleSuspend(selection: BrowseSelection): BrowseMutationReceipt? {
        validateSelection(selection)
        if (selection.isEmpty) return null
        return repository.write(initiator = ExpressiveCollection) {
            val cardIds = normalizeSelection(selection).requireCards()
            val unsuspend = cardIds.all { getCard(it).queue == QueueType.Suspended }
            if (unsuspend) {
                val changes = sched.unsuspendCards(cardIds)
                CollectionWriteResult(
                    value = mutationReceipt(BrowseMutation.SUSPEND, cardIds.size),
                    changes = changes,
                )
            } else {
                val result = sched.suspendCards(cardIds)
                CollectionWriteResult(
                    value = mutationReceipt(BrowseMutation.SUSPEND, result.count),
                    changes = result.changes,
                )
            }
        }
    }

    override suspend fun toggleBury(selection: BrowseSelection): BrowseMutationReceipt? {
        validateSelection(selection)
        if (selection.isEmpty) return null
        return repository.write(initiator = ExpressiveCollection) {
            val cardIds = normalizeSelection(selection).requireCards()
            val unbury = cardIds.all { getCard(it).isBuried() }
            if (unbury) {
                val changes = sched.unburyCards(cardIds)
                CollectionWriteResult(
                    value = mutationReceipt(BrowseMutation.BURY, cardIds.size),
                    changes = changes,
                )
            } else {
                val result = sched.buryCards(cardIds)
                CollectionWriteResult(
                    value = mutationReceipt(BrowseMutation.BURY, result.count),
                    changes = result.changes,
                )
            }
        }
    }

    override suspend fun undo(step: BrowseUndoStep): Boolean =
        repository.write(initiator = ExpressiveCollection) {
            val status = undoStatus()
            if (status.undo == null || status.lastStep != step.backendStep) {
                throw BrowseOperationUnavailableException("This operation is no longer the latest undo step.")
            }
            val result = undo()
            CollectionWriteResult(value = true, changes = result.changes)
        }

    private fun Collection.buildBrowseQuery(request: BrowseSearchRequest): String =
        buildString {
            append(request.query.trim())
            when (request.filter) {
                ExpressiveBrowseFilter.ALL -> Unit
                ExpressiveBrowseFilter.DUE -> append(" is:due")
                ExpressiveBrowseFilter.NEW -> append(" is:new")
                ExpressiveBrowseFilter.SUSPENDED -> append(" is:suspended")
                ExpressiveBrowseFilter.MARKED -> append(" tag:marked")
            }
            request.deckId?.let { deckId ->
                val deckName = decks.name(deckId).replace("\"", "\\\"")
                append(" deck:\"")
                append(deckName)
                append('"')
            }
        }.trim()

    private fun Collection.noteToBrowseRow(noteId: NoteId): UiCard {
        val cardIds = cardIdsOfNote(noteId)
        val representative = cardIds.firstOrNull()?.let(::getCard)
        if (representative != null) {
            return cardToBrowseRow(representative, BrowseRowId.Note(noteId), siblingCount = cardIds.size)
        }
        val note = getNote(noteId)
        return UiCard(
            cardId = 0,
            question = note.fields.firstOrNull().orEmpty(),
            answer = note.fields.drop(1).joinToString(" · "),
            deckName = "No cards",
            state = UiCardState.NEW,
            dueLabel = "note",
            rowId = BrowseRowId.Note(noteId),
            noteId = noteId,
            siblingCount = 0,
            marked = note.hasTag(this, "marked"),
        )
    }

    private fun Collection.cardToBrowseRow(
        card: Card,
        rowId: BrowseRowId,
        siblingCount: Int,
    ): UiCard {
        val note = card.note(this)
        val buried = card.isBuried()
        val days = card.due - sched.today
        return UiCard(
            cardId = card.id,
            question = stripHtml(card.question(this)),
            answer = stripHtml(card.answer(this)),
            deckName = decks.name(card.did),
            state =
                when {
                    card.queue == QueueType.Suspended -> UiCardState.SUSPENDED
                    buried -> UiCardState.BURIED
                    card.type == CardType.New -> UiCardState.NEW
                    card.type == CardType.Lrn -> UiCardState.LEARNING
                    else -> UiCardState.REVIEW
                },
            dueLabel =
                when {
                    card.queue == QueueType.Suspended || buried -> "—"
                    card.type == CardType.New -> "new"
                    card.type == CardType.Lrn -> "soon"
                    days <= 0 -> "today"
                    else -> "in ${days}d"
                },
            rowId = rowId,
            noteId = card.nid,
            deckId = card.did,
            flag = card.userFlag(),
            marked = note.hasTag(this, "marked"),
            buried = buried,
            siblingCount = siblingCount,
        )
    }

    private data class NormalizedBrowseSelection(
        val cardIds: List<CardId>,
        val noteIds: List<NoteId>,
    ) {
        fun requireCards(): List<CardId> =
            cardIds.ifEmpty {
                throw BrowseOperationUnavailableException("The selection contains no cards.")
            }
    }

    private fun Collection.normalizeSelection(selection: BrowseSelection): NormalizedBrowseSelection =
        when (selection.mode) {
            ExpressiveBrowseMode.CARDS -> {
                val cardIds = selection.rowIds.map { (it as BrowseRowId.Card).value }.distinct()
                NormalizedBrowseSelection(cardIds, notesOfCards(cardIds))
            }
            ExpressiveBrowseMode.NOTES -> {
                val noteIds = selection.rowIds.map { (it as BrowseRowId.Note).value }.distinct()
                NormalizedBrowseSelection(noteIds.flatMap(::cardIdsOfNote).distinct(), noteIds)
            }
        }

    private fun validateSelection(selection: BrowseSelection) {
        require(selection.isValid()) { "Browse selection IDs do not match its mode." }
        require(selection.rowIds.all { it.value > 0 }) { "Browse row IDs must be positive." }
    }

    private fun Collection.mutationReceipt(
        mutation: BrowseMutation,
        affectedCount: Int,
    ): BrowseMutationReceipt {
        val status = undoStatus()
        return BrowseMutationReceipt(
            mutation = mutation,
            affectedCount = affectedCount,
            undoStep = status.undo?.let { BrowseUndoStep(status.lastStep, mutation) },
        )
    }

    private fun Card.canReposition(): Boolean = type == CardType.New || queue == QueueType.New

    private fun Card.isBuried(): Boolean = queue == QueueType.ManuallyBuried || queue == QueueType.SiblingBuried

    private fun Set<String>.normalizedTags(): LinkedHashMap<String, String> =
        mapNotNull { raw -> raw.trim().takeIf(String::isNotEmpty) }
            .associateByTo(linkedMapOf()) { it.lowercase() }

    private fun stripHtml(html: String): String =
        html
            .replace(Regex("(?is)<style.*?</style>"), " ")
            .replace(Regex("(?is)<script.*?</script>"), " ")
            .replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun com.ichi2.anki.libanki.Collection.renderContext(context: Context): AndroidCardRenderContext =
        AndroidCardRenderContext.createInstance(
            context,
            this,
            TypeAnswer.createInstance(context.sharedPrefs()),
        )
}
