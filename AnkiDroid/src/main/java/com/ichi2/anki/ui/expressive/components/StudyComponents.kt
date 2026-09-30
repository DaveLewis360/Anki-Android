// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.ui.expressive.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.WavyProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ichi2.anki.Flag
import com.ichi2.anki.R
import com.ichi2.anki.ui.expressive.theme.LocalAnswerColors
import com.ichi2.anki.ui.expressive.theme.PillShape
import com.ichi2.anki.ui.internationalization.SentenceCase

/** After this long on one card, the timer turns to the error container colors. */
private const val LONG_CARD_SECONDS = 60

/** The controls stop growing here, so that buttons stay buttons on tablets and desktops. */
private val STUDY_CONTROLS_MAX_WIDTH = 640.dp

/** Below this height, as on phones in landscape, the card actions and the answer controls share one row... */
private val STUDY_COMPACT_HEIGHT = 480.dp

/** ...if the window is at least this wide: in a short and narrow window, such as split screen, they stay stacked. */
private val STUDY_ONE_ROW_MIN_WIDTH = 600.dp

/** Material's opacity for disabled content, for icons that keep their own colors. */
private const val DISABLED_CONTENT_ALPHA = 0.38f

/** Horizontal padding inside an answer button, and the space between two of them. */
private val ANSWER_BUTTON_PADDING = 8.dp
private val ANSWER_BUTTON_SPACING = 8.dp

/**
 * The Study screen's layout: the app bar on top, the card in between, and the [cardActions] above
 * the [answerControls], clear of the navigation bar and the keyboard; in a short but wide window
 * the two share one row. Snackbars appear above the controls.
 */
@Composable
fun StudyLayout(
    topBar: @Composable () -> Unit,
    cardActions: @Composable () -> Unit,
    answerControls: @Composable () -> Unit,
    snackbarHost: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    card: @Composable (PaddingValues) -> Unit,
) {
    BoxWithConstraints(modifier) {
        val oneRow = maxHeight < STUDY_COMPACT_HEIGHT && maxWidth >= STUDY_ONE_ROW_MIN_WIDTH
        Scaffold(
            topBar = topBar,
            bottomBar = {
                Box(
                    Modifier
                        .fillMaxWidth()
                        // The typing field of type-answer cards sits above the controls: both stay above the keyboard.
                        .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    if (oneRow) {
                        Row(
                            Modifier.widthIn(max = STUDY_CONTROLS_MAX_WIDTH * 1.5f).fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            cardActions()
                            Box(Modifier.weight(1f)) { answerControls() }
                        }
                    } else {
                        Column(
                            Modifier.widthIn(max = STUDY_CONTROLS_MAX_WIDTH).fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            cardActions()
                            answerControls()
                        }
                    }
                }
            },
            snackbarHost = snackbarHost,
            content = card,
        )
    }
}

/**
 * The Study screen's app bar: close, the deck and the cards left, the time on this card, undo, and
 * add note, over the session's wavy progress.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudyTopAppBar(
    deckName: String,
    remaining: Int?,
    progress: Float,
    timerSeconds: Int?,
    canUndo: Boolean,
    canAddNote: Boolean,
    onClose: () -> Unit,
    onUndo: () -> Unit,
    onAddNote: () -> Unit,
    modifier: Modifier = Modifier,
    movingWave: Boolean = true,
) {
    Column(modifier) {
        TopAppBar(
            // The deck name is the user's own text: it keeps its direction in either layout direction.
            title = {
                Text(
                    deckName,
                    style = LocalTextStyle.current.copy(textDirection = TextDirection.Content),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            subtitle = {
                if (remaining != null) {
                    Text(
                        pluralStringResource(R.plurals.expressive_study_cards_left, remaining, remaining),
                        style = LocalTextStyle.current.copy(textDirection = TextDirection.Content),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            navigationIcon = {
                IconButton(onClick = onClose) {
                    Icon(painterResource(R.drawable.close_icon), contentDescription = stringResource(R.string.close))
                }
            },
            actions = {
                if (timerSeconds != null) StudyTimer(timerSeconds)
                IconButton(onClick = onUndo, enabled = canUndo) {
                    Icon(painterResource(R.drawable.ic_undo_white), contentDescription = stringResource(R.string.undo))
                }
                IconButton(onClick = onAddNote, enabled = canAddNote) {
                    Icon(painterResource(R.drawable.ic_add_note), contentDescription = stringResource(R.string.menu_add_note))
                }
            },
        )
        WavyProgress(
            progress = progress,
            moving = movingWave,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}

/** The time spent on the current card, on the error container colors once it gets long. */
@Composable
private fun StudyTimer(seconds: Int) {
    val long = seconds > LONG_CARD_SECONDS
    val time = formatSeconds(seconds)
    val description = stringResource(R.string.expressive_study_time_on_card, time)
    Surface(
        shape = PillShape,
        color = if (long) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (long) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp).clearAndSetSemantics { contentDescription = description },
    ) {
        Text(time, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
    }
}

