package com.rudnam.wallet.feature.imports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rudnam.wallet.core.data.ic.IcCardProblem
import com.rudnam.wallet.core.data.ic.IcCardTap
import com.rudnam.wallet.core.data.ic.IcCards
import com.rudnam.wallet.core.data.repository.Fetched
import com.rudnam.wallet.core.data.repository.ImportRepository
import com.rudnam.wallet.core.data.repository.WalletRepository
import com.rudnam.wallet.core.model.AccountPick
import com.rudnam.wallet.core.model.IcCardRead
import com.rudnam.wallet.core.model.ImportPreview
import com.rudnam.wallet.core.model.ImportResult
import com.rudnam.wallet.core.model.ImportRowStatus
import com.rudnam.wallet.core.model.Wallet
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CardUiState(
    val read: IcCardRead? = null,
    /** What's new on it; null while it's being checked. */
    val preview: ImportPreview? = null,
    val result: ImportResult? = null,
    /** Waiting for a card to be held to the phone. */
    val waiting: Boolean = false,
    val busy: Boolean = false,
    val problem: IcCardProblem? = null,
    /** Checking with the server failed: its reason, or empty for offline. */
    val error: String? = null,
) {
    /** Read and imported before, with nothing lost in between: the choices are known, so one tap imports. */
    val known: Boolean
        get() = preview != null && preview.accounts.firstOrNull()?.accountId != null && !preview.gap &&
            preview.rows.any { it.status == ImportRowStatus.Imported }
}

/** What the card page's controls do. */
interface CardActions {
    /** Waits for a card ([on]), or stops waiting. */
    fun waitForCard(on: Boolean)

    /** A card read (or not) while waiting, or tapped while the app was open. */
    fun tapped(tap: IcCardTap)

    /** Imports the new entries with last time's choices. */
    fun importNew()

    /** Hands the card to the full import preview. */
    fun review()
}

@HiltViewModel
class CardViewModel @Inject constructor(
    wallet: WalletRepository,
    private val imports: ImportRepository,
    private val cards: IcCards,
) : ViewModel(), CardActions {
    val wallet: StateFlow<Wallet?> = wallet.wallet.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _state = MutableStateFlow(CardUiState())
    val state: StateFlow<CardUiState> = _state.asStateFlow()

    init {
        // The card whose tap opened this, then any tapped while it's open.
        viewModelScope.launch { cards.tapped.filterNotNull().collect { cards.take()?.let(::tapped) } }
    }

    override fun waitForCard(on: Boolean) = _state.update { it.copy(waiting = on, problem = null) }

    override fun tapped(tap: IcCardTap) {
        when (tap) {
            is IcCardTap.Failed -> _state.update { it.copy(waiting = false, problem = tap.problem) }
            is IcCardTap.Read -> {
                _state.value = CardUiState(read = tap.card)
                viewModelScope.launch {
                    _state.update {
                        when (val p = imports.previewCard(tap.card)) {
                            is Fetched.Done -> it.copy(preview = p.value)
                            is Fetched.Failed -> it.copy(error = p.message)
                            else -> it.copy(error = "")
                        }
                    }
                }
            }
        }
    }

    override fun importNew() {
        val s = _state.value
        val read = s.read ?: return
        val preview = s.preview ?: return
        if (s.busy) return
        _state.value = s.copy(busy = true, error = null)
        viewModelScope.launch {
            val choices = Choices.suggested(preview).copy(matchBalance = false).asImport(preview)
                .let { c -> c.copy(accounts = preview.accounts.map { AccountPick(it.key, it.accountId ?: AccountPick.NEW) }) }
            when (val r = imports.importCard(read, choices)) {
                is Fetched.Done -> _state.update {
                    it.copy(
                        busy = false,
                        result = r.value,
                        // What's imported shows as imported, without asking again.
                        preview = preview.copy(
                            new = 0,
                            rows = r.value.rows.map { row -> if (row.status == ImportRowStatus.New) row.copy(status = ImportRowStatus.Imported) else row },
                            balance = r.value.balance ?: preview.balance,
                        ),
                    )
                }
                is Fetched.Failed -> _state.update { it.copy(busy = false, error = r.message) }
                else -> _state.update { it.copy(busy = false, error = "") }
            }
        }
    }

    override fun review() {
        cards.review = _state.value.read
    }
}
