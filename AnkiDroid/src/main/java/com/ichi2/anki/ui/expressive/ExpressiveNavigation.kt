// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.ui.expressive

import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ichi2.anki.libanki.DeckId
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Typed destinations rendered by the Expressive shell. */
sealed interface ExpressiveDestination {
    sealed interface Root : ExpressiveDestination

    data object Home : Root

    data object Browse : Root

    data object Stats : Root

    data object More : Root

    data object Settings : ExpressiveDestination

    data object Account : ExpressiveDestination

    data object Import : ExpressiveDestination

    data object Export : ExpressiveDestination

    data class Maintenance(
        val mode: ExpressiveMaintenanceMode,
    ) : ExpressiveDestination

    data class Study(
        val deckId: DeckId,
        val deckName: String,
    ) : ExpressiveDestination
}

/** One root destination and, optionally, one full-screen child destination. */
data class ExpressiveBackStack(
    val entries: List<ExpressiveDestination>,
) {
    init {
        require(entries.isValidExpressiveBackStack())
    }

    val root: ExpressiveDestination.Root
        get() = entries.first() as ExpressiveDestination.Root

    val current: ExpressiveDestination
        get() = entries.last()

    companion object {
        fun home(): ExpressiveBackStack = ExpressiveBackStack(listOf(ExpressiveDestination.Home))

        fun initial(destination: ExpressiveDestination): ExpressiveBackStack =
            when (destination) {
                is ExpressiveDestination.Root -> ExpressiveBackStack(listOf(destination))
                ExpressiveDestination.Settings ->
                    ExpressiveBackStack(listOf(ExpressiveDestination.Home, ExpressiveDestination.Settings))
                ExpressiveDestination.Account ->
                    ExpressiveBackStack(listOf(ExpressiveDestination.More, ExpressiveDestination.Account))
                ExpressiveDestination.Import ->
                    ExpressiveBackStack(listOf(ExpressiveDestination.More, ExpressiveDestination.Import))
                ExpressiveDestination.Export ->
                    ExpressiveBackStack(listOf(ExpressiveDestination.More, ExpressiveDestination.Export))
                is ExpressiveDestination.Maintenance ->
                    ExpressiveBackStack(listOf(ExpressiveDestination.More, destination))
                is ExpressiveDestination.Study ->
                    ExpressiveBackStack(listOf(ExpressiveDestination.Home, destination))
            }
    }
}

private fun List<ExpressiveDestination>.isValidExpressiveBackStack(): Boolean {
    if (size !in 1..2 || firstOrNull() !is ExpressiveDestination.Root) return false
    return size == 1 ||
        last() is ExpressiveDestination.Settings ||
        last() is ExpressiveDestination.Account ||
        last() is ExpressiveDestination.Import ||
        last() is ExpressiveDestination.Export ||
        last() is ExpressiveDestination.Maintenance ||
        last() is ExpressiveDestination.Study
}

/** More is a temporary render projection during sync; the durable stack is not changed. */
internal fun effectiveExpressiveDestination(
    backStack: ExpressiveBackStack,
    syncActive: Boolean,
): ExpressiveDestination = if (syncActive) ExpressiveDestination.More else backStack.current

internal val ExpressiveDestination.Root.tabIndex: Int
    get() =
        when (this) {
            ExpressiveDestination.Home -> 0
            ExpressiveDestination.Browse -> 1
            ExpressiveDestination.Stats -> 2
            ExpressiveDestination.More -> 3
        }

internal fun expressiveRootForTab(index: Int): ExpressiveDestination.Root =
    when (index) {
        1 -> ExpressiveDestination.Browse
        2 -> ExpressiveDestination.Stats
        3 -> ExpressiveDestination.More
        else -> ExpressiveDestination.Home
    }

internal sealed interface ExpressiveNavigationEffect {
    data object OpenDetailedStatistics : ExpressiveNavigationEffect
}

