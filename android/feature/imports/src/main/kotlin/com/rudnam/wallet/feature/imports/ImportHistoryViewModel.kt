package com.rudnam.wallet.feature.imports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rudnam.wallet.core.data.repository.Fetched
import com.rudnam.wallet.core.data.repository.ImportRepository
import com.rudnam.wallet.core.data.repository.WalletRepository
import com.rudnam.wallet.core.model.ImportBatch
import com.rudnam.wallet.core.model.Wallet
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The import page's list of recent imports: none until the server answers, and none offline. */
data class ImportHistoryState(
    val batches: List<ImportBatch> = emptyList(),
    /** The import being undone, while it is. */
    val undoing: Long? = null,
)

/** What the snackbar says after an undo. */
sealed interface HistoryEvent {
    /** [count] records went to the trash; [ids] bring them back. */
    data class Undone(val count: Int, val ids: List<Long>) : HistoryEvent

    /** The server's reason, or empty for offline. */
    data class Failed(val message: String) : HistoryEvent
}

interface HistoryActions {
    fun undo(batch: ImportBatch)

    fun restore(ids: List<Long>)
}

@HiltViewModel
class ImportHistoryViewModel @Inject constructor(
    wallet: WalletRepository,
    private val imports: ImportRepository,
) : ViewModel(), HistoryActions {
    val wallet: StateFlow<Wallet?> = wallet.wallet.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _state = MutableStateFlow(ImportHistoryState())
    val state: StateFlow<ImportHistoryState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<HistoryEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<HistoryEvent> = _events.asSharedFlow()

    /** Asks again: on opening the page, and on coming back to it from an import. */
    fun refresh() {
        viewModelScope.launch {
            (imports.history() as? Fetched.Done)?.let { f -> _state.update { it.copy(batches = f.value) } }
        }
    }

    override fun undo(batch: ImportBatch) {
        if (_state.value.undoing != null) return
        _state.update { it.copy(undoing = batch.id) }
        viewModelScope.launch {
            val f = imports.undo(batch.id)
            _state.update { it.copy(undoing = null) }
            _events.tryEmit(
                when (f) {
                    is Fetched.Done -> HistoryEvent.Undone(f.value.size, f.value)
                    is Fetched.Failed -> HistoryEvent.Failed(f.message)
                    else -> HistoryEvent.Failed("")
                },
            )
            refresh()
        }
    }

    override fun restore(ids: List<Long>) {
        viewModelScope.launch {
            imports.restore(ids)
            refresh()
        }
    }
}