/**
 * Session progress on Material 3 Expressive's wavy indicator; the fill glides forward on the
 * theme's slow spatial spring. A still wave ([moving] false) keeps screenshots stable.
 */
@Composable
fun WavyProgress(
    progress: Float,
    modifier: Modifier = Modifier,
    moving: Boolean = true,
) {
    val glide by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = MaterialTheme.motionScheme.slowSpatialSpec(),
        label = "waveGlide",
    )
    LinearWavyProgressIndicator(
        progress = { glide },
        modifier = modifier,
        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        amplitude = { 1.0f },
        waveSpeed = if (moving) WavyProgressIndicatorDefaults.LinearDeterminateWavelength else 0.dp,
    )
}

private fun formatSeconds(total: Int): String {
    val m = total / 60
    val s = total % 60
    return "%d:%02d".format(m, s)
}

/** Labels of the card actions, in Anki's own translations. */
@Immutable
data class StudyActionLabels(
    val markNote: String,
    val flagCard: String,
    val buryCard: String,
    val buryNote: String,
    val suspendCard: String,
    val suspendNote: String,
)

/** The card action labels from Anki's translations, in sentence case. */
@Composable
fun rememberStudyActionLabels(): StudyActionLabels {
    val context = LocalContext.current
    return remember(context) {
        with(context) {
            StudyActionLabels(
                markNote = SentenceCase.markNote,
                flagCard = SentenceCase.flagCard,
                buryCard = SentenceCase.buryCard,
                buryNote = SentenceCase.buryNote,
                suspendCard = SentenceCase.suspendCard,
                suspendNote = SentenceCase.suspendNote,
            )
        }
    }
}

/**
 * The current card's actions on a floating toolbar: replay its audio, mark its note, flag it,
 * edit its note, and bury or suspend it. Flags are listed with their names, as the user named them;
 * until the names are known, the flag button steps to the next flag instead.
 */
