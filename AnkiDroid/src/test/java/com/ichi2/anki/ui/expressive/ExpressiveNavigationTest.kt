// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.ui.expressive

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExpressiveNavigationTest {
    @Test
    fun `selected tab survives SavedStateHandle recreation`() {
        val handle = SavedStateHandle()
        val first = ExpressiveNavigationViewModel(handle)
        first.selectRoot(ExpressiveDestination.Browse)

        val restored = ExpressiveNavigationViewModel(handle, initialDestination = ExpressiveDestination.Home)

        assertEquals(ExpressiveDestination.Browse, restored.backStack.value.current)
        assertEquals(listOf(ExpressiveDestination.Browse), restored.backStack.value.entries)
    }

    @Test
    fun `Study typed arguments survive snapshot round trip`() {
        val handle = SavedStateHandle()
        val first = ExpressiveNavigationViewModel(handle)
        first.openStudy(deckId = Long.MAX_VALUE, deckName = "日本語 🧠 :: ")

        val restored = ExpressiveNavigationViewModel(handle)
        val study = assertIs<ExpressiveDestination.Study>(restored.backStack.value.current)

        assertEquals(Long.MAX_VALUE, study.deckId)
        assertEquals("日本語 🧠 :: ", study.deckName)
    }

    @Test
    fun `version one Study snapshot encoding matches golden wire schema`() {
        val handle = SavedStateHandle()
        ExpressiveNavigationViewModel(handle).openStudy(deckId = 42L, deckName = "Deck")

        assertEquals(
            STUDY_V1_GOLDEN,
            handle.get<String>(ExpressiveNavigationViewModel.SAVED_STATE_KEY),
        )
    }

    @Test
    fun `version one Study golden snapshot restores typed stack`() {
        val handle =
            SavedStateHandle(
                mapOf(ExpressiveNavigationViewModel.SAVED_STATE_KEY to STUDY_V1_GOLDEN),
            )

        assertEquals(
            listOf(
                ExpressiveDestination.Home,
                ExpressiveDestination.Study(deckId = 42L, deckName = "Deck"),
            ),
            ExpressiveNavigationViewModel(handle).backStack.value.entries,
        )
    }

    @Test
    fun `Settings Account and Study pop before root exit`() {
        val navigation = ExpressiveNavigationViewModel(SavedStateHandle())

        navigation.openSettings()
        assertTrue(navigation.pop())
        assertEquals(ExpressiveDestination.Home, navigation.backStack.value.current)
        assertFalse(navigation.pop())

        navigation.selectRoot(ExpressiveDestination.More)
        navigation.openAccount()
        assertTrue(navigation.pop())
        assertEquals(ExpressiveDestination.More, navigation.backStack.value.current)
        assertFalse(navigation.pop())

        navigation.selectRoot(ExpressiveDestination.Home)
        navigation.openStudy(deckId = 42L, deckName = "Deck")
        assertTrue(navigation.pop())
        assertEquals(ExpressiveDestination.Home, navigation.backStack.value.current)
        assertFalse(navigation.pop())
    }

    @Test
    fun `Account destination survives SavedStateHandle recreation without credentials`() {
        val handle = SavedStateHandle()
        ExpressiveNavigationViewModel(handle).apply {
            selectRoot(ExpressiveDestination.More)
            openAccount()
        }

        val restored = ExpressiveNavigationViewModel(handle)

        assertEquals(
            listOf(ExpressiveDestination.More, ExpressiveDestination.Account),
            restored.backStack.value.entries,
        )
    }

    @Test
    fun `tab selection replaces root and does not grow stack`() {
        val navigation = ExpressiveNavigationViewModel(SavedStateHandle())
        navigation.openSettings()

        navigation.selectRoot(ExpressiveDestination.Stats)
        navigation.selectRoot(ExpressiveDestination.More)

        assertEquals(listOf(ExpressiveDestination.More), navigation.backStack.value.entries)
    }

    @Test
    fun `detailed statistics launch is emitted only for a fresh Stats root selection`() =
        runTest {
            val handle = SavedStateHandle()
            val navigation = ExpressiveNavigationViewModel(handle)

            navigation.selectRoot(ExpressiveDestination.Stats)
            assertEquals(ExpressiveNavigationEffect.OpenDetailedStatistics, navigation.effects.first())

            navigation.selectRoot(ExpressiveDestination.Stats)
            assertNull(withTimeoutOrNull(1) { navigation.effects.first() })

            navigation.selectRoot(ExpressiveDestination.Browse)
            navigation.selectRoot(ExpressiveDestination.Stats)
            assertEquals(ExpressiveNavigationEffect.OpenDetailedStatistics, navigation.effects.first())

            val restored = ExpressiveNavigationViewModel(handle)
            assertNull(withTimeoutOrNull(1) { restored.effects.first() })
        }

    @Test
    fun `saved stack wins over repeated initial destination`() {
        val handle = SavedStateHandle()
        ExpressiveNavigationViewModel(handle).apply {
            selectRoot(ExpressiveDestination.Browse)
            openSettings()
        }

        val restored =
            ExpressiveNavigationViewModel(
                savedStateHandle = handle,
                initialDestination = ExpressiveDestination.Study(99L, "Intent deck"),
            )

        assertEquals(
            listOf(ExpressiveDestination.Browse, ExpressiveDestination.Settings),
            restored.backStack.value.entries,
        )
    }

    @Test
    fun `unknown version and malformed Study recover to Home`() {
        val unknownVersion =
            SavedStateHandle(
                mapOf(ExpressiveNavigationViewModel.SAVED_STATE_KEY to "{\"version\":999,\"entries\":[]}"),
            )
        val malformedStudy =
            SavedStateHandle(
                mapOf(
                    ExpressiveNavigationViewModel.SAVED_STATE_KEY to
                        "{\"version\":1,\"entries\":[{\"id\":\"expressive/home/v1\"},{\"id\":\"expressive/study/v1\",\"args\":{\"deckId\":\"not-a-long\",\"deckName\":\"Deck\"}}]}",
                ),
            )

        assertEquals(ExpressiveDestination.Home, ExpressiveNavigationViewModel(unknownVersion).backStack.value.current)
        assertEquals(ExpressiveDestination.Home, ExpressiveNavigationViewModel(malformedStudy).backStack.value.current)
    }

    @Test
    fun `active sync projects More without mutating saved Study stack`() {
        val navigation = ExpressiveNavigationViewModel(SavedStateHandle())
        navigation.openStudy(deckId = 7L, deckName = "Biology")
        val before = navigation.backStack.value

        assertEquals(
            ExpressiveDestination.More,
            effectiveExpressiveDestination(before, syncActive = true),
        )
        assertEquals(before, navigation.backStack.value)
        assertEquals(
            ExpressiveDestination.Study(7L, "Biology"),
            effectiveExpressiveDestination(navigation.backStack.value, syncActive = false),
        )
    }

    @Test
    fun `Import and Export overlays survive route restoration and pop to their root`() {
        val handle = SavedStateHandle()
        val navigation = ExpressiveNavigationViewModel(handle)

        navigation.selectRoot(ExpressiveDestination.More)
        navigation.openImport()
        assertEquals(ExpressiveDestination.Import, ExpressiveNavigationViewModel(handle).backStack.value.current)
        assertTrue(navigation.pop())
        navigation.openExport()
        assertEquals(ExpressiveDestination.Export, ExpressiveNavigationViewModel(handle).backStack.value.current)
        assertTrue(navigation.pop())
        assertEquals(ExpressiveDestination.More, navigation.backStack.value.current)
    }

    @Test
    fun `every Maintenance mode survives route restoration and pops to its root`() {
        ExpressiveMaintenanceMode.entries.forEach { mode ->
            val handle = SavedStateHandle()
            val navigation = ExpressiveNavigationViewModel(handle)
            navigation.selectRoot(ExpressiveDestination.More)

            navigation.openMaintenance(mode)

            assertEquals(
                listOf(ExpressiveDestination.More, ExpressiveDestination.Maintenance(mode)),
                ExpressiveNavigationViewModel(handle).backStack.value.entries,
            )
            assertTrue(navigation.pop())
            assertEquals(ExpressiveDestination.More, navigation.backStack.value.current)
        }
    }

    @Test
    fun `unknown Maintenance mode snapshot recovers to Home`() {
        val handle =
            SavedStateHandle(
                mapOf(
                    ExpressiveNavigationViewModel.SAVED_STATE_KEY to
                        "{\"version\":1,\"entries\":[{\"id\":\"expressive/more/v1\"},{\"id\":\"expressive/maintenance/v1\",\"args\":{\"mode\":\"FORMAT_DISK\"}}]}",
                ),
            )

        assertEquals(ExpressiveDestination.Home, ExpressiveNavigationViewModel(handle).backStack.value.current)
    }

    private companion object {
        const val STUDY_V1_GOLDEN =
            "{\"version\":1,\"entries\":[{\"id\":\"expressive/home/v1\"},{\"id\":\"expressive/study/v1\",\"args\":{\"deckId\":\"42\",\"deckName\":\"Deck\"}}]}"
    }
}
