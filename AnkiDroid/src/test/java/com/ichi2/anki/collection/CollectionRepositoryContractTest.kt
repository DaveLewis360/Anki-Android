// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.collection

import androidx.test.ext.junit.runners.AndroidJUnit4
import anki.collection.opChanges
import com.ichi2.anki.CollectionManager
import com.ichi2.anki.CollectionReplacementRecoveryException
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.observability.ChangeManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class CollectionRepositoryContractTest : RobolectricTest() {
    private val repository: CollectionRepository = DefaultCollectionRepository

    @Test
    fun `read returns its value without publishing a collection change`() =
        runTest {
            val events = mutableListOf<CollectionChange>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                repository.changes.toList(events)
            }

            val cardCount = repository.read { cardCount() }
            runCurrent()

            assertEquals(col.cardCount(), cardCount)
            assertTrue(events.isEmpty())
        }

    @Test
    fun `write keeps an Int result separate from exactly one typed change`() =
        runTest {
            val events = mutableListOf<CollectionChange>()
            val initiator = Any()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                repository.changes.toList(events)
            }

            val value =
                repository.write(initiator) {
                    CollectionWriteResult(
                        value = 7,
                        changes = opChanges { card = true },
                    )
                }
            runCurrent()

            assertEquals(7, value)
            assertEquals(1, events.size)
            assertTrue(events.single().changes.card)
            assertSame(initiator, events.single().initiator)
        }

    @Test
    fun `cancellation after a write starts preserves its result and change`() =
        runTest {
            val events = mutableListOf<CollectionChange>()
            val returned = CompletableDeferred<Int>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                repository.changes.toList(events)
            }

            val writer =
                launch {
                    val writerJob = currentCoroutineContext().job
                    val value =
                        repository.write {
                            writerJob.cancel()
                            CollectionWriteResult(
                                value = 9,
                                changes = opChanges { card = true },
                            )
                        }
                    returned.complete(value)
                }
            advanceUntilIdle()

            assertTrue(writer.isCancelled)
            assertTrue(returned.isCompleted)
            assertEquals(9, returned.await())
            assertEquals(1, events.size)
            assertTrue(events.single().changes.card)
        }

    @Test
    fun `failed write publishes no collection change`() =
        runTest {
            val events = mutableListOf<CollectionChange>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                repository.changes.toList(events)
            }

            val failure =
                runCatching {
                    repository.write<Int> {
                        error("write failed")
                    }
                }.exceptionOrNull()
            runCurrent()

            assertIs<IllegalStateException>(failure)
            assertTrue(events.isEmpty())
        }

    @Test
    fun `external ChangeManager publish reaches the repository stream`() =
        runTest {
            val events = mutableListOf<CollectionChange>()
            val initiator = Any()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                repository.changes.toList(events)
            }

            ChangeManager.publish(
                changes = opChanges { noteText = true },
                handler = initiator,
            )
            advanceUntilIdle()

            assertEquals(1, events.size)
            assertTrue(events.single().changes.noteText)
            assertSame(initiator, events.single().initiator)
        }

    @Test
    fun `slow active collector does not lose a relevant invalidation`() =
        runTest {
            val releaseFirstEvent = CompletableDeferred<Unit>()
            val events = mutableListOf<CollectionChange>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                repository.changes.collect { event ->
                    events.add(event)
                    if (events.size == 1) releaseFirstEvent.await()
                }
            }

            ChangeManager.publish(opChanges { config = true })
            runCurrent()
            ChangeManager.publish(opChanges { noteText = true })
            repeat(80) {
                ChangeManager.publish(opChanges { config = true })
            }
            releaseFirstEvent.complete(Unit)
            advanceUntilIdle()

            assertEquals(82, events.size)
            assertTrue(events.any { it.changes.noteText })
        }

    @Test
    fun `repository stream does not replay changes published before collection`() =
        runTest {
            ChangeManager.publish(opChanges { card = true })
            advanceUntilIdle()
            val events = mutableListOf<CollectionChange>()

            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                repository.changes.toList(events)
            }
            runCurrent()

            assertTrue(events.isEmpty())
        }

    @Test
    fun `strict replacement close failure prevents mutation and reopen`() {
        var replaceCalls = 0
        var reopenCalls = 0
        val closeFailure = IllegalStateException("close failed")

        val failure =
            runCatching {
                CollectionManager.runStrictCollectionReplacementProtocol(
                    close = { throw closeFailure },
                    isClosed = { false },
                    replace = { replaceCalls++ },
                    reopen = { reopenCalls++ },
                )
            }.exceptionOrNull()

        assertSame(closeFailure, failure)
        assertEquals(0, replaceCalls)
        assertEquals(0, reopenCalls)
    }

    @Test
    fun `strict replacement recovers after close partially succeeds`() {
        var closed = false
        var replaceCalls = 0
        var reopenCalls = 0
        val closeFailure = IllegalStateException("close failed after closing")

        val failure =
            runCatching {
                CollectionManager.runStrictCollectionReplacementProtocol(
                    close = {
                        closed = true
                        throw closeFailure
                    },
                    isClosed = { closed },
                    replace = { replaceCalls++ },
                    reopen = {
                        reopenCalls++
                        closed = false
                    },
                )
            }.exceptionOrNull()

        assertSame(closeFailure, failure)
        assertEquals(0, replaceCalls)
        assertEquals(1, reopenCalls)
        assertFalse(closed)
    }

    @Test
    fun `strict replacement preserves backend failure and suppressed recovery failure`() {
        var closed = false
        val backendFailure = IllegalStateException("backend failed")
        val reopenFailure = IllegalStateException("reopen failed")

        val failure =
            runCatching {
                CollectionManager.runStrictCollectionReplacementProtocol(
                    close = { closed = true },
                    isClosed = { closed },
                    replace = { throw backendFailure },
                    reopen = { throw reopenFailure },
                )
            }.exceptionOrNull()

        assertSame(backendFailure, failure)
        assertEquals(listOf(reopenFailure), failure?.suppressedExceptions)
    }

    @Test
    fun `strict replacement reports committed reopen recovery failure`() {
        var closed = false
        val reopenFailure = IllegalStateException("reopen failed")

        val failure =
            runCatching {
                CollectionManager.runStrictCollectionReplacementProtocol(
                    close = { closed = true },
                    isClosed = { closed },
                    replace = { Unit },
                    reopen = { throw reopenFailure },
                )
            }.exceptionOrNull()

        val recovery = assertIs<CollectionReplacementRecoveryException>(failure)
        assertSame(reopenFailure, recovery.cause)
    }

    @Test
    fun `collection export cancellation is observed before reopen`() {
        var closed = false
        var reopenCalls = 0
        val cancellation = CancellationException("aborted")
        val failure =
            runCatching {
                CollectionManager.runFullCollectionExportProtocol(
                    close = { closed = true },
                    isClosed = { closed },
                    export = { throw cancellation },
                    reopen = {
                        reopenCalls++
                        closed = false
                    },
                )
            }.exceptionOrNull()

        assertSame(cancellation, failure)
        assertEquals(1, reopenCalls)
        assertFalse(closed)
    }

    @Test
    fun `collection export preserves backend failure and reports reopen recovery failure`() {
        var closed = false
        val backendFailure = IllegalStateException("backend failed")
        val reopenFailure = IllegalStateException("reopen failed")
        val failure =
            runCatching {
                CollectionManager.runFullCollectionExportProtocol(
                    close = { closed = true },
                    isClosed = { closed },
                    export = { throw backendFailure },
                    reopen = { throw reopenFailure },
                )
            }.exceptionOrNull()

        assertSame(backendFailure, failure)
        assertEquals(listOf(reopenFailure), failure?.suppressedExceptions)
    }
}
