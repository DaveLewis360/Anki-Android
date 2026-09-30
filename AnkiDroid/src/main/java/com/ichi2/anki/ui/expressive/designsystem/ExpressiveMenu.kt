// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.ui.expressive.designsystem

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp

/** A menu item's least height: in a short window, every group keeps at least this much in view. */
private val MENU_GROUP_MIN_HEIGHT = 48.dp

/**
 * An item of an [ExpressiveMenu]: an action or, when [selected] is given, one option of a choice,
 * shown as selected or not.
 */
@Immutable
class ExpressiveMenuItem(
    val label: String,
    val onClick: () -> Unit,
    val selected: Boolean? = null,
    val leadingIcon: (@Composable () -> Unit)? = null,
)

/**
 * A Material 3 Expressive menu: each of its [groups] is a container of its own, whose first and
 * last items follow its corners. Choosing an item closes the menu, then acts.
 *
 * In a window too short for the menu, as at large font sizes, the groups take the height they need
 * in turn, each leaving the groups below it room for their first item, and scroll within their
 * containers. Every opening starts at the top.
 */
@Composable
fun ExpressiveMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    groups: List<List<ExpressiveMenuItem>>,
    modifier: Modifier = Modifier,
) {
    DropdownMenuPopup(expanded = expanded, onDismissRequest = onDismissRequest, modifier = modifier) {
        Layout(
            content = {
                groups.forEachIndexed { groupIndex, group ->
                    // Inside the popup, whose content is dropped when it closes: the next opening starts at the top.
                    val scroll = rememberScrollState()
                    DropdownMenuGroup(
                        shapes = MenuDefaults.groupShape(groupIndex, groups.size),
                        // The group scrolls inside its container, which keeps its shape and shadow.
                        modifier = Modifier.verticalScroll(scroll),
                    ) {
                        group.forEachIndexed { index, item ->
                            MenuItem(
                                item = item,
                                index = index,
                                count = group.size,
                                onChoose = {
                                    onDismissRequest()
                                    item.onClick()
                                },
                            )
                        }
                    }
                }
            },
            measurePolicy = MenuGroupsMeasurePolicy,
        )
    }
}

@Composable
private fun MenuItem(
    item: ExpressiveMenuItem,
    index: Int,
    count: Int,
    onChoose: () -> Unit,
) {
    val shapes = MenuDefaults.itemShape(index, count)
    val selected = item.selected
    if (selected == null) {
        DropdownMenuItem(
            onClick = onChoose,
            text = { Text(item.label) },
            shape = shapes.shape,
            leadingIcon = item.leadingIcon,
        )
    } else {
        DropdownMenuItem(
            selected = selected,
            onClick = onChoose,
            text = { Text(item.label) },
            shapes = shapes,
            leadingIcon = item.leadingIcon,
        )
    }
}

/**
 * The groups one under another, [MenuDefaults.GroupSpacing] apart. Each group is as tall as its
 * items while the window has room. In a shorter window each group in turn takes the height it
 * needs, but leaves each group below it [MENU_GROUP_MIN_HEIGHT], enough for its first item, and
 * scrolls within what it has.
 *
 * The groups are measured, not asked their intrinsic heights: a menu item with an icon tells one
 * as if its label had no width, many times its real height.
 */
private object MenuGroupsMeasurePolicy : MeasurePolicy {
    override fun MeasureScope.measure(
        measurables: List<Measurable>,
        constraints: Constraints,
    ): MeasureResult {
        val gap = MenuDefaults.GroupSpacing.roundToPx()
        val reserve = MENU_GROUP_MIN_HEIGHT.roundToPx()
        var left = (constraints.maxHeight - gaps(measurables.size)).coerceAtLeast(0)
        val placeables =
            measurables.mapIndexed { index, measurable ->
                val below = measurables.lastIndex - index
                val height =
                    when {
                        !constraints.hasBoundedHeight -> Constraints.Infinity
                        // In a window too short even for the groups' first items, each gets an equal part of what is left.
                        else -> left - minOf(reserve * below, left * below / (below + 1))
                    }
                measurable.measure(Constraints(maxWidth = constraints.maxWidth, maxHeight = height)).also { left -= it.height }
            }
        val width = constraints.constrainWidth(placeables.maxOfOrNull { it.width } ?: 0)
        val height = constraints.constrainHeight(placeables.sumOf { it.height } + gaps(placeables.size))
        return layout(width, height) {
            var y = 0
            placeables.forEach { placeable ->
                placeable.placeRelative(0, y)
                y += placeable.height + gap
            }
        }
    }

    override fun IntrinsicMeasureScope.minIntrinsicWidth(
        measurables: List<IntrinsicMeasurable>,
        height: Int,
    ): Int = measurables.maxOfOrNull { it.minIntrinsicWidth(Constraints.Infinity) } ?: 0

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(
        measurables: List<IntrinsicMeasurable>,
        height: Int,
    ): Int = measurables.maxOfOrNull { it.maxIntrinsicWidth(Constraints.Infinity) } ?: 0

    override fun IntrinsicMeasureScope.minIntrinsicHeight(
        measurables: List<IntrinsicMeasurable>,
        width: Int,
    ): Int = measurables.sumOf { it.minIntrinsicHeight(width) } + gaps(measurables.size)

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(
        measurables: List<IntrinsicMeasurable>,
        width: Int,
    ): Int = measurables.sumOf { it.maxIntrinsicHeight(width) } + gaps(measurables.size)

    private fun Density.gaps(groups: Int): Int = MenuDefaults.GroupSpacing.roundToPx() * (groups - 1).coerceAtLeast(0)
}
