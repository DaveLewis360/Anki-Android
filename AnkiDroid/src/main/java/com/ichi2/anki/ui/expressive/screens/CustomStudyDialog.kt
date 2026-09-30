// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.ui.expressive.screens

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.byValue
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldLabelPosition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import anki.scheduler.CustomStudyRequest.Cram.CramKind
import com.ichi2.anki.CollectionManager.TR
import com.ichi2.anki.R
import com.ichi2.anki.libanki.DeckId
import com.ichi2.anki.ui.expressive.ExpressiveCustomStudyAction
import com.ichi2.anki.ui.expressive.ExpressiveCustomStudyDefaults
import com.ichi2.anki.ui.expressive.ExpressiveDeckGateway
import com.ichi2.anki.ui.expressive.StudyMessage
import com.ichi2.anki.ui.internationalization.SentenceCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** The ways to study outside the regular schedule, as Anki's custom study offers them. */
internal enum class CustomStudyMode {
    EXTEND_NEW,
    EXTEND_REVIEW,
    FORGOTTEN,
    AHEAD,
    PREVIEW,
    CRAM,
    ;

    /** The mode's name in the list of modes. */
    val label: String
        get() =
            when (this) {
                EXTEND_NEW -> TR.customStudyIncreaseTodaysNewCardLimit()
                EXTEND_REVIEW -> TR.customStudyIncreaseTodaysReviewCardLimit()
                FORGOTTEN -> TR.customStudyReviewForgottenCards()
                AHEAD -> TR.customStudyReviewAhead()
                PREVIEW -> TR.customStudyPreviewNewCards()
                CRAM -> TR.customStudyStudyByCardStateOrTag()
            }

    /**
     * What the amount means, the label above its field: "Review ahead by". A limit's prompt is
     * Classic's, which tells that a negative amount lowers it.
     */
    fun amountPrompt(context: Context): String =
        when (this) {
            EXTEND_NEW -> context.getString(R.string.custom_study_new_extend)
            EXTEND_REVIEW -> context.getString(R.string.custom_study_rev_extend)
            FORGOTTEN -> TR.customStudyReviewCardsForgottenInLast()
            AHEAD -> TR.customStudyReviewAheadBy()
            PREVIEW -> TR.customStudyPreviewNewCardsAddedInThe()
            CRAM -> TR.customStudySelect()
        }

    /** The amount's unit, after its field: cards, days, or cards from the deck. */
    fun amountUnit(amount: Int): String =
        when (this) {
            EXTEND_NEW, EXTEND_REVIEW -> TR.customStudyCards(amount)
            FORGOTTEN, AHEAD, PREVIEW -> TR.customStudyDays(amount)
            CRAM -> TR.customStudyCardsFromTheDeck(amount)
        }

    /** A limit may also be lowered: its amount may be negative. */
    val allowsNegative: Boolean
        get() = this == EXTEND_NEW || this == EXTEND_REVIEW
}

private val cramKinds =
    listOf(CramKind.CRAM_KIND_NEW, CramKind.CRAM_KIND_DUE, CramKind.CRAM_KIND_REVIEW, CramKind.CRAM_KIND_ALL)

private fun CramKind.label(): String =
    when (this) {
        CramKind.CRAM_KIND_NEW -> TR.customStudyNewCardsOnly()
        CramKind.CRAM_KIND_DUE -> TR.customStudyDueCardsOnly()
        CramKind.CRAM_KIND_REVIEW -> TR.customStudyAllReviewCardsInRandomOrder()
        else -> TR.customStudyAllCardsInRandomOrderDont()
    }

/** "Available new cards: 5 (2 in subdecks)", as Classic's custom study tells it; null for other modes. */
private fun ExpressiveCustomStudyDefaults.availability(mode: CustomStudyMode): String? {
    fun count(
        available: Int,
        inChildren: Int,
    ) = if (inChildren == 0) available.toString() else "$available ${TR.customStudyAvailableChildCount(inChildren)}"
    return when (mode) {
        CustomStudyMode.EXTEND_NEW -> TR.customStudyAvailableNewCards2(count(availableNew, availableNewInChildren))
        CustomStudyMode.EXTEND_REVIEW -> TR.customStudyAvailableReviewCards2(count(availableReview, availableReviewInChildren))
        else -> null
    }
}

/** Anki's field prompts end with a colon: as a field's label, the label itself says it. */
internal fun String.asFieldLabel(): String = trimEnd(':', '：', ' ')

/**
 * Custom study for [deckId]: extend today's limits, or study forgotten, upcoming, or new cards,
 * or cards by state and tag, in a filtered deck whose id [onDone] receives.
 */
