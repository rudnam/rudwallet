package com.rudnam.wallet.feature.activity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rudnam.wallet.core.data.repository.ActivityRepository
import com.rudnam.wallet.core.data.repository.BulkOutcome
import com.rudnam.wallet.core.data.repository.SetAsideResult
import com.rudnam.wallet.core.data.repository.WalletRepository
import com.rudnam.wallet.core.model.SetAsideRecord
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

sealed interface DuplicatesUiState {
    data object Loading : DuplicatesUiState

    /** [records] null: the server couldn't be reached ([offline]) or failed. */
    data class Ready(
        val wallet: Wallet,
        val records: List<SetAsideRecord>?,
        val offline: Boolean,
        val busy: Boolean,
    ) : DuplicatesUiState
}

/** What the snackbar says on the duplicates page. */
sealed interface DuplicatesMessage {
    data class BroughtBack(val payee: String) : DuplicatesMessage
    data object Offline : DuplicatesMessage
    data class Failed(val message: String) : DuplicatesMessage
}

interface DuplicatesActions {
    fun load()
    /** Not a duplicate after all: back to "to check". */
    fun bringBack(record: SetAsideRecord)
}

/** "Caught duplicates": the payments the server set aside as possible duplicates, to bring back any that weren't. */
@HiltViewModel
class DuplicatesViewModel @Inject constructor(
    wallet: WalletRepository,
    private val activity: ActivityRepository,
) : ViewModel(), DuplicatesActions {
    private val result = MutableStateFlow<SetAsideResult?>(null)
    private val busy = MutableStateFlow(false)

    private val _messages = MutableStateFlow<List<DuplicatesMessage>>(emptyList())
    val messages: StateFlow<List<DuplicatesMessage>> = _messages.asStateFlow()

    val uiState: StateFlow<DuplicatesUiState> = combine(wallet.wallet, result, busy) { w, r, b ->
        if (w == null || r == null) return@combine DuplicatesUiState.Loading
        DuplicatesUiState.Ready(w, (r as? SetAsideResult.Found)?.records, offline = r == SetAsideResult.Offline, busy = b)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DuplicatesUiState.Loading)

    init {
        load()
    }

    override fun load() {
        viewModelScope.launch { result.value = activity.setAside() }
    }

    override fun bringBack(record: SetAsideRecord) {
        viewModelScope.launch {
            busy.value = true
            when (val outcome = activity.restore(listOf(record.record.id))) {
                is BulkOutcome.Done -> {
                    // Gone from here at once; "to check" picks it up with its next refresh.
                    result.update { r -> (r as? SetAsideResult.Found)?.let { f -> f.copy(records = f.records.filterNot { it.record.id == record.record.id }) } ?: r }
                    activity.refreshReview()
                    post(DuplicatesMessage.BroughtBack(record.record.payee))
                }
                is BulkOutcome.Refused -> post(DuplicatesMessage.Failed(outcome.message))
                else -> post(DuplicatesMessage.Offline)
            }
            busy.value = false
        }
    }

    fun messageShown(message: DuplicatesMessage) = _messages.update { it - message }

    private fun post(message: DuplicatesMessage) = _messages.update { it + message }
}
