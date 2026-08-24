// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.ui.expressive.screens

import android.webkit.WebView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.ichi2.anki.R
import com.ichi2.anki.libanki.DeckId
import com.ichi2.anki.ui.expressive.ExpressiveCollection
import com.ichi2.anki.ui.expressive.StudyCardUi
import com.ichi2.anki.ui.expressive.components.AnswerButtons
import com.ichi2.anki.ui.expressive.components.ShowAnswerButton
import com.ichi2.anki.ui.expressive.components.StudyTopBar
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One card in the study session. */
data class AnsweredCard(
    val card: StudyCardUi,
    val backHtml: String,
    val intervals: List<String>,
)

sealed interface StudyUiState {
    data object Loading : StudyUiState

    data class Question(
        val card: StudyCardUi,
        val remaining: Int,
    ) : StudyUiState

    data class Answered(
        val card: AnsweredCard,
        val remaining: Int,
    ) : StudyUiState

    data object Finished : StudyUiState

    data class Error(
        val message: String,
    ) : StudyUiState
}

private fun StudyUiState.remainingCards(): Int =
    when (this) {
        is StudyUiState.Question -> remaining
        is StudyUiState.Answered -> remaining
        else -> 0
    }

private fun ratingFor(index: Int): anki.scheduler.CardAnswer.Rating =
    when (index) {
        0 -> anki.scheduler.CardAnswer.Rating.AGAIN
        1 -> anki.scheduler.CardAnswer.Rating.HARD
        2 -> anki.scheduler.CardAnswer.Rating.GOOD
        else -> anki.scheduler.CardAnswer.Rating.EASY
    }

private fun logFailure(e: Exception) {
    android.util.Log.w("ExpressiveStudy", "study operation failed", e)
}

