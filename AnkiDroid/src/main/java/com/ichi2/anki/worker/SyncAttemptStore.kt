// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.worker

import android.content.Context
import android.content.SharedPreferences
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.ichi2.anki.settings.Prefs
import com.ichi2.anki.settings.enums.ShouldFetchMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/** Collection operation owned by [SyncWorker]. */
enum class SyncOperation {
    NORMAL,
    FULL_UPLOAD,
    FULL_DOWNLOAD,
}

/** Media policy captured when a logical sync attempt is enqueued. */
enum class SyncMediaPolicy {
    ALWAYS,
    ONLY_UNMETERED,
    NEVER,
    ;

    companion object {
        fun current(): SyncMediaPolicy =
            when (Prefs.shouldFetchMedia) {
                ShouldFetchMedia.ALWAYS -> ALWAYS
                ShouldFetchMedia.ONLY_UNMETERED -> ONLY_UNMETERED
                ShouldFetchMedia.NEVER -> NEVER
            }
    }
}

/** Exact WorkManager identities for one logical collection + media attempt. */
data class SyncWorkIds(
    val attemptId: UUID,
    val collectionWorkId: UUID,
    val mediaWorkId: UUID?,
)

internal data class SyncAttemptRecord(
    val attemptId: UUID,
    val credentialGeneration: String,
    val operation: SyncOperation,
    val mediaPolicy: SyncMediaPolicy,
    val collectionWorkId: UUID,
    val mediaWorkId: UUID?,
    val acknowledgedCollectionWorkId: UUID? = null,
    val acknowledgedMediaWorkId: UUID? = null,
) {
    fun ids(): SyncWorkIds = SyncWorkIds(attemptId, collectionWorkId, mediaWorkId)

    fun isFullyAcknowledged(): Boolean =
        acknowledgedCollectionWorkId == collectionWorkId &&
            (mediaWorkId == null || acknowledgedMediaWorkId == mediaWorkId)
}

/**
 * Stores only non-secret orchestration metadata. Candidate IDs remain pending until WorkManager
 * confirms their collection request exists; only then are they atomically promoted to accepted.
 * Credentials remain exclusively in SyncCredentialStore and are resolved by generation.
 */
// Every write is committed and its result checked: the KTX edit {} would drop the result.
@Suppress("UseKtx")
internal object SyncAttemptStore {
    private const val PREFERENCES = "durable-sync-attempt-v1"
    private const val PENDING_PREFIX = "pending."
    private const val ACCEPTED_PREFIX = "accepted."
    private const val ATTEMPT_ID = "attemptId"
    private const val CREDENTIAL_GENERATION = "credentialGeneration"
    private const val OPERATION = "operation"
    private const val MEDIA_POLICY = "mediaPolicy"
    private const val COLLECTION_WORK_ID = "collectionWorkId"
    private const val MEDIA_WORK_ID = "mediaWorkId"
    private const val ACKNOWLEDGED_COLLECTION_WORK_ID = "acknowledgedCollectionWorkId"
    private const val ACKNOWLEDGED_MEDIA_WORK_ID = "acknowledgedMediaWorkId"
    private const val EXPRESSIVE_STARTUP_SYNC_PENDING = "expressiveStartupSyncPending"

    internal const val COLLECTION_RECOVERY_TAG_PREFIX = "ankidroid.sync.collection.v1:"

    @Synchronized
    fun read(context: Context): SyncAttemptRecord? {
        val preferences = preferences(context)
        readRecord(preferences, ACCEPTED_PREFIX)?.let { return it }
        val legacy = readRecord(preferences, prefix = "") ?: return null
        check(
            preferences
                .edit()
                .apply {
                    writeRecord(ACCEPTED_PREFIX, legacy, clearAcknowledgement = false)
                    clearRecord(prefix = "")
                }.commit(),
        )
        return legacy
    }

    @Synchronized
    fun readPending(context: Context): SyncAttemptRecord? = readRecord(preferences(context), PENDING_PREFIX)

    @Synchronized
    fun writePending(
        context: Context,
        record: SyncAttemptRecord,
    ) {
        check(
            preferences(context)
                .edit()
                .apply { writeRecord(PENDING_PREFIX, record, clearAcknowledgement = true) }
                .commit(),
        )
    }

    /** Atomically replaces accepted metadata only after exact WorkManager acceptance is known. */
    @Synchronized
    fun promotePending(
        context: Context,
        ids: SyncWorkIds,
    ): SyncAttemptRecord? {
        val preferences = preferences(context)
        val pending = readRecord(preferences, PENDING_PREFIX) ?: return null
        if (pending.ids() != ids) return null
        check(
            preferences
                .edit()
                .apply {
                    writeRecord(ACCEPTED_PREFIX, pending, clearAcknowledgement = true)
                    clearRecord(PENDING_PREFIX)
                    clearRecord(prefix = "")
                }.commit(),
        )
        return pending
    }