@Composable
fun StudyCardActions(
    marked: Boolean,
    flag: Flag,
    flagNames: Map<Flag, String>,
    labels: StudyActionLabels,
    enabled: Boolean,
    onReplay: () -> Unit,
    onToggleMark: () -> Unit,
    onSetFlag: (Flag) -> Unit,
    onEdit: () -> Unit,
    onBuryCard: () -> Unit,
    onBuryNote: () -> Unit,
    onSuspendCard: () -> Unit,
    onSuspendNote: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    var flagMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    HorizontalFloatingToolbar(expanded = true, modifier = modifier) {
        IconButton(onClick = onReplay, enabled = enabled) {
            Icon(painterResource(R.drawable.ic_replay), contentDescription = stringResource(R.string.replay_media))
        }
        IconToggleButton(
            checked = marked,
            onCheckedChange = { checked ->
                haptics.performHapticFeedback(if (checked) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                onToggleMark()
            },
            shapes = IconButtonDefaults.toggleableShapes(),
            enabled = enabled,
        ) {
            Icon(
                painterResource(if (marked) R.drawable.ic_star else R.drawable.ic_star_border_white),
                contentDescription = labels.markNote,
            )
        }
        Box {
            val flagName = flagNames[flag]
            IconButton(
                onClick = {
                    // Without the flags' names there is no menu to show: the button cycles through the flags.
                    if (flagNames.isEmpty()) onSetFlag(Flag.entries[(flag.ordinal + 1) % Flag.entries.size]) else flagMenu = true
                },
                enabled = enabled,
                modifier =
                    Modifier.semantics {
                        if (flag != Flag.NONE && flagName != null) stateDescription = flagName
                    },
            ) {
                FlagIcon(flag, contentDescription = labels.flagCard, enabled = enabled)
            }
            DropdownMenuPopup(expanded = flagMenu, onDismissRequest = { flagMenu = false }) {
                val named = Flag.entries.mapNotNull { option -> flagNames[option]?.let { option to it } }
                // Eight flags are taller than a phone's landscape window at large font sizes: the group scrolls.
                DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 1), modifier = Modifier.verticalScroll(rememberScrollState())) {
                    named.forEachIndexed { index, (option, name) ->
                        DropdownMenuItem(
                            selected = option == flag,
                            onClick = {
                                flagMenu = false
                                onSetFlag(option)
                            },
                            text = { Text(name) },
                            shapes = MenuDefaults.itemShape(index, named.size),
                            leadingIcon = { FlagIcon(option, contentDescription = null) },
                        )
                    }
                }
            }
        }
        IconButton(onClick = onEdit, enabled = enabled) {
            Icon(painterResource(R.drawable.ic_edit_note), contentDescription = stringResource(R.string.cardeditor_title_edit_card))
        }
        Box {
            IconButton(onClick = { moreMenu = true }, enabled = enabled) {
                Icon(
                    painterResource(R.drawable.ic_more_vertical),
                    contentDescription = stringResource(R.string.expressive_study_more_card_actions),
                )
            }
            DropdownMenuPopup(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                // Burying and suspending are two groups: each acts on the card or on its whole note.
                val groups =
                    listOf(
                        listOf(labels.buryCard to onBuryCard, labels.buryNote to onBuryNote),
                        listOf(labels.suspendCard to onSuspendCard, labels.suspendNote to onSuspendNote),
                    )
                groups.forEachIndexed { groupIndex, group ->
                    if (groupIndex > 0) Spacer(Modifier.height(MenuDefaults.GroupSpacing))
                    DropdownMenuGroup(shapes = MenuDefaults.groupShape(groupIndex, groups.size)) {
                        group.forEachIndexed { index, (label, action) ->
                            DropdownMenuItem(
                                onClick = {
                                    moreMenu = false
                                    action()
                                },
                                text = { Text(label) },
                                shape = MenuDefaults.itemShape(index, group.size).shape,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * A flag in its own color, as Anki draws it, faded like other disabled icons when not [enabled];
 * no flag is an outline in the content color.
 */
@Composable
private fun FlagIcon(
    flag: Flag,
    contentDescription: String?,
    enabled: Boolean = true,
) {
    if (flag == Flag.NONE) {
        Icon(painterResource(R.drawable.ic_flag_neutral), contentDescription = contentDescription)
    } else {
        Icon(
            painterResource(flag.drawableRes),
            contentDescription = contentDescription,
            tint = Color.Unspecified,
            modifier = Modifier.alpha(if (enabled) 1f else DISABLED_CONTENT_ALPHA),
        )
    }
}

/**
 * The question side's main action: a full-width button that reveals the answer. While the card's
 * custom scheduling is prepared it waits, keeping its colors, so it does not flash on every card.
 */
@Composable
fun ShowAnswerButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val height = ButtonDefaults.MediumContainerHeight
    Button(
        onClick = onClick,
        shapes = ButtonDefaults.shapesFor(height),
        modifier = modifier.fillMaxWidth().heightIn(min = height),
        enabled = enabled,
        colors =
            ButtonDefaults.buttonColors(
                disabledContainerColor = MaterialTheme.colorScheme.primary,
                disabledContentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        contentPadding = ButtonDefaults.contentPaddingFor(height, hasStartIcon = false, hasEndIcon = false),
    ) {
        Text(stringResource(R.string.show_answer), style = ButtonDefaults.textStyleFor(height))
    }
}

/** One answer rating: its label, when the card would be shown next, and its colors. */
@Immutable
data class AnswerOption(
    val label: String,
    val interval: String,
    val container: Color,
    val onContainer: Color,
)

/** Answering gives haptic feedback, whether by button or by gesture: a distinct one for Again. */
fun answerHaptic(again: Boolean): HapticFeedbackType = if (again) HapticFeedbackType.Reject else HapticFeedbackType.Confirm

/**
 * The four answer ratings as button groups: the pressed button widens and changes shape, and
 * answering gives haptic feedback, a distinct one for Again. Each rating shows its next interval.
 * The ratings share one row when their labels fit at the current font size and window width, and
 * otherwise wrap into rows of two, or of one whose label may wrap, so that no label is cut.
 */
@Composable
fun AnswerButtons(
    intervals: List<String>,
    onAnswer: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val answerColors = LocalAnswerColors.current
    val haptics = LocalHapticFeedback.current

    fun interval(index: Int): String = intervals.getOrNull(index).orEmpty()
    val c = answerColors
    val options =
        listOf(
            AnswerOption(stringResource(R.string.ease_button_again), interval(0), c.againContainer, c.onAgainContainer),
            AnswerOption(stringResource(R.string.ease_button_hard), interval(1), c.hardContainer, c.onHardContainer),
            AnswerOption(stringResource(R.string.ease_button_good), interval(2), c.goodContainer, c.onGoodContainer),
            AnswerOption(stringResource(R.string.ease_button_easy), interval(3), c.easyContainer, c.onEasyContainer),
        )
    val answer: (Int) -> Unit = { index ->
        haptics.performHapticFeedback(answerHaptic(again = index == 0))
        onAnswer(index)
    }
    val labelStyle = MaterialTheme.typography.titleMediumEmphasized
    val intervalStyle = MaterialTheme.typography.labelMedium
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // The widest rating decides: every button in a row has the same width.
    val widestRating =
        remember(options, labelStyle, intervalStyle, measurer, density) {
            val widest =
                options.maxOf { option ->
                    maxOf(
                        measurer.measure(option.label, labelStyle, maxLines = 1).size.width,
                        measurer.measure(option.interval, intervalStyle, maxLines = 1).size.width,
                    )
                }
            with(density) { widest.toDp() } + ANSWER_BUTTON_PADDING * 2
        }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val perRow =
            listOf(options.size, 2, 1).first { count ->
                count == 1 || widestRating * count + ANSWER_BUTTON_SPACING * (count - 1) <= maxWidth
            }
        Column(verticalArrangement = Arrangement.spacedBy(ANSWER_BUTTON_SPACING)) {
            options.withIndex().chunked(perRow).forEach { row ->
                // A rating alone in its row may still be wider than the row: its label wraps.
                AnswerButtonRow(row, enabled, labelMaxLines = if (perRow == 1) Int.MAX_VALUE else 1, onAnswer = answer)
            }
        }
    }
}

/**
 * One row of answer buttons. Every rating has a weight, so the group always fits and never moves a
 * rating into its overflow menu.
 */
@Composable
private fun AnswerButtonRow(
    row: List<IndexedValue<AnswerOption>>,
    enabled: Boolean,
    labelMaxLines: Int,
    onAnswer: (Int) -> Unit,
) {
    ButtonGroup(
        overflowIndicator = { menuState -> ButtonGroupDefaults.OverflowIndicator(menuState) },
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ANSWER_BUTTON_SPACING),
    ) {
        row.forEach { (index, option) ->
            customItem(
                buttonGroupContent = {
                    val interaction = remember { MutableInteractionSource() }
                    AnswerButton(
                        option = option,
                        enabled = enabled,
                        interactionSource = interaction,
                        labelMaxLines = labelMaxLines,
                        modifier = Modifier.weight(1f).animateWidth(interaction),
                        onClick = { onAnswer(index) },
                    )
                },
                menuContent = { menuState ->
                    DropdownMenuItem(
                        text = { Text(option.label) },
                        enabled = enabled,
                        onClick = {
                            menuState.dismiss()
                            onAnswer(index)
                        },
                    )
                },
            )
        }
    }
}

@Composable
private fun AnswerButton(
    option: AnswerOption,
    enabled: Boolean,
    interactionSource: MutableInteractionSource,
    labelMaxLines: Int,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val height = ButtonDefaults.MediumContainerHeight
    val description =
        if (option.interval.isEmpty()) {
            option.label
        } else {
            stringResource(R.string.expressive_study_answer_with_interval, option.label, option.interval)
        }
    Button(
        onClick = onClick,
        shapes = ButtonDefaults.shapesFor(height),
        modifier = modifier.heightIn(min = height).semantics { contentDescription = description },
        enabled = enabled,
        // Disabled only while the previous answer is saved: the ratings keep their colors meanwhile.
        colors =
            ButtonDefaults.buttonColors(
                containerColor = option.container,
                contentColor = option.onContainer,
                disabledContainerColor = option.container,
                disabledContentColor = option.onContainer,
            ),
        contentPadding = PaddingValues(horizontal = ANSWER_BUTTON_PADDING, vertical = 8.dp),
        interactionSource = interactionSource,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (option.interval.isNotEmpty()) {
                // Intervals mix digits, symbols, and units: their own direction keeps "<1m" intact in right-to-left layouts.
                Text(
                    option.interval,
                    style = MaterialTheme.typography.labelMedium.copy(textDirection = TextDirection.Content),
                    maxLines = 1,
                )
            }
            Text(
                option.label,
                style = MaterialTheme.typography.titleMediumEmphasized,
                textAlign = TextAlign.Center,
                maxLines = labelMaxLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
