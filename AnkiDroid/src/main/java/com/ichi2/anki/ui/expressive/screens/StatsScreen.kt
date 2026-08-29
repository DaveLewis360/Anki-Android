// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.ui.expressive.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ichi2.anki.R
import com.ichi2.anki.ui.expressive.ExpressiveCollection
import com.ichi2.anki.ui.expressive.ExpressiveStats
import com.ichi2.anki.ui.expressive.ExpressiveStatsGateway
import com.ichi2.anki.ui.expressive.theme.PillShape

private val dayLabels = listOf("M", "T", "W", "T", "F", "S", "S")

/** Stats: real collection statistics with an expressive dashboard layout. */
@Composable
fun StatsScreen(statsGateway: ExpressiveStatsGateway = ExpressiveCollection) {
    var stats by remember { mutableStateOf<ExpressiveStats?>(null) }
    var failed by remember { mutableStateOf(false) }
    var animateIn by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        stats =
            try {
                statsGateway.loadStats()
            } catch (e: Exception) {
                failed = true
                null
            }
        animateIn = true
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = 20.dp, bottom = 120.dp),
    ) {
        Text(
            "Statistics",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Spacer(Modifier.height(18.dp))

        val s = stats
        when {
            s == null && failed ->
                Text(
                    "Couldn't load statistics.",
                    Modifier.fillMaxWidth().padding(32.dp),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            s == null ->
                Box(Modifier.fillMaxWidth().padding(top = 60.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            else -> {
                HeroRow(s, animateIn)
                Spacer(Modifier.height(12.dp))
                WeekChartCard(s, animateIn)
                Spacer(Modifier.height(12.dp))
                CardMixCard(s, animateIn)
                Spacer(Modifier.height(12.dp))
                AllTimeCard(s)
            }
        }
    }
}

@Composable
private fun HeroRow(
    s: ExpressiveStats,
    animateIn: Boolean,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        HeroTile(
            label = "Today",
            value = "${s.reviewsToday}",
            suffix = "reviews",
            iconRes = R.drawable.ic_bar_chart_black,
            container = MaterialTheme.colorScheme.primaryContainer,
            content = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.weight(1.2f),
        )
        HeroTile(
            label = "Retention",
            value = "${s.retentionToday}%",
            suffix = "correct",
            iconRes = R.drawable.ic_cards_star,
            container = MaterialTheme.colorScheme.secondaryContainer,
            content = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.weight(1f),
        )
        HeroTile(
            label = "Streak",
            value = "${s.streak}",
            suffix = "days",
            iconRes = R.drawable.ic_round_favorite_24,
            container = MaterialTheme.colorScheme.tertiaryContainer,
            content = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.weight(1f),
        )
    }
    Spacer(Modifier.height(10.dp))
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        MiniTile("Time today", "${s.minutesToday} min", Modifier.weight(1f))
        MiniTile("Due today", "${s.dueToday}", Modifier.weight(1f))
        MiniTile("New left", "${s.newLeftToday}", Modifier.weight(1f))
    }
}

@Composable
private fun HeroTile(
    label: String,
    value: String,
    suffix: String,
    iconRes: Int,
    container: Color,
    content: Color,
    modifier: Modifier = Modifier,
) {
    Surface(shape = MaterialTheme.shapes.extraLarge, color = container, modifier = modifier) {
        Column(Modifier.padding(16.dp)) {
            Icon(painterResource(iconRes), contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    value,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = content,
                )
                Spacer(Modifier.width(4.dp))
                Text(suffix, style = MaterialTheme.typography.labelSmall, color = content.copy(alpha = 0.7f))
            }
            Text(label, style = MaterialTheme.typography.labelMedium, color = content.copy(alpha = 0.7f))
        }
    }
}

