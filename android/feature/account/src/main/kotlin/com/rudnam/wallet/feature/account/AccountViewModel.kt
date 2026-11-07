package com.rudnam.wallet.feature.account

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.rudnam.wallet.core.data.repository.AccountRepository
import com.rudnam.wallet.core.data.repository.CountResult
import com.rudnam.wallet.core.data.repository.WalletRepository
import com.rudnam.wallet.core.model.Account
import com.rudnam.wallet.core.model.Transaction
import com.rudnam.wallet.core.model.TransactionKind
import com.rudnam.wallet.core.model.Wallet
import com.rudnam.wallet.core.model.amountInput
import com.rudnam.wallet.core.model.parseAmount
import com.rudnam.wallet.feature.account.navigation.AccountRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.YearMonth
import javax.inject.Inject

sealed interface AccountUiState {
    data object Loading : AccountUiState

    /** Deleted elsewhere since its card was tapped. */
    data object Gone : AccountUiState

    data class Ready(
        val wallet: Wallet,
        val account: Account,
        val month: YearMonth,
        /** Money out of and into this account this month, transfers included. */
        val out: Long,
        val into: Long,
        /** This month's records touching the account, newest first. */
        val records: List<Transaction>,
    ) : AccountUiState
}

/** What the snackbar says after counting. */
sealed interface AccountMessage {
    data class Counted(val balance: Long) : AccountMessage
    data class AlreadyRight(val balance: Long) : AccountMessage
    data object Offline : AccountMessage
    data class Failed(val message: String) : AccountMessage
}

@HiltViewModel
class AccountViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val wallet: WalletRepository,
    private val accounts: AccountRepository,
    private val clock: Clock,
) : ViewModel() {
    private val id = savedState.toRoute<AccountRoute>().id
    private val month = YearMonth.now(clock)
    private val from = month.atDay(1).atStartOfDay(clock.zone).toInstant()
    private val to = month.plusMonths(1).atDay(1).atStartOfDay(clock.zone).toInstant()

    private val _messages = MutableStateFlow<List<AccountMessage>>(emptyList())
    val messages: StateFlow<List<AccountMessage>> = _messages.asStateFlow()

    /** Count cash: false while it's not open, else what's typed so far. */
    private val _counting = MutableStateFlow<CountState?>(null)
    val counting: StateFlow<CountState?> = _counting.asStateFlow()

    data class CountState(val text: String, val invalid: Boolean = false, val busy: Boolean = false)

    val uiState: StateFlow<AccountUiState> = combine(wallet.wallet, wallet.transactions(from, to)) { w, list ->
        when {
            w == null -> AccountUiState.Loading
            else -> {
                val account = w.account(id) ?: return@combine AccountUiState.Gone
                val mine = list.filter { it.accountId == id || it.toAccountId == id }
                val confirmed = mine.filter { it.confirmed }
                AccountUiState.Ready(
                    wallet = w,
                    account = account,
                    month = month,
                    out = confirmed.filter { it.accountId == id && it.kind != TransactionKind.Income }.sumOf { it.amount },
                    into = confirmed.filter { (it.kind == TransactionKind.Income && it.accountId == id) || (it.kind == TransactionKind.Transfer && it.toAccountId == id) }.sumOf { it.amount },
                    records = mine,
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccountUiState.Loading)

    init {
        viewModelScope.launch { wallet.refresh(from, to) }
    }

    fun startCounting() {
        val ready = uiState.value as? AccountUiState.Ready ?: return
        _counting.value = CountState(amountInput(ready.account.balance, ready.wallet.settings.currencyExponent))
    }

    fun typeCount(text: String) = _counting.update { it?.copy(text = text, invalid = false) }

    fun cancelCounting() {
        _counting.value = null
    }

    /** Saves what's in the wallet now; the difference from the app's balance is recorded. */
    fun saveCount() {
        val ready = uiState.value as? AccountUiState.Ready ?: return
        val state = _counting.value ?: return
        val minor = parseAmount(state.text, ready.wallet.settings.currencyExponent)
        if (minor == null) {
            _counting.value = state.copy(invalid = true)
            return
        }
        _counting.value = state.copy(busy = true)
        viewModelScope.launch {
            when (val result = accounts.count(id, minor)) {
                is CountResult.Recorded -> done(AccountMessage.Counted(minor))
                CountResult.AlreadyRight -> done(AccountMessage.AlreadyRight(minor))
                CountResult.Offline, CountResult.SignedOut -> {
                    _counting.update { it?.copy(busy = false) }
                    post(AccountMessage.Offline)
                }
                is CountResult.Refused -> {
                    _counting.update { it?.copy(busy = false) }
                    post(AccountMessage.Failed(result.message))
                }
            }
        }
    }

    private suspend fun done(message: AccountMessage) {
        _counting.value = null
        post(message)
        // The balance and the new record come from the server.
        wallet.refresh(from, to)
    }

    fun messageShown(message: AccountMessage) = _messages.update { it - message }

    private fun post(message: AccountMessage) = _messages.update { it + message }
}
