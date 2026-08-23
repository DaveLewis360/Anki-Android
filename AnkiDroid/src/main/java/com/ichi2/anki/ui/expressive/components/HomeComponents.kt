// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.ui.expressive.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ichi2.anki.R
import com.ichi2.anki.ui.expressive.UiDeck
import com.ichi2.anki.ui.expressive.theme.PillShape
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun GreetingHeader(modifier: Modifier = Modifier) {
    val hour = LocalTime.now().hour
    val greeting =
        when {
            hour < 5 -> "Good night"
            hour < 12 -> "Good morning"
            hour < 18 -> "Good afternoon"
            else -> "Good evening"
        }
    val date = LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, MMM d", Locale.ENGLISH))
    Column(modifier = modifier) {
        Text(greeting, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
        Text(date, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Big tonal card summarizing today's workload. */
@Composable
fun TodaySummaryCard(
    newCount: Int,
    learnCount: Int,
    dueCount: Int,
    modifier: Modifier = Modifier,
    onStartStudying: () -> Unit = {},
) {
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .pressScale(remember { MutableInteractionSource() })
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onStartStudying),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Ready to study",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(Modifier.weight(1f))
                Icon(
                    painterResource(R.drawable.ic_flashcard_black),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                SummaryNumber(newCount, "New", MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimaryContainer)
                DividerDot()
                SummaryNumber(learnCount, "Learning", MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.onPrimaryContainer)
                DividerDot()
                SummaryNumber(dueCount, "Due", MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}

@Composable
private fun SummaryNumber(
    value: Int,
    label: String,
    accent: Color,
    base: Color,
) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            "$value",
            style = MaterialTheme.typography.displayMedium,
            color = accent,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = base.copy(alpha = 0.8f),
            modifier = Modifier.padding(bottom = 10.dp),
        )
    }
}

@Composable
private fun DividerDot() {
    Box(
        Modifier
            .padding(bottom = 28.dp)
            .size(5.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.25f)),
    )
}

/** One deck rendered as an expressive tonal card. */
@Composable
fun DeckCard(
    deck: UiDeck,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(
                    start = 12.dp + (deck.depth * 18).dp,
                    end = 12.dp,
                    top = 5.dp,
                    bottom = 5.dp,
                ).pressScale(interaction)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        shape = MaterialTheme.shapes.large,
        color =
            if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (deck.children.isNotEmpty()) {
                Icon(
                    painterResource(R.drawable.ic_expand_more_black),
                    contentDescription = if (!deck.collapsed) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier =
                        Modifier
                            .size(24.dp)
                            .rotate(
                                animateFloatAsState(
                                    targetValue = if (!deck.collapsed) 0f else -90f,
                                    label = "chevron",
                                ).value,
                            ),
                )
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    deck.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (deck.filtered) FontWeight.Normal else FontWeight.Medium,
                    color =
                        if (deck.filtered) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                )
            }
            Spacer(Modifier.width(8.dp))
            if (deck.newCount > 0) CountChip("${deck.newCount}", MaterialTheme.colorScheme.primary)
            if (deck.learnCount > 0) CountChip("${deck.learnCount}", MaterialTheme.colorScheme.tertiary)
            if (deck.dueCount > 0) CountChip("${deck.dueCount}", MaterialTheme.colorScheme.error)
            if (deck.newCount + deck.learnCount + deck.dueCount == 0) {
                Text(
                    "Done",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

/** Expanding speed-dial FAB: main pill FAB + revealable actions. */
@Composable
fun ExpressiveFab(
    expanded: Boolean,
    onToggle: () -> Unit,
    onAddCard: () -> Unit,
    onAddDeck: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.End) {
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(spring(stiffness = Spring.StiffnessMediumLow)) + expandVertically(spring(stiffness = Spring.StiffnessMediumLow)),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Column(horizontalAlignment = Alignment.End) {
                FabAction(label = "New deck", iconRes = R.drawable.ic_add_deck_filled, onClick = onAddDeck)
                Spacer(Modifier.height(10.dp))
                FabAction(label = "Add card", iconRes = R.drawable.ic_add_note, onClick = onAddCard)
                Spacer(Modifier.height(14.dp))
            }
        }
        Surface(
            shape = PillShape,
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            tonalElevation = 3.dp,
            shadowElevation = 6.dp,
            modifier =
                Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onToggle() },
        ) {
            Row(
                Modifier.padding(start = 20.dp, end = 24.dp, top = 16.dp, bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painterResource(R.drawable.ic_add),
                    contentDescription = "Add",
                    modifier =
                        Modifier.size(26.dp).rotate(
                            animateFloatAsState(
                                targetValue = if (expanded) 135f else 0f,
                                animationSpec = spring(stiffness = Spring.StiffnessMedium, dampingRatio = Spring.DampingRatioMediumBouncy),
                                label = "fabRotate",
                            ).value,
                        ),
                )
                Spacer(Modifier.width(10.dp))
                Text("Add", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun FabAction(
    label: String,
    iconRes: Int,
    onClick: () -> Unit,
) {
    Surface(
        shape = PillShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 4.dp,
        modifier =
            Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
    ) {
        Row(
            Modifier.padding(start = 18.dp, end = 20.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(iconRes), contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
        }
    }
}

/** Data class holder for bottom bar destination. */
data class BottomDestination(
    val label: String,
    val iconRes: Int,
)

val bottomDestinations =
    listOf(
        BottomDestination("Decks", R.drawable.ic_list_black),
        BottomDestination("Browse", R.drawable.ic_flashcard_black),
        BottomDestination("Stats", R.drawable.ic_bar_chart_black),
        BottomDestination("More", R.drawable.ic_menu_24),
    )