@Composable
private fun MiniTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier,
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WeekChartCard(
    s: ExpressiveStats,
    animateIn: Boolean,
) {
    val todayIndex = 6
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "This week",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${s.week.sum()} reviews",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(18.dp))
            Row(
                Modifier.fillMaxWidth().height(120.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                s.week.forEachIndexed { index, count ->
                    val target = if (animateIn) count.toFloat() / s.weekMax else 0f
                    val fraction by animateFloatAsState(
                        targetValue = target.coerceIn(0.03f, 1f),
                        animationSpec = tween(durationMillis = 550, delayMillis = index * 60, easing = FastOutSlowInEasing),
                        label = "bar$index",
                    )
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (count > 0) {
                            Text(
                                "$count",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height((fraction * 88).dp)
                                .background(
                                    if (index ==
                                        todayIndex
                                    ) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.primaryContainer
                                    },
                                    MaterialTheme.shapes.small,
                                ),
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                dayLabels.forEachIndexed { index, label ->
                    Text(
                        label,
                        Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (index == todayIndex) FontWeight.Bold else FontWeight.Normal,
                        color =
                            if (index == todayIndex) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                    )
                }
            }
        }
    }
}

@Composable
private fun CardMixCard(
    s: ExpressiveStats,
    animateIn: Boolean,
) {
    val total = (s.mixNew + s.mixLearn + s.mixReview + s.mixSuspended).coerceAtLeast(1)
    val newFraction by animateFloatAsState(
        targetValue = if (animateIn) s.mixNew.toFloat() / total else 0f,
        animationSpec = tween(600),
        label = "mixNew",
    )
    val learnFraction by animateFloatAsState(
        targetValue = if (animateIn) s.mixLearn.toFloat() / total else 0f,
        animationSpec = tween(600, delayMillis = 80),
        label = "mixLearn",
    )
    val reviewFraction by animateFloatAsState(
        targetValue = if (animateIn) s.mixReview.toFloat() / total else 0f,
        animationSpec = tween(600, delayMillis = 160),
        label = "mixReview",
    )
    val suspendedFraction by animateFloatAsState(
        targetValue = if (animateIn) s.mixSuspended.toFloat() / total else 0f,
        animationSpec = tween(600, delayMillis = 240),
        label = "mixSusp",
    )

    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text("Card mix", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.fillMaxWidth().height(16.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                MixSegment(newFraction, MaterialTheme.colorScheme.tertiary, Modifier.weight(newFraction.coerceAtLeast(0.001f)))
                MixSegment(learnFraction, Color(0xFFFDBA74), Modifier.weight(learnFraction.coerceAtLeast(0.001f)))
                MixSegment(reviewFraction, MaterialTheme.colorScheme.primary, Modifier.weight(reviewFraction.coerceAtLeast(0.001f)))
                MixSegment(
                    suspendedFraction,
                    MaterialTheme.colorScheme.outlineVariant,
                    Modifier.weight(suspendedFraction.coerceAtLeast(0.001f)),
                )
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                MixLegend(Color(0xFF7DD3FC), "New", s.mixNew)
                MixLegend(Color(0xFFFDBA74), "Learning", s.mixLearn)
                MixLegend(MaterialTheme.colorScheme.primary, "Review", s.mixReview)
                MixLegend(MaterialTheme.colorScheme.outlineVariant, "Suspended", s.mixSuspended)
            }
        }
    }
}

@Composable
private fun MixSegment(
    fraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
) {
    if (fraction > 0.001f) {
        Box(modifier.height(16.dp).background(color, PillShape))
    } else {
        Spacer(modifier)
    }
}

@Composable
private fun MixLegend(
    color: Color,
    label: String,
    count: Int,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(4.dp))
        Text(
            "$count $label",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AllTimeCard(s: ExpressiveStats) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text("All time", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                AllTimeItem("${s.totalCards}", "cards")
                AllTimeItem("${s.totalNotes}", "notes")
                AllTimeItem("${s.deckCount}", "decks")
                AllTimeItem("%,d".format(s.totalReviews), "reviews")
            }
        }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun AllTimeItem(
    value: String,
    label: String,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
