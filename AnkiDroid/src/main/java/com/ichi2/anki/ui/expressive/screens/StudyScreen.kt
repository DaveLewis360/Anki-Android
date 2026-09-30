// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.ui.expressive.screens

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.view.KeyEvent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import anki.scheduler.CardAnswer.Rating
import anki.scheduler.UnburyDeckRequest
import com.ichi2.anki.Flag
import com.ichi2.anki.R
import com.ichi2.anki.common.preferences.sharedPrefs
import com.ichi2.anki.dialogs.TtsVoicesDialogFragment
import com.ichi2.anki.libanki.Card
import com.ichi2.anki.libanki.CardId
import com.ichi2.anki.libanki.DeckId
import com.ichi2.anki.libanki.Decks
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
import com.ichi2.anki.ui.expressive.StudyMessage
import com.ichi2.anki.ui.expressive.StudyUiState
import com.ichi2.anki.ui.expressive.StudyViewModel
import com.ichi2.anki.ui.expressive.UiDeck
import com.ichi2.anki.ui.expressive.components.AnswerButtons
import com.ichi2.anki.ui.expressive.components.CardGestureArea
import com.ichi2.anki.ui.expressive.components.GestureAction
import com.ichi2.anki.ui.expressive.components.ShowAnswerButton
import com.ichi2.anki.ui.expressive.components.StudyCardActions
import com.ichi2.anki.ui.expressive.components.StudyLayout
import com.ichi2.anki.ui.expressive.components.StudyTopAppBar
import com.ichi2.anki.ui.expressive.components.answerHaptic
import com.ichi2.anki.ui.expressive.components.rememberStudyActionLabels
import com.ichi2.anki.ui.expressive.flagNamesOrNone
import com.ichi2.anki.ui.expressive.ratingFor
import com.ichi2.anki.ui.expressive.remainingCards
import com.ichi2.anki.utils.openUrl
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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
    var customStudyInfo by remember { mutableStateOf<ExpressiveCongratulationsInfo?>(null) }
    var unburyInfo by remember { mutableStateOf<ExpressiveCongratulationsInfo?>(null) }
    var composeInputFocused by remember { mutableStateOf(false) }
    var webInputFocused by remember { mutableStateOf(false) }
    var missingUserAction by remember { mutableStateOf<String?>(null) }
    var unsupportedSignalShown by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    androidx.compose.runtime.LaunchedEffect(viewModel) {
        viewModel.actionErrors.collect { snackbarHostState.showSnackbar(it.resolve(context)) }
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
    // The last render the WebView can draw without any of the previous one; the card pane enters on it.
    var visibleRenderKey by remember { mutableStateOf<ExpressiveCardRenderKey?>(null) }
    val cardMotion = remember { CardMotion() }
    // Cards follow the screen's light or dark theme, whatever theme the shared renderer last saw.
    val cardCanvas = MaterialTheme.colorScheme.background
    val onCardCanvas = MaterialTheme.colorScheme.onBackground
    val cardDark = cardCanvas.luminance() < 0.5f
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
                    coroutineScope.launch {
                        snackbarHostState.showSnackbar(context.getString(R.string.card_viewer_could_not_find_image, filename))
                    }
                },
                onTtsError = { error ->
                    coroutineScope.launch { snackbarHostState.showSnackbar(error.localizedErrorMessage(context)) }
                },
                onShowTtsVoices = {
                    val activity = context.findFragmentActivity()
                    if (activity == null) {
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar(context.getString(R.string.expressive_study_tts_voices_unavailable))
                        }
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
                        snackbarHostState.showSnackbar(context.getString(R.string.expressive_study_unsupported_card_signal))
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
                    message = customSchedulingFailure(internalNavigationPayload(uri)),
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
                            message = StudyMessage.Resource(R.string.expressive_study_custom_scheduling_failed),
                        )
                    }
                    coroutineScope.launch {
                        snackbarHostState.showSnackbar(context.getString(R.string.expressive_study_card_renderer_error, message))
                    }
                },
                onLocalResourceError = { uri ->
                    coroutineScope.launch {
                        snackbarHostState.showSnackbar(
                            context.getString(R.string.card_viewer_could_not_find_image, uri.lastPathSegment.orEmpty()),
                        )
                    }
                },
                onInternalNavigation = ::handleInternalNavigation,
                onPageFinished = { renderKey ->
                    // A finished page is drawable too, should the commit signal have been missed.
                    visibleRenderKey = renderKey
                    mediaController.onPageFinished(renderKey)
                    currentSchedulingMutation.value
                        ?.takeIf { command ->
                            command.cardId == renderKey.cardId &&
                                command.renderRevision == renderKey.renderRevision
                        }?.let { command ->
                            webViewHolder.get()?.evaluateJavascript(schedulingMutationJavascript(command))
                        }
                },
                onPageVisible = { renderKey -> visibleRenderKey = renderKey },
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
            // A cleared WebView draws nothing: no later render counts as drawable until it reports so.
            visibleRenderKey = null
        }
    }
    androidx.compose.runtime.LaunchedEffect(
        currentQueueStateOf(state)?.topCard?.id,
        state is StudyUiState.Answered,
    ) {
        webInputFocused = false
    }

    val haptics = LocalHapticFeedback.current

    /** Answers by gesture, with the answer buttons' haptic feedback when the answer is taken. */
    fun gradeByGesture(rating: Rating) {
        gestureAnswerHaptic(rating, answered = state is StudyUiState.Answered, working = working)?.let(haptics::performHapticFeedback)
        viewModel.grade(rating)
    }

    fun performGesture(actionKey: String) {
        val qs = currentQueueStateOf(state)
        when (GestureAction.fromKey(actionKey)) {
            GestureAction.SHOW_ANSWER -> viewModel.revealCurrent(typedAnswer)
            GestureAction.GOOD -> gradeByGesture(Rating.GOOD)
            GestureAction.HARD -> gradeByGesture(Rating.HARD)
            GestureAction.AGAIN -> gradeByGesture(Rating.AGAIN)
            GestureAction.EASY -> gradeByGesture(Rating.EASY)
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

    val flagNames by produceState(emptyMap<Flag, String>(), studyGateway) { value = studyGateway.flagNamesOrNone() }
    val actionLabels = rememberStudyActionLabels()
    val showsCard = state is StudyUiState.Question || state is StudyUiState.Answered

    StudyLayout(
        topBar = {
            StudyTopAppBar(
                // As in Classic, the deck itself: its parents would push it out of the title.
                deckName = Decks.basename(deckName),
                remaining = if (showsCard) state.remainingCards() else null,
                // Count the card in flight as partial progress so the wave is visible
                // from the very first question and glides forward on every answer.
                progress =
                    if (sessionTotal > 0) {
                        ((sessionTotal - state.remainingCards() + 1f) / (sessionTotal + 1f)).coerceIn(0.03f, 0.97f)
                    } else {
                        0f
                    },
                timerSeconds = if (showsCard) seconds else null,
                canUndo = !working && canUndo,
                canAddNote = !working,
                onClose = onClose,
                onUndo = viewModel::undo,
                onAddNote = onAddNote,
            )
        },
        cardActions = {
            if (showsCard) {
                StudyCardActions(
                    marked = marked,
                    flag = Flag.fromCode(flag),
                    flagNames = flagNames,
                    labels = actionLabels,
                    enabled = !working,
                    onReplay = mediaController::replay,
                    onToggleMark = viewModel::toggleMark,
                    onSetFlag = { viewModel.setFlag(it.code) },
                    onEdit = { performGesture(GestureAction.EDIT.key) },
                    onBuryCard = viewModel::buryCurrent,
                    onBuryNote = viewModel::buryNote,
                    onSuspendCard = viewModel::suspendCurrent,
                    onSuspendNote = viewModel::suspendNote,
                )
            }
        },
        answerControls = {
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
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        CardGestureArea(
            modifier = Modifier.padding(padding).fillMaxSize(),
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
                is StudyUiState.Loading -> LoadingPane()
                is StudyUiState.Error -> ErrorPane(s.message.resolve(context), onRetry = viewModel::retry)
                is StudyUiState.TimeboxReached ->
                    TimeboxDialog(
                        info = s.info,
                        onContinue = viewModel::continueAfterTimebox,
                        onFinish = onClose,
                    )
                is StudyUiState.Finished -> {
                    val info = congratulationsInfo
                    if (info == null) {
                        LoadingPane()
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
                        val frontHtml =
                            remember(s.card.frontHtml, cardDark, cardCanvas, onCardCanvas, s.card.typeUseInputTag) {
                                expressiveCardHtml(
                                    rendered = s.card.frontHtml,
                                    dark = cardDark,
                                    canvas = cardCanvas,
                                    onCanvas = onCardCanvas,
                                    hideTypeInput = s.card.typeUseInputTag,
                                )
                            }
                        CardPane(
                            holder = cardWebViewHolder,
                            card = s.card.queueState.topCard,
                            cardId = s.card.queueState.topCard.id,
                            side = ExpressiveCardSide.QUESTION,
                            html = frontHtml,
                            baseUrl = s.card.baseUrl,
                            autoplay = s.card.autoplay,
                            rendererGeneration = rendererGeneration,
                            renderRevision = s.card.renderRevision,
                            mediaController = mediaController,
                            motion = cardMotion,
                            transition = transition.toQuestionKind(),
                            visibleRenderKey = visibleRenderKey,
                            // The typing field below keeps its height: the card takes the rest.
                            modifier = Modifier.weight(1f),
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
                                label = { Text(stringResource(R.string.type_answer_hint)) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { viewModel.revealCurrent() }),
                                shape = MaterialTheme.shapes.extraLarge,
                            )
                        }
                    }
                is StudyUiState.Answered -> {
                    val backHtml =
                        remember(s.card.backHtml, cardDark, cardCanvas, onCardCanvas) {
                            expressiveCardHtml(s.card.backHtml, cardDark, cardCanvas, onCardCanvas)
                        }
                    CardPane(
                        holder = cardWebViewHolder,
                        card = s.card.card.queueState.topCard,
                        cardId = s.card.card.queueState.topCard.id,
                        side = ExpressiveCardSide.ANSWER,
                        html = backHtml,
                        baseUrl = s.card.card.baseUrl,
                        autoplay = s.card.card.autoplay,
                        rendererGeneration = rendererGeneration,
                        renderRevision = s.card.card.renderRevision,
                        mediaController = mediaController,
                        motion = cardMotion,
                        transition = CardTransition.ANSWER_REVEAL,
                        visibleRenderKey = visibleRenderKey,
                    )
                }
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
                            snackbarHostState.showSnackbar(context.getString(R.string.expressive_study_custom_study_not_opened))
                        } else {
                            onOpenStudy(session.did, session.name)
                        }
                    }
                }
            },
        )
    }

    unburyInfo?.let {
        UnburyDialog(
            onUnbury = { mode ->
                unburyInfo = null
                viewModel.unburyFinished(mode)
            },
            onDismiss = { unburyInfo = null },
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

/**
 * The haptic feedback of answering with [rating] by gesture: the answer buttons' own, if the answer
 * is taken, which it is only on the answer side, and while no other answer is being saved.
 */
internal fun gestureAnswerHaptic(
    rating: Rating,
    answered: Boolean,
    working: Boolean,
): HapticFeedbackType? = if (answered && !working) answerHaptic(again = rating == Rating.AGAIN) else null

/** The longest error of a deck's custom scheduling code the learner is shown. */
private const val MAX_CUSTOM_SCHEDULING_ERROR_LENGTH = 500

/**
 * The message when a deck's custom scheduling code failed: the error the code reported, which helps
 * its author, if it reported one. The app's own failures report none.
 */
internal fun customSchedulingFailure(reportedError: String): StudyMessage =
    if (reportedError.isBlank()) {
        StudyMessage.Resource(R.string.expressive_study_custom_scheduling_failed)
    } else {
        StudyMessage.Resource(
            R.string.expressive_study_custom_scheduling_failed_detail,
            listOf(reportedError.take(MAX_CUSTOM_SCHEDULING_ERROR_LENGTH)),
        )
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
    motion: CardMotion,
    transition: CardTransition,
    visibleRenderKey: ExpressiveCardRenderKey?,
    modifier: Modifier = Modifier,
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
    val visible = visibleRenderKey == renderKey
    val motionScheme = MaterialTheme.motionScheme
    val density = LocalDensity.current
    val towardsEnd = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
    LaunchedEffect(renderKey, visible) {
        val spatial = motionScheme.defaultSpatialSpec<Float>()
        val effects = motionScheme.defaultEffectsSpec<Float>()
        val animationsOff = coroutineContext[MotionDurationScale]?.scaleFactor == 0f
        when (cardPaneStep(side, visible, motion.hidden, animationsOff)) {
            CardPaneStep.ENTER -> {
                val (fromX, fromY) =
                    with(density) {
                        when (transition) {
                            CardTransition.QUESTION_FORWARD -> towardsEnd * CARD_SLIDE_DISTANCE.toPx() to 0f
                            CardTransition.QUESTION_BACK -> -towardsEnd * CARD_SLIDE_DISTANCE.toPx() to 0f
                            else -> 0f to CARD_RISE_DISTANCE.toPx()
                        }
                    }
                motion.enter(fromX, fromY, spatial, effects)
            }
            CardPaneStep.SETTLE -> motion.settle(spatial, effects)
            CardPaneStep.HIDE_UNTIL_VISIBLE -> {
                // Whatever the WebView still shows fades out, a fade interrupted by a quick reveal included.
                launch { motion.leave(motionScheme.fastEffectsSpec()) }
                delay(CARD_VISIBLE_FALLBACK_MILLIS)
                motion.settle(spatial, effects)
            }
        }
    }
    androidx.compose.runtime.key(holder) {
        AndroidView(
            factory = { holder.webView },
            update = {
                mediaController.prepare(card, renderKey)
                holder.setMediaPlaybackRequiresUserGesture(!autoplay)
                holder.render(renderKey)
            },
            modifier =
                modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp)
                    .graphicsLayer {
                        alpha = motion.alpha.value
                        translationX = motion.offsetX.value
                        translationY = motion.offsetY.value
                    },
        )
    }
}

/** How far a new question slides in after an answer, toward the reading direction or against it after Again. */
private val CARD_SLIDE_DISTANCE = 56.dp

/** How far the first question of a session rises in. */
private val CARD_RISE_DISTANCE = 24.dp

/** How long the pane waits for a render to become drawable before it shows the WebView anyway. */
private const val CARD_VISIBLE_FALLBACK_MILLIS = 1_500L

/**
 * The card pane's move from one document to the next, on the theme's motion springs. Nothing is
 * animated inside the card: the card on screen leaves as a new question starts loading, and the
 * question enters once the WebView can draw it without any content of the previous card. An answer
 * replaces its question in place (see [cardPaneStep]).
 */
@Stable
private class CardMotion {
    val alpha = Animatable(1f)
    val offsetX = Animatable(0f)
    val offsetY = Animatable(0f)

    /** Whether a document left and its successor has not been shown yet. */
    var hidden = false
        private set

    suspend fun leave(effects: FiniteAnimationSpec<Float>) {
        hidden = true
        alpha.animateTo(0f, effects)
    }

    suspend fun enter(
        fromX: Float,
        fromY: Float,
        spatial: FiniteAnimationSpec<Float>,
        effects: FiniteAnimationSpec<Float>,
    ) {
        offsetX.snapTo(fromX)
        offsetY.snapTo(fromY)
        settle(spatial, effects)
    }

    suspend fun settle(
        spatial: FiniteAnimationSpec<Float>,
        effects: FiniteAnimationSpec<Float>,
    ) {
        hidden = false
        coroutineScope {
            launch { alpha.animateTo(1f, effects) }
            launch { offsetX.animateTo(0f, spatial) }
            launch { offsetY.animateTo(0f, spatial) }
        }
    }
}

/** What the card pane does when its render, or whether that render can be drawn, changes. */
internal enum class CardPaneStep {
    /** Slide and fade the drawable question in. */
    ENTER,

    /** Come to rest, visible, from wherever the pane is. */
    SETTLE,

    /** Hide what the WebView still shows until this document can be drawn; show the pane after a while regardless. */
    HIDE_UNTIL_VISIBLE,
}

/**
 * The card pane's next step for a document on [side]: whether it can be [visible]ly drawn yet,
 * whether the pane is [hidden] after a document left, and whether system [animationsOff].
 */
internal fun cardPaneStep(
    side: ExpressiveCardSide,
    visible: Boolean,
    hidden: Boolean,
    animationsOff: Boolean,
): CardPaneStep =
    when {
        // Without animations, the WebView swaps documents in place: nothing hides, nothing slides.
        animationsOff -> CardPaneStep.SETTLE
        visible -> if (side == ExpressiveCardSide.QUESTION) CardPaneStep.ENTER else CardPaneStep.SETTLE
        // A new question hides the previous card while it loads. The answer keeps the question in
        // place until its document replaces it, unless the question itself never appeared: then the
        // WebView would still show the card before.
        side == ExpressiveCardSide.QUESTION || hidden -> CardPaneStep.HIDE_UNTIL_VISIBLE
        else -> CardPaneStep.SETTLE
    }