/** Activity-scoped router whose versioned wire snapshot is persisted by [SavedStateHandle]. */
internal class ExpressiveNavigationViewModel(
    private val savedStateHandle: SavedStateHandle,
    initialDestination: ExpressiveDestination = ExpressiveDestination.Home,
) : ViewModel() {
    private val _backStack =
        MutableStateFlow(
            savedStateHandle.get<String>(SAVED_STATE_KEY)?.let { encoded ->
                ExpressiveNavigationCodec.decode(encoded) ?: ExpressiveBackStack.home()
            } ?: ExpressiveBackStack.initial(initialDestination),
        )
    val backStack: StateFlow<ExpressiveBackStack> = _backStack.asStateFlow()

    private val _effects = Channel<ExpressiveNavigationEffect>(Channel.BUFFERED)
    val effects: Flow<ExpressiveNavigationEffect> = _effects.receiveAsFlow()

    init {
        persist(_backStack.value)
    }

    fun selectRoot(destination: ExpressiveDestination.Root) {
        val isFreshRootSelection = backStack.value.root != destination
        update(ExpressiveBackStack(listOf(destination)))
        if (isFreshRootSelection && destination == ExpressiveDestination.Stats) {
            _effects.trySend(ExpressiveNavigationEffect.OpenDetailedStatistics)
        }
    }

    fun openSettings() {
        update(ExpressiveBackStack(listOf(backStack.value.root, ExpressiveDestination.Settings)))
    }

    fun openAccount() {
        update(ExpressiveBackStack(listOf(backStack.value.root, ExpressiveDestination.Account)))
    }

    fun openImport() {
        update(ExpressiveBackStack(listOf(backStack.value.root, ExpressiveDestination.Import)))
    }

    fun openExport() {
        update(ExpressiveBackStack(listOf(backStack.value.root, ExpressiveDestination.Export)))
    }

    fun openMaintenance(mode: ExpressiveMaintenanceMode) {
        update(ExpressiveBackStack(listOf(backStack.value.root, ExpressiveDestination.Maintenance(mode))))
    }

    fun openStudy(
        deckId: DeckId,
        deckName: String,
    ) {
        require(deckId > 0) { "deckId must be positive" }
        update(ExpressiveBackStack(listOf(backStack.value.root, ExpressiveDestination.Study(deckId, deckName))))
    }

    /** Pops an overlay. Returns false when the root should be handled by the Activity. */
    fun pop(): Boolean {
        if (backStack.value.entries.size == 1) return false
        update(ExpressiveBackStack(listOf(backStack.value.root)))
        return true
    }

    private fun update(value: ExpressiveBackStack) {
        _backStack.value = value
        persist(value)
    }

    private fun persist(value: ExpressiveBackStack) {
        savedStateHandle[SAVED_STATE_KEY] = ExpressiveNavigationCodec.encode(value)
    }

    companion object {
        internal const val SAVED_STATE_KEY = "expressive.navigation.snapshot"

        fun factory(intent: Intent): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    ExpressiveNavigationViewModel(
                        savedStateHandle = createSavedStateHandle(),
                        initialDestination = ExpressiveNavigationIntent.initialDestination(intent),
                    )
                }
            }
    }
}

/** Stable Activity intent contract. Saved state always takes precedence after recreation. */
internal object ExpressiveNavigationIntent {
    private const val EXTRA_ROUTE_ID = "io.github.davelewis360.ankidroid.m3expressive.extra.ROUTE_ID"
    private const val EXTRA_DECK_ID = "io.github.davelewis360.ankidroid.m3expressive.extra.DECK_ID"
    private const val EXTRA_DECK_NAME = "io.github.davelewis360.ankidroid.m3expressive.extra.DECK_NAME"
    private const val EXTRA_MAINTENANCE_MODE = "io.github.davelewis360.ankidroid.m3expressive.extra.MAINTENANCE_MODE"

    fun initialDestination(intent: Intent): ExpressiveDestination =
        when (intent.getStringExtra(EXTRA_ROUTE_ID)) {
            ExpressiveNavigationCodec.HOME_ID -> ExpressiveDestination.Home
            ExpressiveNavigationCodec.BROWSE_ID -> ExpressiveDestination.Browse
            ExpressiveNavigationCodec.STATS_ID -> ExpressiveDestination.Stats
            ExpressiveNavigationCodec.MORE_ID -> ExpressiveDestination.More
            ExpressiveNavigationCodec.SETTINGS_ID -> ExpressiveDestination.Settings
            ExpressiveNavigationCodec.ACCOUNT_ID -> ExpressiveDestination.Account
            ExpressiveNavigationCodec.IMPORT_ID -> ExpressiveDestination.Import
            ExpressiveNavigationCodec.EXPORT_ID -> ExpressiveDestination.Export
            ExpressiveNavigationCodec.MAINTENANCE_ID ->
                intent
                    .getStringExtra(EXTRA_MAINTENANCE_MODE)
                    ?.let { runCatching { ExpressiveMaintenanceMode.valueOf(it) }.getOrNull() }
                    ?.let { mode -> ExpressiveDestination.Maintenance(mode) }
                    ?: ExpressiveDestination.Home
            ExpressiveNavigationCodec.STUDY_ID -> {
                val deckId = intent.getLongExtra(EXTRA_DECK_ID, 0L)
                val deckName = intent.getStringExtra(EXTRA_DECK_NAME)
                if (deckId > 0 && deckName != null) {
                    ExpressiveDestination.Study(deckId, deckName)
                } else {
                    ExpressiveDestination.Home
                }
            }
            else -> ExpressiveDestination.Home
        }

