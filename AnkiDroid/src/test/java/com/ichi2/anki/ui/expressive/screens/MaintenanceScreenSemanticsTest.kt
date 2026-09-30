// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.ui.expressive.screens

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.ui.expressive.ExpressiveMaintenanceError
import com.ichi2.anki.ui.expressive.ExpressiveMaintenanceMode
import com.ichi2.anki.ui.expressive.ExpressiveMaintenanceOperation
import com.ichi2.anki.ui.expressive.ExpressiveMaintenanceProgress
import com.ichi2.anki.ui.expressive.ExpressiveMaintenanceUiState
import com.ichi2.anki.ui.expressive.theme.ExpressiveAnkiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

/** TalkBack contract of the Maintenance route: headings, and live regions only where a change must be announced. */
@RunWith(AndroidJUnit4::class)
class MaintenanceScreenSemanticsTest : RobolectricTest() {
    @get:Rule
    val composeRule = createComposeRule()

    private val announced = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)
    private val silent = SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion)

    @Test
    fun `progress announces a stage change once while counters update silently`() {
        val progress = ExpressiveMaintenanceProgress(stage = "Checking cards", current = 3, total = 10)
        show(
            ExpressiveMaintenanceMode.DATABASE,
            ExpressiveMaintenanceUiState.Running(1, ExpressiveMaintenanceOperation.DATABASE_CHECK, progress),
        )

        composeRule.onNode(isHeading() and hasText(ExpressiveMaintenanceMode.DATABASE.title(targetContext))).assertExists()
        composeRule.onNodeWithText("Checking cards").assert(announced)
        composeRule.onNodeWithText(targetContext.getString(R.string.preview_progress_bar_text, 3, 10)).assert(silent)
        composeRule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo(0.3f, 0f..1f))).assertExists()
        composeRule.onNodeWithText(targetContext.getString(R.string.expressive_maintenance_keep_open)).assert(silent)
    }

    @Test
    fun `a database check that reported problems announces a summary`() {
        show(
            ExpressiveMaintenanceMode.DATABASE,
            ExpressiveMaintenanceUiState.Terminal(
                requestId = 1,
                operation = ExpressiveMaintenanceOperation.DATABASE_CHECK,
                succeeded = true,
                messages = listOf("Fixed one card.", "Fixed one note."),
            ),
        )

        val summary = targetContext.resources.getQuantityString(R.plurals.expressive_maintenance_database_problems, 2, 2)
        composeRule.onNodeWithText(summary).assert(announced)
        composeRule.onNodeWithText("Fixed one card.").assert(silent)
    }

    @Test
    fun `a restore refused by a media sync announces why`() {
        show(
            ExpressiveMaintenanceMode.BACKUPS,
            ExpressiveMaintenanceUiState.ConfirmRestore(
                requestId = 1,
                displayName = "backup",
                generation = 1,
                blockedBy = ExpressiveMaintenanceError.MEDIA_SYNC_ACTIVE,
            ),
        )

        composeRule.onNodeWithText(targetContext.getString(R.string.expressive_maintenance_error_media_sync_active)).assert(announced)
    }

    @Test
    fun `a restore that kept media aside announces it with the result`() {
        show(
            ExpressiveMaintenanceMode.BACKUPS,
            ExpressiveMaintenanceUiState.Terminal(1, ExpressiveMaintenanceOperation.RESTORE, succeeded = true, affectedCount = 2),
        )

        composeRule.onNodeWithText(targetContext.getString(R.string.expressive_maintenance_restored)).assert(announced)
        val warning = targetContext.resources.getQuantityString(R.plurals.expressive_maintenance_restore_media_quarantined, 2, 2)
        composeRule.onNodeWithText(warning).assert(announced)
    }

    @Test
    fun `kept restored media can be added again, or deleted only after confirmation`() {
        var merges = 0
        var deletes = 0
        show(
            ExpressiveMaintenanceMode.BACKUPS,
            ExpressiveMaintenanceUiState.Overview(ExpressiveMaintenanceMode.BACKUPS, quarantinedMediaFiles = 3),
            onMerge = { merges++ },
            onDelete = { deletes++ },
        )

        val quarantineTitle = targetContext.getString(R.string.expressive_maintenance_quarantine_title)
        composeRule.onNode(isHeading() and hasText(quarantineTitle)).assertExists()
        composeRule.onNodeWithText(targetContext.getString(R.string.expressive_maintenance_quarantine_merge)).performClick()
        composeRule.onNodeWithText(targetContext.getString(R.string.expressive_maintenance_quarantine_delete)).performClick()
        assertEquals(0, deletes, "deleting asks first")
        composeRule.onNodeWithText(targetContext.getString(R.string.dialog_positive_delete)).performClick()

        assertEquals(1, merges)
        assertEquals(1, deletes)
    }

    private fun show(
        mode: ExpressiveMaintenanceMode,
        state: ExpressiveMaintenanceUiState,
        onMerge: () -> Unit = {},
        onDelete: () -> Unit = {},
    ) {
        composeRule.setContent {
            ExpressiveAnkiTheme(themeMode = "light", dynamicColor = false) {
                MaintenanceScreen(
                    routeMode = mode,
                    state = state,
                    onBack = {},
                    onCreateBackup = {},
                    onPickRestore = {},
                    onPrepareBackupRestore = {},
                    onRequestDeleteBackup = {},
                    onConfirmDeleteBackup = {},
                    onUpdateLimits = {},
                    onConfirmRestore = {},
                    onRequestDatabaseCheck = {},
                    onConfirmDatabaseCheck = {},
                    onFindEmptyCards = {},
                    onDeleteEmptyCards = {},
                    onCheckMedia = {},
                    onTagMissingMedia = {},
                    onTrashUnusedMedia = {},
                    onRestoreMediaTrash = {},
                    onEmptyMediaTrash = {},
                    onRebuildMediaDatabase = {},
                    onMergeQuarantinedMedia = onMerge,
                    onDeleteQuarantinedMedia = onDelete,
                    onDone = {},
                )
            }
        }
        composeRule.waitForIdle()
    }
}
