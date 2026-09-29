// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.ui.expressive.screens

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.view.KeyEvent
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import anki.scheduler.CardAnswer.Rating
import anki.scheduler.UnburyDeckRequest
import com.ichi2.anki.R
import com.ichi2.anki.common.preferences.sharedPrefs
import com.ichi2.anki.dialogs.TtsVoicesDialogFragment
import com.ichi2.anki.libanki.Card
import com.ichi2.anki.libanki.CardId
import com.ichi2.anki.libanki.DeckId
import com.ichi2.anki.localizedErrorMessage
import com.ichi2.anki.preferences.reviewer.ViewerAction
import com.ichi2.anki.reviewer.BindingMap
import com.ichi2.anki.reviewer.BindingProcessor
import com.ichi2.anki.reviewer.CardSide
import com.ichi2.anki.reviewer.ReviewerBinding
import com.ichi2.anki.security.AppPermissions
import com.ichi2.anki.ui.expressive.CardTransition
import com.ichi2.anki.ui.expressive.ExpressiveCollection
import com.ichi2.anki.ui.expressive.ExpressiveCongratulationsInfo
import com.ichi2.anki.ui.expressive.ExpressiveDeckGateway
import com.ichi2.anki.ui.expressive.ExpressiveEditorOrigin
import com.ichi2.anki.ui.expressive.ExpressiveEditorResult
import com.ichi2.anki.ui.expressive.ExpressiveReviewerInputHost
import com.ichi2.anki.ui.expressive.ExpressiveSettings
import com.ichi2.anki.ui.expressive.ExpressiveStudyGateway
import com.ichi2.anki.ui.expressive.StudyUiState
import com.ichi2.anki.ui.expressive.StudyViewModel
import com.ichi2.anki.ui.expressive.UiDeck
import com.ichi2.anki.ui.expressive.components.AnswerButtons
import com.ichi2.anki.ui.expressive.components.CardGestureArea
import com.ichi2.anki.ui.expressive.components.GestureAction
import com.ichi2.anki.ui.expressive.components.ShowAnswerButton
import com.ichi2.anki.ui.expressive.components.StudyTopBar
import com.ichi2.anki.ui.expressive.ratingFor
import com.ichi2.anki.ui.expressive.remainingCards
import com.ichi2.anki.utils.openUrl
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/** Study: the real review loop against the collection, state held by [StudyViewModel]. */
@Composable
fun StudyScreen(
    deckId: DeckId,
    deckName: String,
    gestures: ExpressiveSettings,
    onClose: () -> Unit,
    onAddNote: () -> Unit = {},
    onEditCard: (CardId) -> Unit = {},
    onDeckOptions: (DeckId, Boolean) -> Unit = { _, _ -> },
    onOpenStudy: (DeckId, String) -> Unit = { _, _ -> },
    editorResult: ExpressiveEditorResult? = null,
    onEditorResultConsumed: (Long) -> Unit = {},
    studyGateway: ExpressiveStudyGateway = ExpressiveCollection,
    deckGateway: ExpressiveDeckGateway = ExpressiveCollection,
) {
    val context = LocalContext.current
    val viewModel: StudyViewModel = viewModel(factory = StudyViewModel.factory(studyGateway))

    val state by viewModel.state.collectAsStateWithLifecycle()
    val marked by viewModel.marked.collectAsStateWithLifecycle()
    val flag by viewModel.flag.collectAsStateWithLifecycle()
    val working by viewModel.working.collectAsStateWithLifecycle()
    val canUndo by viewModel.canUndo.collectAsStateWithLifecycle()
    val typedAnswer by viewModel.typedAnswer.collectAsStateWithLifecycle()
    val seconds by viewModel.seconds.collectAsStateWithLifecycle()
    val sessionTotal by viewModel.sessionTotal.collectAsStateWithLifecycle()
    val transition by viewModel.transition.collectAsStateWithLifecycle()
    val schedulingMutation by viewModel.schedulingMutation.collectAsStateWithLifecycle()
    val schedulingReady by viewModel.schedulingReady.collectAsStateWithLifecycle()
    val congratulationsInfo by viewModel.congratulationsInfo.collectAsStateWithLifecycle()

    var gestureMenu by remember { mutableStateOf(false) }
    var buryMenu by remember { mutableStateOf(false) }
    var suspendMenu by remember { mutableStateOf(false) }
    var customStudyInfo by remember { mutableStateOf<ExpressiveCongratulationsInfo?>(null) }
    var unburyInfo by remember { mutableStateOf<ExpressiveCongratulationsInfo?>(null) }
    var composeInputFocused by remember { mutableStateOf(false) }
    var webInputFocused by remember { mutableStateOf(false) }
    var missingUserAction by remember { mutableStateOf<String?>(null) }
    var unsupportedSignalShown by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    androidx.compose.runtime.LaunchedEffect(viewModel) {
        viewModel.actionErrors.collect { snackbarHostState.showSnackbar(it) }
    }
    androidx.compose.runtime.LaunchedEffect(editorResult?.requestId) {
        val result = editorResult ?: return@LaunchedEffect
        if (result.origin != ExpressiveEditorOrigin.STUDY) return@LaunchedEffect
        viewModel.refreshAfterNoteEditor(result.changed, result.reloadRequired)
        onEditorResultConsumed(result.requestId)
    }

    // One WebView for the whole study session. Renderer-process recovery increments the
    // generation, which creates a fresh holder and reloads the current render key.
    val coroutineScope = rememberCoroutineScope()
    var rendererGeneration by remember { mutableIntStateOf(0) }
    val webViewHolder = remember { AtomicReference<ExpressiveCardWebViewHolder?>(null) }
    val appPermissions =
        remember(context, snackbarHostState, coroutineScope) {
            AppPermissions(context) { message ->
                coroutineScope.launch { snackbarHostState.showSnackbar(message) }
            }
        }
    val mediaController =
        remember(context, coroutineScope, snackbarHostState) {
            ExpressiveReviewerMediaController(
                javascriptEvaluator = { script -> webViewHolder.get()?.evaluateJavascript(script) },
                scope = coroutineScope,
                onMediaError = { filename ->
                    coroutineScope.launch { snackbarHostState.showSnackbar("Media not found: $filename") }
                },
                onTtsError = { error ->
                    coroutineScope.launch { snackbarHostState.showSnackbar(error.localizedErrorMessage(context)) }
                },
                onShowTtsVoices = {
                    val activity = context.findFragmentActivity()
                    if (activity == null) {
                        coroutineScope.launch { snackbarHostState.showSnackbar("TTS voices are unavailable") }
                    } else {
                        TtsVoicesDialogFragment().show(activity.supportFragmentManager, "ttsVoices")
                    }
                },
                onMissingUserAction = { missingUserAction = it },
            )
        }
    DisposableEffect(mediaController) {
        onDispose { mediaController.close() }
    }
    androidx.compose.runtime.LaunchedEffect(viewModel, mediaController) {
        viewModel.stopMedia.collect { mediaController.stop() }
    }
    val currentSchedulingMutation = rememberUpdatedState(schedulingMutation)

    fun handleInternalNavigation(
        uri: Uri,
        renderKey: ExpressiveCardRenderKey,
    ) {
        when (uri.scheme?.lowercase()) {
            "ankidroid" ->
                when (uri.host?.lowercase()) {
                    "show-answer" -> viewModel.revealCurrent()
                    "input-focus" -> webInputFocused = true
                    "input-blur" -> webInputFocused = false
                }
            "typeentertext" -> {
                viewModel.updateTypedAnswer(Uri.decode(uri.toString().substringAfter(':')))
                viewModel.revealCurrent()
            }
            "typechangetext" -> viewModel.updateTypedAnswer(Uri.decode(uri.toString().substringAfter(':')))
            "signal" -> {
                if (!unsupportedSignalShown) {
                    unsupportedSignalShown = true
                    coroutineScope.launch {
                        snackbarHostState.showSnackbar("This card requested an unsupported reviewer signal")
                    }
                }
            }
            "state-mutation-result" ->
                viewModel.applySchedulingMutation(
                    cardId = renderKey.cardId,
                    renderRevision = renderKey.renderRevision,
                    encodedRequest = internalNavigationPayload(uri),
                )
            "state-mutation-error" ->
                viewModel.failSchedulingMutation(
                    cardId = renderKey.cardId,
                    renderRevision = renderKey.renderRevision,
                    message =
                        internalNavigationPayload(uri)
                            .take(500)
                            .ifBlank { "Custom scheduling failed; using the original intervals" },
                )
            else -> mediaController.handleInternalNavigation(uri, renderKey)
        }
    }

    val cardWebViewHolder =
        remember(context, rendererGeneration, appPermissions, mediaController) {
            createExpressiveCardWebViewHolder(
                context = context,
                onOpenExternal = { uri ->
                    if (appPermissions.checkCanLaunchExternalApps()) {
                        context.openUrl(uri)
                    }
                },
                onRendererGone = { rendererGeneration++ },
                onMainFrameError = { message ->
                    currentSchedulingMutation.value?.let { command ->
                        viewModel.failSchedulingMutation(
                            cardId = command.cardId,
                            renderRevision = command.renderRevision,
                            message = "Card renderer failed before custom scheduling completed",
                        )
                    }
                    coroutineScope.launch { snackbarHostState.showSnackbar("Card renderer error: $message") }
                },
                onLocalResourceError = { uri ->
                    coroutineScope.launch {
                        snackbarHostState.showSnackbar("Card resource not found: ${uri.lastPathSegment.orEmpty()}")
                    }
                },
                onInternalNavigation = ::handleInternalNavigation,
                onPageFinished = { renderKey ->
                    mediaController.onPageFinished(renderKey)
                    currentSchedulingMutation.value
                        ?.takeIf { command ->
                            command.cardId == renderKey.cardId &&
                                command.renderRevision == renderKey.renderRevision
                        }?.let { command ->
                            webViewHolder.get()?.evaluateJavascript(schedulingMutationJavascript(command))
                        }
                },
            ).also(webViewHolder::set)
        }
    DisposableEffect(cardWebViewHolder) {
        onDispose {
            webViewHolder.compareAndSet(cardWebViewHolder, null)
            cardWebViewHolder.destroy()
        }
    }

    androidx.compose.runtime.DisposableEffect(lifecycleOwner, cardWebViewHolder) {
        var appliedResumed: Boolean? = null

        fun applyReviewerLifecycle(resumed: Boolean) {
            if (appliedResumed == resumed) return
            appliedResumed = resumed
            viewModel.setReviewerResumed(resumed)
            if (resumed) {
                cardWebViewHolder.onResume()
                mediaController.setEnabled(true)
            } else {
                mediaController.setEnabled(false)
                cardWebViewHolder.onPause()
            }
        }

        applyReviewerLifecycle(
            lifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED),
        )
        val observer =
            androidx.lifecycle.LifecycleEventObserver { _, event ->
                when (event) {
                    androidx.lifecycle.Lifecycle.Event.ON_RESUME -> applyReviewerLifecycle(true)
                    androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> applyReviewerLifecycle(false)
                    else -> {}
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            applyReviewerLifecycle(false)
        }
    }

    androidx.compose.runtime.DisposableEffect(viewModel, deckId) {
        viewModel.attach(deckId)
        onDispose {
            val preserveSession = context.findFragmentActivity()?.isChangingConfigurations == true
            viewModel.detach(preserveSession)
        }
    }

    androidx.compose.runtime.LaunchedEffect(state, cardWebViewHolder, mediaController) {
        if (state !is StudyUiState.Question && state !is StudyUiState.Answered) {
            cardWebViewHolder.clear()
            mediaController.clear()
        }
    }
    androidx.compose.runtime.LaunchedEffect(
        currentQueueStateOf(state)?.topCard?.id,
        state is StudyUiState.Answered,
    ) {
        webInputFocused = false
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
                qs?.let { onEditCard(it.topCard.id) }
            }
            GestureAction.BURY -> viewModel.buryCurrent()
            GestureAction.SUSPEND -> viewModel.suspendCurrent()
            GestureAction.MENU -> gestureMenu = true
            GestureAction.NONE -> {}
        }
    }

    fun processViewerAction(
        action: ViewerAction,
        binding: ReviewerBinding,
    ): Boolean {
        val currentSide = if (state is StudyUiState.Answered) CardSide.ANSWER else CardSide.QUESTION
        if (binding.side != CardSide.BOTH && binding.side != currentSide) return false
        val hasCard = currentQueueStateOf(state) != null

        fun directFlag(value: Int): Boolean {
            if (!hasCard || working) return false
            viewModel.setFlag(value)
            return true
        }

        fun toggleFlag(value: Int): Boolean = directFlag(if (flag == value) 0 else value)
        return when (action) {
            ViewerAction.SHOW_ANSWER -> {
                if (state !is StudyUiState.Question || working || !schedulingReady) {
                    false
                } else {
                    true.also { viewModel.revealCurrent() }
                }
            }
            ViewerAction.ANSWER_AGAIN ->
                (state is StudyUiState.Answered && !working).also { if (it) viewModel.grade(Rating.AGAIN) }
            ViewerAction.ANSWER_HARD ->
                (state is StudyUiState.Answered && !working).also { if (it) viewModel.grade(Rating.HARD) }
            ViewerAction.ANSWER_GOOD ->
                (state is StudyUiState.Answered && !working).also { if (it) viewModel.grade(Rating.GOOD) }
            ViewerAction.ANSWER_EASY ->
                (state is StudyUiState.Answered && !working).also { if (it) viewModel.grade(Rating.EASY) }
            ViewerAction.UNDO -> (!working && canUndo).also { if (it) viewModel.undo() }
            ViewerAction.MARK -> (hasCard && !working).also { if (it) viewModel.toggleMark() }
            ViewerAction.EDIT ->
                (hasCard && !working).also {
                    if (it) currentQueueStateOf(state)?.topCard?.id?.let(onEditCard)
                }
            ViewerAction.ADD_NOTE -> (hasCard && !working).also { if (it) onAddNote() }
            ViewerAction.PLAY_MEDIA,
            ViewerAction.REPLAY_VOICE,
            -> (hasCard && !working).also { if (it) mediaController.replay() }
            ViewerAction.BURY_CARD -> (hasCard && !working).also { if (it) viewModel.buryCurrent() }
            ViewerAction.BURY_NOTE -> (hasCard && !working).also { if (it) viewModel.buryNote() }
            ViewerAction.SUSPEND_CARD -> (hasCard && !working).also { if (it) viewModel.suspendCurrent() }
            ViewerAction.SUSPEND_NOTE -> (hasCard && !working).also { if (it) viewModel.suspendNote() }
            ViewerAction.UNSET_FLAG -> directFlag(0)
            ViewerAction.FLAG_RED -> directFlag(1)
            ViewerAction.FLAG_ORANGE -> directFlag(2)
            ViewerAction.FLAG_GREEN -> directFlag(3)
            ViewerAction.FLAG_BLUE -> directFlag(4)
            ViewerAction.FLAG_PINK -> directFlag(5)
            ViewerAction.FLAG_TURQUOISE -> directFlag(6)
            ViewerAction.FLAG_PURPLE -> directFlag(7)
            ViewerAction.TOGGLE_FLAG_RED -> toggleFlag(1)
            ViewerAction.TOGGLE_FLAG_ORANGE -> toggleFlag(2)
            ViewerAction.TOGGLE_FLAG_GREEN -> toggleFlag(3)
            ViewerAction.TOGGLE_FLAG_BLUE -> toggleFlag(4)
            ViewerAction.TOGGLE_FLAG_PINK -> toggleFlag(5)
            ViewerAction.TOGGLE_FLAG_TURQUOISE -> toggleFlag(6)
            ViewerAction.TOGGLE_FLAG_PURPLE -> toggleFlag(7)
            ViewerAction.PAGE_UP -> hasCard.also { if (it) cardWebViewHolder.pageUp() }
            ViewerAction.PAGE_DOWN -> hasCard.also { if (it) cardWebViewHolder.pageDown() }
            ViewerAction.EXIT -> true.also { onClose() }
            else -> false
        }
    }

    val currentViewerActionProcessor =
        rememberUpdatedState<(ViewerAction, ReviewerBinding) -> Boolean>(::processViewerAction)
    val bindingMap =
        remember(context) {
            BindingMap(
                context.sharedPrefs(),
                ViewerAction.entries,
                BindingProcessor { action, binding -> currentViewerActionProcessor.value(action, binding) },
            )
        }
    val inputHost = context.findFragmentActivity() as? ExpressiveReviewerInputHost
    val inputOwner = remember { Any() }
    DisposableEffect(inputHost, bindingMap, composeInputFocused, webInputFocused) {
        inputHost?.installReviewerInputHandlers(
            owner = inputOwner,
            keyHandler = { event ->
                event.action == KeyEvent.ACTION_DOWN &&
                    !composeInputFocused &&
                    !webInputFocused &&
                    bindingMap.onKeyDown(event)
            },
            motionHandler = { event ->
                !composeInputFocused && !webInputFocused && bindingMap.onGenericMotionEvent(event)
            },
        )
        onDispose { inputHost?.clearReviewerInputHandlers(inputOwner) }
    }

    Box(Modifier.fillMaxSize()) {
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
                TextButton(enabled = !working, onClick = onAddNote) {
                    Text("Add note")
                }
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
                    enabled = !working && canUndo,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = { viewModel.undo() },
                )
                StudyActionButton(
                    iconRes = R.drawable.ic_play_circle_white,
                    label = "Replay media",
                    enabled = !working && qs != null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = mediaController::replay,
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
                Box {
                    StudyActionButton(
                        iconRes = com.ichi2.anki.R.drawable.ic_hide,
                        label = "Bury",
                        enabled = !working && qs != null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        onClick = { buryMenu = true },
                    )
                    DropdownMenu(expanded = buryMenu, onDismissRequest = { buryMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Bury card") },
                            onClick = {
                                buryMenu = false
                                viewModel.buryCurrent()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Bury note") },
                            onClick = {
                                buryMenu = false
                                viewModel.buryNote()
                            },
                        )
                    }
                }
                Box {
                    StudyActionButton(
                        iconRes = com.ichi2.anki.R.drawable.round_pause_24,
                        label = "Suspend",
                        enabled = !working && qs != null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        onClick = { suspendMenu = true },
                    )
                    DropdownMenu(expanded = suspendMenu, onDismissRequest = { suspendMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Suspend card") },
                            onClick = {
                                suspendMenu = false
                                viewModel.suspendCurrent()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Suspend note") },
                            onClick = {
                                suspendMenu = false
                                viewModel.suspendNote()
                            },
                        )
                    }
                }
            }
            CardGestureArea(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                shouldHandleGesture = {
                    !composeInputFocused && !webInputFocused && cardWebViewHolder.shouldHandleCardGesture()
                },
                gestureKey =
                    Triple(
                        currentQueueStateOf(state)?.topCard?.id,
                        state is StudyUiState.Answered,
                        rendererGeneration,
                    ),
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
                    is StudyUiState.Error -> ErrorPane(s.message, onRetry = viewModel::retry)
                    is StudyUiState.TimeboxReached ->
                        TimeboxPane(
                            info = s.info,
                            onContinue = viewModel::continueAfterTimebox,
                            onFinish = onClose,
                        )
                    is StudyUiState.Finished -> {
                        val info = congratulationsInfo
                        if (info == null) {
                            LoadingIndicator()
                        } else {
                            CongratsPane(
                                info = info,
                                working = working,
                                onClose = onClose,
                                onDeckOptions = { onDeckOptions(info.deckId, info.isFiltered) },
                                onStudyMore = { customStudyInfo = info },
                                onUnbury = {
                                    if (info.haveUserBuried && info.haveSchedBuried) {
                                        unburyInfo = info
                                    } else {
                                        viewModel.unburyFinished(UnburyDeckRequest.Mode.ALL)
                                    }
                                },
                                onRebuild = viewModel::rebuildFinishedDeck,
                                onEmpty = viewModel::emptyFinishedDeck,
                            )
                        }
                    }
                    is StudyUiState.Question ->
                        Column {
                            CardPane(
                                holder = cardWebViewHolder,
                                card = s.card.queueState.topCard,
                                cardId = s.card.queueState.topCard.id,
                                side = ExpressiveCardSide.QUESTION,
                                html =
                                    themedCardHtml(
                                        content = s.card.frontHtml,
                                        kind = transition.toQuestionKind(),
                                        hideTypeInput = s.card.typeUseInputTag,
                                    ),
                                baseUrl = s.card.baseUrl,
                                autoplay = s.card.autoplay,
                                rendererGeneration = rendererGeneration,
                                renderRevision = s.card.renderRevision,
                                mediaController = mediaController,
                            )
                            // Type-answer cards: learner types before revealing.
                            if (s.card.typeCorrect != null) {
                                OutlinedTextField(
                                    value = typedAnswer,
                                    onValueChange = viewModel::updateTypedAnswer,
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 20.dp, vertical = 8.dp)
                                            .onFocusChanged { composeInputFocused = it.isFocused },
                                    label = { Text("Type answer") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                    keyboardActions = KeyboardActions(onDone = { viewModel.revealCurrent() }),
                                    shape = MaterialTheme.shapes.large,
                                )
                            }
                        }
                    is StudyUiState.Answered ->
                        CardPane(
                            holder = cardWebViewHolder,
                            card = s.card.card.queueState.topCard,
                            cardId = s.card.card.queueState.topCard.id,
                            side = ExpressiveCardSide.ANSWER,
                            html = themedCardHtml(s.card.backHtml, CardTransition.ANSWER_REVEAL),
                            baseUrl = s.card.card.baseUrl,
                            autoplay = s.card.card.autoplay,
                            rendererGeneration = rendererGeneration,
                            renderRevision = s.card.card.renderRevision,
                            mediaController = mediaController,
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
                                enabled = !working && schedulingReady,
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
        SnackbarHost(
            hostState = snackbarHostState,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(bottom = 72.dp),
        )
    }

    customStudyInfo?.let { info ->
        CustomStudyDialog(
            deckId = info.deckId,
            deckName = deckName,
            deckGateway = deckGateway,
            onDismiss = { customStudyInfo = null },
            onDone = { sessionDeckId ->
                customStudyInfo = null
                if (sessionDeckId == null) {
                    viewModel.loadNext()
                } else {
                    coroutineScope.launch {
                        val session = deckGateway.loadDecks().findDeckById(sessionDeckId)
                        if (session == null) {
                            snackbarHostState.showSnackbar(
                                "The Custom Study session was created but could not be opened.",
                            )
                        } else {
                            onOpenStudy(session.did, session.name)
                        }
                    }
                }
            },
        )
    }

    unburyInfo?.let {
        AlertDialog(
            onDismissRequest = { unburyInfo = null },
            title = { Text("Unbury cards") },
            text = {
                Column {
                    TextButton(
                        onClick = {
                            unburyInfo = null
                            viewModel.unburyFinished(UnburyDeckRequest.Mode.USER_ONLY)
                        },
                    ) { Text("Manually buried cards") }
                    TextButton(
                        onClick = {
                            unburyInfo = null
                            viewModel.unburyFinished(UnburyDeckRequest.Mode.SCHED_ONLY)
                        },
                    ) { Text("Buried siblings") }
                    TextButton(
                        onClick = {
                            unburyInfo = null
                            viewModel.unburyFinished(UnburyDeckRequest.Mode.ALL)
                        },
                    ) { Text("All buried cards") }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { unburyInfo = null }) { Text("Cancel") }
            },
        )
    }

    missingUserAction?.let { actionNumber ->
        AlertDialog(
            onDismissRequest = { missingUserAction = null },
            text = { Text(context.getString(R.string.missing_user_action_dialog_message, actionNumber)) },
            confirmButton = {
                TextButton(onClick = { missingUserAction = null }) {
                    Text(context.getString(R.string.dialog_ok))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        missingUserAction = null
                        context.openUrl(R.string.link_user_actions_help)
                    },
                ) {
                    Text(context.getString(R.string.help))
                }
            },
        )
    }
}

private fun List<UiDeck>.findDeckById(deckId: DeckId): UiDeck? {
    for (deck in this) {
        if (deck.did == deckId) return deck
        deck.children.findDeckById(deckId)?.let { return it }
    }
    return null
}

private tailrec fun Context.findFragmentActivity(): FragmentActivity? =
    when (this) {
        is FragmentActivity -> this
        is ContextWrapper -> baseContext.findFragmentActivity()
        else -> null
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
    holder: ExpressiveCardWebViewHolder,
    card: Card,
    cardId: CardId,
    side: ExpressiveCardSide,
    html: String,
    baseUrl: String,
    autoplay: Boolean,
    rendererGeneration: Int,
    renderRevision: Long,
    mediaController: ExpressiveReviewerMediaController,
) {
    val renderKey =
        ExpressiveCardRenderKey(
            cardId = cardId,
            side = side,
            rendererGeneration = rendererGeneration,
            baseUrl = baseUrl,
            html = html,
            renderRevision = renderRevision,
        )
    androidx.compose.runtime.key(holder) {
        AndroidView(
            factory = { holder.webView },
            update = {
                mediaController.prepare(card, renderKey)
                holder.setMediaPlaybackRequiresUserGesture(!autoplay)
                holder.render(renderKey)
            },
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp),
        )
    }
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
private fun ErrorPane(
    message: String,
    onRetry: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "Couldn't load the card:\n$message",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry) {
            Text("Retry")
        }
    }
}

