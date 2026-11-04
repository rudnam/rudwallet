package com.rudnam.wallet.feature.wallet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rudnam.wallet.core.data.repository.SyncResult
import com.rudnam.wallet.core.data.repository.UpdateRepository
import com.rudnam.wallet.core.data.repository.WalletRepository
import com.rudnam.wallet.core.datastore.UserPreferencesRepository
import com.rudnam.wallet.core.model.AppUpdate
import com.rudnam.wallet.core.model.spent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject

@HiltViewModel
class WalletViewModel @Inject constructor(
    private val wallet: WalletRepository,
    private val updates: UpdateRepository,
    private val preferences: UserPreferencesRepository,
    private val clock: Clock,
) : ViewModel() {
    private val month: YearMonth = YearMonth.now(clock)
    private val from = month.atDay(1).atStartOfDay(clock.zone).toInstant()
    private val to = month.plusMonths(1).atDay(1).atStartOfDay(clock.zone).toInstant()

    private val sync = MutableStateFlow(SyncState())
    private val update = MutableStateFlow<AppUpdate?>(null)
    private val notificationsSent = MutableStateFlow<Boolean?>(null)

    /** One-off messages for the screen's snackbar. */
    private val _messages = MutableStateFlow<List<WalletMessage>>(emptyList())
    val messages: StateFlow<List<WalletMessage>> = _messages.asStateFlow()

    private data class SyncState(val refreshing: Boolean = false, val last: SyncResult? = null)

    private val extras = combine(sync, update, notificationsSent, preferences.preferences) { s, u, n, p -> Extras(s, u, n, p.setupDismissed) }

    private data class Extras(val sync: SyncState, val update: AppUpdate?, val notificationsSent: Boolean?, val setupDismissed: Boolean)

    val uiState: StateFlow<WalletUiState> = combine(
        wallet.wallet,
        wallet.transactions(from, to),
        wallet.budgets,
        extras,
    ) { w, records, budgets, x ->
        when {
            w != null -> WalletUiState.Ready(
                wallet = w,
                accounts = w.activeAccounts,
                month = month,
                spent = records.spent(),
                headline = monthHeadline(records, budgets, month, LocalDate.now(clock)),
                latest = records.take(5),
                setup = setup(w.accounts.size, records.isNotEmpty(), budgets.isNotEmpty(), x.notificationsSent, x.setupDismissed),
                update = x.update,
                refreshing = x.sync.refreshing,
            )
            x.sync.last == null || x.sync.refreshing -> WalletUiState.Loading
            else -> WalletUiState.Unavailable
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WalletUiState.Loading)

    private var refreshing: Job? = null

    init {
        refresh(userAsked = false)
        viewModelScope.launch { update.value = updates.check() }
    }

    private var resumedBefore = false

    /** The screen came back into view: the first time it's already loading, after that it asks again. */
    fun onResume() {
        if (resumedBefore) refresh(userAsked = false) else resumedBefore = true
    }

    /** Fetches the wallet again. [userAsked]: pulled down to refresh, so a failure says so. */
    fun refresh(userAsked: Boolean) {
        if (refreshing?.isActive == true) return
        refreshing = viewModelScope.launch {
            sync.update { it.copy(refreshing = userAsked) }
            val result = wallet.refresh(from, to)
            sync.value = SyncState(refreshing = false, last = result)
            if (result == SyncResult.Done && notificationsSent.value != true) notificationsSent.value = wallet.hasSentNotifications()
            when (result) {
                SyncResult.Offline -> if (userAsked) post(WalletMessage.Offline)
                SyncResult.Failed -> post(WalletMessage.Failed)
                else -> Unit
            }
        }
    }

    fun dismissSetup() {
        viewModelScope.launch { preferences.dismissSetup() }
    }

    fun updateLater() {
        val u = update.value ?: return
        update.value = null
        viewModelScope.launch { updates.later(u) }
    }

    fun messageShown(message: WalletMessage) {
        _messages.update { it - message }
    }

    private fun post(message: WalletMessage) {
        _messages.update { if (message in it) it else it + message }
    }

    private fun setup(accounts: Int, hasRecords: Boolean, hasBudget: Boolean, notified: Boolean?, dismissed: Boolean): SetupChecklist? {
        // Unknown until the server says whether notifications came: shown only once it's known, so it doesn't flicker.
        if (dismissed || notified == null) return null
        val list = SetupChecklist(accountsAdded = accounts > 1, notificationsOn = notified, budgetSet = hasBudget, noRecordsYet = !hasRecords)
        return list.takeIf { it.done < it.total }
    }
}

enum class WalletMessage { Offline, Failed }
