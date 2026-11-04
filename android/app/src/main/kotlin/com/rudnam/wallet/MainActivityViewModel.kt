package com.rudnam.wallet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rudnam.wallet.core.data.repository.RecordEvent
import com.rudnam.wallet.core.data.repository.RecordEvents
import com.rudnam.wallet.core.data.repository.RecordRepository
import com.rudnam.wallet.core.data.repository.SaveResult
import com.rudnam.wallet.core.data.repository.WalletRepository
import com.rudnam.wallet.core.datastore.Appearance
import com.rudnam.wallet.core.datastore.SessionStore
import com.rudnam.wallet.core.datastore.UserPreferencesRepository
import com.rudnam.wallet.core.model.WalletSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.YearMonth
import javax.inject.Inject

/** What the app as a whole shows: who's signed in, how it looks, the Activity tab's count, and what happened to records. */
@HiltViewModel
class MainActivityViewModel @Inject constructor(
    session: SessionStore,
    preferences: UserPreferencesRepository,
    private val wallet: WalletRepository,
    recordEvents: RecordEvents,
    private val records: RecordRepository,
    private val clock: Clock,
) : ViewModel() {
    val uiState: StateFlow<MainUiState> = combine(session.token, preferences.preferences) { token, prefs ->
        MainUiState.Ready(signedIn = token != null, appearance = prefs.appearance)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainUiState.Loading)

    /** Records waiting for a check, for the Activity tab's badge. */
    val reviewCount: StateFlow<Int> = wallet.wallet.map { it?.reviewCount ?: 0 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** What happened to records (the record sheet), for the snackbar. */
    val recordEvents: SharedFlow<RecordEvent> = recordEvents.all

    /** The wallet's currency, for writing amounts in the snackbar. */
    val settings: StateFlow<WalletSettings?> = wallet.wallet.map { it?.settings }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        // Signed out (here, in the web view, or by the server): the next person starts from nothing.
        viewModelScope.launch {
            session.token.map { it != null }.distinctUntilChanged().drop(1).filter { !it }.collect { wallet.forget() }
        }
    }

    /** Undo, from the snackbar: a changed record as it was, or a deleted one back. */
    suspend fun undo(event: RecordEvent): SaveResult = when (event) {
        is RecordEvent.Changed -> records.update(event.id, event.before)
        is RecordEvent.Deleted -> records.restore(event.id).also { result ->
            // It comes back to the lists with the server's answer.
            if (result == SaveResult.Done) {
                val month = YearMonth.now(clock)
                wallet.refresh(month.atDay(1).atStartOfDay(clock.zone).toInstant(), month.plusMonths(1).atDay(1).atStartOfDay(clock.zone).toInstant())
            }
        }
        else -> SaveResult.Done
    }
}

sealed interface MainUiState {
    data object Loading : MainUiState

    data class Ready(val signedIn: Boolean, val appearance: Appearance) : MainUiState
}
