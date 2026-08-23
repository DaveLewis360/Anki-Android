// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.ui.expressive.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ichi2.anki.ui.expressive.theme.PillShape

/**
 * Expressive bottom navigation bar: each destination animates between a wide
 * pill (selected: icon + label) and a circular dot (unselected: icon only).
 * Label width, pill color and icon color all animate — no snapping.
 */
@Composable
fun ExpressiveBottomBar(
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Row(
            Modifier
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            bottomDestinations.forEachIndexed { index, destination ->
                val selected = index == selectedIndex
                val scale by
                    animateFloatAsState(
                        targetValue = if (selected) 1f else 0.92f,
                        animationSpec = spring(stiffness = Spring.StiffnessMedium, dampingRatio = Spring.DampingRatioMediumBouncy),
                        label = "navScale$index",
                    )
                val pillColor by
                    animateColorAsState(
                        targetValue = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                        label = "navPillColor$index",
                    )
                val contentColor by
                    animateColorAsState(
                        targetValue =
                            if (selected) {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                        label = "navContentColor$index",
                    )
                Surface(
                    shape = PillShape,
                    color = pillColor,
                    contentColor = contentColor,
                    modifier =
                        Modifier
                            .height(44.dp)
                            .scale(scale)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { onSelect(index) },
                ) {
                    Row(
                        Modifier
                            .clipToBounds()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            painter = painterResource(destination.iconRes),
                            contentDescription = destination.label,
                            modifier = Modifier.size(24.dp),
                        )
                        AnimatedVisibility(
                            visible = selected,
                            enter =
                                fadeIn(spring(stiffness = Spring.StiffnessMediumLow)) +
                                    expandHorizontally(
                                        spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioLowBouncy),
                                    ),
                            exit =
                                fadeOut(tween(durationMillis = 180)) +
                                    shrinkHorizontally(
                                        spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioNoBouncy),
                                    ),
                        ) {
                            Text(
                                destination.label,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
