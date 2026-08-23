// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.ui.expressive.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ichi2.anki.ui.expressive.theme.LocalAnswerColors
import com.ichi2.anki.ui.expressive.theme.PillShape

/** Rounded progress + timer chip shown at the top of the study screen. */
@Composable
fun StudyTopBar(
    progress: Float,
    timerSeconds: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .background(
                    if (timerSeconds > 60) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                    PillShape,
                ).padding(horizontal = 14.dp, vertical = 6.dp),
        ) {
            Text(
                text = formatSeconds(timerSeconds),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (timerSeconds > 60) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.width(14.dp))
        WavyProgress(
            progress = progress,
            modifier = Modifier.weight(1f).height(14.dp),
        )
    }
}

/**
 * Progress bar backed by the official Material 3 Expressive
 * [LinearWavyProgressIndicator]. The wave animates continuously and the fill
 * glides forward with a spring when progress changes (e.g. next card).
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun WavyProgress(
    progress: Float,
    modifier: Modifier = Modifier,
) {
    val glide by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = spring(stiffness = 90f, dampingRatio = Spring.DampingRatioLowBouncy),
        label = "waveGlide",
    )
    androidx.compose.material3.LinearWavyProgressIndicator(
        progress = { glide },
        modifier = modifier,
        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        amplitude = { 1.0f },
    )
}

private fun formatSeconds(total: Int): String {
    val m = total / 60
    val s = total % 60
    return "%d:%02d".format(m, s)
}

/** Big full-width pill that reveals the answer. */
@Composable
fun ShowAnswerButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    Surface(
        modifier =
            modifier
                .alpha(if (enabled) 1f else 0.6f)
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .pressScale(interaction)
                .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick),
        shape = PillShape,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shadowElevation = 4.dp,
    ) {
        Box(Modifier.padding(vertical = 18.dp), contentAlignment = Alignment.Center) {
            Text(
                "SHOW ANSWER",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
        }
    }
}

data class AnswerOption(
    val label: String,
    val interval: String,
    val container: Color,
    val onContainer: Color,
)

/** The four expressive answer buttons with real next-interval captions. */
@Composable
fun AnswerButtons(
    intervals: List<String>,
    onAnswer: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val answerColors = LocalAnswerColors.current

    fun interval(index: Int): String = intervals.getOrNull(index) ?: ""
    val options =
        listOf(
            AnswerOption("Again", interval(0), answerColors.againContainer, answerColors.onAgainContainer),
            AnswerOption("Hard", interval(1), answerColors.hardContainer, answerColors.onHardContainer),
            AnswerOption("Good", interval(2), answerColors.goodContainer, answerColors.onGoodContainer),
            AnswerOption("Easy", interval(3), answerColors.easyContainer, answerColors.onEasyContainer),
        )
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        options.forEachIndexed { index, option ->
            AnswerButton(option, onClick = { onAnswer(index) }, enabled = enabled, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun AnswerButton(
    option: AnswerOption,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    Surface(
        modifier =
            modifier
                .alpha(if (enabled) 1f else 0.6f)
                .pressScale(interaction)
                .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = option.container,
        contentColor = option.onContainer,
        shadowElevation = 2.dp,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(option.interval, style = MaterialTheme.typography.labelMedium, color = option.onContainer.copy(alpha = 0.75f))
            Spacer(Modifier.height(2.dp))
            Text(option.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}