    @Synchronized
    fun discardPendingIfMatches(
        context: Context,
        ids: SyncWorkIds,
    ) {
        val preferences = preferences(context)
        if (readRecord(preferences, PENDING_PREFIX)?.ids() != ids) return
        check(preferences.edit().apply { clearRecord(PENDING_PREFIX) }.commit())
    }

    @Synchronized
    fun replaceAccepted(
        context: Context,
        record: SyncAttemptRecord,
    ) {
        check(
            preferences(context)
                .edit()
                .apply {
                    writeRecord(ACCEPTED_PREFIX, record, clearAcknowledgement = true)
                    clearRecord(prefix = "")
                }.commit(),
        )
    }

    /** Test/setup compatibility for directly installing an already accepted WorkManager record. */
    fun write(
        context: Context,
        record: SyncAttemptRecord,
    ) = replaceAccepted(context, record)

    @Synchronized
    fun clearIfMatches(
        context: Context,
        ids: SyncWorkIds,
    ) {
        if (read(context)?.ids() == ids) clearAccepted(context)
        discardPendingIfMatches(context, ids)
    }

    /** Exact and idempotent: true means durable acknowledgement is already or is now committed. */
    @Synchronized
    fun acknowledgeTerminal(
        context: Context,
        attemptId: UUID,
        collectionWorkId: UUID,
        mediaWorkId: UUID?,
    ): Boolean {
        val current = read(context) ?: return false
        if (
            current.attemptId != attemptId ||
            current.collectionWorkId != collectionWorkId ||
            current.mediaWorkId != mediaWorkId
        ) {
            return false
        }
        if (current.isFullyAcknowledged()) return true
        return preferences(context)
            .edit()
            .apply {
                putString(ACCEPTED_PREFIX + ACKNOWLEDGED_COLLECTION_WORK_ID, collectionWorkId.toString())
                if (mediaWorkId != null) {
                    putString(ACCEPTED_PREFIX + ACKNOWLEDGED_MEDIA_WORK_ID, mediaWorkId.toString())
                } else {
                    remove(ACCEPTED_PREFIX + ACKNOWLEDGED_MEDIA_WORK_ID)
                }
            }.commit()
    }

    /**
     * Repairs process-death windows before exposing an attempt. Pending work is promoted only when
     * its exact collection ID exists. Stale accepted IDs adopt the tagged unfinished owner, or are
     * cleared so a permanently missing WorkInfo can never project as queued forever.
     */
    suspend fun reconcile(
        context: Context,
        workManager: WorkManager = WorkManager.getInstance(context.applicationContext),
    ): SyncAttemptRecord? =
        withContext(Dispatchers.IO) {
            val pending = readPending(context)
            if (pending != null) {
                if (workManager.getWorkInfoById(pending.collectionWorkId).get() != null) {
                    promotePending(context, pending.ids())
                } else {
                    discardPendingIfMatches(context, pending.ids())
                }
            }

            val accepted = read(context)
            if (accepted != null) {
                val collectionInfo = workManager.getWorkInfoById(accepted.collectionWorkId).get()
                val mediaInfo = accepted.mediaWorkId?.let { workManager.getWorkInfoById(it).get() }
                if (collectionInfo != null && (accepted.mediaWorkId == null || mediaInfo != null)) {
                    return@withContext accepted
                }
            }

            val uniqueWork = workManager.getWorkInfosForUniqueWork(UniqueWorkNames.SYNC).get()
            val knownIds = uniqueWork.mapTo(mutableSetOf()) { it.id }
            val owner =
                uniqueWork
                    .asSequence()
                    .filter { info -> !info.state.isFinished() && info.hasCollectionRecoveryTag() }
                    .mapNotNull { info -> info.toAttemptRecord() }
                    .firstOrNull { record -> record.mediaWorkId == null || record.mediaWorkId in knownIds }
            when {
                owner != null -> {
                    replaceAccepted(context, owner)
                    owner
                }
                else -> {
                    clearAccepted(context)
                    null
                }
            }
        }

    @Synchronized
    fun clearAccepted(context: Context) {
        val preferences = preferences(context)
        check(
            preferences
                .edit()
                .apply {
                    clearRecord(ACCEPTED_PREFIX)
                    clearRecord(prefix = "")
                }.commit(),
        )
    }

    @Synchronized
    fun markExpressiveStartupSyncPending(context: Context) {
        check(preferences(context).edit().putBoolean(EXPRESSIVE_STARTUP_SYNC_PENDING, true).commit())
    }

    @Synchronized
    fun isExpressiveStartupSyncPending(context: Context): Boolean = preferences(context).getBoolean(EXPRESSIVE_STARTUP_SYNC_PENDING, false)

    @Synchronized
    fun clearExpressiveStartupSyncPending(context: Context) {
        check(preferences(context).edit().remove(EXPRESSIVE_STARTUP_SYNC_PENDING).commit())
    }

    fun recoveryTag(record: SyncAttemptRecord): String =
        buildString {
            append(COLLECTION_RECOVERY_TAG_PREFIX)
            append(record.attemptId)
            append(':')
            append(record.credentialGeneration)
            append(':')
            append(record.operation.name)
            append(':')
            append(record.mediaPolicy.name)
            append(':')
            append(record.mediaWorkId ?: "none")
        }

