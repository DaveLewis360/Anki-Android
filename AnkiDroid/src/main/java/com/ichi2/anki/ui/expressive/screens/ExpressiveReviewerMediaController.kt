// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.ui.expressive.screens

import android.net.Uri
import com.ichi2.anki.cardviewer.CardMediaPlayer
import com.ichi2.anki.cardviewer.JavascriptEvaluator
import com.ichi2.anki.cardviewer.MediaErrorHandler
import com.ichi2.anki.cardviewer.SingleCardSide
import com.ichi2.anki.libanki.AvTag
import com.ichi2.anki.libanki.Card
import com.ichi2.anki.libanki.TtsPlayer
import com.ichi2.anki.multimedia.getAvTag
import com.ichi2.anki.reviewer.CardSide
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.Closeable

/** Owns native media playback for one Expressive study session. */
internal class ExpressiveReviewerMediaController(
    private val cardMediaPlayer: CardMediaPlayer,
    private val scope: CoroutineScope,
    private val onShowTtsVoices: () -> Unit,
    private val onMissingUserAction: (String) -> Unit,
    private val avTagResolver: suspend (Card, String) -> AvTag? = ::getAvTag,
) : Closeable {
    constructor(
        javascriptEvaluator: JavascriptEvaluator,
        scope: CoroutineScope,
        onMediaError: (String) -> Unit,
        onTtsError: (TtsPlayer.TtsError) -> Unit,
        onShowTtsVoices: () -> Unit,
        onMissingUserAction: (String) -> Unit,
    ) : this(
        cardMediaPlayer =
            CardMediaPlayer(
                javascriptEvaluator = javascriptEvaluator,
                mediaErrorListener =
                    MediaErrorHandler(
                        onMediaError = onMediaError,
                        onTtsError = onTtsError,
                    ),
            ),
        scope = scope,
        onShowTtsVoices = onShowTtsVoices,
        onMissingUserAction = onMissingUserAction,
    )

    private var currentRenderKey: ExpressiveCardRenderKey? = null
    private var pageReadyRenderKey: ExpressiveCardRenderKey? = null
    private var currentCard: Card? = null
    private var prepareJob: Deferred<Unit>? = null
    private var enableTransitionJob: Job? = null
    private var closed = false

    fun prepare(
        card: Card,
        renderKey: ExpressiveCardRenderKey,
    ) {
        if (closed || currentRenderKey == renderKey) return

        currentRenderKey = renderKey
        pageReadyRenderKey = null
        currentCard = card
        val previousJob = prepareJob
        prepareJob =
            scope.async {
                previousJob?.cancelAndJoin()
                cardMediaPlayer.loadCardAvTags(card)
            }
    }

    fun onPageFinished(renderKey: ExpressiveCardRenderKey) {
        if (closed || currentRenderKey != renderKey) return

        pageReadyRenderKey = renderKey
        val expectedPrepareJob = prepareJob
        scope.launch {
            try {
                expectedPrepareJob?.await()
                if (currentRenderKey != renderKey || pageReadyRenderKey != renderKey) return@launch
                cardMediaPlayer.autoplayAllForSide(renderKey.side.toCardSide())
            } catch (exc: CancellationException) {
                throw exc
            } catch (exc: Exception) {
                Timber.w(exc, "Unable to prepare Expressive card media")
            }
        }
    }

    fun handleInternalNavigation(
        uri: Uri,
        renderKey: ExpressiveCardRenderKey,
    ) {
        if (closed || currentRenderKey != renderKey || pageReadyRenderKey != renderKey) return

        when (uri.scheme?.lowercase()) {
            "playsound" -> {
                val card = currentCard ?: return
                scope.launch {
                    try {
                        val tag = avTagResolver(card, uri.toString()) ?: return@launch
                        if (currentRenderKey == renderKey && pageReadyRenderKey == renderKey) {
                            cardMediaPlayer.playOne(tag)
                        }
                    } catch (exc: CancellationException) {
                        throw exc
                    } catch (exc: Exception) {
                        Timber.w(exc, "Unable to play Expressive card media")
                    }
                }
            }
            "videoended" -> cardMediaPlayer.onVideoFinished()
            "videopause" -> cardMediaPlayer.onVideoPaused()
            "tts-voices" -> onShowTtsVoices()
            "missing-user-action" -> onMissingUserAction(uri.toString().substringAfter(':'))
            else -> Timber.w("Unhandled Expressive internal navigation: %s", uri.scheme)
        }
    }

    fun replay() {
        if (closed) return

        val renderKey = currentRenderKey ?: return
        if (pageReadyRenderKey != renderKey) return
        val expectedPrepareJob = prepareJob
        scope.launch {
            try {
                expectedPrepareJob?.await()
                if (currentRenderKey != renderKey || pageReadyRenderKey != renderKey) return@launch
                cardMediaPlayer.replayAll(renderKey.side.toSingleCardSide())
            } catch (exc: CancellationException) {
                throw exc
            } catch (exc: Exception) {
                Timber.w(exc, "Unable to replay Expressive card media")
            }
        }
    }

    fun setEnabled(enabled: Boolean) {
        if (closed) return
        val previousTransition = enableTransitionJob
        enableTransitionJob =
            scope.launch {
                previousTransition?.cancelAndJoin()
                if (!closed) cardMediaPlayer.setEnabled(enabled)
            }
    }

    fun stop() {
        if (closed) return
        pageReadyRenderKey = null
        prepareJob?.cancel()
        prepareJob = null
        scope.launch { cardMediaPlayer.stop() }
    }

    fun clear() {
        if (closed) return
        currentRenderKey = null
        pageReadyRenderKey = null
        currentCard = null
        prepareJob?.cancel()
        prepareJob = null
        stop()
    }

    override fun close() {
        if (closed) return
        closed = true
        currentRenderKey = null
        pageReadyRenderKey = null
        currentCard = null
        prepareJob?.cancel()
        prepareJob = null
        enableTransitionJob?.cancel()
        enableTransitionJob = null
        cardMediaPlayer.close()
    }
}

private fun ExpressiveCardSide.toCardSide(): CardSide =
    when (this) {
        ExpressiveCardSide.QUESTION -> CardSide.QUESTION
        ExpressiveCardSide.ANSWER -> CardSide.ANSWER
    }

private fun ExpressiveCardSide.toSingleCardSide(): SingleCardSide =
    when (this) {
        ExpressiveCardSide.QUESTION -> SingleCardSide.FRONT
        ExpressiveCardSide.ANSWER -> SingleCardSide.BACK
    }
