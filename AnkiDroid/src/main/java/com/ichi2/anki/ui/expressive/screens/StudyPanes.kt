// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.ui.expressive.screens

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import anki.scheduler.UnburyDeckRequest
import com.ichi2.anki.CollectionManager.TR
import com.ichi2.anki.R
import com.ichi2.anki.ui.expressive.ExpressiveCongratulationsInfo
import com.ichi2.anki.ui.expressive.ExpressiveTimeboxInfo
import com.ichi2.anki.ui.expressive.components.GestureAction
import com.ichi2.anki.ui.internationalization.SentenceCase
import com.ichi2.anki.ui.internationalization.toSentenceCase
import kotlin.math.roundToInt

/** The panes' text and buttons stop growing here, so that lines stay easy to read on tablets. */
private val PANE_MAX_WIDTH = 400.dp

private val BADGE_SIZE = 112.dp
private val BADGE_ICON_SIZE = 48.dp

/** The badge grows into place from this fraction of its size. */
private const val BADGE_START_SCALE = 0.6f

private const val SECONDS_PER_MINUTE = 60
private const val SECONDS_PER_HOUR = 3_600

/** Anki tells when the next learning card is due only when it is due within a day. */
private const val SECONDS_PER_DAY = 86_400

/** Shown in the card's place while the next card loads. */
@Composable
internal fun LoadingPane() {
    val description = stringResource(R.string.expressive_study_loading)
    Box(
        Modifier.fillMaxSize().semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        LoadingIndicator()
    }
}

/** Studying cannot go on: why, and a way to try again. */
@Composable
internal fun ErrorPane(
    message: String,
    onRetry: () -> Unit,
) {
    StudyPane(
        badge = MaterialShapes.Cookie4Sided.toShape(),
        badgeColor = MaterialTheme.colorScheme.errorContainer,
        onBadgeColor = MaterialTheme.colorScheme.onErrorContainer,
        icon = R.drawable.ic_error_outline,
        title = stringResource(R.string.expressive_study_error_title),
        messages = listOf(message),
    ) {
        PaneButton(stringResource(R.string.retry), PaneEmphasis.PRIMARY, onClick = onRetry)
    }
}

/**
 * The deck is finished for now: what it still holds for today, in the words of Anki's own
 * congratulations page, and the ways to study on.
 */
@Composable
internal fun CongratsPane(
    info: ExpressiveCongratulationsInfo,
    working: Boolean,
    onClose: () -> Unit,
    onDeckOptions: () -> Unit,
    onStudyMore: () -> Unit,
    onUnbury: () -> Unit,
    onRebuild: () -> Unit,
    onEmpty: () -> Unit,
) {
    val context = LocalContext.current
    val messages = remember(info) { congratulationsMessages(info) }
    StudyPane(
        badge = MaterialShapes.Cookie9Sided.toShape(),
        badgeColor = MaterialTheme.colorScheme.primaryContainer,
        onBadgeColor = MaterialTheme.colorScheme.onPrimaryContainer,
        icon = R.drawable.ic_done,
        title = remember { TR.schedulingCongratulationsFinished() },
        messages = messages,
        // A small celebration: the badge turns into place as it grows.
        badgeTurn = -45f,
    ) {
        PaneButton(stringResource(R.string.expressive_study_back_to_decks), PaneEmphasis.PRIMARY, onClick = onClose)
        if (info.haveUserBuried || info.haveSchedBuried) {
            PaneButton(TR.studyingUnbury(), PaneEmphasis.SECONDARY, enabled = !working, onClick = onUnbury)
        }
        if (info.isFiltered) {
            PaneButton(TR.actionsRebuild(), PaneEmphasis.SECONDARY, enabled = !working, onClick = onRebuild)
            PaneButton(TR.studyingEmpty(), PaneEmphasis.TERTIARY, enabled = !working, onClick = onEmpty)
        } else {
            PaneButton(with(context) { SentenceCase.customStudy }, PaneEmphasis.SECONDARY, enabled = !working, onClick = onStudyMore)
        }
        PaneButton(with(context) { SentenceCase.deckOptions }, PaneEmphasis.TERTIARY, enabled = !working, onClick = onDeckOptions)
    }
}