    fun putInitialDestination(
        intent: Intent,
        destination: ExpressiveDestination,
    ): Intent =
        intent.apply {
            putExtra(EXTRA_ROUTE_ID, ExpressiveNavigationCodec.routeId(destination))
            if (destination is ExpressiveDestination.Study) {
                putExtra(EXTRA_DECK_ID, destination.deckId)
                putExtra(EXTRA_DECK_NAME, destination.deckName)
            } else {
                removeExtra(EXTRA_DECK_ID)
                removeExtra(EXTRA_DECK_NAME)
            }
            if (destination is ExpressiveDestination.Maintenance) {
                putExtra(EXTRA_MAINTENANCE_MODE, destination.mode.name)
            } else {
                removeExtra(EXTRA_MAINTENANCE_MODE)
            }
        }
}

@Serializable
private data class SavedExpressiveNavigation(
    val version: Int,
    val entries: List<SavedExpressiveRoute>,
)

@Serializable
private data class SavedExpressiveRoute(
    val id: String,
    val args: Map<String, String> = emptyMap(),
)

private object ExpressiveNavigationCodec {
    const val HOME_ID = "expressive/home/v1"
    const val BROWSE_ID = "expressive/browse/v1"
    const val STATS_ID = "expressive/stats/v1"
    const val MORE_ID = "expressive/more/v1"
    const val SETTINGS_ID = "expressive/settings/v1"
    const val ACCOUNT_ID = "expressive/account/v1"
    const val IMPORT_ID = "expressive/import/v1"
    const val EXPORT_ID = "expressive/export/v1"
    const val MAINTENANCE_ID = "expressive/maintenance/v1"
    const val STUDY_ID = "expressive/study/v1"
    private const val VERSION = 1
    private const val MAINTENANCE_MODE_ARG = "mode"
    private const val DECK_ID_ARG = "deckId"
    private const val DECK_NAME_ARG = "deckName"
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(backStack: ExpressiveBackStack): String =
        json.encodeToString(
            SavedExpressiveNavigation(
                version = VERSION,
                entries = backStack.entries.map(::encodeRoute),
            ),
        )

    fun decode(encoded: String): ExpressiveBackStack? =
        runCatching {
            val snapshot = json.decodeFromString<SavedExpressiveNavigation>(encoded)
            if (snapshot.version != VERSION) return null
            val entries = snapshot.entries.map { decodeRoute(it) ?: return null }
            if (!entries.isValidExpressiveBackStack()) return null
            ExpressiveBackStack(entries)
        }.getOrNull()

    fun routeId(destination: ExpressiveDestination): String =
        when (destination) {
            ExpressiveDestination.Home -> HOME_ID
            ExpressiveDestination.Browse -> BROWSE_ID
            ExpressiveDestination.Stats -> STATS_ID
            ExpressiveDestination.More -> MORE_ID
            ExpressiveDestination.Settings -> SETTINGS_ID
            ExpressiveDestination.Account -> ACCOUNT_ID
            ExpressiveDestination.Import -> IMPORT_ID
            ExpressiveDestination.Export -> EXPORT_ID
            is ExpressiveDestination.Maintenance -> MAINTENANCE_ID
            is ExpressiveDestination.Study -> STUDY_ID
        }

    private fun encodeRoute(destination: ExpressiveDestination): SavedExpressiveRoute =
        when (destination) {
            is ExpressiveDestination.Maintenance ->
                SavedExpressiveRoute(id = MAINTENANCE_ID, args = mapOf(MAINTENANCE_MODE_ARG to destination.mode.name))
            is ExpressiveDestination.Study ->
                SavedExpressiveRoute(
                    id = STUDY_ID,
                    args = mapOf(DECK_ID_ARG to destination.deckId.toString(), DECK_NAME_ARG to destination.deckName),
                )
            else -> SavedExpressiveRoute(id = routeId(destination))
        }

    private fun decodeRoute(route: SavedExpressiveRoute): ExpressiveDestination? =
        when (route.id) {
            HOME_ID -> ExpressiveDestination.Home
            BROWSE_ID -> ExpressiveDestination.Browse
            STATS_ID -> ExpressiveDestination.Stats
            MORE_ID -> ExpressiveDestination.More
            SETTINGS_ID -> ExpressiveDestination.Settings
            ACCOUNT_ID -> ExpressiveDestination.Account
            IMPORT_ID -> ExpressiveDestination.Import
            EXPORT_ID -> ExpressiveDestination.Export
            MAINTENANCE_ID ->
                route.args[MAINTENANCE_MODE_ARG]
                    ?.let { runCatching { ExpressiveMaintenanceMode.valueOf(it) }.getOrNull() }
                    ?.let { mode -> ExpressiveDestination.Maintenance(mode) }
            STUDY_ID -> {
                val deckId = route.args[DECK_ID_ARG]?.toLongOrNull()?.takeIf { it > 0 } ?: return null
                val deckName = route.args[DECK_NAME_ARG] ?: return null
                ExpressiveDestination.Study(deckId, deckName)
            }
            else -> null
        }
}
