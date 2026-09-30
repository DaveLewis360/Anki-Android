// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.ui.expressive.designsystem

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign

/**
 * A medium filled button, Material 3 Expressive's size for a screen's main action: taller than a
 * default button, with a larger label, and a shape that morphs while pressed. Text in [content]
 * takes the medium label style unless it sets its own. The button grows with its content, as at
 * large font sizes.
 */
@Composable
fun MediumButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    contentPadding: PaddingValues =
        ButtonDefaults.contentPaddingFor(ButtonDefaults.MediumContainerHeight, hasStartIcon = false, hasEndIcon = false),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val height = ButtonDefaults.MediumContainerHeight
    Button(
        onClick = onClick,
        shapes = ButtonDefaults.shapesFor(height),
        modifier = modifier.heightIn(min = height),
        enabled = enabled,
        colors = colors,
        contentPadding = contentPadding,
        interactionSource = interactionSource,
    ) {
        // Button gives its content the default size's label style: the medium size has a larger one.
        ProvideTextStyle(ButtonDefaults.textStyleFor(height)) { content() }
    }
}

/** A [MediumButton] that reads [label], centered should it wrap. */
@Composable
fun MediumButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
) {
    MediumButton(onClick = onClick, modifier = modifier, enabled = enabled, colors = colors) {
        Text(label, textAlign = TextAlign.Center)
    }
}