/** Study: the real review loop against the collection. */
@Composable
fun StudyScreen(
    deckId: DeckId,
    deckName: String,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<StudyUiState>(StudyUiState.Loading) }
    var sessionTotal by remember { mutableIntStateOf(-1) }
    var seconds by remember { mutableIntStateOf(0) }

    // Single in-flight guard: rapid taps must never double-grade a card or
    // restart the pipeline. The current card stays visible until the next one
    // is ready, so the loop never shows a blocking state between answers.
    var working by remember { mutableStateOf(false) }
    var marked by remember { mutableStateOf(false) }
    var flag by remember { mutableIntStateOf(0) }
    var reloadOnResume by remember { mutableStateOf(false) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    fun loadNext() {
        scope.launch {
            state =
                try {
                    val card = ExpressiveCollection.currentCard(context)
                    if (card == null) {
                        StudyUiState.Finished
                    } else {
                        val counts = card.queueState.counts
                        val remaining = counts.new + counts.lrn + counts.rev
                        if (sessionTotal < 0) sessionTotal = remaining
                        seconds = 0
                        marked = card.marked
                        flag = card.flag
                        StudyUiState.Question(card, remaining)
                    }
                } catch (e: Exception) {
                    StudyUiState.Error(e.message ?: "Failed to load card")
                }
            working = false
        }
    }

    fun runStudyAction(block: suspend () -> Unit) {
        if (working) return
        working = true
        scope.launch {
            try {
                block()
            } catch (e: Exception) {
                logFailure(e)
            }
            loadNext()
        }
    }

    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer =
            androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME && reloadOnResume) {
                    reloadOnResume = false
                    loadNext()
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(deckId) {
        state = StudyUiState.Loading
        sessionTotal = -1
        try {
            ExpressiveCollection.selectDeck(deckId)
        } catch (e: Exception) {
            logFailure(e)
        }
        loadNext()
    }

    LaunchedEffect(state) {
        if (state is StudyUiState.Question) {
            while (true) {
                delay(1000)
                seconds++
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        StudyTopBar(
            // Count the card in flight as partial progress so the wave is visible
            // from the very first question and glides forward on every answer.
            progress =
                if (sessionTotal > 0) {
                    (sessionTotal - state.remainingCards() + 1f) / (sessionTotal + 1f)
                } else {
                    0f
                },
            timerSeconds = seconds,
        )
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                deckName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClose) {
                Icon(painterResource(R.drawable.close_icon), contentDescription = "Close")
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            val qs = currentQueueStateOf(state)
            StudyActionButton(
                iconRes = R.drawable.ic_arrow_u_left_top,
                label = "Undo",
                enabled = !working,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = { runStudyAction { ExpressiveCollection.undoLast() } },
            )
            StudyActionButton(
                iconRes = com.ichi2.anki.R.drawable.ic_star_black_24,
                label = "Mark",
                enabled = !working && qs != null,
                tint = if (marked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = {
                    if (qs != null && !working) {
                        working = true
                        scope.launch {
                            try {
                                marked = ExpressiveCollection.toggleMark(qs)
                            } catch (e: Exception) {
                                logFailure(e)
                            }
                            working = false
                        }
                    }
                },
            )
            StudyActionButton(
                iconRes = flagDrawable(flag),
                label = "Flag",
                enabled = !working && qs != null,
                tint = androidx.compose.ui.graphics.Color.Unspecified,
                onClick = {
                    if (qs != null && !working) {
                        working = true
                        scope.launch {
                            try {
                                flag = ExpressiveCollection.cycleFlag(qs)
                            } catch (e: Exception) {
                                logFailure(e)
                            }
                            working = false
                        }
                    }
                },
            )
            StudyActionButton(
                iconRes = com.ichi2.anki.R.drawable.ic_edit_note,
                label = "Edit",
                enabled = !working && qs != null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = {
                    if (qs != null) {
                        reloadOnResume = true
                        context.startActivity(
                            com.ichi2.anki.noteeditor.NoteEditorLauncher
                                .EditSelection(
                                    cardIds = listOf(qs.topCard.id),
                                    animation = com.ichi2.anki.common.ui.TransitionDirection.FADE,
                                ).toIntent(context),
                        )
                    }
                },
            )
            StudyActionButton(
                iconRes = com.ichi2.anki.R.drawable.ic_card_state_buried,
                label = "Bury",
                enabled = !working && qs != null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = {
                    if (qs != null) runStudyAction { ExpressiveCollection.buryCurrent(qs) }
                },
            )
            StudyActionButton(
                iconRes = com.ichi2.anki.R.drawable.ic_card_state_suspended,
                label = "Suspend",
                enabled = !working && qs != null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = {
                    if (qs != null) runStudyAction { ExpressiveCollection.suspendCurrent(qs) }
                },
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (val s = state) {
                is StudyUiState.Loading -> LoadingIndicator()
                is StudyUiState.Error -> ErrorPane(s.message)
                is StudyUiState.Finished -> CongratsPane(onClose)
                is StudyUiState.Question ->
                    CardPane(
                        frontHtml = s.card.frontHtml,
                        backHtml = null,
                        baseUrl = s.card.baseUrl,
                    )
                is StudyUiState.Answered ->
                    CardPane(
                        frontHtml = s.card.card.frontHtml,
                        backHtml = s.card.backHtml,
                        baseUrl = s.card.card.baseUrl,
                    )
            }
        }
        Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars)) {
            Column {
                when (val s = state) {
                    is StudyUiState.Question ->
                        ShowAnswerButton(
                            enabled = !working,
                            onClick = {
                                if (!working) {
                                    working = true
                                    scope.launch {
                                        state =
                                            try {
                                                val (back, intervals) = ExpressiveCollection.revealCard(context, s.card.queueState)
                                                StudyUiState.Answered(AnsweredCard(s.card, back, intervals), s.remaining)
                                            } catch (e: Exception) {
                                                StudyUiState.Error(e.message ?: "Failed to reveal card")
                                            }
                                        working = false
                                    }
                                }
                            },
                        )
                    is StudyUiState.Answered ->
                        AnswerButtons(
                            intervals = s.card.intervals,
                            enabled = !working,
                            onAnswer = { ratingIndex ->
                                if (!working) {
                                    working = true
                                    scope.launch {
                                        try {
                                            ExpressiveCollection.answer(s.card.card.queueState, ratingFor(ratingIndex))
                                        } catch (e: Exception) {
                                            logFailure(e)
                                        }
                                        loadNext()
                                    }
                                }
                            },
                        )
                    else -> {}
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

/** Current queue state regardless of question/answered phase, null while loading. */
private fun currentQueueStateOf(state: StudyUiState): com.ichi2.anki.libanki.sched.CurrentQueueState? =
    when (state) {
        is StudyUiState.Question -> state.card.queueState
        is StudyUiState.Answered -> state.card.card.queueState
        else -> null
    }

private fun flagDrawable(flag: Int): Int =
    when (flag) {
        1 -> com.ichi2.anki.R.drawable.ic_flag_red
        2 -> com.ichi2.anki.R.drawable.ic_flag_orange
        3 -> com.ichi2.anki.R.drawable.ic_flag_green
        4 -> com.ichi2.anki.R.drawable.ic_flag_blue
        5 -> com.ichi2.anki.R.drawable.ic_flag_pink
        6 -> com.ichi2.anki.R.drawable.ic_flag_turquoise
        7 -> com.ichi2.anki.R.drawable.ic_flag_purple
        else -> com.ichi2.anki.R.drawable.ic_flag_transparent
    }

@Composable
private fun StudyActionButton(
    iconRes: Int,
    label: String,
    enabled: Boolean,
    tint: Color,
    onClick: () -> Unit,
) {
    IconButton(enabled = enabled, onClick = onClick) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = label,
            tint = tint.copy(alpha = if (enabled) 1f else 0.4f),
        )
    }
}

@Composable
private fun CardPane(
    frontHtml: String,
    backHtml: String?,
    baseUrl: String,
) {
    // The back template already contains the front side ({{FrontSide}}), so render
    // exactly one side at a time — concatenating them duplicated the question.
    val html = themedCardHtml(backHtml ?: frontHtml)
    AnimatedContent(
        targetState = html,
        transitionSpec = { (fadeIn(tween(140)) + slideInVertically(tween(140)) { it / 12 }).togetherWith(fadeOut(tween(90))) },
        label = "card",
    ) { content ->
        AndroidView(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.allowFileAccess = true
                    settings.loadWithOverviewMode = true
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    isVerticalScrollBarEnabled = false
                }
            },
            update = { webView ->
                if (webView.tag != content) {
                    webView.tag = content
                    webView.loadDataWithBaseURL(baseUrl, content, "text/html", "utf-8", null)
                }
            },
        )
    }
}

/**
 * Injects a theme-adaptive style block into the rendered card HTML.
 *
 * The stock `flashcard.css` hard-codes a black background with white text, which
 * ignores the expressive theme; the override below (placed after the notetype CSS)
 * re-colors the page from MaterialTheme so light/dark/palettes/dynamic color all apply.
 */
@Composable
private fun themedCardHtml(content: String): String {
    val bg = MaterialTheme.colorScheme.background
    val fg = MaterialTheme.colorScheme.onBackground
    val bgHex = "#%06X".format(0xFFFFFF and bg.toArgb())
    val fgHex = "#%06X".format(0xFFFFFF and fg.toArgb())
    val override =
        "<style id=\"expressive-theme\">" +
            "html, body { background-color: $bgHex !important; }" +
            "body, #content, #qa, .card { color: $fgHex !important; background-color: transparent !important; }" +
            "img { max-width: 100%; height: auto; }" +
            "</style>"
    return if (content.contains("</head>", ignoreCase = true)) {
        content.replaceFirst(Regex("</head>", RegexOption.IGNORE_CASE), "$override</head>")
    } else {
        content + override
    }
}

@Composable
private fun LoadingIndicator() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Loading…", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ErrorPane(message: String) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "Couldn't load the card:\n$message",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun CongratsPane(onClose: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("🎉", style = MaterialTheme.typography.displayMedium)
        Spacer(Modifier.height(12.dp))
        Text(
            "Well done!",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "This deck is finished for now.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Surface(
            onClick = onClose,
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ) {
            Text(
                "Back to decks",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 14.dp),
            )
        }
    }
}
