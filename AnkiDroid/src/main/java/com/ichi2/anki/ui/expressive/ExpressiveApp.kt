// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.ui.expressive

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import com.ichi2.anki.common.destinations.StatisticsDestination
import com.ichi2.anki.pages.toIntent
import com.ichi2.anki.ui.expressive.components.ExpressiveBottomBar
import com.ichi2.anki.ui.expressive.screens.AccountScreen
import com.ichi2.anki.ui.expressive.screens.BrowseScreen
import com.ichi2.anki.ui.expressive.screens.HomeScreen
import com.ichi2.anki.ui.expressive.screens.MaintenanceScreen
import com.ichi2.anki.ui.expressive.screens.MoreScreen
import com.ichi2.anki.ui.expressive.screens.SettingsScreen
import com.ichi2.anki.ui.expressive.screens.StatsScreen
import com.ichi2.anki.ui.expressive.screens.StudyScreen
import com.ichi2.anki.ui.expressive.screens.TransferScreen

/**
 * Root composable: tabbed shell with a full-screen study overlay.
 *
 * Tabs stay composed while collection access is available (state preserved: scroll, deck expansion,
 * search). During an active collection sync, data-backed tabs leave composition so no stale collection
 * operation can queue behind the worker; the durable More screen remains available for cancellation.
 */
