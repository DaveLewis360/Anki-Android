// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.ui.expressive

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ichi2.anki.libanki.CardId
import com.ichi2.anki.libanki.DeckId
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Expressive feature that owns an editor request and must receive its result. */
@Serializable
enum class ExpressiveEditorOrigin {
    HOME,
    BROWSE,
    STUDY,
}

/** Typed input contract for the dedicated note editor feature Activity. */
@Serializable
sealed interface ExpressiveEditorRequest {
    val origin: ExpressiveEditorOrigin

    @Serializable
    @SerialName("add")
    data class Add(
        override val origin: ExpressiveEditorOrigin,
        val deckId: DeckId? = null,
        val searchTerms: String? = null,
    ) : ExpressiveEditorRequest

    @Serializable
    @SerialName("edit")
    data class Edit(
        override val origin: ExpressiveEditorOrigin,
        val cardId: CardId,
    ) : ExpressiveEditorRequest
}

/** Result enriched with the persisted request origin. */
@Serializable
data class ExpressiveEditorResult(
    val requestId: Long,
    val origin: ExpressiveEditorOrigin,
    val changed: Boolean,
    val reloadRequired: Boolean,
)

internal data class ExpressiveEditorLaunch(
    val requestId: Long,
    val request: ExpressiveEditorRequest,
)

@Serializable
private data class SavedEditorRequest(
    val version: Int,
    val requestId: Long,
    val request: ExpressiveEditorRequest,
    val launchDispatched: Boolean = false,
)

/**
 * Activity-owned editor coordinator.
 *
 * The pending request is persisted before launch. A restored pending request is deliberately not
 * emitted again: ActivityResultRegistry owns delivery for the already launched Activity. The result
 * remains persisted until the originating Expressive feature consumes it.
 */
internal class ExpressiveEditorCoordinatorViewModel(
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val restoredPending =
        savedStateHandle.get<String>(PENDING_KEY)?.let(EditorStateCodec::decodeRequest)
    private val restoredResult =
        savedStateHandle.get<String>(RESULT_KEY)?.let(EditorStateCodec::decodeResult)

    private val pendingState = MutableStateFlow(restoredPending)

    private val _result = MutableStateFlow(restoredResult)
    val result: StateFlow<ExpressiveEditorResult?> = _result.asStateFlow()

    private val _launches = Channel<ExpressiveEditorLaunch>(Channel.BUFFERED)
    val launches: Flow<ExpressiveEditorLaunch> = _launches.receiveAsFlow()

    init {
        if (restoredPending == null) {
            savedStateHandle.remove<String>(PENDING_KEY)
            savedStateHandle[SUPPRESS_RESUME_REFRESH_KEY] = false
        } else if (!restoredPending.launchDispatched) {
            _launches.trySend(ExpressiveEditorLaunch(restoredPending.requestId, restoredPending.request))
        }
        if (restoredResult == null) savedStateHandle.remove<String>(RESULT_KEY)
    }

    /** Returns false rather than replacing an in-flight request or an unconsumed result. */
    fun open(request: ExpressiveEditorRequest): Boolean {
        if (pendingState.value != null || _result.value != null || !request.isValid()) return false
        val requestId = savedStateHandle.get<Long>(NEXT_ID_KEY) ?: 1L
        savedStateHandle[NEXT_ID_KEY] = requestId + 1L
        val saved = SavedEditorRequest(VERSION, requestId, request)
        savedStateHandle[PENDING_KEY] = EditorStateCodec.encodeRequest(saved)
        savedStateHandle[SUPPRESS_RESUME_REFRESH_KEY] = true
        pendingState.value = saved
        _launches.trySend(ExpressiveEditorLaunch(requestId, request))
        return true
    }

    /** Completes exactly the persisted request; a missing/malformed request fails closed. */
    fun complete(
        changed: Boolean,
        reloadRequired: Boolean,
    ): ExpressiveEditorResult? {
        val saved = pendingState.value ?: return null
        val completed =
            ExpressiveEditorResult(
                requestId = saved.requestId,
                origin = saved.request.origin,
                changed = changed,
                reloadRequired = reloadRequired,
            )
        pendingState.value = null
        savedStateHandle.remove<String>(PENDING_KEY)
        _result.value = completed
        savedStateHandle[RESULT_KEY] = EditorStateCodec.encodeResult(completed)
        return completed
    }

    fun markLaunchDispatched(requestId: Long) {
        val saved = pendingState.value ?: return
        if (saved.requestId != requestId || saved.launchDispatched) return
        val dispatched = saved.copy(launchDispatched = true)
        pendingState.value = dispatched
        savedStateHandle[PENDING_KEY] = EditorStateCodec.encodeRequest(dispatched)
    }

    fun failLaunch(requestId: Long) {
        if (pendingState.value?.requestId != requestId) return
        pendingState.value = null
        savedStateHandle.remove<String>(PENDING_KEY)
        savedStateHandle[SUPPRESS_RESUME_REFRESH_KEY] = false
    }

    fun consumeResult(requestId: Long) {
        if (_result.value?.requestId != requestId) return
        _result.value = null
        savedStateHandle.remove<String>(RESULT_KEY)
    }

    /** Prevents the generic Activity-resume invalidation from treating editor cancellation as a change. */
    fun consumeResumeRefreshSuppression(): Boolean {
        val suppress = savedStateHandle.get<Boolean>(SUPPRESS_RESUME_REFRESH_KEY) == true
        if (suppress) savedStateHandle[SUPPRESS_RESUME_REFRESH_KEY] = false
        return suppress
    }

    companion object {
        private const val VERSION = 1
        private const val PENDING_KEY = "expressive.editor.pending.v1"
        private const val RESULT_KEY = "expressive.editor.result.v1"
        private const val NEXT_ID_KEY = "expressive.editor.next-id"
        private const val SUPPRESS_RESUME_REFRESH_KEY = "expressive.editor.suppress-resume-refresh"

        fun factory(): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    ExpressiveEditorCoordinatorViewModel(createSavedStateHandle())
                }
            }
    }
}

private fun ExpressiveEditorRequest.isValid(): Boolean =
    when (this) {
        is ExpressiveEditorRequest.Add -> deckId == null || deckId > 0
        is ExpressiveEditorRequest.Edit -> cardId > 0
    }

private object EditorStateCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encodeRequest(request: SavedEditorRequest): String = json.encodeToString(request)

    fun decodeRequest(encoded: String): SavedEditorRequest? =
        runCatching { json.decodeFromString<SavedEditorRequest>(encoded) }
            .getOrNull()
            ?.takeIf { it.version == 1 && it.requestId > 0 && it.request.isValid() }

    fun encodeResult(result: ExpressiveEditorResult): String = json.encodeToString(result)

    fun decodeResult(encoded: String): ExpressiveEditorResult? =
        runCatching { json.decodeFromString<ExpressiveEditorResult>(encoded) }
            .getOrNull()
            ?.takeIf { it.requestId > 0 }
}
