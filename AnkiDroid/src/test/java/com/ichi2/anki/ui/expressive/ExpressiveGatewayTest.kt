// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.ui.expressive

import android.app.Application
import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import anki.scheduler.CardAnswer.Rating
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.libanki.Consts.DEFAULT_DECK_ID
import com.ichi2.anki.libanki.DeckId
import com.ichi2.anki.libanki.sched.CurrentQueueState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class ExpressiveGatewayTest : RobolectricTest() {
    private val viewModels = mutableListOf<StudyViewModel>()
    private val accountViewModels = mutableListOf<ExpressiveAccountViewModel>()

    override fun getCollectionStorageMode() = CollectionStorageMode.IN_MEMORY_WITH_MEDIA

    @After
    override fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        accountViewModels.forEach { it.viewModelScope.cancel() }
        super.tearDown()
    }

    /**
     * Uses the in-memory collection integration harness instead of launching the full Compose
     * activity, whose asynchronous rendering is not stable in this Robolectric test setup.
     * [HomeScreen][com.ichi2.anki.ui.expressive.screens.HomeScreen] performs this gateway load on
     * opening, so this covers its complete collection access path without a Compose timing seam.
     */
    @Test
    fun `loading Expressive Home does not mutate the collection`() =
        runTest {
            val deckCount = col.decks.count()
            val noteCount = col.noteCount()
            val cardCount = col.cardCount()
            val gateway: ExpressiveDeckGateway = ExpressiveCollection

            gateway.loadDecks()

            assertEquals(deckCount, col.decks.count())
            assertEquals(noteCount, col.noteCount())
            assertEquals(cardCount, col.cardCount())
            assertNull(col.decks.byName("Gépelős teszt"))
        }

    @Test
    fun `native account leaves interrupted credentials in memory only`() =
        runTest {
            val gateway = FakeAccountGateway()
            val first = createAccountViewModel(gateway)

            first.login()
            assertTrue(first.state.value.usernameMissing)
            assertTrue(first.state.value.passwordMissing)

            first.onUsernameChanged("user@example.com")
            first.onPasswordChanged("secret")
            val restored = createAccountViewModel(gateway)

            assertEquals("", restored.state.value.username)
            assertEquals("", restored.state.value.password)
        }

    @Test
    fun `native account login is single flight and clears the password`() =
        runTest {
            val release = CompletableDeferred<Unit>()
            val gateway = FakeAccountGateway(loginRelease = release)
            val viewModel = createAccountViewModel(gateway)
            viewModel.onUsernameChanged(" user@example.com ")
            viewModel.onPasswordChanged("secret")

            viewModel.login()
            viewModel.login()
            runCurrent()

            assertEquals(1, gateway.loginCalls)
            assertTrue(viewModel.state.value.signingIn)
            release.complete(Unit)
            runCurrent()

            assertEquals(
                "user@example.com",
                viewModel.state.value.account
                    ?.username,
            )
            assertEquals("", viewModel.state.value.password)
            assertFalse(viewModel.state.value.signingIn)
        }

    @Test
    fun `native account maps network failure without retaining the password`() =
        runTest {
            val gateway = FakeAccountGateway(loginFailure = IOException("contains private server detail"))
            val viewModel = createAccountViewModel(gateway)
            viewModel.onUsernameChanged("user@example.com")
            viewModel.onPasswordChanged("secret")

            viewModel.login()
            runCurrent()

            assertEquals(ExpressiveAccountError.NETWORK, viewModel.state.value.error)
            assertEquals("", viewModel.state.value.password)
            assertNull(viewModel.state.value.account)
        }

    @Test
    fun `native account publishes signed out only after forget completes`() =
        runTest {
            val release = CompletableDeferred<Unit>()
            val gateway =
                FakeAccountGateway(
                    initialAccount = ExpressiveAccount("user@example.com"),
                    forgetRelease = release,
                )
            val viewModel = createAccountViewModel(gateway)

            viewModel.requestForget()
            viewModel.confirmForget()
            runCurrent()

            assertTrue(viewModel.state.value.forgetting)
            assertEquals(ExpressiveAccount("user@example.com"), viewModel.state.value.account)
            release.complete(Unit)
            runCurrent()

            assertEquals(1, gateway.forgetCalls)
            assertNull(viewModel.state.value.account)
            assertFalse(viewModel.state.value.forgetting)
        }

    @Test
    fun `destructive logout cannot be cancelled after it starts`() =
        runTest {
            val release = CompletableDeferred<Unit>()
            val gateway =
                FakeAccountGateway(
                    initialAccount = ExpressiveAccount("user@example.com"),
                    forgetRelease = release,
                )
            val viewModel = createAccountViewModel(gateway)

            viewModel.requestForget()
            viewModel.confirmForget()
            runCurrent()

            assertFalse(viewModel.cancelOperation())
            assertTrue(viewModel.state.value.forgetting)
            assertEquals(0, gateway.cancelCalls)
            release.complete(Unit)
            runCurrent()
            assertNull(viewModel.state.value.account)
        }

    @Test
    fun `StudyViewModel accepts an injected gateway without queue state fabrication`() =
        runTest {
            val gateway = FakeStudyGateway()
            val viewModel = createViewModel(gateway)

            viewModel.selectDeckAndLoad(TEST_DECK_ID)
            advanceUntilIdle()

            assertEquals(TEST_DECK_ID, gateway.selectedDeckId)
            assertSame(StudyUiState.Finished, viewModel.state.value)
        }

    @Test
    fun `select failure does not request a current card`() =
        runTest {
            val gateway = FakeStudyGateway(selectFailure = IllegalStateException("select failed"))
            val viewModel = createViewModel(gateway)

            viewModel.selectDeckAndLoad(TEST_DECK_ID)
            advanceUntilIdle()

            assertEquals(0, gateway.currentCardCalls)
            assertEquals("select failed", assertIs<StudyUiState.Error>(viewModel.state.value).message)
            assertFalse(viewModel.working.value)

            viewModel.retry()
            runCurrent()

            assertEquals(2, gateway.selectCalls)
            assertEquals(1, gateway.currentCardCalls)
            assertSame(StudyUiState.Finished, viewModel.state.value)
        }

    @Test
    fun `undo without a session token does not call the backend`() =
        runTest {
            val gateway = FakeStudyGateway()
            val viewModel = createViewModel(gateway)

            viewModel.undo()
            advanceUntilIdle()

            assertEquals(0, gateway.undoCalls)
            assertFalse(viewModel.canUndo.value)
            assertFalse(viewModel.working.value)
        }

    @Test
    fun `answer failure stays answered and can be retried`() =
        runTest {
            addBasicNote("front", "back")
            val gateway = FailOnceAnswerGateway()
            val viewModel = createViewModel(gateway)
            loadAnswered(viewModel)

            viewModel.grade(Rating.GOOD)
            runCurrent()

            assertIs<StudyUiState.Answered>(viewModel.state.value)
            assertFalse(viewModel.working.value)
            assertFalse(viewModel.canUndo.value)

            viewModel.grade(Rating.GOOD)
            runCurrent()

            assertEquals(2, gateway.answerCalls)
            assertTrue(viewModel.canUndo.value)
            viewModel.viewModelScope.cancel()
        }

    @Test
    fun `rapid double grade makes one answer call`() =
        runTest {
            addBasicNote("front", "back")
            val gateway = BlockingAnswerGateway()
            val viewModel = createViewModel(gateway)
            loadAnswered(viewModel)

            viewModel.grade(Rating.GOOD)
            runCurrent()
            viewModel.grade(Rating.EASY)
            runCurrent()

            assertEquals(1, gateway.answerCalls)
            assertTrue(viewModel.working.value)

            gateway.releaseAnswer.complete(Unit)
            runCurrent()

            assertEquals(1, gateway.answerCalls)
            assertFalse(viewModel.working.value)
            viewModel.viewModelScope.cancel()
        }

    @Test
    fun `committed answer without undo token is not retried`() =
        runTest {
            addBasicNote("front", "back")
            val gateway = NoUndoTokenGateway()
            val viewModel = createViewModel(gateway)
            loadAnswered(viewModel)

            viewModel.grade(Rating.GOOD)
            runCurrent()
            viewModel.grade(Rating.EASY)
            runCurrent()

            assertEquals(1, gateway.answerCalls)
            assertFalse(viewModel.canUndo.value)
            assertFalse(viewModel.state.value is StudyUiState.Answered)
            viewModel.viewModelScope.cancel()
        }

    @Test
    fun `cancellation after answer commit preserves its undo token`() =
        runTest {
            addBasicNote("front", "back")
            val gateway = PostCommitBlockingAnswerGateway()
            val viewModel = createViewModel(gateway)
            loadAnswered(viewModel)

            viewModel.grade(Rating.GOOD)
            runCurrent()
            assertTrue(gateway.committed.isCompleted)

            viewModel.viewModelScope.cancel()
            gateway.releaseResult.complete(Unit)
            runCurrent()

            assertEquals(1, gateway.answerCalls)
            assertTrue(viewModel.canUndo.value)
            assertFalse(viewModel.working.value)
        }

    @Test
    fun `deck selection waits for an in flight answer`() =
        runTest {
            addBasicNote("front", "back")
            val gateway = BlockingAnswerGateway()
            val viewModel = createViewModel(gateway)
            loadAnswered(viewModel)

            viewModel.grade(Rating.GOOD)
            runCurrent()
            viewModel.selectDeckAndLoad(DEFAULT_DECK_ID)
            runCurrent()

            assertEquals(1, gateway.answerCalls)
            assertEquals(1, gateway.selectCalls)
            assertTrue(viewModel.working.value)

            gateway.releaseAnswer.complete(Unit)
            runCurrent()

            assertEquals(1, gateway.answerCalls)
            assertEquals(2, gateway.selectCalls)
            assertFalse(viewModel.canUndo.value)
            assertFalse(viewModel.working.value)
            viewModel.viewModelScope.cancel()
        }

    @Test
    fun `foreign backend operation blocks session undo`() =
        runTest {
            addBasicNote("front", "back")
            val viewModel = createViewModel(DelegatingStudyGateway())
            loadAnswered(viewModel)
            val answered = assertIs<StudyUiState.Answered>(viewModel.state.value)
            val cardId = answered.card.card.queueState.topCard.id

            viewModel.grade(Rating.GOOD)
            runCurrent()
            assertTrue(viewModel.canUndo.value)

            assertTrue(ExpressiveCollection.toggleMark(answered.card.card.queueState))
            val foreignTopStatus = col.undoStatus()

            viewModel.undo()
            runCurrent()

            assertEquals(foreignTopStatus, col.undoStatus())
            assertTrue(col.getCard(cardId).note(col).hasTag(col, "marked"))
            assertFalse(viewModel.canUndo.value)
            assertFalse(viewModel.working.value)
            viewModel.viewModelScope.cancel()
        }

    @Test
    fun `answer load failure keeps undo token and successful undo reloads card`() =
        runTest {
            val originalCardId = addBasicNote("front", "back").firstCard().id
            val gateway = PostAnswerLoadFailureGateway()
            val viewModel = createViewModel(gateway)
            loadAnswered(viewModel)

            viewModel.grade(Rating.GOOD)
            runCurrent()

            val error = assertIs<StudyUiState.Error>(viewModel.state.value)
            assertTrue(error.message.startsWith("Answer saved"))
            assertTrue(viewModel.canUndo.value)

            viewModel.undo()
            runCurrent()

            val question = assertIs<StudyUiState.Question>(viewModel.state.value)
            assertEquals(originalCardId, question.card.queueState.topCard.id)
            assertFalse(viewModel.canUndo.value)
            assertFalse(viewModel.working.value)
            viewModel.viewModelScope.cancel()
        }

    @Test
    fun `selecting a deck resets session undo tokens`() =
        runTest {
            addBasicNote("front", "back")
            val gateway = CountingUndoGateway()
            val viewModel = createViewModel(gateway)
            loadAnswered(viewModel)
            viewModel.grade(Rating.GOOD)
            runCurrent()
            assertTrue(viewModel.canUndo.value)

            viewModel.selectDeckAndLoad(DEFAULT_DECK_ID)
            assertFalse(viewModel.canUndo.value)
            runCurrent()
            viewModel.undo()
            runCurrent()

            assertEquals(0, gateway.undoCalls)
            viewModel.viewModelScope.cancel()
        }

    private fun createAccountViewModel(gateway: ExpressiveAccountGateway): ExpressiveAccountViewModel =
        ExpressiveAccountViewModel(gateway).also(accountViewModels::add)

    private fun createViewModel(gateway: ExpressiveStudyGateway): StudyViewModel =
        StudyViewModel(
            application = ApplicationProvider.getApplicationContext<Application>(),
            studyGateway = gateway,
        ).also(viewModels::add)

    private fun TestScope.loadAnswered(viewModel: StudyViewModel) {
        viewModel.selectDeckAndLoad(DEFAULT_DECK_ID)
        runCurrent()
        assertIs<StudyUiState.Question>(viewModel.state.value)

        viewModel.revealCurrent()
        runCurrent()
        assertIs<StudyUiState.Answered>(viewModel.state.value)
    }

    private class FakeAccountGateway(
        initialAccount: ExpressiveAccount? = null,
        private val loginRelease: CompletableDeferred<Unit>? = null,
        private val forgetRelease: CompletableDeferred<Unit>? = null,
        private val loginFailure: Exception? = null,
    ) : ExpressiveAccountGateway {
        private var account = initialAccount
        var loginCalls = 0
        var forgetCalls = 0
        var cancelCalls = 0

        override fun currentAccount(): ExpressiveAccount? = account

        override suspend fun login(
            username: String,
            password: String,
        ): ExpressiveAccount {
            loginCalls += 1
            loginFailure?.let { throw it }
            loginRelease?.await()
            return ExpressiveAccount(username).also { account = it }
        }

        override suspend fun forgetAccount() {
            forgetCalls += 1
            forgetRelease?.await()
            account = null
        }

        override fun cancel() {
            cancelCalls += 1
        }
    }

    private class FakeStudyGateway(
        private var selectFailure: Exception? = null,
    ) : ExpressiveStudyGateway {
        var selectedDeckId: DeckId? = null
        var selectCalls = 0
        var currentCardCalls = 0
        var undoCalls = 0

        override suspend fun selectDeck(did: DeckId) {
            selectCalls += 1
            selectedDeckId = did
            selectFailure?.let {
                selectFailure = null
                throw it
            }
        }

        override suspend fun currentCard(context: Context): StudyCardUi? {
            currentCardCalls += 1
            return null
        }

        override suspend fun revealCard(
            context: Context,
            state: CurrentQueueState,
            typedInput: String?,
        ): Nothing = error("Not used in this boundary test")

        override suspend fun answer(
            state: CurrentQueueState,
            rating: Rating,
        ): Nothing = error("Not used in this boundary test")

        override suspend fun toggleMark(state: CurrentQueueState): Nothing = error("Not used in this boundary test")

        override suspend fun cycleFlag(state: CurrentQueueState): Nothing = error("Not used in this boundary test")

        override suspend fun buryCurrent(state: CurrentQueueState): Nothing = error("Not used in this boundary test")

        override suspend fun suspendCurrent(state: CurrentQueueState): Nothing = error("Not used in this boundary test")

        override suspend fun undoReview(expectedToken: ReviewUndoToken): UndoReviewResult {
            undoCalls += 1
            return UndoReviewResult.Undone
        }
    }

    private open class DelegatingStudyGateway : ExpressiveStudyGateway by ExpressiveCollection

    private class FailOnceAnswerGateway : DelegatingStudyGateway() {
        var answerCalls = 0

        override suspend fun answer(
            state: CurrentQueueState,
            rating: Rating,
        ): ReviewAnswerResult {
            answerCalls += 1
            if (answerCalls == 1) error("answer failed")
            return ExpressiveCollection.answer(state, rating)
        }
    }

    private class BlockingAnswerGateway : DelegatingStudyGateway() {
        val releaseAnswer = CompletableDeferred<Unit>()
        var answerCalls = 0
        var selectCalls = 0

        override suspend fun selectDeck(did: DeckId) {
            selectCalls += 1
            ExpressiveCollection.selectDeck(did)
        }

        override suspend fun answer(
            state: CurrentQueueState,
            rating: Rating,
        ): ReviewAnswerResult {
            answerCalls += 1
            releaseAnswer.await()
            return ExpressiveCollection.answer(state, rating)
        }
    }

    private class PostCommitBlockingAnswerGateway : DelegatingStudyGateway() {
        val committed = CompletableDeferred<Unit>()
        val releaseResult = CompletableDeferred<Unit>()
        var answerCalls = 0

        override suspend fun answer(
            state: CurrentQueueState,
            rating: Rating,
        ): ReviewAnswerResult {
            answerCalls += 1
            val result = ExpressiveCollection.answer(state, rating)
            committed.complete(Unit)
            releaseResult.await()
            return result
        }
    }

    private class NoUndoTokenGateway : DelegatingStudyGateway() {
        var answerCalls = 0

        override suspend fun answer(
            state: CurrentQueueState,
            rating: Rating,
        ): ReviewAnswerResult {
            answerCalls += 1
            return ExpressiveCollection.answer(state, rating).copy(undoToken = null)
        }
    }

    private class PostAnswerLoadFailureGateway : DelegatingStudyGateway() {
        private var failLoad = false

        override suspend fun answer(
            state: CurrentQueueState,
            rating: Rating,
        ): ReviewAnswerResult = ExpressiveCollection.answer(state, rating).also { failLoad = true }

        override suspend fun currentCard(context: Context): StudyCardUi? {
            if (failLoad) error("next card failed")
            return ExpressiveCollection.currentCard(context)
        }

        override suspend fun undoReview(expectedToken: ReviewUndoToken): UndoReviewResult =
            ExpressiveCollection.undoReview(expectedToken).also { result ->
                if (result == UndoReviewResult.Undone) failLoad = false
            }
    }

    private class CountingUndoGateway : DelegatingStudyGateway() {
        var undoCalls = 0

        override suspend fun undoReview(expectedToken: ReviewUndoToken): UndoReviewResult {
            undoCalls += 1
            return ExpressiveCollection.undoReview(expectedToken)
        }
    }

    private companion object {
        const val TEST_DECK_ID: DeckId = 42L
    }
}
