// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.ui.expressive

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import anki.sync.SyncAuth
import com.ichi2.anki.CollectionManager
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.currentSyncCredential
import com.ichi2.anki.forgetSyncAccount
import com.ichi2.anki.getEndpoint
import com.ichi2.anki.updateLogin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import net.ankiweb.rsdroid.exceptions.BackendSyncException
import java.io.IOException

data class ExpressiveAccount(
    val username: String?,
)

enum class ExpressiveAccountError {
    INVALID_CREDENTIALS,
    NETWORK,
    SERVICE_UNAVAILABLE,
    LOCAL_CLEANUP_INCOMPLETE,
}

data class ExpressiveAccountUiState(
    val account: ExpressiveAccount? = null,
    val username: String = "",
    val password: String = "",
    val usernameMissing: Boolean = false,
    val passwordMissing: Boolean = false,
    val signingIn: Boolean = false,
    val forgetting: Boolean = false,
    val confirmForget: Boolean = false,
    val error: ExpressiveAccountError? = null,
) {
    val busy: Boolean
        get() = signingIn || forgetting
}

internal sealed interface ExpressiveAccountEffect {
    data object AuthenticationSucceeded : ExpressiveAccountEffect
}

internal interface ExpressiveAccountGateway {
    fun currentAccount(): ExpressiveAccount?

    suspend fun login(
        username: String,
        password: String,
    ): ExpressiveAccount

    suspend fun forgetAccount()

    fun cancel()
}

internal class BackendExpressiveAccountGateway : ExpressiveAccountGateway {
    override fun currentAccount(): ExpressiveAccount? =
        currentSyncCredential()?.let { credential -> ExpressiveAccount(credential.username) }

    override suspend fun login(
        username: String,
        password: String,
    ): ExpressiveAccount {
        val auth: SyncAuth =
            withCol {
                syncLogin(username, password, getEndpoint())
            }
        updateLogin(username, auth.hkey)
        return ExpressiveAccount(username)
    }

    override suspend fun forgetAccount() {
        val generation = currentSyncCredential()?.generation ?: return
        check(forgetSyncAccount(generation)) { "Sync account changed during logout" }
    }

    override fun cancel() {
        CollectionManager.getBackend().setWantsAbort()
    }
}

internal class ExpressiveAccountViewModel(
    private val gateway: ExpressiveAccountGateway,
) : ViewModel() {
    private val _state = MutableStateFlow(ExpressiveAccountUiState(account = gateway.currentAccount()))
    val state: StateFlow<ExpressiveAccountUiState> = _state.asStateFlow()

    private val _effects = Channel<ExpressiveAccountEffect>(Channel.BUFFERED)
    val effects: Flow<ExpressiveAccountEffect> = _effects.receiveAsFlow()

    private var operation: Job? = null

    fun refresh() {
        if (_state.value.busy) return
        _state.value =
            _state.value.copy(
                account = gateway.currentAccount(),
                password = "",
                confirmForget = false,
            )
    }

    fun onUsernameChanged(username: String) {
        if (_state.value.busy) return
        _state.value = _state.value.copy(username = username, usernameMissing = false, error = null)
    }

    fun onPasswordChanged(password: String) {
        if (_state.value.busy) return
        _state.value = _state.value.copy(password = password, passwordMissing = false, error = null)
    }

    fun login() {
        if (_state.value.busy) return
        val username = _state.value.username.trim()
        val password = _state.value.password
        val usernameMissing = username.isEmpty()
        val passwordMissing = password.isEmpty()
        if (usernameMissing || passwordMissing) {
            _state.value =
                _state.value.copy(
                    usernameMissing = usernameMissing,
                    passwordMissing = passwordMissing,
                    error = null,
                )
            return
        }
        _state.value =
            _state.value.copy(
                username = username,
                signingIn = true,
                usernameMissing = false,
                passwordMissing = false,
                error = null,
            )
        operation =
            viewModelScope.launch {
                try {
                    val account = gateway.login(username, password)
                    _state.value =
                        _state.value.copy(
                            account = account,
                            password = "",
                            signingIn = false,
                        )
                    _effects.trySend(ExpressiveAccountEffect.AuthenticationSucceeded)
                } catch (exception: CancellationException) {
                    throw exception
                } catch (_: BackendSyncException.BackendSyncAuthFailedException) {
                    _state.value =
                        _state.value.copy(
                            password = "",
                            signingIn = false,
                            error = ExpressiveAccountError.INVALID_CREDENTIALS,
                        )
                } catch (_: IOException) {
                    _state.value =
                        _state.value.copy(
                            password = "",
                            signingIn = false,
                            error = ExpressiveAccountError.NETWORK,
                        )
                } catch (_: Exception) {
                    _state.value =
                        _state.value.copy(
                            password = "",
                            signingIn = false,
                            error = ExpressiveAccountError.SERVICE_UNAVAILABLE,
                        )
                } finally {
                    operation = null
                }
            }
    }

    fun requestForget() {
        if (_state.value.account == null || _state.value.busy) return
        _state.value = _state.value.copy(confirmForget = true, error = null)
    }

    fun dismissForget() {
        if (_state.value.busy) return
        _state.value = _state.value.copy(confirmForget = false)
    }

    fun confirmForget() {
        if (_state.value.account == null || _state.value.busy) return
        _state.value = _state.value.copy(forgetting = true, confirmForget = false, error = null)
        operation =
            viewModelScope.launch {
                try {
                    gateway.forgetAccount()
                    _state.value = ExpressiveAccountUiState()
                } catch (exception: CancellationException) {
                    throw exception
                } catch (_: Exception) {
                    _state.value =
                        ExpressiveAccountUiState(
                            account = gateway.currentAccount(),
                            error = ExpressiveAccountError.LOCAL_CLEANUP_INCOMPLETE,
                        )
                } finally {
                    operation = null
                }
            }
    }

    /** Returns false while destructive logout cleanup must not be interrupted. */
    fun cancelOperation(): Boolean {
        if (_state.value.forgetting) return false
        val active = operation
        if (active != null) {
            gateway.cancel()
            active.cancel()
            operation = null
        }
        _state.value = _state.value.copy(password = "", signingIn = false)
        return true
    }

    companion object {
        fun factory(
            gatewayFactory: (Application) -> ExpressiveAccountGateway = { BackendExpressiveAccountGateway() },
        ): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    val application = checkNotNull(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY])
                    ExpressiveAccountViewModel(
                        gateway = gatewayFactory(application),
                    )
                }
            }
    }
}
