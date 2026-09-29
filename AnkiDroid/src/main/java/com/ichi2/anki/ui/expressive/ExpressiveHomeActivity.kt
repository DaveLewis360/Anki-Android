// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.ui.expressive

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import anki.collection.OpChanges
import com.ichi2.anki.NoteEditorFragment
import com.ichi2.anki.common.destinations.StatisticsDestination
import com.ichi2.anki.common.ui.TransitionDirection
import com.ichi2.anki.noteeditor.NoteEditorLauncher
import com.ichi2.anki.observability.ChangeManager
import com.ichi2.anki.pages.toIntent
import com.ichi2.anki.sync.MeteredSyncPolicy
import com.ichi2.anki.ui.expressive.theme.ExpressiveAnkiTheme
import com.ichi2.anki.ui.expressive.theme.isExpressiveDark
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Activity-level bridge so reviewer bindings see events owned by focused WebViews and gamepads. */
interface ExpressiveReviewerInputHost {
    fun installReviewerInputHandlers(
        owner: Any,
        keyHandler: (KeyEvent) -> Boolean,
        motionHandler: (MotionEvent) -> Boolean,
    )

    fun clearReviewerInputHandlers(owner: Any)
}

/** Hosts the from-scratch Material 3 Expressive Compose UI. */
class ExpressiveHomeActivity :
    FragmentActivity(),
    ChangeManager.Subscriber,
    ExpressiveReviewerInputHost {
    private val syncViewModel by viewModels<ExpressiveSyncViewModel> { ExpressiveSyncViewModel.factory() }
    private val accountViewModel by viewModels<ExpressiveAccountViewModel> { ExpressiveAccountViewModel.factory() }
    private val navigationViewModel by viewModels<ExpressiveNavigationViewModel> {
        ExpressiveNavigationViewModel.factory(intent)
    }
    private val editorCoordinator by viewModels<ExpressiveEditorCoordinatorViewModel> {
        ExpressiveEditorCoordinatorViewModel.factory()
    }
    private val transferCoordinator by viewModels<ExpressiveTransferCoordinatorViewModel> {
        ExpressiveTransferCoordinatorViewModel.factory()
    }
    private val maintenanceCoordinator by viewModels<ExpressiveMaintenanceCoordinatorViewModel> {
        ExpressiveMaintenanceCoordinatorViewModel.factory(this)
    }
    private val browseViewModel by viewModels<ExpressiveBrowseViewModel> {
        ExpressiveBrowseViewModel.factory()
    }
    private val returnsAuthenticationResult by lazy(LazyThreadSafetyMode.NONE) {
        isAccountResultIntent(intent)
    }
    internal val navigationForTesting: ExpressiveNavigationViewModel
        get() = navigationViewModel
    internal val syncForTesting: ExpressiveSyncViewModel
        get() = syncViewModel
    internal val accountForTesting: ExpressiveAccountViewModel
        get() = accountViewModel
    internal val transferForTesting: ExpressiveTransferCoordinatorViewModel
        get() = transferCoordinator
    internal val maintenanceForTesting: ExpressiveMaintenanceCoordinatorViewModel
        get() = maintenanceCoordinator

    private val statsCollectionRefreshKey = MutableStateFlow(0)
    private val resumeGeneration = MutableStateFlow(0)
    private var hasResumed = false
    private var reviewerInputOwner: Any? = null
    private var reviewerKeyHandler: ((KeyEvent) -> Boolean)? = null
    private var reviewerMotionHandler: ((MotionEvent) -> Boolean)? = null

    override fun installReviewerInputHandlers(
        owner: Any,
        keyHandler: (KeyEvent) -> Boolean,
        motionHandler: (MotionEvent) -> Boolean,
    ) {
        reviewerInputOwner = owner
        reviewerKeyHandler = keyHandler
        reviewerMotionHandler = motionHandler
    }

    override fun clearReviewerInputHandlers(owner: Any) {
        if (reviewerInputOwner !== owner) return
        reviewerInputOwner = null
        reviewerKeyHandler = null
        reviewerMotionHandler = null
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = reviewerKeyHandler?.invoke(event) == true || super.dispatchKeyEvent(event)

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
        reviewerMotionHandler?.invoke(event) == true || super.dispatchGenericMotionEvent(event)

    private val noteEditorLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val imageOcclusionUpdated = result.resultCode == NoteEditorFragment.RESULT_UPDATED_IO_NOTE
            val noteChanged = result.data?.getBooleanExtra(NoteEditorFragment.EXTRA_NOTE_CHANGED, false) == true
            val changed = imageOcclusionUpdated || (result.resultCode == Activity.RESULT_OK && noteChanged)
            val reloadRequired =
                imageOcclusionUpdated ||
                    result.data?.getBooleanExtra(NoteEditorFragment.EXTRA_RELOAD_REQUIRED, false) == true
            val completed = editorCoordinator.complete(changed, reloadRequired)
            if (completed != null && (completed.changed || completed.reloadRequired)) {
                statsCollectionRefreshKey.update { it + 1 }
            }
        }

    private val importPickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val requestId = transferCoordinator.consumeImportResultRequestId()
            if (requestId != null) {
                val data = result.data
                if (result.resultCode != Activity.RESULT_OK || data == null) {
                    transferCoordinator.pickerCancelled(requestId)
                } else {
                    transferCoordinator.stageImport(this, requestId, data)
                }
            }
        }

    private val restorePickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val requestId = maintenanceCoordinator.consumeRestoreResultRequestId()
            if (requestId != null) {
                val data = result.data
                if (result.resultCode != Activity.RESULT_OK || data == null) {
                    maintenanceCoordinator.restorePickerCancelled(requestId)
                } else {
                    maintenanceCoordinator.stageExternalRestore(requestId, data)
                }
            }
        }

    private val backendImportLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val requestId = result.data?.getLongExtra(ExpressiveImportPageActivity.EXTRA_REQUEST_ID, 0L) ?: 0L
            val succeeded =
                result.resultCode == Activity.RESULT_OK &&
                    result.data?.getStringExtra(ExpressiveImportPageActivity.EXTRA_RESULT) ==
                    ExpressiveImportPageActivity.RESULT_IMPORTED
            if (requestId > 0) transferCoordinator.completeBackendImport(this, requestId, succeeded)
        }

    private val exportSaveLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val requestId = transferCoordinator.consumeSaveResultRequestId()
            if (requestId != null) {
                transferCoordinator.saveResult(
                    context = this,
                    requestId = requestId,
                    destination = result.data?.data,
                    succeededResult = result.resultCode == Activity.RESULT_OK,
                )
            }
        }

    init {
        ChangeManager.subscribe(this)
    }

    override fun onResume() {
        super.onResume()
        accountViewModel.refresh()
        if (completeAuthenticationResultIfPossible()) return
        resumeGeneration.update { it + 1 }
        val suppressRefresh = editorCoordinator.consumeResumeRefreshSuppression()
        if (hasResumed && !suppressRefresh) {
            statsCollectionRefreshKey.update { it + 1 }
        } else {
            hasResumed = true
        }
    }

    override fun opExecuted(
        changes: OpChanges,
        handler: Any?,
    ) {
        if (changes.studyQueues || changes.card || changes.note || changes.deck) {
            lifecycleScope.launch {
                statsCollectionRefreshKey.update { it + 1 }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (
                        navigationViewModel.backStack.value.current == ExpressiveDestination.Import ||
                        navigationViewModel.backStack.value.current == ExpressiveDestination.Export
                    ) {
                        handleTransferBack()
                        return
                    }
                    if (navigationViewModel.backStack.value.current is ExpressiveDestination.Maintenance) {
                        handleMaintenanceBack()
                        return
                    }
                    if (navigationViewModel.backStack.value.current == ExpressiveDestination.Account) {
                        handleAccountBack()
                        return
                    }
                    if (navigationViewModel.pop()) return
                    isEnabled = false
                    try {
                        onBackPressedDispatcher.onBackPressed()
                    } finally {
                        isEnabled = true
                    }
                }
            },
        )
        enableEdgeToEdge()
        var initialSettings = ExpressiveSettings.load(this@ExpressiveHomeActivity)
        intent.getStringExtra("palette")?.let { initialSettings = initialSettings.copy(paletteKey = it) }
        if (intent.hasExtra("dynamic")) {
            initialSettings = initialSettings.copy(dynamicColor = intent.getBooleanExtra("dynamic", true))
        }
        if (intent.hasExtra("theme")) {
            initialSettings = initialSettings.copy(themeMode = intent.getStringExtra("theme") ?: "system")
        }
        initialSettings.syncToClassicThemePrefs()
        lifecycleScope.launch {
            editorCoordinator.launches.collect { launch ->
                try {
                    noteEditorLauncher.launch(launch.request.toNoteEditorIntent())
                    editorCoordinator.markLaunchDispatched(launch.requestId)
                } catch (_: Exception) {
                    editorCoordinator.failLaunch(launch.requestId)
                }
            }
        }
        lifecycleScope.launch {
            navigationViewModel.effects.collect { effect ->
                when (effect) {
                    ExpressiveNavigationEffect.OpenDetailedStatistics ->
                        startActivity(StatisticsDestination.toIntent(this@ExpressiveHomeActivity))
                }
            }
        }
        lifecycleScope.launch {
            accountViewModel.effects.collect { effect ->
                when (effect) {
                    ExpressiveAccountEffect.AuthenticationSucceeded -> {
                        if (completeAuthenticationResultIfPossible()) return@collect
                        if (syncViewModel.onLoginResult(succeeded = true)) {
                            navigationViewModel.pop()
                        }
                    }
                }
            }
        }
        lifecycleScope.launch {
            transferCoordinator.launches.collect { launch ->
                when (launch) {
                    is ExpressiveTransferLaunch.OpenImport -> {
                        if (transferCoordinator.markImportLaunchDispatched(launch.requestId)) {
                            runCatching { importPickerLauncher.launch(launch.intent) }
                                .onFailure { transferCoordinator.importLaunchFailed(launch.requestId) }
                        }
                    }
                    is ExpressiveTransferLaunch.BackendImport -> {
                        runCatching { backendImportLauncher.launch(launch.intent) }
                            .onFailure { transferCoordinator.failBackendImportLaunch(this@ExpressiveHomeActivity, launch.requestId) }
                    }
                    is ExpressiveTransferLaunch.SaveExport -> {
                        if (transferCoordinator.markSaveLaunchDispatched(launch.requestId)) {
                            runCatching { exportSaveLauncher.launch(launch.intent) }
                                .onFailure { transferCoordinator.saveLaunchFailed(launch.requestId) }
                        }
                    }
                }
            }
        }
        lifecycleScope.launch {
            maintenanceCoordinator.launches.collect { launch ->
                when (launch) {
                    is ExpressiveMaintenanceLaunch.OpenRestoreDocument -> {
                        if (maintenanceCoordinator.markRestoreLaunchDispatched(launch.requestId)) {
                            runCatching { restorePickerLauncher.launch(launch.intent) }
                                .onFailure { maintenanceCoordinator.restorePickerLaunchFailed(launch.requestId) }
                        }
                    }
                }
            }
        }
        transferCoordinator.cleanupInterruptedArtifacts(this)
        val sync = syncViewModel
        // Captures the retained ViewModel, never this Activity, so recreation cannot leak it.
        maintenanceCoordinator.syncActivity = { sync.state.value.maintenanceSyncActivity() }
        maintenanceCoordinator.cleanupInterruptedArtifacts()
        consumeExternalImportIntent()
        setContent {
            var settings = remember { mutableStateOf(initialSettings) }
            ExpressiveAnkiTheme(
                themeMode = settings.value.themeMode,
                paletteKey = settings.value.paletteKey,
                dynamicColor = settings.value.dynamicColor,
            ) {
                val effectiveDark = isExpressiveDark(settings.value.themeMode)
                val syncState by syncViewModel.state.collectAsStateWithLifecycle()
                val accountState by accountViewModel.state.collectAsStateWithLifecycle()
                val syncRefreshKey by syncViewModel.refreshKey.collectAsStateWithLifecycle()
                val statsRefreshKey by statsCollectionRefreshKey.collectAsStateWithLifecycle()
                val backStack by navigationViewModel.backStack.collectAsStateWithLifecycle()
                val editorResult by editorCoordinator.result.collectAsStateWithLifecycle()
                val transferState by transferCoordinator.state.collectAsStateWithLifecycle()
                val maintenanceState by maintenanceCoordinator.state.collectAsStateWithLifecycle()
                val resumes by resumeGeneration.collectAsStateWithLifecycle()
                LaunchedEffect(effectiveDark) {
                    enableEdgeToEdge(
                        statusBarStyle =
                            if (effectiveDark) {
                                SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                            } else {
                                SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                            },
                        navigationBarStyle =
                            if (effectiveDark) {
                                SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                            } else {
                                SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                            },
                    )
                }
                LaunchedEffect(syncViewModel) {
                    syncViewModel.effects.collect { effect ->
                        when (effect) {
                            ExpressiveSyncEffect.OpenAccount -> {
                                accountViewModel.refresh()
                                navigationViewModel.openAccount()
                            }
                        }
                    }
                }
                LaunchedEffect(syncState) {
                    if (syncState is ExpressiveSyncUiState.AwaitingMeteredConfirmation) {
                        MeteredSyncPolicy.confirmThen(
                            onDialogShown = {},
                            onCancel = syncViewModel::onMeteredSyncCancelled,
                            onConfirm = syncViewModel::onMeteredSyncConfirmed,
                        )
                    }
                }
                LaunchedEffect(backStack.current, maintenanceState) {
                    val destination = backStack.current as? ExpressiveDestination.Maintenance
                    if (destination != null && maintenanceState is ExpressiveMaintenanceUiState.Idle) {
                        maintenanceCoordinator.open(destination.mode)
                    }
                }
                LaunchedEffect(syncState.isCollectionBlocking, transferState.isCollectionBlocking, resumes) {
                    // Classic parity: an interval-aware backup is attempted whenever Home becomes
                    // usable; the backend's minimum interval decides whether one is actually due.
                    if (shouldAttemptAutomaticBackup(resumes, syncState, transferState.isCollectionBlocking)) {
                        maintenanceCoordinator.maybeStartAutomaticBackup()
                    }
                }
                ExpressiveApp(
                    settings = settings.value,
                    backStack = backStack,
                    onSelectRoot = navigationViewModel::selectRoot,
                    onOpenSettings = navigationViewModel::openSettings,
                    onOpenAccount = {
                        accountViewModel.refresh()
                        navigationViewModel.openAccount()
                    },
                    onOpenStudy = { destination ->
                        navigationViewModel.openStudy(destination.deckId, destination.deckName)
                    },
                    onOpenImport = ::openImport,
                    onOpenExport = ::openExport,
                    onOpenMaintenance = ::openMaintenance,
                    onBack = { navigationViewModel.pop() },
                    onAccountBack = ::handleAccountBack,
                    browseViewModel = browseViewModel,
                    accountState = accountState,
                    onAccountUsernameChanged = accountViewModel::onUsernameChanged,
                    onAccountPasswordChanged = accountViewModel::onPasswordChanged,
                    onAccountSignIn = accountViewModel::login,
                    onAccountRequestForget = accountViewModel::requestForget,
                    onAccountDismissForget = accountViewModel::dismissForget,
                    onAccountConfirmForget = accountViewModel::confirmForget,
                    onOpenEditor = { request -> editorCoordinator.open(request) },
                    editorResult = editorResult,
                    onEditorResultConsumed = editorCoordinator::consumeResult,
                    syncState = syncState,
                    transferState = transferState,
                    onTransferBack = ::handleTransferBack,
                    onConfirmImport = { transferCoordinator.confirmImport(this@ExpressiveHomeActivity) },
                    onExportOptions = transferCoordinator::updateExportOptions,
                    onConfirmExport = { transferCoordinator.confirmExport(this@ExpressiveHomeActivity) },
                    onSaveExport = transferCoordinator::requestSave,
                    onShareExport = ::shareReadyExport,
                    onCancelTransfer = { transferCoordinator.cancel(this@ExpressiveHomeActivity) },
                    onTransferDone = {
                        transferCoordinator.dismissTerminal(this@ExpressiveHomeActivity)
                        navigationViewModel.pop()
                    },
                    maintenanceState = maintenanceState,
                    onMaintenanceBack = ::handleMaintenanceBack,
                    onCreateBackup = maintenanceCoordinator::createBackup,
                    onPickRestore = maintenanceCoordinator::startRestorePicker,
                    onPrepareBackupRestore = maintenanceCoordinator::prepareLocalRestore,
                    onRequestDeleteBackup = maintenanceCoordinator::requestDeleteBackup,
                    onConfirmDeleteBackup = maintenanceCoordinator::confirmDeleteBackup,
                    onUpdateBackupLimits = maintenanceCoordinator::updateBackupLimits,
                    onConfirmRestore = maintenanceCoordinator::confirmRestore,
                    onRequestDatabaseCheck = maintenanceCoordinator::requestDatabaseCheck,
                    onConfirmDatabaseCheck = maintenanceCoordinator::confirmDatabaseCheck,
                    onFindEmptyCards = maintenanceCoordinator::findEmptyCards,
                    onDeleteEmptyCards = maintenanceCoordinator::deleteEmptyCards,
                    onCheckMedia = maintenanceCoordinator::checkMedia,
                    onTagMissingMedia = maintenanceCoordinator::tagMissingMedia,
                    onTrashUnusedMedia = maintenanceCoordinator::trashUnusedMedia,
                    onRestoreMediaTrash = maintenanceCoordinator::restoreMediaTrash,
                    onEmptyMediaTrash = maintenanceCoordinator::emptyMediaTrash,
                    onRebuildMediaDatabase = maintenanceCoordinator::rebuildMediaDatabase,
                    onMergeQuarantinedMedia = maintenanceCoordinator::mergeQuarantinedMedia,
                    onDeleteQuarantinedMedia = maintenanceCoordinator::deleteQuarantinedMedia,
                    onMaintenanceDone = {
                        val mode = (navigationViewModel.backStack.value.current as? ExpressiveDestination.Maintenance)?.mode
                        if (mode != null) maintenanceCoordinator.returnToOverview(mode)
                    },
                    syncRefreshKey = syncRefreshKey,
                    homeRefreshKey = syncRefreshKey + statsRefreshKey,
                    browseRefreshKey = syncRefreshKey + statsRefreshKey,
                    statsRefreshKey = syncRefreshKey + statsRefreshKey,
                    onSettingsChanged = {
                        it.save(this)
                        it.syncToClassicThemePrefs()
                        settings.value = it
                    },
                    onSync = ::requestSync,
                    onKeepLocal = syncViewModel::onKeepLocalSelected,
                    onKeepRemote = syncViewModel::onKeepRemoteSelected,
                    onCancelFullSyncChoice = syncViewModel::onFullSyncChoiceCancelled,
                    onConfirmFullSync = syncViewModel::onFullSyncConfirmed,
                    onCancelFullSyncConfirmation = syncViewModel::onFullSyncConfirmationCancelled,
                )
            }
        }
    }

    private fun handleAccountBack() {
        if (!accountViewModel.cancelOperation()) return
        if (returnsAuthenticationResult) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }
        syncViewModel.onLoginResult(succeeded = false)
        navigationViewModel.pop()
    }

    private fun completeAuthenticationResultIfPossible(): Boolean {
        if (!returnsAuthenticationResult || accountViewModel.state.value.account == null) return false
        setResult(Activity.RESULT_OK)
        finish()
        return true
    }

    private fun ExpressiveEditorRequest.toNoteEditorIntent(): Intent {
        val launcher =
            when (this) {
                is ExpressiveEditorRequest.Add ->
                    when (origin) {
                        ExpressiveEditorOrigin.HOME -> NoteEditorLauncher.AddNote(deckId)
                        ExpressiveEditorOrigin.BROWSE ->
                            NoteEditorLauncher.AddNoteFromCardBrowser(
                                searchTerms = searchTerms.orEmpty(),
                                deckId = deckId,
                            )
                        ExpressiveEditorOrigin.STUDY ->
                            NoteEditorLauncher.AddNoteFromReviewer(TransitionDirection.FADE)
                    }
                is ExpressiveEditorRequest.Edit ->
                    NoteEditorLauncher.EditSelection(
                        cardIds = listOf(cardId),
                        animation = TransitionDirection.FADE,
                    )
            }
        return launcher.toIntent(this@ExpressiveHomeActivity)
    }

    private fun openImport() {
        if (syncViewModel.state.value.isCollectionBlocking || maintenanceCoordinator.state.value.isCollectionBlocking) return
        if (transferCoordinator.startImport()) navigationViewModel.openImport()
    }

    private fun openExport(scope: ExpressiveExportScope) {
        if (syncViewModel.state.value.isCollectionBlocking || maintenanceCoordinator.state.value.isCollectionBlocking) return
        if (transferCoordinator.startExport(scope)) navigationViewModel.openExport()
    }

    private fun openMaintenance(mode: ExpressiveMaintenanceMode) {
        if (syncViewModel.state.value.isCollectionBlocking || transferCoordinator.state.value.isCollectionBlocking) return
        if (maintenanceCoordinator.open(mode)) navigationViewModel.openMaintenance(mode)
    }

    private fun handleMaintenanceBack() {
        val mode = (navigationViewModel.backStack.value.current as? ExpressiveDestination.Maintenance)?.mode ?: return
        if (maintenanceCoordinator.back(mode)) navigationViewModel.pop()
    }

    private fun requestSync() {
        if (transferCoordinator.state.value.isCollectionBlocking || maintenanceCoordinator.state.value.blocksSync) return
        syncViewModel.onSyncClick()
    }

    internal fun requestSyncForTesting() = requestSync()

    private fun handleTransferBack() {
        if (transferCoordinator.cancel(this)) navigationViewModel.pop()
    }

    private fun consumeExternalImportIntent() {
        if (!intent.getBooleanExtra(EXTRA_EXTERNAL_IMPORT, false)) return
        val externalRequestId = intent.getStringExtra(EXTRA_EXTERNAL_REQUEST_ID) ?: return
        val source = Intent(intent)
        intent.removeExtra(EXTRA_EXTERNAL_IMPORT)
        intent.removeExtra(EXTRA_EXTERNAL_REQUEST_ID)
        intent.removeExtra(Intent.EXTRA_STREAM)
        intent.data = null
        intent.clipData = null
        intent.type = null
        val requestId = transferCoordinator.startExternalImport(this, externalRequestId) ?: return
        navigationViewModel.openImport()
        transferCoordinator.stageImport(this, requestId, source)
    }

    private fun shareReadyExport() {
        val ready = transferCoordinator.state.value as? ExpressiveTransferUiState.Ready ?: return
        val file = ExpressiveTransferFiles.ownedExportFile(this, ready.artifactToken) ?: return
        val uri = runCatching { FileProvider.getUriForFile(this, "$packageName.apkgfileprovider", file) }.getOrNull() ?: return
        val send = ExpressiveTransferFiles.createShareIntent(this, ready, uri)
        val chooser = Intent.createChooser(send, getString(com.ichi2.anki.R.string.expressive_export_share))
        if (send.resolveActivity(packageManager) == null) {
            transferCoordinator.requestSave()
        } else {
            startActivity(chooser)
        }
    }

    companion object {
        private const val EXTRA_RETURN_AUTHENTICATION_RESULT =
            "io.github.davelewis360.ankidroid.m3expressive.extra.RETURN_AUTHENTICATION_RESULT"
        internal const val EXTRA_EXTERNAL_IMPORT =
            "io.github.davelewis360.ankidroid.m3expressive.extra.EXTERNAL_IMPORT"
        internal const val EXTRA_EXTERNAL_REQUEST_ID =
            "io.github.davelewis360.ankidroid.m3expressive.extra.EXTERNAL_REQUEST_ID"

        fun getExternalImportIntent(
            context: Context,
            source: Intent,
        ): Intent =
            ExpressiveNavigationIntent
                .putInitialDestination(
                    Intent(context, ExpressiveHomeActivity::class.java),
                    ExpressiveDestination.Import,
                ).apply {
                    putExtra(EXTRA_EXTERNAL_IMPORT, true)
                    source
                        .getStringExtra(EXTRA_EXTERNAL_REQUEST_ID)
                        ?.takeIf(ExpressiveTransferCoordinatorViewModel::isExternalRequestId)
                        ?.let { requestId ->
                            putExtra(EXTRA_EXTERNAL_REQUEST_ID, requestId)
                        }
                    action = source.action
                    setDataAndType(source.data, source.type)
                    clipData = source.clipData
                    IntentCompat.getParcelableExtra(source, Intent.EXTRA_STREAM, Uri::class.java)?.let {
                        putExtra(Intent.EXTRA_STREAM, it)
                    }
                    addFlags(source.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

        fun getAccountResultIntent(context: Context): Intent =
            ExpressiveNavigationIntent
                .putInitialDestination(
                    Intent(context, ExpressiveHomeActivity::class.java),
                    ExpressiveDestination.Account,
                ).putExtra(EXTRA_RETURN_AUTHENTICATION_RESULT, true)

        internal fun isAccountResultIntent(intent: Intent): Boolean =
            intent.getBooleanExtra(EXTRA_RETURN_AUTHENTICATION_RESULT, false) &&
                ExpressiveNavigationIntent.initialDestination(intent) == ExpressiveDestination.Account
    }
}