    private fun WorkInfo.hasCollectionRecoveryTag(): Boolean = tags.any { it.startsWith(COLLECTION_RECOVERY_TAG_PREFIX) }

    private fun WorkInfo.toAttemptRecord(): SyncAttemptRecord? =
        tags
            .firstOrNull { it.startsWith(COLLECTION_RECOVERY_TAG_PREFIX) }
            ?.removePrefix(COLLECTION_RECOVERY_TAG_PREFIX)
            ?.split(':')
            ?.takeIf { it.size == RECOVERY_FIELD_COUNT }
            ?.let { fields ->
                runCatching {
                    SyncAttemptRecord(
                        attemptId = UUID.fromString(fields[0]),
                        credentialGeneration = UUID.fromString(fields[1]).toString(),
                        operation = enumValueOf(fields[2]),
                        mediaPolicy = enumValueOf(fields[3]),
                        collectionWorkId = id,
                        mediaWorkId = fields[4].takeUnless { it == "none" }?.let(UUID::fromString),
                    )
                }.getOrNull()
            }

    private fun readRecord(
        preferences: SharedPreferences,
        prefix: String,
    ): SyncAttemptRecord? {
        if (!preferences.contains(prefix + ATTEMPT_ID)) return null
        return runCatching {
            SyncAttemptRecord(
                attemptId = UUID.fromString(preferences.getString(prefix + ATTEMPT_ID, null)),
                credentialGeneration = requireNotNull(preferences.getString(prefix + CREDENTIAL_GENERATION, null)),
                operation = enumValueOf(requireNotNull(preferences.getString(prefix + OPERATION, null))),
                mediaPolicy = enumValueOf(requireNotNull(preferences.getString(prefix + MEDIA_POLICY, null))),
                collectionWorkId = UUID.fromString(preferences.getString(prefix + COLLECTION_WORK_ID, null)),
                mediaWorkId = preferences.getString(prefix + MEDIA_WORK_ID, null)?.let(UUID::fromString),
                acknowledgedCollectionWorkId =
                    preferences.getString(prefix + ACKNOWLEDGED_COLLECTION_WORK_ID, null)?.let(UUID::fromString),
                acknowledgedMediaWorkId =
                    preferences.getString(prefix + ACKNOWLEDGED_MEDIA_WORK_ID, null)?.let(UUID::fromString),
            )
        }.getOrElse {
            check(preferences.edit().apply { clearRecord(prefix) }.commit())
            null
        }
    }

    private fun SharedPreferences.Editor.writeRecord(
        prefix: String,
        record: SyncAttemptRecord,
        clearAcknowledgement: Boolean,
    ) {
        putString(prefix + ATTEMPT_ID, record.attemptId.toString())
        putString(prefix + CREDENTIAL_GENERATION, record.credentialGeneration)
        putString(prefix + OPERATION, record.operation.name)
        putString(prefix + MEDIA_POLICY, record.mediaPolicy.name)
        putString(prefix + COLLECTION_WORK_ID, record.collectionWorkId.toString())
        record.mediaWorkId?.let { putString(prefix + MEDIA_WORK_ID, it.toString()) } ?: remove(prefix + MEDIA_WORK_ID)
        if (clearAcknowledgement) {
            remove(prefix + ACKNOWLEDGED_COLLECTION_WORK_ID)
            remove(prefix + ACKNOWLEDGED_MEDIA_WORK_ID)
        } else {
            record.acknowledgedCollectionWorkId?.let {
                putString(prefix + ACKNOWLEDGED_COLLECTION_WORK_ID, it.toString())
            } ?: remove(prefix + ACKNOWLEDGED_COLLECTION_WORK_ID)
            record.acknowledgedMediaWorkId?.let {
                putString(prefix + ACKNOWLEDGED_MEDIA_WORK_ID, it.toString())
            } ?: remove(prefix + ACKNOWLEDGED_MEDIA_WORK_ID)
        }
    }

    private fun SharedPreferences.Editor.clearRecord(prefix: String) {
        remove(prefix + ATTEMPT_ID)
        remove(prefix + CREDENTIAL_GENERATION)
        remove(prefix + OPERATION)
        remove(prefix + MEDIA_POLICY)
        remove(prefix + COLLECTION_WORK_ID)
        remove(prefix + MEDIA_WORK_ID)
        remove(prefix + ACKNOWLEDGED_COLLECTION_WORK_ID)
        remove(prefix + ACKNOWLEDGED_MEDIA_WORK_ID)
    }

    private fun WorkInfo.State.isFinished(): Boolean =
        this == WorkInfo.State.SUCCEEDED || this == WorkInfo.State.FAILED || this == WorkInfo.State.CANCELLED

    private fun preferences(context: Context) = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private const val RECOVERY_FIELD_COUNT = 5
}

fun markExpressiveStartupSyncPending(context: Context) {
    SyncAttemptStore.markExpressiveStartupSyncPending(context)
}
