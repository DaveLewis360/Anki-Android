// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.collection

import anki.collection.OpChanges
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.libanki.Collection
import com.ichi2.anki.observability.ChangeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext

/** A normalized collection invalidation emitted after a successful write. */
data class CollectionChange(
    val changes: OpChanges,
    val initiator: Any?,
)

/** Keeps a collection operation's domain value separate from its invalidation payload. */
data class CollectionWriteResult<out R>(
    val value: R,
    val changes: OpChanges,
)

/**
 * Serialized access to the active collection.
 *
 * Both blocks are deliberately non-suspending. [withCol] therefore executes each block as one
 * collection transaction without coroutine interleaving.
 */
interface CollectionRepository {
    val changes: SharedFlow<CollectionChange>

    suspend fun <R> read(block: Collection.() -> R): R

    suspend fun <R> write(
        initiator: Any? = null,
        block: Collection.() -> CollectionWriteResult<R>,
    ): R
}

/** Production repository backed by [com.ichi2.anki.CollectionManager]'s serialized queue. */
object DefaultCollectionRepository : CollectionRepository {
    override val changes: SharedFlow<CollectionChange> = ChangeManager.changes

    override suspend fun <R> read(block: Collection.() -> R): R = withCol(block)

    override suspend fun <R> write(
        initiator: Any?,
        block: Collection.() -> CollectionWriteResult<R>,
    ): R {
        currentCoroutineContext().ensureActive()
        return withContext(NonCancellable) {
            val result = withCol(block)
            withContext(Dispatchers.Main) {
                ChangeManager.notifySubscribers(result.changes, initiator)
            }
            result.value
        }
    }
}
