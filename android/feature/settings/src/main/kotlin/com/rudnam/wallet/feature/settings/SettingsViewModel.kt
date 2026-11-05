package com.rudnam.wallet.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rudnam.wallet.core.data.repository.ChangeResult
import com.rudnam.wallet.core.data.repository.SettingsRepository
import com.rudnam.wallet.core.data.repository.UserRepository
import com.rudnam.wallet.core.data.repository.WalletRepository
import com.rudnam.wallet.core.datastore.Appearance
import com.rudnam.wallet.core.datastore.UserPreferencesRepository
import com.rudnam.wallet.core.model.Me
import com.rudnam.wallet.core.model.Wallet
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    /** Null until the wallet has loaded once. */
    val wallet: Wallet?,
    /** Null until the server has said who's signed in (offline, the first time). */
    val me: Me?,
    val appearance: Appearance,
)

/** Deleting the account: what's typed to confirm it, and how it's going. */
data class DeleteAccountState(val text: String = "", val refused: String? = null, val busy: Boolean = false)

/** What the snackbar says. */
sealed interface SettingsMessage {
    data object Offline : SettingsMessage

    data class Refused(val message: String) : SettingsMessage
}

/** What Settings' own controls do (the menu, the account, Appearance and Currency). */
interface SettingsActions {
    fun setAppearance(appearance: Appearance)
    fun setCurrency(currency: String)
    fun signOut()
    fun startDeleting()
    fun typeConfirmation(text: String)
    fun deleteAccount()
    fun cancelDeleting()
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    wallet: WalletRepository,
    private val users: UserRepository,
    private val settings: SettingsRepository,
    private val preferences: UserPreferencesRepository,
) : ViewModel(), SettingsActions {
    val uiState: StateFlow<SettingsUiState> = combine(wallet.wallet, users.me, preferences.preferences) { w, me, prefs ->
        SettingsUiState(w, me, prefs.appearance)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState(null, null, Appearance.System))

    private val _deleting = MutableStateFlow<DeleteAccountState?>(null)
    val deleting: StateFlow<DeleteAccountState?> = _deleting.asStateFlow()

    private val _messages = MutableStateFlow<List<SettingsMessage>>(emptyList())
    val messages: StateFlow<List<SettingsMessage>> = _messages.asStateFlow()

    init {
        viewModelScope.launch { users.refreshMe() }
    }

    override fun setAppearance(appearance: Appearance) {
        viewModelScope.launch { preferences.setAppearance(appearance) }
    }

    override fun setCurrency(currency: String) {
        if (currency == uiState.value.wallet?.settings?.currency) return
        viewModelScope.launch { report(settings.setCurrency(currency)) }
    }

    override fun signOut() {
        viewModelScope.launch { users.signOut() }
    }

    override fun startDeleting() {
        _deleting.value = DeleteAccountState()
    }

    override fun typeConfirmation(text: String) = _deleting.update { it?.copy(text = text, refused = null) }

    override fun deleteAccount() {
        val state = _deleting.value ?: return
        val me = uiState.value.me ?: return
        if (state.busy || state.text.isBlank()) return
        _deleting.value = state.copy(busy = true)
        viewModelScope.launch {
            when (val result = users.deleteAccount(state.text, withPassword = me.hasPassword)) {
                ChangeResult.Saved, ChangeResult.SignedOut -> _deleting.value = null
                ChangeResult.Offline -> {
                    _deleting.update { it?.copy(busy = false) }
                    post(SettingsMessage.Offline)
                }
                is ChangeResult.Refused -> _deleting.update { it?.copy(busy = false, refused = result.message) }
            }
        }
    }

    override fun cancelDeleting() {
        _deleting.value = null
    }

    fun messageShown(message: SettingsMessage) = _messages.update { it - message }

    private fun report(result: ChangeResult) {
        when (result) {
            ChangeResult.Offline -> post(SettingsMessage.Offline)
            is ChangeResult.Refused -> post(SettingsMessage.Refused(result.message))
            else -> Unit
        }
    }

    private fun post(message: SettingsMessage) = _messages.update { it + message }
}
