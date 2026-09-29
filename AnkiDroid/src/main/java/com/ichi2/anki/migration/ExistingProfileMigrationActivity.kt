// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.migration

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.ichi2.anki.DeckPicker
import com.ichi2.anki.common.storage.CollectionHelper
import com.ichi2.anki.startup.getLegacyAnkiDroidDirectory
import com.ichi2.anki.ui.expressive.ExpressiveSettings
import com.ichi2.anki.ui.expressive.theme.ExpressiveAnkiTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** First-run UI for a cold, source-preserving copy from a readable Classic profile. */
class ExistingProfileMigrationActivity : FragmentActivity() {
    private lateinit var source: File
    private lateinit var target: File
    private var recoveryOnly = false
    private var uiState by mutableStateOf<MigrationUiState>(MigrationUiState.Ready)

    private val storagePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startMigration()
            } else {
                uiState =
                    MigrationUiState.Failed(
                        "Read access was not granted. Classic data was not changed; you can retry or start empty.",
                    )
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (uiState !is MigrationUiState.Working) finish()
                }
            },
        )
        enableEdgeToEdge()
        source = getLegacyAnkiDroidDirectory()
        target = CollectionHelper.getCurrentAnkiDroidDirectory(this)
        recoveryOnly = intent.getBooleanExtra(EXTRA_RECOVERY_ONLY, false)
        val settings = ExpressiveSettings.load(this)
        setContent {
            ExpressiveAnkiTheme(
                themeMode = settings.themeMode,
                paletteKey = settings.paletteKey,
                dynamicColor = settings.dynamicColor,
            ) {
                MigrationContent(
                    source = source,
                    target = target,
                    state = uiState,
                    recoveryOnly = recoveryOnly,
                    onCopy = ::requestMigration,
                    onRetryRecovery = ::recoverAndContinue,
                    onCancel = ::finish,
                )
            }
        }
        if (recoveryOnly) recoverAndContinue()
    }

    private fun requestMigration() {
        if (uiState is MigrationUiState.Working) return
        val needsLegacyReadPermission =
            Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
                PackageManager.PERMISSION_GRANTED
        if (needsLegacyReadPermission) {
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            startMigration()
        }
    }

    private fun startMigration() {
        uiState = MigrationUiState.Working("Copying and validating Classic data…")
        lifecycleScope.launch {
            try {
                val summary = ExistingProfileMigrationService(this@ExistingProfileMigrationActivity).migrate(source, target)
                uiState =
                    MigrationUiState.Succeeded(
                        "Copied ${summary.copiedFiles} files (${summary.copiedBytes} bytes). Classic data was not changed.",
                    )
                setResult(Activity.RESULT_OK)
                finish()
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                uiState =
                    MigrationUiState.Failed(
                        failure.message ?: "Classic data could not be copied safely. Nothing was removed from the source.",
                    )
            }
        }
    }

    private fun recoverAndContinue() {
        if (uiState is MigrationUiState.Working) return
        uiState = MigrationUiState.Working("Recovering an interrupted migration…")
        lifecycleScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    ExistingProfileMigrationService.recoverInterruptedMigration(
                        this@ExistingProfileMigrationActivity,
                        target,
                    )
                }
            when (result) {
                ExistingProfileRecoveryResult.NoAction,
                ExistingProfileRecoveryResult.Recovered,
                -> continueToDeckPicker()
                is ExistingProfileRecoveryResult.Blocked -> uiState = MigrationUiState.Failed(result.reason)
            }
        }
    }

    private fun continueToDeckPicker() {
        val deckPicker =
            DeckPicker.getIntent(
                this,
                postStartupDestination = DeckPicker.postStartupDestination(intent),
            )
        deckPicker.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(deckPicker)
        finish()
    }

    companion object {
        private const val EXTRA_RECOVERY_ONLY =
            "io.github.davelewis360.ankidroid.m3expressive.extra.MIGRATION_RECOVERY_ONLY"

        fun getIntent(
            context: Context,
            recoveryOnly: Boolean = false,
        ): Intent =
            Intent(context, ExistingProfileMigrationActivity::class.java).apply {
                putExtra(EXTRA_RECOVERY_ONLY, recoveryOnly)
            }
    }
}

private sealed interface MigrationUiState {
    data object Ready : MigrationUiState

    data class Working(
        val message: String,
    ) : MigrationUiState

    data class Succeeded(
        val message: String,
    ) : MigrationUiState

    data class Failed(
        val message: String,
    ) : MigrationUiState
}

@Composable
private fun MigrationContent(
    source: File,
    target: File,
    state: MigrationUiState,
    recoveryOnly: Boolean,
    onCopy: () -> Unit,
    onRetryRecovery: () -> Unit,
    onCancel: () -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 48.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                if (recoveryOnly) "Recover migration" else "Copy Classic AnkiDroid data",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "Your Classic profile is copied into this app's private collection folder. " +
                    "The source is never moved, edited, or deleted. Fully close Classic AnkiDroid before continuing.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(18.dp))
            Text("Source: ${source.absolutePath}", style = MaterialTheme.typography.bodySmall)
            Text("Target: ${target.absolutePath}", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(24.dp))
            when (state) {
                MigrationUiState.Ready -> Unit
                is MigrationUiState.Working ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator()
                        Text(state.message)
                    }
                is MigrationUiState.Succeeded -> Text(state.message, color = MaterialTheme.colorScheme.primary)
                is MigrationUiState.Failed -> Text(state.message, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    enabled = state !is MigrationUiState.Working,
                    onClick = if (recoveryOnly) onRetryRecovery else onCopy,
                ) {
                    Text(if (recoveryOnly) "Retry recovery" else "Copy data")
                }
                OutlinedButton(enabled = state !is MigrationUiState.Working, onClick = onCancel) {
                    Text("Cancel")
                }
            }
        }
    }
}
