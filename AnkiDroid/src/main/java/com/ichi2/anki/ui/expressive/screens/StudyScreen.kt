// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.ui.expressive.screens

import android.webkit.WebView
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import anki.scheduler.CardAnswer.Rating
import com.ichi2.anki.R
import com.ichi2.anki.libanki.DeckId
import com.ichi2.anki.ui.expressive.CardTransition
import com.ichi2.anki.ui.expressive.ExpressiveSettings
import com.ichi2.anki.ui.expressive.StudyUiState
import com.ichi2.anki.ui.expressive.StudyViewModel
import com.ichi2.anki.ui.expressive.components.AnswerButtons
import com.ichi2.anki.ui.expressive.components.CardGestureArea
import com.ichi2.anki.ui.expressive.components.GestureAction
import com.ichi2.anki.ui.expressive.components.ShowAnswerButton
import com.ichi2.anki.ui.expressive.components.StudyTopBar
import com.ichi2.anki.ui.expressive.ratingFor
import com.ichi2.anki.ui.expressive.remainingCards

/** Study: the real review loop against the collection, state held by [StudyViewModel]. */
@Composable
fun StudyScreen(
    deckId: DeckId,
    deckName: String,
    gestures: ExpressiveSettings,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val viewModel: StudyViewModel = viewModel()

    val state by viewModel.state.collectAsStateWithLifecycle()
    val marked by viewModel.marked.collectAsStateWithLifecycle()
    val flag by viewModel.flag.collectAsStateWithLifecycle()
    val working by viewModel.working.collectAsStateWithLifecycle()
    val seconds by viewModel.seconds.collectAsStateWithLifecycle()
    val sessionTotal by viewModel.sessionTotal.collectAsStateWithLifecycle()
    val transition by viewModel.transition.collectAsStateWithLifecycle()

    var gestureMenu by remember { mutableStateOf(false) }
    var typedAnswer by remember { mutableStateOf("") }
    var reloadOnResume by remember { mutableStateOf(false) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    // One WebView for the whole study session: hoisted above the Question/Answered
    // branches so revealing the answer reuses it instead of destroying/recreating it.
    val cardWebView =
        remember {
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.allowFileAccess = true
                settings.loadWithOverviewMode = true
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                isVerticalScrollBarEnabled = false
            }
        }
    DisposableEffect(Unit) {
        onDispose { cardWebView.destroy() }
    }

    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer =
            androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME && reloadOnResume) {
                    reloadOnResume = false
                    viewModel.loadNext()
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    androidx.compose.runtime.LaunchedEffect(deckId) {
        viewModel.selectDeckAndLoad(deckId)
    }

    // Reset the typed answer whenever a new card is shown.
    androidx.compose.runtime.LaunchedEffect(
        (state as? StudyUiState.Question)
            ?.card
            ?.queueState
            ?.topCard
            ?.id,
    ) {
        typedAnswer = ""
    }

    fun performGesture(actionKey: String) {
        val qs = currentQueueStateOf(state)
        when (GestureAction.fromKey(actionKey)) {
            GestureAction.SHOW_ANSWER -> viewModel.revealCurrent(typedAnswer)
            GestureAction.GOOD -> viewModel.grade(Rating.GOOD)
            GestureAction.HARD -> viewModel.grade(Rating.HARD)
            GestureAction.AGAIN -> viewModel.grade(Rating.AGAIN)
            GestureAction.EASY -> viewModel.grade(Rating.EASY)
            GestureAction.UNDO -> viewModel.undo()
            GestureAction.MARK -> viewModel.toggleMark()
            GestureAction.FLAG -> viewModel.cycleFlag()
            GestureAction.EDIT -> {
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
            }
            GestureAction.BURY -> viewModel.buryCurrent()
            GestureAction.SUSPEND -> viewModel.suspendCurrent()
            GestureAction.MENU -> gestureMenu = true
            GestureAction.NONE -> {}
        }
    }

    Column(Modifier.fillMaxSize()) {
        StudyTopBar(
            // Count the card in flight as partial progress so the wave is visible
            // from the very first question and glides forward on every answer.
            progress =
                if (sessionTotal > 0) {
                    ((sessionTotal - state.remainingCards() + 1f) / (sessionTotal + 1f)).coerceIn(0.03f, 0.97f)
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
                onClick = { viewModel.undo() },
            )
            StudyActionButton(
                iconRes = com.ichi2.anki.R.drawable.ic_star_black_24,
                label = "Mark",
                enabled = !working && qs != null,
                tint = if (marked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = { viewModel.toggleMark() },
            )
            Box {
                StudyActionButton(
                    iconRes = com.ichi2.anki.R.drawable.ic_flag_neutral_filled,
                    label = "Flag",
                    enabled = !working && qs != null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = { viewModel.cycleFlag() },
                )
                // Small colored dot so the flag state stays visible without
                // breaking the toolbar's uniform gray icon language.
                if (flag > 0) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 8.dp, end = 8.dp)
                            .size(8.dp)
                            .background(flagTint(flag), CircleShape),
                    )
                }
            }
            StudyActionButton(
                iconRes = com.ichi2.anki.R.drawable.ic_edit_note,
                label = "Edit",
                enabled = !working && qs != null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = { performGesture(GestureAction.EDIT.key) },
            )
            StudyActionButton(
                iconRes = com.ichi2.anki.R.drawable.ic_hide,
                label = "Bury",
                enabled = !working && qs != null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = { viewModel.buryCurrent() },
            )
            StudyActionButton(
                iconRes = com.ichi2.anki.R.drawable.round_pause_24,
                label = "Suspend",
                enabled = !working && qs != null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = { viewModel.suspendCurrent() },
            )
        }
        CardGestureArea(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            onTap = {
                performGesture(
                    if (state is StudyUiState.Question) gestures.gestureTapQuestion else gestures.gestureTapAnswer,
                )
            },
            onDoubleTap = { performGesture(gestures.gestureDoubleTap) },
            onLongPress = { performGesture(gestures.gestureLongPress) },
            onSwipeLeft = { performGesture(gestures.gestureSwipeLeft) },
            onSwipeRight = { performGesture(gestures.gestureSwipeRight) },
        ) {
            when (val s = state) {
                is StudyUiState.Loading -> LoadingIndicator()
                is StudyUiState.Error -> ErrorPane(s.message)
                is StudyUiState.Finished -> CongratsPane(onClose)
                is StudyUiState.Question ->
                    Column {
                        CardPane(
                            webView = cardWebView,
                            html =
                                themedCardHtml(
                                    content = s.card.frontHtml,
                                    kind = transition.toQuestionKind(),
                                    hideTypeInput = s.card.typeUseInputTag,
                                ),
                            baseUrl = s.card.baseUrl,
                        )
                        // Type-answer cards: learner types before revealing.
                        if (s.card.typeCorrect != null) {
                            OutlinedTextField(
                                value = typedAnswer,
                                onValueChange = { typedAnswer = it },
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 20.dp, vertical = 8.dp),
                                label = { Text("Type answer") },
                                singleLine = true,
                                shape = MaterialTheme.shapes.large,
                            )
                        }
                    }
                is StudyUiState.Answered ->
                    CardPane(
                        webView = cardWebView,
                        html = themedCardHtml(s.card.backHtml, CardTransition.ANSWER_REVEAL),
                        baseUrl = s.card.card.baseUrl,
                    )
            }
            if (gestureMenu) {
                GestureActionMenu(
                    onAction = { action ->
                        gestureMenu = false
                        performGesture(action)
                    },
                    onDismiss = { gestureMenu = false },
                )
            }
        }
        Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars)) {
            Column {
                when (val s = state) {
                    is StudyUiState.Question ->
                        ShowAnswerButton(
                            enabled = !working,
                            onClick = { viewModel.revealCurrent(typedAnswer) },
                        )
                    is StudyUiState.Answered ->
                        AnswerButtons(
                            intervals = s.card.intervals,
                            enabled = !working,
                            onAnswer = { ratingIndex -> viewModel.grade(ratingFor(ratingIndex)) },
                        )
                    else -> {}
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

private fun CardTransition.toQuestionKind(): CardTransition =
    when (this) {
        CardTransition.QUESTION_BACK -> CardTransition.QUESTION_BACK
        CardTransition.QUESTION_FORWARD -> CardTransition.QUESTION_FORWARD
        else -> CardTransition.QUESTION_RISE
    }

/** Current queue state regardless of question/answered phase, null while loading. */
private fun currentQueueStateOf(state: StudyUiState): com.ichi2.anki.libanki.sched.CurrentQueueState? =
    when (state) {
        is StudyUiState.Question -> state.card.queueState
        is StudyUiState.Answered -> state.card.card.queueState
        else -> null
    }

private fun flagTint(flag: Int): Color =
    when (flag) {
        1 -> Color(0xFFEF5350)
        2 -> Color(0xFFFFB74D)
        3 -> Color(0xFF81C784)
        4 -> Color(0xFF64B5F6)
        5 -> Color(0xFFF06292)
        6 -> Color(0xFF4DD0E1)
        7 -> Color(0xFF9575CD)
        else -> Color(0xFF9AA0A6)
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
    webView: WebView,
    html: String,
    baseUrl: String,
) {
    AndroidView(
        factory = { webView },
        update = { view ->
            if (view.tag != html) {
                view.tag = html
                view.loadDataWithBaseURL(baseUrl, html, "text/html", "utf-8", null)
            }
        },
        modifier =
            Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
    )
}

/**
 * Injects a theme-adaptive style block into the rendered card HTML.
 *
 * The stock `flashcard.css` hard-codes a black background with white text, which
 * ignores the expressive theme; the override below (placed after the notetype CSS)
 * re-colors the page from MaterialTheme so light/dark/palettes/dynamic color all apply.
 *
 * The enter animation runs as CSS keyframes INSIDE the WebView, so the content is
 * already animating on the very first painted frame — no async race with the
 * WebView's own loading, which is what made container-level animation glitch.
 */
@Composable
private fun themedCardHtml(
    content: String,
    kind: CardTransition,
    hideTypeInput: Boolean = false,
): String {
    val bg = MaterialTheme.colorScheme.background
    val fg = MaterialTheme.colorScheme.onBackground
    val bgHex = "#%06X".format(0xFFFFFF and bg.toArgb())
    val fgHex = "#%06X".format(0xFFFFFF and fg.toArgb())
    val typeInputHide =
        if (hideTypeInput) {
            "#typeans { display: none !important; }"
        } else {
            ""
        }
    val contentAnimation =
        when (kind) {
            CardTransition.QUESTION_RISE -> "#content { animation: ankiRise 320ms cubic-bezier(0.2, 0, 0, 1) both; }"
            CardTransition.QUESTION_FORWARD -> "#content { animation: ankiSlideForward 320ms cubic-bezier(0.2, 0, 0, 1) both; }"
            CardTransition.QUESTION_BACK -> "#content { animation: ankiSlideBack 320ms cubic-bezier(0.2, 0, 0, 1) both; }"
            // Answer reveal: the question part stays still, only the hr and
            // everything after it animate in. Works with default templates where
            // FrontSide, hr#answer and the answer are siblings.
            CardTransition.ANSWER_REVEAL ->
                "#answer { animation: ankiFade 300ms cubic-bezier(0.2, 0, 0, 1) both; }" +
                    "#answer ~ * { animation: ankiRise 320ms cubic-bezier(0.2, 0, 0, 1) both; }"
        }
    val fallbackScript =
        if (kind == CardTransition.ANSWER_REVEAL) {
            // Exotic templates may not have hr#answer: animate the whole content instead
            "<script>(function(){var a=document.getElementById('answer');" +
                "var c=document.getElementById('content');" +
                "if(c&&!a){c.style.animation='ankiRise 320ms cubic-bezier(0.2,0,0,1) both';}})();</script>"
        } else {
            ""
        }
    val override =
        "<style id=\"expressive-theme\">" +
            "html, body { background-color: $bgHex !important; }" +
            "body, #content, #qa, .card { color: $fgHex !important; background-color: transparent !important; }" +
            "img { max-width: 100%; height: auto; }" +
            typeInputHide +
            "@keyframes ankiRise { from { opacity: 0; transform: translateY(24px); } " +
            "to { opacity: 1; transform: translateY(0); } }" +
            "@keyframes ankiFade { from { opacity: 0; } to { opacity: 1; } }" +
            "@keyframes ankiSlideForward { from { opacity: 0; transform: translateX(56px); } " +
            "to { opacity: 1; transform: translateX(0); } }" +
            "@keyframes ankiSlideBack { from { opacity: 0; transform: translateX(-56px); } " +
            "to { opacity: 1; transform: translateX(0); } }" +
            contentAnimation +
            "</style>" +
            fallbackScript
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

/** Quick action menu opened by the long-press gesture. */
@Composable
private fun GestureActionMenu(
    onAction: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        listOf(
            GestureAction.FLAG,
            GestureAction.EDIT,
            GestureAction.BURY,
            GestureAction.SUSPEND,
            GestureAction.UNDO,
        ).forEach { action ->
            DropdownMenuItem(
                text = { Text(action.label) },
                onClick = { onAction(action.key) },
            )
        }
    }
}