/** What the finished deck still holds for today, as Anki's congratulations page tells it. */
internal fun congratulationsMessages(info: ExpressiveCongratulationsInfo): List<String> =
    buildList {
        nextLearnDue(info.secsUntilNextLearn)?.let { (unit, amount) ->
            val due = TR.schedulingNextLearnDue(unit = unit, amount = amount)
            add(due + " " + TR.schedulingLearnRemaining(remaining = info.learnRemaining))
        }
        if (info.reviewLimitReached) add(TR.schedulingTodayReviewLimitReached())
        if (info.newLimitReached) add(TR.schedulingTodayNewLimitReached())
        if (info.haveUserBuried || info.haveSchedBuried) add(TR.schedulingBuriedCardsFound(unburyThem = TR.schedulingUnburyThem()))
        if (!info.isFiltered) add(TR.schedulingHowToCustomStudy(customStudy = TR.schedulingCustomStudy()))
    }.map { it.asParagraph() }

/** Anki wraps some of these texts for its HTML page, where line breaks are spaces: so they are here. */
internal fun String.asParagraph(): String = replace(Regex("\\s*\\n\\s*"), " ").trim()

/**
 * When the next learning card is due, in the unit Anki tells it in: seconds under a minute, then
 * minutes under an hour, then hours. Null when it is not due within a day.
 */
internal fun nextLearnDue(secondsUntil: Int): Pair<String, Int>? =
    when {
        secondsUntil >= SECONDS_PER_DAY -> null
        secondsUntil < SECONDS_PER_MINUTE -> "seconds" to secondsUntil
        secondsUntil < SECONDS_PER_HOUR -> "minutes" to (secondsUntil / SECONDS_PER_MINUTE.toFloat()).roundToInt()
        else -> "hours" to (secondsUntil / SECONDS_PER_HOUR.toFloat()).roundToInt()
    }

/** The session's time box is up: study on, or finish. As in the new reviewer, only the buttons close it. */
@Composable
internal fun TimeboxDialog(
    info: ExpressiveTimeboxInfo,
    onContinue: () -> Unit,
    onFinish: () -> Unit,
) {
    val message = remember(info) { timeboxMessage(info) }
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.timebox_reached_title)) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onContinue, shapes = ButtonDefaults.shapes()) { Text(TR.studyingContinue()) }
        },
        dismissButton = {
            TextButton(onClick = onFinish, shapes = ButtonDefaults.shapes()) { Text(TR.studyingFinish()) }
        },
    )
}

/** "12 cards studied in 5 minutes.", in Anki's words, as the reviewer tells it. */
internal fun timeboxMessage(info: ExpressiveTimeboxInfo): String =
    TR.studyingCardStudiedIn(info.reviews) + " " + TR.studyingMinute((info.seconds / SECONDS_PER_MINUTE.toFloat()).roundToInt())

/** Which buried cards to show again: those the user buried, the siblings Anki buried, or all. */
@Composable
internal fun UnburyDialog(
    onUnbury: (UnburyDeckRequest.Mode) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val options =
        remember(context) {
            // Anki's English names are in title case: the sentence case ones replace them.
            fun sentence(
                title: String,
                @StringRes sentenceCase: Int,
            ) = title.toSentenceCase(context, sentenceCase)
            listOf(
                UnburyDeckRequest.Mode.USER_ONLY to sentence(TR.studyingManuallyBuriedCards(), R.string.sentence_manually_buried_cards),
                UnburyDeckRequest.Mode.SCHED_ONLY to sentence(TR.studyingBuriedSiblings(), R.string.sentence_buried_siblings),
                UnburyDeckRequest.Mode.ALL to sentence(TR.studyingAllBuriedCards(), R.string.sentence_all_buried_cards),
            )
        }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(TR.studyingUnbury()) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                options.forEachIndexed { index, (mode, label) ->
                    SegmentedListItem(
                        onClick = { onUnbury(mode) },
                        shapes = ListItemDefaults.segmentedShapes(index, options.size),
                        // A clickable list item has no role of its own: each choice acts at once, as a button.
                        modifier = Modifier.semantics { role = Role.Button },
                    ) {
                        Text(label)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.dialog_cancel)) }
        },
    )
}

