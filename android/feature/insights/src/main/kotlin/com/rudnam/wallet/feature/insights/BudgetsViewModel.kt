package com.rudnam.wallet.feature.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rudnam.wallet.core.data.repository.BudgetResult
import com.rudnam.wallet.core.data.repository.InsightsRepository
import com.rudnam.wallet.core.data.repository.WalletRepository
import com.rudnam.wallet.core.model.Budget
import com.rudnam.wallet.core.model.BudgetInput
import com.rudnam.wallet.core.model.Wallet
import com.rudnam.wallet.core.model.amountInput
import com.rudnam.wallet.core.model.parseAmount
import com.rudnam.wallet.core.model.spentIn
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

/** A budget and what this month has spent of it. */
data class BudgetRow(val budget: Budget, val spent: Long)

sealed interface BudgetsUiState {
    data object Loading : BudgetsUiState

    data class Ready(val wallet: Wallet, val month: YearMonth, val budgets: List<BudgetRow>) : BudgetsUiState
}

/**
 * The budget being made or changed: [id] null for a new one. [categoryId]
 * null is all spending; [name] empty takes the category's name.
 */
data class BudgetEditor(
    val id: Long?,
    val categoryId: Long?,
    val amount: String,
    val name: String,
    /** The limit isn't an amount above zero. */
    val invalidAmount: Boolean = false,
    /** The server's reason for refusing it. */
    val refused: String? = null,
    val busy: Boolean = false,
    /** Asking whether to delete it. */
    val confirmingDelete: Boolean = false,
)

/** What the snackbar says. */
sealed interface BudgetsMessage {
    data object Offline : BudgetsMessage

    data class Deleted(val name: String) : BudgetsMessage
}

/** What the budgets page's controls do. */
interface BudgetsActions {
    fun create()
    fun edit(budget: Budget)
    fun setCategory(categoryId: Long?)
    fun setAmount(text: String)
    fun setName(text: String)
    /** Saves it; with no name typed, it's called [defaultName] (its category's, or "All spending"). */
    fun save(defaultName: String)
    fun askDelete(asking: Boolean)
    fun delete()
    fun close()
}

@HiltViewModel
class BudgetsViewModel @Inject constructor(
    private val wallet: WalletRepository,
    private val insights: InsightsRepository,
    private val clock: Clock,
) : ViewModel(), BudgetsActions {
    private val month = YearMonth.now(clock)
    private val from = month.atDay(1).atStartOfDay(clock.zone).toInstant()
    private val to = month.plusMonths(1).atDay(1).atStartOfDay(clock.zone).toInstant()

    val uiState: StateFlow<BudgetsUiState> = combine(wallet.wallet, wallet.budgets, wallet.transactions(from, to)) { w, budgets, records ->
        if (w == null) BudgetsUiState.Loading else BudgetsUiState.Ready(w, month, budgets.map { BudgetRow(it, it.spentIn(records)) })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BudgetsUiState.Loading)

    private val _editor = MutableStateFlow<BudgetEditor?>(null)
    val editor: StateFlow<BudgetEditor?> = _editor.asStateFlow()

    private val _messages = MutableStateFlow<List<BudgetsMessage>>(emptyList())
    val messages: StateFlow<List<BudgetsMessage>> = _messages.asStateFlow()

    init {
        viewModelScope.launch { wallet.refresh(from, to) }
    }

    private val exponent: Int get() = (uiState.value as? BudgetsUiState.Ready)?.wallet?.settings?.currencyExponent ?: 0

    override fun create() {
        _editor.value = BudgetEditor(id = null, categoryId = null, amount = "", name = "")
    }

    override fun edit(budget: Budget) {
        _editor.value = BudgetEditor(budget.id, budget.categoryId, amountInput(budget.amount, exponent), budget.name)
    }

    override fun setCategory(categoryId: Long?) = _editor.update { it?.copy(categoryId = categoryId, refused = null) }

    override fun setAmount(text: String) = _editor.update { it?.copy(amount = text, invalidAmount = false, refused = null) }

    override fun setName(text: String) = _editor.update { it?.copy(name = text.take(60), refused = null) }

    override fun save(defaultName: String) {
        val e = _editor.value ?: return
        if (e.busy) return
        val minor = parseAmount(e.amount, exponent)?.takeIf { it > 0 }
        if (minor == null) {
            _editor.value = e.copy(invalidAmount = true)
            return
        }
        val name = e.name.trim().ifEmpty { defaultName }
        _editor.value = e.copy(busy = true)
        viewModelScope.launch {
            val input = BudgetInput(name, e.categoryId, minor)
            val result = if (e.id == null) insights.createBudget(input) else insights.updateBudget(e.id, input)
            finish(result)
        }
    }

    override fun askDelete(asking: Boolean) = _editor.update { it?.copy(confirmingDelete = asking) }

    override fun delete() {
        val e = _editor.value ?: return
        val id = e.id ?: return
        val name = (uiState.value as? BudgetsUiState.Ready)?.budgets?.firstOrNull { it.budget.id == id }?.budget?.name.orEmpty()
        _editor.value = e.copy(busy = true, confirmingDelete = false)
        viewModelScope.launch {
            val result = insights.deleteBudget(id)
            finish(result)
            if (result == BudgetResult.Saved) post(BudgetsMessage.Deleted(name))
        }
    }

    private fun finish(result: BudgetResult) {
        when (result) {
            BudgetResult.Saved -> _editor.value = null
            BudgetResult.Offline -> {
                _editor.update { it?.copy(busy = false) }
                post(BudgetsMessage.Offline)
            }
            BudgetResult.SignedOut -> _editor.value = null
            is BudgetResult.Refused -> _editor.update { it?.copy(busy = false, refused = result.message) }
        }
    }

    override fun close() {
        _editor.value = null
    }

    fun messageShown(message: BudgetsMessage) = _messages.update { it - message }

    private fun post(message: BudgetsMessage) = _messages.update { it + message }
}
