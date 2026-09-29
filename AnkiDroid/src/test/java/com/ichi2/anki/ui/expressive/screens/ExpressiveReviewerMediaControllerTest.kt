// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.ui.expressive.screens

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.cardviewer.CardMediaPlayer
import com.ichi2.anki.cardviewer.SingleCardSide
import com.ichi2.anki.libanki.Card
import com.ichi2.anki.libanki.SoundOrVideoTag
import com.ichi2.anki.reviewer.CardSide
import io.mockk.coVerify
import io.mockk.coVerifySequence
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.sameInstance
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExpressiveReviewerMediaControllerTest {
    @Test
    fun `prepare loads once and page finished autoplays the current side`() =
        runTest {
            val player = mockk<CardMediaPlayer>(relaxed = true)
            val card = card(42)
            val controller = controller(player)

            val question = renderKey(cardId = 42, side = ExpressiveCardSide.QUESTION, html = "question")
            controller.prepare(card, question)
            controller.prepare(card, question)
            controller.onPageFinished(question)
            advanceUntilIdle()

            coVerifySequence {
                player.loadCardAvTags(card)
                player.autoplayAllForSide(CardSide.QUESTION)
            }

            val answer = renderKey(cardId = 42, side = ExpressiveCardSide.ANSWER, html = "answer")
            controller.prepare(card, answer)
            controller.onPageFinished(question)
            controller.onPageFinished(answer)
            advanceUntilIdle()

            coVerify(exactly = 1) { player.autoplayAllForSide(CardSide.ANSWER) }
        }

    @Test
    fun `internal navigation routes playsound video and dialog commands`() =
        runTest {
            val player = mockk<CardMediaPlayer>(relaxed = true)
            val card = card(7)
            val tag = SoundOrVideoTag("sound.mp3")
            var resolvedCard: Card? = null
            var resolvedUrl: String? = null
            var ttsVoiceRequests = 0
            var missingAction: String? = null
            val controller =
                ExpressiveReviewerMediaController(
                    cardMediaPlayer = player,
                    scope = this,
                    onShowTtsVoices = { ttsVoiceRequests++ },
                    onMissingUserAction = { missingAction = it },
                    avTagResolver = { requestedCard, url ->
                        resolvedCard = requestedCard
                        resolvedUrl = url
                        tag
                    },
                )

            val question = renderKey(cardId = 7, side = ExpressiveCardSide.QUESTION)
            controller.prepare(card, question)
            controller.onPageFinished(question)
            advanceUntilIdle()
            controller.handleInternalNavigation(Uri.parse("playsound:q:0"), question)
            controller.handleInternalNavigation(Uri.parse("videoended:"), question)
            controller.handleInternalNavigation(Uri.parse("videopause:"), question)
            controller.handleInternalNavigation(Uri.parse("tts-voices:"), question)
            controller.handleInternalNavigation(Uri.parse("missing-user-action:3"), question)
            advanceUntilIdle()

            assertThat(resolvedCard, sameInstance(card))
            assertThat(resolvedUrl, equalTo("playsound:q:0"))
            coVerify(exactly = 1) { player.playOne(tag) }
            verify(exactly = 1) { player.onVideoFinished() }
            verify(exactly = 1) { player.onVideoPaused() }
            assertThat(ttsVoiceRequests, equalTo(1))
            assertThat(missingAction, equalTo("3"))
        }

    @Test
    fun `replay lifecycle clear and close are delegated without work after close`() =
        runTest {
            val player = mockk<CardMediaPlayer>(relaxed = true)
            val card = card(9)
            val controller = controller(player)

            val answer = renderKey(cardId = 9, side = ExpressiveCardSide.ANSWER, rendererGeneration = 1)
            controller.prepare(card, answer)
            controller.onPageFinished(answer)
            advanceUntilIdle()
            controller.replay()
            controller.setEnabled(false)
            advanceUntilIdle()
            controller.clear()
            advanceUntilIdle()
            controller.close()
            controller.close()
            controller.replay()
            controller.setEnabled(true)
            controller.handleInternalNavigation(Uri.parse("videoended:"), answer)
            advanceUntilIdle()

            coVerify(exactly = 1) { player.replayAll(SingleCardSide.BACK) }
            coVerify(exactly = 1) { player.setEnabled(false) }
            coVerify(exactly = 0) { player.setEnabled(true) }
            coVerify(exactly = 1) { player.stop() }
            verify(exactly = 1) { player.close() }
            verify(exactly = 0) { player.onVideoFinished() }
        }

    private fun kotlinx.coroutines.test.TestScope.controller(player: CardMediaPlayer) =
        ExpressiveReviewerMediaController(
            cardMediaPlayer = player,
            scope = this,
            onShowTtsVoices = {},
            onMissingUserAction = {},
        )

    private fun renderKey(
        cardId: Long,
        side: ExpressiveCardSide,
        rendererGeneration: Int = 0,
        html: String = "card",
    ) = ExpressiveCardRenderKey(
        cardId = cardId,
        side = side,
        rendererGeneration = rendererGeneration,
        baseUrl = "https://appassets.androidplatform.net/",
        html = html,
    )

    private fun card(id: Long): Card =
        mockk<Card>().also {
            every { it.id } returns id
        }
}
