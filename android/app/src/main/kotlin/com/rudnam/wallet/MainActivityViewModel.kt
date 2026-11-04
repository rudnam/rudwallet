package com.rudnam.wallet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rudnam.wallet.core.data.repository.WalletRepository
import com.rudnam.wallet.core.datastore.Appearance
import com.rudnam.wallet.core.datastore.SessionStore
import com.rudnam.wallet.core.datastore.UserPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainActivityViewModel @Inject constructor(
    session: SessionStore,
    preferences: UserPreferencesRepository,
    wallet: WalletRepository,
) : ViewModel() {
    val uiState: StateFlow<MainUiState> = combine(session.token, preferences.preferences) { token, prefs ->
        MainUiState.Ready(signedIn = token != null, appearance = prefs.appearance)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainUiState.Loading)

    /** Records waiting for a check, for the Activity tab's badge. */
    val reviewCount: StateFlow<Int> = wallet.wallet.map { it?.reviewCount ?: 0 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    init {
        // Signed out (here, in the web view, or by the server): the next person starts from nothing.
        viewModelScope.launch {
            session.token.map { it != null }.distinctUntilChanged().drop(1).filter { !it }.collect { wallet.forget() }
        }
    }
}

sealed interface MainUiState {
    data object Loading : MainUiState

    data class Ready(val signedIn: Boolean, val appearance: Appearance) : MainUiState
}
