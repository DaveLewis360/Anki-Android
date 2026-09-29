// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.ui.expressive

import androidx.test.ext.junit.runners.AndroidJUnit4
import anki.collection.opChanges
import anki.scheduler.customStudyRequest
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.collection.CollectionChange
import com.ichi2.anki.libanki.Consts.DEFAULT_DECK_ID
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class ExpressiveCollectionChangeTest : RobolectricTest() {
    @Test
    fun `Int flag result is returned separately from one collection change`() =
        runTest {
            addBasicNote("front", "back")
            ExpressiveCollection.selectDeck(DEFAULT_DECK_ID)
            val queueState = requireNotNull(col.sched.currentQueueState())
            val events = mutableListOf<CollectionChange>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                ExpressiveCollection.collectionChanges.toList(events)
            }

            val newFlag = ExpressiveCollection.cycleFlag(queueState)
            runCurrent()

            assertEquals(1, newFlag)
            assertEquals(1, events.size)
            assertTrue(events.single().changes.affectsExpressiveBrowser())
        }

    @Test
    fun `custom study publishes one typed change without runtime payload conversion`() =
        runTest {
            val events = mutableListOf<CollectionChange>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                ExpressiveCollection.collectionChanges.toList(events)
            }

            ExpressiveCollection.customStudy(
                customStudyRequest {
                    deckId = DEFAULT_DECK_ID
                    newLimitDelta = 1
                },
            )
            runCurrent()

            assertEquals(1, events.size)
            assertTrue(events.single().changes.studyQueues)
        }

    @Test
    fun `Browse invalidates for every row-affecting collection flag`() {
        assertTrue(opChanges { browserTable = true }.affectsExpressiveBrowser())
        assertTrue(opChanges { noteText = true }.affectsExpressiveBrowser())
        assertTrue(opChanges { card = true }.affectsExpressiveBrowser())
        assertTrue(opChanges { note = true }.affectsExpressiveBrowser())
        assertTrue(opChanges { deck = true }.affectsExpressiveBrowser())
        assertTrue(opChanges { studyQueues = true }.affectsExpressiveBrowser())
        assertFalse(opChanges { config = true }.affectsExpressiveBrowser())
    }
}
