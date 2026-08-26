// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.ui.expressive

import android.content.Context
import anki.decks.SetDeckCollapsedRequest
import anki.scheduler.CardAnswer.Rating
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.cardviewer.AndroidCardRenderContext
import com.ichi2.anki.cardviewer.SingleCardSide
import com.ichi2.anki.cardviewer.TypeAnswer
import com.ichi2.anki.common.annotations.NeedsTest
import com.ichi2.anki.common.preferences.sharedPrefs
import com.ichi2.anki.libanki.CardId
import com.ichi2.anki.libanki.CardType
import com.ichi2.anki.libanki.DeckId
import com.ichi2.anki.libanki.QueueType
import com.ichi2.anki.libanki.sched.CurrentQueueState
import com.ichi2.anki.libanki.sched.DeckNode

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
    val marked: Boolean,
    val flag: Int,
    /** Non-null when the card expects a typed answer. */
    val typeCorrect: String?,
    val typeUseInputTag: Boolean,
)

enum class UiCardState { NEW, LEARNING, REVIEW, SUSPENDED }

/** A browsed card flattened to plain text for list display. */
data class UiCard(
    val cardId: CardId,
    val question: String,
    val answer: String,
    val deckName: String,
    val state: UiCardState,
    val dueLabel: String,
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
    val week: List<Int>,
    val weekMax: Int,
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
object ExpressiveCollection {
    /** Real deck tree (counts include subdecks, matching the classic deck picker). */
    @NeedsTest("empty collection yields empty list")
    suspend fun loadDecks(): List<UiDeck> =
        withCol {
            sched.deckDueTree().children.map { it.toUiDeck(0) }
        }

    /** Creates one type-answer demo card once, so the typing flow can be tried. */
    suspend fun ensureTypeTestCard() {
        withCol {
            if (decks.byName("Gépelős teszt") != null) return@withCol
            notetypes.byName("Basic (type in the answer)")?.let { typeNotetype ->
                val deckId = decks.id("Gépelős teszt")
                val note = newNote(typeNotetype)
                note.setField(0, "What does 'scarce' mean?")
                note.setField(1, "ritka, szűkös")
                addNote(note, deckId)
            }
        }
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

    suspend fun setCollapsed(
        did: DeckId,
        collapsed: Boolean,
    ) = withCol {
        decks.setCollapsed(did, collapsed, SetDeckCollapsedRequest.Scope.REVIEWER)
    }

    suspend fun renameDeck(
        did: DeckId,
        newName: String,
    ) = withCol {
        decks.rename(did, newName)
    }

    suspend fun deleteDeck(did: DeckId) =
        withCol {
            decks.remove(listOf(did))
        }

    suspend fun createDeck(name: String): DeckId =
        withCol {
            decks.id(name)
        }

    /**
     * Runs a custom-study request against [deckId] (extends daily limits or builds a
     * temporary "Custom Study Session" filtered deck, depending on the request).
     */
    suspend fun customStudy(request: anki.scheduler.CustomStudyRequest) =
        com.ichi2.anki.observability
            .undoableOp { sched.customStudy(request) }

    suspend fun selectDeck(did: DeckId) =
        withCol {
            decks.select(did)
        }

    /** Renders the current card's question side; null when the queue is empty. */
    suspend fun currentCard(context: Context): StudyCardUi? =
        withCol {
            val state = sched.currentQueueState() ?: return@withCol null
            val renderContext = renderContext(context)
            renderContext.prepareTypeAnswer(this, state.topCard, context.resources)
            val (typeEnabled, typeCorrect) = renderContext.typeAnswerInfo()
            StudyCardUi(
                queueState = state,
                frontHtml = renderContext.renderCard(this, state.topCard, SingleCardSide.FRONT).html,
                baseUrl = "file://${media.dir}/",
                marked = state.topCard.note(this).hasTag(this, "marked"),
                flag = state.topCard.userFlag(),
                typeCorrect = typeCorrect,
                typeUseInputTag = typeEnabled && renderContext.typeAnswerUseInputTag,
            )
        }

    /** Renders the answer side and the four next-interval labels.
     *  [typedInput] is the learner's typed answer for type-answer cards. */
    suspend fun revealCard(
        context: Context,
        state: CurrentQueueState,
        typedInput: String? = null,
    ): Pair<String, List<String>> =
        withCol {
            val renderContext = renderContext(context)
            renderContext.prepareTypeAnswer(this, state.topCard, context.resources)
            if (typedInput != null) {
                renderContext.setTypeAnswerInput(typedInput)
            }
            val back = renderContext.renderCard(this, state.topCard, SingleCardSide.BACK).html
            back to sched.describeNextStates(state.states)
        }

    suspend fun answer(
        state: CurrentQueueState,
        rating: Rating,
    ) = withCol {
        sched.answerCard(state, rating)
    }

    /** Toggles the "marked" tag on the current card; returns the new marked state. */
    suspend fun toggleMark(state: CurrentQueueState): Boolean =
        withCol {
            // Re-read the note: the cached queue card's tags go stale after the
            // first toggle, which would make every second tap a no-op.
            val note = getCard(state.topCard.id).note(this)
            if (note.hasTag(this, "marked")) note.removeTag("marked") else note.addTag("marked")
            updateNote(note)
            note.hasTag(this, "marked")
        }

    /** Cycles the card flag 0 -> 1 .. 7 -> 0; returns the new flag. */
    suspend fun cycleFlag(state: CurrentQueueState): Int =
        withCol {
            // Fresh read for the same reason as in toggleMark.
            val card = getCard(state.topCard.id)
            val next = (card.userFlag() + 1) % 8
            setUserFlagForCards(listOf(card.id), next)
            next
        }

    suspend fun buryCurrent(state: CurrentQueueState) =
        withCol {
            sched.buryCards(listOf(state.topCard.id))
        }

    suspend fun suspendCurrent(state: CurrentQueueState) =
        withCol {
            sched.suspendCards(listOf(state.topCard.id))
        }

    suspend fun undoLast() =
        withCol {
            undo()
        }

    /** Real statistics for the Stats tab, computed from the collection. */
    suspend fun loadStats(): ExpressiveStats =
        withCol {
            val cutoff = sched.dayCutoff
            // dayCutoff is EpochSeconds (seconds); revlog ids are epoch milliseconds.
            val todayStart = cutoff * 1000 - 86_400_000L
            val reviewsToday = db.queryScalar("SELECT count() FROM revlog WHERE id > ?", todayStart)
            val msToday = db.queryScalar("SELECT COALESCE(sum(time), 0) FROM revlog WHERE id > ?", todayStart)
            val againToday = db.queryScalar("SELECT count() FROM revlog WHERE id > ? AND ease = 1", todayStart)
            val retentionToday =
                if (reviewsToday > 0) (reviewsToday - againToday) * 100 / reviewsToday else 100

            val tree = sched.deckDueTree()

            val weekStart = todayStart - 6L * 86_400_000L
            val week = IntArray(7)
            db.query("SELECT id FROM revlog WHERE id > ?", weekStart).use { cursor ->
                while (cursor.moveToNext()) {
                    val idx = ((cursor.getLong(0) - weekStart) / 86_400_000L).toInt().coerceIn(0, 6)
                    week[idx]++
                }
            }

            val horizon = todayStart - 400L * 86_400_000L
            val reviewedDays = HashSet<Int>()
            db.query("SELECT id FROM revlog WHERE id > ?", horizon).use { cursor ->
                while (cursor.moveToNext()) {
                    reviewedDays.add(((cursor.getLong(0) - todayStart) / 86_400_000L).toInt())
                }
            }
            var streak = 0
            var day = if (0 in reviewedDays) 0 else -1
            while (day in reviewedDays) {
                streak++
                day--
            }

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
                week = week.toList(),
                weekMax = (week.maxOrNull() ?: 0).coerceAtLeast(1),
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

    /**
     * Real card search for the Browse tab. [filter] is one of
     * All / Due / New / Suspended / Marked and maps to Anki search syntax.
     */
    suspend fun searchCards(
        query: String,
        filter: String,
        limit: Int = 60,
    ): List<UiCard> =
        withCol {
            val fullQuery =
                buildString {
                    append(query.trim())
                    when (filter) {
                        "Due" -> append(" is:due")
                        "New" -> append(" is:new")
                        "Suspended" -> append(" is:suspended")
                        "Marked" -> append(" tag:marked")
                    }
                }.trim()
            findCards(fullQuery)
                .take(limit)
                .map { id ->
                    val card = getCard(id)
                    val days = card.due - sched.today
                    val dueLabel =
                        when {
                            card.queue == QueueType.Suspended -> "—"
                            card.type == CardType.New -> "new"
                            card.type == CardType.Lrn -> "soon"
                            days <= 0 -> "today"
                            else -> "in ${days}d"
                        }
                    UiCard(
                        cardId = id,
                        question = stripHtml(card.question(this)),
                        answer = stripHtml(card.answer(this)),
                        deckName = decks.name(card.did),
                        state =
                            when {
                                card.queue == QueueType.Suspended -> UiCardState.SUSPENDED
                                card.type == CardType.New -> UiCardState.NEW
                                card.type == CardType.Lrn -> UiCardState.LEARNING
                                else -> UiCardState.REVIEW
                            },
                        dueLabel = dueLabel,
                    )
                }
        }

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