@Composable
internal fun CustomStudyDialog(
    deckId: DeckId,
    deckName: String,
    deckGateway: ExpressiveDeckGateway,
    onDismiss: () -> Unit,
    onDone: (sessionDeckId: DeckId?) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var defaults by remember { mutableStateOf<ExpressiveCustomStudyDefaults?>(null) }
    var modeName by rememberSaveable(deckId) { mutableStateOf(CustomStudyMode.EXTEND_NEW.name) }
    val mode = CustomStudyMode.valueOf(modeName)
    val amount = rememberSaveable(deckId, saver = TextFieldState.Saver) { TextFieldState() }
    var includeTags by rememberSaveable(deckId) { mutableStateOf("") }
    var excludeTags by rememberSaveable(deckId) { mutableStateOf("") }
    var cramKindName by rememberSaveable(deckId) { mutableStateOf(CramKind.CRAM_KIND_NEW.name) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(deckId) {
        try {
            defaults = deckGateway.customStudyDefaults(deckId)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            error = StudyMessage.of(failure, R.string.expressive_custom_study_defaults_failed).resolve(context)
        }
    }
    LaunchedEffect(mode, defaults) {
        amount.setTextAndPlaceCursorAtEnd(
            when (mode) {
                CustomStudyMode.EXTEND_NEW -> defaults?.extendNew?.toString().orEmpty()
                CustomStudyMode.EXTEND_REVIEW -> defaults?.extendReview?.toString().orEmpty()
                CustomStudyMode.FORGOTTEN,
                CustomStudyMode.AHEAD,
                CustomStudyMode.PREVIEW,
                -> "1"
                CustomStudyMode.CRAM -> "100"
            },
        )
    }

    val parsedAmount = amount.text.toString().toIntOrNull()
    val canSubmit = defaults != null && parsedAmount != null && parsedAmount != 0 && !busy
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(with(context) { SentenceCase.customStudy }) },
        text = {
            // A handful of rows: all of them are composed, so that TalkBack reaches each one.
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(deckName, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (defaults == null && error == null) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) { LoadingIndicator() }
                }
                error?.let { message -> Text(message, color = MaterialTheme.colorScheme.error) }
                defaults?.let { loaded ->
                    Column(Modifier.selectableGroup()) {
                        CustomStudyMode.entries.forEach { candidate ->
                            ChoiceRow(candidate.label, selected = mode == candidate) { modeName = candidate.name }
                        }
                    }
                    loaded.availability(mode)?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    // The prompt can be long: it is the field's label, above it rather than on its outline.
                    OutlinedTextField(
                        state = amount,
                        labelPosition = TextFieldLabelPosition.Above(),
                        label = { Text(mode.amountPrompt(context)) },
                        suffix = { Text(mode.amountUnit(parsedAmount ?: 0)) },
                        inputTransformation =
                            InputTransformation.byValue { _, proposed ->
                                proposed.filterIndexed { index, character ->
                                    character.isDigit() || (character == '-' && index == 0 && mode.allowsNegative)
                                }
                            },
                        lineLimits = TextFieldLineLimits.SingleLine,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (mode == CustomStudyMode.CRAM) {
                        Column(Modifier.selectableGroup()) {
                            cramKinds.forEach { kind ->
                                ChoiceRow(kind.label(), selected = cramKindName == kind.name) { cramKindName = kind.name }
                            }
                        }
                        OutlinedTextField(
                            value = includeTags,
                            onValueChange = { includeTags = it },
                            label = { Text(TR.customStudyRequireOneOrMoreOfThese().asFieldLabel()) },
                            supportingText =
                                loaded.tags.takeIf { it.isNotEmpty() }?.let { tags ->
                                    { Text(stringResource(R.string.expressive_custom_study_tags_hint, tags.take(8).joinToString())) }
                                },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = excludeTags,
                            onValueChange = { excludeTags = it },
                            label = { Text(TR.customStudySelectTagsToExclude().asFieldLabel()) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSubmit,
                shapes = ButtonDefaults.shapes(),
                onClick = {
                    val count = parsedAmount ?: return@TextButton
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            val action =
                                when (mode) {
                                    CustomStudyMode.EXTEND_NEW -> ExpressiveCustomStudyAction.ExtendNew(count)
                                    CustomStudyMode.EXTEND_REVIEW -> ExpressiveCustomStudyAction.ExtendReview(count)
                                    CustomStudyMode.FORGOTTEN -> ExpressiveCustomStudyAction.Forgotten(count)
                                    CustomStudyMode.AHEAD -> ExpressiveCustomStudyAction.ReviewAhead(count)
                                    CustomStudyMode.PREVIEW -> ExpressiveCustomStudyAction.PreviewNew(count)
                                    CustomStudyMode.CRAM ->
                                        ExpressiveCustomStudyAction.Cram(
                                            amount = count,
                                            kind = CramKind.valueOf(cramKindName),
                                            includeTags = includeTags.asTagList(),
                                            excludeTags = excludeTags.asTagList(),
                                        )
                                }
                            val result = deckGateway.customStudy(deckId, action)
                            onDone(result.sessionDeckId)
                        } catch (failure: CancellationException) {
                            throw failure
                        } catch (failure: Exception) {
                            error = StudyMessage.of(failure, R.string.expressive_custom_study_failed).resolve(context)
                        } finally {
                            busy = false
                        }
                    }
                },
            ) {
                // While the session is created, the button says so.
                if (busy) {
                    LoadingIndicator(Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                }
                Text(stringResource(R.string.dialog_ok))
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, shapes = ButtonDefaults.shapes(), onClick = onDismiss) {
                Text(stringResource(R.string.dialog_cancel))
            }
        },
    )
}

/** One choice of a radio group: the whole row, its label too, selects it. */
@Composable
private fun ChoiceRow(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(MaterialTheme.shapes.medium)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 12.dp))
    }
}

private fun String.asTagList(): List<String> =
    split(',')
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
        .take(100)
