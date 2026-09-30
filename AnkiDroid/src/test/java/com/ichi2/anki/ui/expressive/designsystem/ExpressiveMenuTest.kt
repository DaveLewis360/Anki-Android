// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.ui.expressive.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.ui.expressive.theme.ExpressiveAnkiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals

/**
 * The Expressive menu closes before it acts, and keeps every group within reach in a short window.
 * Native graphics measure text as a device does: labels wrap where they would.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExpressiveMenuTest : RobolectricTest() {
    @get:Rule
    val composeRule = createComposeRule()

    private val events = mutableListOf<String>()
    private var expanded by mutableStateOf(true)

    @Test
    fun `choosing an item closes the menu, then acts`() {
        show(groups(2, 2))

        composeRule.onNodeWithText("B2").performClick()

        assertEquals(listOf("dismissed", "chose B2"), events)
        composeRule.onNodeWithText("B2").assertDoesNotExist()
    }

    /** A phone in landscape at twice the font size: the first group alone is taller than the window. */
    @Test
    @Config(qualifiers = "w914dp-h411dp", fontScale = 2f)
    fun `in a short window every group keeps its first item in view and scrolls to its last`() {
        show(groups(10, 6))

        listOf("A1", "B1").forEach { first -> composeRule.onNodeWithText(first).assertIsDisplayed() }
        listOf("A10", "B6").forEach { last ->
            composeRule
                .onNodeWithText(last)
                .assertIsNotDisplayed()
                .performScrollTo()
                .assertIsDisplayed()
        }
        composeRule.onNodeWithText("B6").performClick()

        assertEquals(listOf("dismissed", "chose B6"), events)
    }

    @Test
    @Config(qualifiers = "w914dp-h411dp", fontScale = 2f)
    fun `a menu opens at its top, however far it was scrolled before`() {
        show(groups(10))
        composeRule.onNodeWithText("A10").performScrollTo().assertIsDisplayed()

        expanded = false
        composeRule.waitForIdle()
        expanded = true

        composeRule.onNodeWithText("A1").assertIsDisplayed()
        composeRule.onNodeWithText("A10").assertIsNotDisplayed()
    }

    /**
     * An item with an icon tells an intrinsic height as if its label had no width, many times its
     * real height: were the groups sized by it, the group below would scroll with the window half empty.
     */
    @Test
    fun `a group with an icon leaves the groups below it all of their items`() {
        val icon: @Composable () -> Unit = { Box(Modifier.size(24.dp)) }
        show(
            listOf(
                listOf(ExpressiveMenuItem("Flag the card in the color of your choice", onClick = {}, leadingIcon = icon)),
                (1..4).map { position -> ExpressiveMenuItem("B$position", onClick = {}) },
            ),
        )

        (1..4).forEach { position -> composeRule.onNodeWithText("B$position").assertIsDisplayed() }
    }

    /** A group of actions for each of the [sizes], named by group and position: "A1", "B3". */
    private fun groups(vararg sizes: Int): List<List<ExpressiveMenuItem>> =
        sizes.mapIndexed { group, size ->
            (1..size).map { position ->
                val name = "${'A' + group}$position"
                ExpressiveMenuItem(name, onClick = { events += "chose $name" })
            }
        }

    private fun show(groups: List<List<ExpressiveMenuItem>>) {
        composeRule.setContent {
            ExpressiveAnkiTheme(themeMode = "light", dynamicColor = false) {
                ExpressiveMenu(
                    expanded = expanded,
                    onDismissRequest = {
                        events += "dismissed"
                        expanded = false
                    },
                    groups = groups,
                )
            }
        }
    }
}