@Composable
private fun TimeboxPane(
    info: com.ichi2.anki.ui.expressive.ExpressiveTimeboxInfo,
    onContinue: () -> Unit,
    onFinish: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Timebox reached") },
        text = {
            Text("You reviewed ${info.reviews} cards in ${info.seconds / 60} minutes.")
        },
        confirmButton = {
            TextButton(onClick = onContinue) { Text("Continue") }
        },
        dismissButton = {
            TextButton(onClick = onFinish) { Text("Finish") }
        },
    )
}

@Composable
private fun CongratsPane(
    info: ExpressiveCongratulationsInfo,
    working: Boolean,
    onClose: () -> Unit,
    onDeckOptions: () -> Unit,
    onStudyMore: () -> Unit,
    onUnbury: () -> Unit,
    onRebuild: () -> Unit,
    onEmpty: () -> Unit,
) {
    val dueMessage =
        when {
            info.secsUntilNextLearn >= 86_400 -> "This deck is finished for now."
            info.secsUntilNextLearn < 60 -> "The next learning card is due in ${info.secsUntilNextLearn} seconds."
            info.secsUntilNextLearn < 3_600 ->
                "The next learning card is due in ${info.secsUntilNextLearn / 60} minutes."
            else -> "The next learning card is due in ${info.secsUntilNextLearn / 3_600} hours."
        }
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
            dueMessage,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        if (info.haveUserBuried || info.haveSchedBuried) {
            Button(enabled = !working, onClick = onUnbury) { Text("Unbury cards") }
        }
        if (info.isFiltered) {
            Button(enabled = !working, onClick = onRebuild) { Text("Rebuild filtered deck") }
            TextButton(enabled = !working, onClick = onEmpty) { Text("Empty filtered deck") }
        } else {
            Button(enabled = !working, onClick = onStudyMore) { Text("Study more") }
        }
        TextButton(enabled = !working, onClick = onDeckOptions) { Text("Deck options") }
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