@Composable
fun ExpressiveApp(
    settings: ExpressiveSettings,
    onSettingsChanged: (ExpressiveSettings) -> Unit,
    backStack: ExpressiveBackStack,
    onSelectRoot: (ExpressiveDestination.Root) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenStudy: (ExpressiveDestination.Study) -> Unit,
    onOpenImport: () -> Unit,
    onOpenExport: (ExpressiveExportScope) -> Unit,
    onBack: () -> Unit,
    onAccountBack: () -> Unit,
    browseViewModel: ExpressiveBrowseViewModel,
    accountState: ExpressiveAccountUiState,
    onAccountUsernameChanged: (String) -> Unit,
    onAccountPasswordChanged: (String) -> Unit,
    onAccountSignIn: () -> Unit,
    onAccountRequestForget: () -> Unit,
    onAccountDismissForget: () -> Unit,
    onAccountConfirmForget: () -> Unit,
    onOpenEditor: (ExpressiveEditorRequest) -> Unit = {},
    editorResult: ExpressiveEditorResult? = null,
    onEditorResultConsumed: (Long) -> Unit = {},
    syncState: ExpressiveSyncUiState = ExpressiveSyncUiState.Idle,
    transferState: ExpressiveTransferUiState = ExpressiveTransferUiState.Idle,
    onTransferBack: () -> Unit = {},
    onConfirmImport: () -> Unit = {},
    onExportOptions: (ExpressiveExportOptions) -> Unit = {},
    onConfirmExport: () -> Unit = {},
    onSaveExport: () -> Unit = {},
    onShareExport: () -> Unit = {},
    onCancelTransfer: () -> Unit = {},
    onTransferDone: () -> Unit = {},
    maintenanceState: ExpressiveMaintenanceUiState = ExpressiveMaintenanceUiState.Idle,
    onOpenMaintenance: (ExpressiveMaintenanceMode) -> Unit = {},
    onMaintenanceBack: () -> Unit = {},
    onCreateBackup: () -> Unit = {},
    onPickRestore: () -> Unit = {},
    onPrepareBackupRestore: (ExpressiveBackupEntry) -> Unit = {},
    onRequestDeleteBackup: (ExpressiveBackupEntry) -> Unit = {},
    onConfirmDeleteBackup: () -> Unit = {},
    onUpdateBackupLimits: (ExpressiveBackupLimits) -> Unit = {},
    onConfirmRestore: () -> Unit = {},
    onRequestDatabaseCheck: () -> Unit = {},
    onConfirmDatabaseCheck: () -> Unit = {},
    onFindEmptyCards: () -> Unit = {},
    onDeleteEmptyCards: (Boolean) -> Unit = {},
    onCheckMedia: () -> Unit = {},
    onTagMissingMedia: () -> Unit = {},
    onTrashUnusedMedia: () -> Unit = {},
    onRestoreMediaTrash: () -> Unit = {},
    onEmptyMediaTrash: () -> Unit = {},
    onRebuildMediaDatabase: () -> Unit = {},
    onMergeQuarantinedMedia: () -> Unit = {},
    onDeleteQuarantinedMedia: () -> Unit = {},
    onMaintenanceDone: () -> Unit = {},
    syncRefreshKey: Int = 0,
    homeRefreshKey: Int = syncRefreshKey,
    browseRefreshKey: Int = syncRefreshKey,
    statsRefreshKey: Int = syncRefreshKey,
    onSync: () -> Unit = {},
    onKeepLocal: () -> Unit = {},
    onKeepRemote: () -> Unit = {},
    onCancelFullSyncChoice: () -> Unit = {},
    onConfirmFullSync: () -> Unit = {},
    onCancelFullSyncConfirmation: () -> Unit = {},
) {
    var homeRefresh by remember { mutableIntStateOf(0) }
    var statsSessionRefresh by remember { mutableIntStateOf(0) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val transferOrMaintenanceBlocking = transferState.isCollectionBlocking || maintenanceState.isCollectionBlocking
    val collectionBlocking = syncState.isCollectionBlocking || transferOrMaintenanceBlocking
    val effectiveDestination = effectiveExpressiveDestination(backStack, syncState.isCollectionBlocking)
    val visibleTab =
        (effectiveDestination as? ExpressiveDestination.Root)?.tabIndex
            ?: backStack.root.tabIndex
    val showSettings = effectiveDestination == ExpressiveDestination.Settings
    val showAccount = effectiveDestination == ExpressiveDestination.Account
    val showTransfer =
        effectiveDestination == ExpressiveDestination.Import || effectiveDestination == ExpressiveDestination.Export
    val maintenanceDestination = effectiveDestination as? ExpressiveDestination.Maintenance
    val studyDestination = effectiveDestination as? ExpressiveDestination.Study
    LaunchedEffect(editorResult?.requestId, studyDestination) {
        val result = editorResult ?: return@LaunchedEffect
        when (result.origin) {
            ExpressiveEditorOrigin.HOME -> onEditorResultConsumed(result.requestId)
            ExpressiveEditorOrigin.BROWSE -> {
                browseViewModel.onEditorResult(result)
                onEditorResultConsumed(result.requestId)
            }
            ExpressiveEditorOrigin.STUDY -> if (studyDestination == null) onEditorResultConsumed(result.requestId)
        }
    }
    val closeStudy = {
        if (studyDestination != null) {
            onBack()
            homeRefresh++
            statsSessionRefresh++
        }
    }
    BackHandler(enabled = showSettings, onBack = onBack)
    BackHandler(enabled = showAccount, onBack = onAccountBack)
    BackHandler(enabled = showTransfer, onBack = onTransferBack)
    BackHandler(enabled = maintenanceDestination != null, onBack = onMaintenanceBack)
    BackHandler(enabled = studyDestination != null, onBack = closeStudy)

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .clipToBounds(),
            ) {
                if (!collectionBlocking) {
                    TabLayer(tabIndex = 0, currentTab = visibleTab) {
                        HomeScreen(
                            onDeckClick = { did, name ->
                                onOpenStudy(ExpressiveDestination.Study(deckId = did, deckName = name))
                            },
                            onAddCard = { deckId ->
                                onOpenEditor(
                                    ExpressiveEditorRequest.Add(
                                        origin = ExpressiveEditorOrigin.HOME,
                                        deckId = deckId,
                                    ),
                                )
                            },
                            onDeckOptions = { did, filtered ->
                                context.startActivity(
                                    com.ichi2.anki.common.destinations
                                        .DeckOptionsDestination(
                                            deckId = did,
                                            isFiltered = filtered,
                                        ).toIntent(context),
                                )
                            },
                            onImport = onOpenImport,
                            onMaintenance = onOpenMaintenance,
                            onExportDeck = { deckId, deckName ->
                                onOpenExport(ExpressiveExportScope.Deck(deckId, deckName))
                            },
                            refreshKey = homeRefresh + homeRefreshKey,
                        )
                    }
                    TabLayer(tabIndex = 1, currentTab = visibleTab) {
                        BrowseScreen(
                            viewModel = browseViewModel,
                            refreshKey = browseRefreshKey,
                            backHandlingEnabled =
                                visibleTab == 1 && studyDestination == null && !showSettings && !showAccount,
                            onAddNote = { searchTerms, deckId ->
                                onOpenEditor(
                                    ExpressiveEditorRequest.Add(
                                        origin = ExpressiveEditorOrigin.BROWSE,
                                        deckId = deckId,
                                        searchTerms = searchTerms,
                                    ),
                                )
                            },
                            onEditCard = { cardId ->
                                onOpenEditor(ExpressiveEditorRequest.Edit(ExpressiveEditorOrigin.BROWSE, cardId))
                            },
                            onExportSelection = { selection ->
                                runCatching { ExpressiveTransferFiles.writeSelectionToken(context, selection) }
                                    .getOrNull()
                                    ?.let(onOpenExport)
                            },
                        )
                    }
                    TabLayer(tabIndex = 2, currentTab = visibleTab) {
                        StatsScreen(
                            refreshKey = statsRefreshKey + statsSessionRefresh,
                            visible = visibleTab == 2 && studyDestination == null,
                            onOpenDetailedStatistics = {
                                context.startActivity(StatisticsDestination.toIntent(context))
                            },
                        )
                    }
                }
                TabLayer(tabIndex = 3, currentTab = visibleTab) {
                    MoreScreen(
                        onOpenSettings = onOpenSettings,
                        onOpenAccount = onOpenAccount,
                        onImport = onOpenImport,
                        onExport = { onOpenExport(ExpressiveExportScope.WholeCollection) },
                        onMaintenance = onOpenMaintenance,
                        externalCollectionBlocking = transferOrMaintenanceBlocking,
                        accountState = accountState,
                        syncState = syncState,
                        onSync = onSync,
                        onKeepLocal = onKeepLocal,
                        onKeepRemote = onKeepRemote,
                        onCancelFullSyncChoice = onCancelFullSyncChoice,
                        onConfirmFullSync = onConfirmFullSync,
                        onCancelFullSyncConfirmation = onCancelFullSyncConfirmation,
                    )
                }
            }
            ExpressiveBottomBar(
                selectedIndex = visibleTab,
                onSelect = { index ->
                    if (!collectionBlocking) onSelectRoot(expressiveRootForTab(index))
                },
            )
        }

        // Settings overlay slides up over everything
        AnimatedVisibility(
            visible = showSettings,
            enter = slideInVertically(tween(280)) { it } + fadeIn(tween(220)),
            exit = slideOutVertically(tween(220)) { it } + fadeOut(tween(160)),
        ) {
            Surface(color = MaterialTheme.colorScheme.background) {
                SettingsScreen(
                    settings = settings,
                    onChange = onSettingsChanged,
                    onBack = onBack,
                )
            }
        }

        AnimatedVisibility(
            visible = showAccount,
            enter = slideInVertically(tween(280)) { it } + fadeIn(tween(220)),
            exit = slideOutVertically(tween(220)) { it } + fadeOut(tween(160)),
        ) {
            Surface(color = MaterialTheme.colorScheme.background) {
                AccountScreen(
                    state = accountState,
                    onUsernameChanged = onAccountUsernameChanged,
                    onPasswordChanged = onAccountPasswordChanged,
                    onSignIn = onAccountSignIn,
                    onRequestForget = onAccountRequestForget,
                    onDismissForget = onAccountDismissForget,
                    onConfirmForget = onAccountConfirmForget,
                    onBack = onAccountBack,
                )
            }
        }

        AnimatedVisibility(
            visible = showTransfer,
            enter = slideInVertically(tween(280)) { it } + fadeIn(tween(220)),
            exit = slideOutVertically(tween(220)) { it } + fadeOut(tween(160)),
        ) {
            TransferScreen(
                state = transferState,
                onBack = onTransferBack,
                onConfirmImport = onConfirmImport,
                onExportOptions = onExportOptions,
                onConfirmExport = onConfirmExport,
                onSave = onSaveExport,
                onShare = onShareExport,
                onCancel = onCancelTransfer,
                onDone = onTransferDone,
            )
        }

        AnimatedVisibility(
            visible = maintenanceDestination != null,
            enter = slideInVertically(tween(280)) { it } + fadeIn(tween(220)),
            exit = slideOutVertically(tween(220)) { it } + fadeOut(tween(160)),
        ) {
            maintenanceDestination?.let { destination ->
                MaintenanceScreen(
                    routeMode = destination.mode,
                    state = maintenanceState,
                    onBack = onMaintenanceBack,
                    onCreateBackup = onCreateBackup,
                    onPickRestore = onPickRestore,
                    onPrepareBackupRestore = onPrepareBackupRestore,
                    onRequestDeleteBackup = onRequestDeleteBackup,
                    onConfirmDeleteBackup = onConfirmDeleteBackup,
                    onUpdateLimits = onUpdateBackupLimits,
                    onConfirmRestore = onConfirmRestore,
                    onRequestDatabaseCheck = onRequestDatabaseCheck,
                    onConfirmDatabaseCheck = onConfirmDatabaseCheck,
                    onFindEmptyCards = onFindEmptyCards,
                    onDeleteEmptyCards = onDeleteEmptyCards,
                    onCheckMedia = onCheckMedia,
                    onTagMissingMedia = onTagMissingMedia,
                    onTrashUnusedMedia = onTrashUnusedMedia,
                    onRestoreMediaTrash = onRestoreMediaTrash,
                    onEmptyMediaTrash = onEmptyMediaTrash,
                    onRebuildMediaDatabase = onRebuildMediaDatabase,
                    onMergeQuarantinedMedia = onMergeQuarantinedMedia,
                    onDeleteQuarantinedMedia = onDeleteQuarantinedMedia,
                    onDone = onMaintenanceDone,
                )
            }
        }

        // Study overlay slides over everything — opaque, above the nav bar
        AnimatedVisibility(
            visible = studyDestination != null,
            enter = scaleIn(tween(260), initialScale = 0.94f) + fadeIn(tween(200)),
            exit = fadeOut(tween(180)),
        ) {
            studyDestination?.let { destination ->
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    StudyScreen(
                        deckId = destination.deckId,
                        deckName = destination.deckName,
                        gestures = settings,
                        onClose = closeStudy,
                        onAddNote = {
                            onOpenEditor(ExpressiveEditorRequest.Add(ExpressiveEditorOrigin.STUDY))
                        },
                        onEditCard = { cardId ->
                            onOpenEditor(ExpressiveEditorRequest.Edit(ExpressiveEditorOrigin.STUDY, cardId))
                        },
                        onDeckOptions = { did, filtered ->
                            context.startActivity(
                                com.ichi2.anki.common.destinations
                                    .DeckOptionsDestination(
                                        deckId = did,
                                        isFiltered = filtered,
                                    ).toIntent(context),
                            )
                        },
                        onOpenStudy = { did, name ->
                            closeStudy()
                            onOpenStudy(ExpressiveDestination.Study(deckId = did, deckName = name))
                        },
                        editorResult = editorResult,
                        onEditorResultConsumed = onEditorResultConsumed,
                    )
                }
            }
        }
    }
}

/** One always-composed tab page; fades and shifts instead of being recreated. */
@Composable
private fun TabLayer(
    tabIndex: Int,
    currentTab: Int,
    content: @Composable () -> Unit,
) {
    val selected = tabIndex == currentTab
    val transition = updateTransition(targetState = selected, label = "tab$tabIndex")
    val alpha by transition.animateFloat(
        transitionSpec = { tween(if (targetState) 220 else 150) },
        label = "alpha$tabIndex",
    ) { if (it) 1f else 0f }
    val shiftFraction by transition.animateFloat(
        transitionSpec = { tween(240) },
        label = "shift$tabIndex",
    ) { visible ->
        if (visible) 0f else ((tabIndex - currentTab).coerceIn(-1, 1)).toFloat()
    }
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                this.alpha = alpha
                translationX = shiftFraction * size.width * 0.12f
            }.zIndex(if (selected) 1f else 0f),
    ) {
        content()
    }
}