/** The card actions a long press on the card offers. */
@Composable
internal fun GestureActionMenu(
    onAction: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val actions = listOf(GestureAction.FLAG, GestureAction.EDIT, GestureAction.BURY, GestureAction.SUSPEND, GestureAction.UNDO)
    DropdownMenuPopup(expanded = true, onDismissRequest = onDismiss) {
        // As the flag menu, it scrolls in a window too short for it, as at large font sizes.
        DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 1), modifier = Modifier.verticalScroll(rememberScrollState())) {
            actions.forEachIndexed { index, action ->
                DropdownMenuItem(
                    onClick = { onAction(action.key) },
                    text = { Text(action.label(context)) },
                    shape = MenuDefaults.itemShape(index, actions.size).shape,
                )
            }
        }
    }
}

private enum class PaneEmphasis { PRIMARY, SECONDARY, TERTIARY }

/**
 * A pane in the card's place: a badge in one of Material 3 Expressive's shapes, a title, its
 * messages, and its actions, most emphasized first. Centered, and scrolled when it does not fit,
 * as at large font sizes.
 */
@Composable
private fun StudyPane(
    badge: Shape,
    badgeColor: Color,
    onBadgeColor: Color,
    @DrawableRes icon: Int,
    title: String,
    messages: List<String>,
    badgeTurn: Float = 0f,
    actions: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .then(if (constraints.hasBoundedHeight) Modifier.heightIn(min = maxHeight) else Modifier)
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            PaneBadge(badge, badgeColor, onBadgeColor, icon, badgeTurn)
            Text(
                title,
                style = MaterialTheme.typography.headlineSmallEmphasized,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = PANE_MAX_WIDTH).semantics { heading() },
            )
            messages.forEach { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = PANE_MAX_WIDTH),
                )
            }
            Column(
                Modifier.widthIn(max = PANE_MAX_WIDTH).fillMaxWidth().padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = actions,
            )
        }
    }
}

/**
 * The pane's badge: it grows, and turns by [turn] degrees, into place on the theme's slow spatial
 * spring. With animations off, it is simply there.
 */
@Composable
private fun PaneBadge(
    shape: Shape,
    color: Color,
    onColor: Color,
    @DrawableRes icon: Int,
    turn: Float,
) {
    val entrance = remember { Animatable(0f) }
    val spring = MaterialTheme.motionScheme.slowSpatialSpec<Float>()
    LaunchedEffect(entrance) { entrance.animateTo(1f, spring) }
    Surface(
        shape = shape,
        color = color,
        contentColor = onColor,
        modifier =
            Modifier.size(BADGE_SIZE).graphicsLayer {
                val progress = entrance.value
                scaleX = BADGE_START_SCALE + (1f - BADGE_START_SCALE) * progress
                scaleY = scaleX
                rotationZ = turn * (1f - progress)
            },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(BADGE_ICON_SIZE))
        }
    }
}

/** A pane's action: the primary one a medium filled button, then tonal, then text buttons. */
@Composable
private fun PaneButton(
    label: String,
    emphasis: PaneEmphasis,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val modifier = Modifier.fillMaxWidth()
    when (emphasis) {
        PaneEmphasis.PRIMARY -> {
            val height = ButtonDefaults.MediumContainerHeight
            Button(
                onClick = onClick,
                shapes = ButtonDefaults.shapesFor(height),
                modifier = modifier.heightIn(min = height),
                enabled = enabled,
                contentPadding = ButtonDefaults.contentPaddingFor(height, hasStartIcon = false, hasEndIcon = false),
            ) {
                Text(label, style = ButtonDefaults.textStyleFor(height), textAlign = TextAlign.Center)
            }
        }
        PaneEmphasis.SECONDARY ->
            FilledTonalButton(onClick = onClick, shapes = ButtonDefaults.shapes(), modifier = modifier, enabled = enabled) {
                Text(label, textAlign = TextAlign.Center)
            }
        PaneEmphasis.TERTIARY ->
            TextButton(onClick = onClick, shapes = ButtonDefaults.shapes(), modifier = modifier, enabled = enabled) {
                Text(label, textAlign = TextAlign.Center)
            }
    }
}
