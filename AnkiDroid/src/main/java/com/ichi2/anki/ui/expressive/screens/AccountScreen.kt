// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.ui.expressive.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.ichi2.anki.CollectionManager.TR
import com.ichi2.anki.R
import com.ichi2.anki.ui.expressive.ExpressiveAccountError
import com.ichi2.anki.ui.expressive.ExpressiveAccountUiState
import com.ichi2.anki.ui.internationalization.SentenceCase

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AccountScreen(
    state: ExpressiveAccountUiState,
    onUsernameChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onSignIn: () -> Unit,
    onRequestForget: () -> Unit,
    onDismissForget: () -> Unit,
    onConfirmForget: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(with(LocalContext.current) { SentenceCase.ankiWebAccount }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_baseline_arrow_back_24),
                            contentDescription = stringResource(R.string.previewer_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        if (state.account == null) {
            SignedOutAccountContent(
                state = state,
                onUsernameChanged = onUsernameChanged,
                onPasswordChanged = onPasswordChanged,
                onSignIn = onSignIn,
                modifier = Modifier.padding(padding),
            )
        } else {
            SignedInAccountContent(
                state = state,
                onRequestForget = onRequestForget,
                modifier = Modifier.padding(padding),
            )
        }
    }

    if (state.confirmForget) {
        AlertDialog(
            onDismissRequest = onDismissForget,
            title = { Text(stringResource(R.string.expressive_account_forget_title)) },
            text = { Text(stringResource(R.string.expressive_account_forget_message)) },
            confirmButton = {
                TextButton(onClick = onConfirmForget) {
                    Text(with(LocalContext.current) { SentenceCase.logOut })
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissForget) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            },
        )
    }
}

@Composable
private fun SignedOutAccountContent(
    state: ExpressiveAccountUiState,
    onUsernameChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val passwordFocus = remember { FocusRequester() }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.expressive_account_sign_in_heading),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = stringResource(R.string.expressive_account_sign_in_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = state.username,
            onValueChange = onUsernameChanged,
            enabled = !state.busy,
            isError = state.usernameMissing,
            label = { Text(stringResource(R.string.username)) },
            supportingText =
                if (state.usernameMissing) {
                    { Text(stringResource(R.string.invalid_email)) }
                } else {
                    null
                },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { passwordFocus.requestFocus() }),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.password,
            onValueChange = onPasswordChanged,
            enabled = !state.busy,
            isError = state.passwordMissing,
            label = { Text(stringResource(R.string.password)) },
            supportingText =
                if (state.passwordMissing) {
                    { Text(stringResource(R.string.password_empty)) }
                } else {
                    null
                },
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconToggleButton(checked = passwordVisible, onCheckedChange = { passwordVisible = it }, enabled = !state.busy) {
                    if (passwordVisible) {
                        Icon(painterResource(R.drawable.ic_visibility_off), stringResource(R.string.expressive_account_hide_password))
                    } else {
                        Icon(painterResource(R.drawable.ic_remove_red_eye_white), stringResource(R.string.expressive_account_show_password))
                    }
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSignIn() }),
            singleLine = true,
            modifier = Modifier.fillMaxWidth().focusRequester(passwordFocus),
        )
        state.error?.let { error ->
            AccountError(error)
        }
        Button(
            onClick = onSignIn,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            if (state.signingIn) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text(TR.syncSignIn())
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(
                onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(context.getString(R.string.register_url))))
                },
                enabled = !state.busy,
            ) {
                Text(stringResource(R.string.sign_up))
            }
            TextButton(
                onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(context.getString(R.string.resetpw_url))))
                },
                enabled = !state.busy,
            ) {
                Text(stringResource(R.string.reset_password))
            }
        }
    }
}

@Composable
private fun SignedInAccountContent(
    state: ExpressiveAccountUiState,
    onRequestForget: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text(
            text = stringResource(R.string.expressive_account_signed_in_heading),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics { heading() },
        )
        Surface(
            shape = MaterialTheme.shapes.extraLargeIncreased,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(R.string.expressive_account_status_label),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text =
                        state.account?.username
                            ?: stringResource(R.string.expressive_account_signed_in_without_identity),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
        state.error?.let { error -> AccountError(error) }
        Button(
            onClick = onRequestForget,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            if (state.forgetting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text(stringResource(R.string.expressive_account_forget_action))
            }
        }
        Text(
            text = stringResource(R.string.expressive_account_forget_explanation),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AccountError(error: ExpressiveAccountError) {
    val message =
        stringResource(
            when (error) {
                ExpressiveAccountError.INVALID_CREDENTIALS -> R.string.expressive_account_error_credentials
                ExpressiveAccountError.NETWORK -> R.string.expressive_account_error_network
                ExpressiveAccountError.SERVICE_UNAVAILABLE -> R.string.expressive_account_error_service
                ExpressiveAccountError.LOCAL_CLEANUP_INCOMPLETE -> R.string.expressive_account_error_cleanup
            },
        )
    Box(
        Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}
