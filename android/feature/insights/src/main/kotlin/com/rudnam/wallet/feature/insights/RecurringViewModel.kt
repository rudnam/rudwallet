package com.rudnam.wallet.feature.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rudnam.wallet.core.data.repository.ChangeResult
import com.rudnam.wallet.core.data.repository.Fetched
import com.rudnam.wallet.core.data.repository.RecordEvents
import com.rudnam.wallet.core.data.repository.RecurringRepository
import com.rudnam.wallet.core.data.repository.WalletRepository
import com.rudnam.wallet.core.model.Recurring
import com.rudnam.wallet.core.model.RecurringInput
import com.rudnam.wallet.core.model.RecurringKind
import com.rudnam.wallet.core.model.RecurringOverview
import com.rudnam.wallet.core.model.RecurringPayment
import com.rudnam.wallet.core.model.RecurringSuggestion
import com.rudnam.wallet.core.model.Wallet
import com.rudnam.wallet.core.model.amountInput
import com.rudnam.wallet.core.model.parseAmount
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
import java.time.LocalDate
import javax.inject.Inject

sealed interface RecurringUiState {
    data object Loading : RecurringUiState

    data class Ready(
        val wallet: Wallet,
        /** Null until the server has answered once. */
        val overview: RecurringOverview?,
        val today: LocalDate,
        val refreshing: Boolean,
        val problem: InsightsProblem?,
    ) : RecurringUiState
}

/** How often a recurring payment comes, in months: the choices offered. */
val recurringMonths = listOf(1, 2, 3, 6, 12)

/**
 * The recurring payment being added or changed: [id] null for a new one,
 * [suggestion] the key of the suggestion it comes from, if any.
 */
data class RecurringEditor(
    val id: Long?,
    val suggestion: String?,
    val kind: RecurringKind,
    val name: String,
    val amount: String,
    val months: Int,
    val startDate: LocalDate,
    val categoryId: Long?,
    val accountId: Long?,
    /** Texts its records carry, separated by commas. */
    val matches: String,
    val active: Boolean,
    val payments: List<RecurringPayment>,
    val invalidName: Boolean = false,
    val invalidAmount: Boolean = false,
    val refused: String? = null,
    val busy: Boolean = false,
    val confirmingDelete: Boolean = false,
)

sealed interface RecurringMessage {
    data object Offline : RecurringMessage

    data class Deleted(val name: String) : RecurringMessage

    data class Added(val count: Int) : RecurringMessage
}

/** What the recurring payments page's controls do. */
interface RecurringActions {
    fun refresh()
    /** Adds a suggestion as it is. */
    fun add(suggestion: RecurringSuggestion)
    fun addAll()
    /** Says a suggestion isn't recurring. */
    fun dismiss(suggestion: RecurringSuggestion)
    fun create()
    fun review(suggestion: RecurringSuggestion)
    fun edit(recurring: Recurring)
    fun setKind(kind: RecurringKind)
    fun setName(text: String)
    fun setAmount(text: String)
    fun setMonths(months: Int)
    fun setStartDate(date: LocalDate)
    fun setCategory(categoryId: Long?)
    fun setAccount(accountId: Long?)
    fun setMatches(text: String)
    fun setActive(active: Boolean)
    fun save()
    fun askDelete(asking: Boolean)
    fun delete()
    fun close()
}

@HiltViewModel
class RecurringViewModel @Inject constructor(
    wallet: WalletRepository,
    private val recurring: RecurringRepository,
    recordEvents: RecordEvents,
    private val clock: Clock,
) : ViewModel(), RecurringActions {
    private val status = MutableStateFlow(Status(refreshing = false, problem = null))

    private data class Status(val refreshing: Boolean, val problem: InsightsProblem?)

    val uiState: StateFlow<RecurringUiState> = combine(wallet.wallet, recurring.overview, status) { w, o, s ->
        if (w == null) RecurringUiState.Loading else RecurringUiState.Ready(w, o, LocalDate.now(clock), s.refreshing, s.problem)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecurringUiState.Loading)

    private val _editor = MutableStateFlow<RecurringEditor?>(null)
    val editor: StateFlow<RecurringEditor?> = _editor.asStateFlow()

    private val _messages = MutableStateFlow<List<RecurringMessage>>(emptyList())
    val messages: StateFlow<List<RecurringMessage>> = _messages.asStateFlow()

    init {
        viewModelScope.launch { load(userAsked = false) }
        // A record added or changed may be one of their payments.
        viewModelScope.launch { recordEvents.all.collect { load(userAsked = false) } }
    }

    private suspend fun load(userAsked: Boolean) {
        if (userAsked) status.update { it.copy(refreshing = true) }
        val problem = when (val r = recurring.refresh(clock.zone)) {
            is Fetched.Done, Fetched.SignedOut -> null
            Fetched.Offline -> InsightsProblem.Offline
            is Fetched.Failed -> InsightsProblem.Failed(r.message)
        }
        status.value = Status(refreshing = false, problem = problem)
    }

    override fun refresh() {
        viewModelScope.launch { load(userAsked = true) }
    }

    private val exponent: Int get() = (uiState.value as? RecurringUiState.Ready)?.wallet?.settings?.currencyExponent ?: 0

    private val suggestions: List<RecurringSuggestion>
        get() = (uiState.value as? RecurringUiState.Ready)?.overview?.suggestions.orEmpty()

    override fun add(suggestion: RecurringSuggestion) {
        viewModelScope.launch { saved(recurring.create(suggestion.input), added = 1) }
    }

    override fun addAll() {
        val all = suggestions
        viewModelScope.launch {
            var added = 0
            for (s in all) {
                val r = recurring.create(s.input)
                if (r != ChangeResult.Saved) return@launch saved(r, added)
                added++
            }
            saved(ChangeResult.Saved, added)
        }
    }

    override fun dismiss(suggestion: RecurringSuggestion) {
        viewModelScope.launch { saved(recurring.dismiss(suggestion.key)) }
    }

    /** After a change: asks again for what it changed, or says why it couldn't. */
    private suspend fun saved(result: ChangeResult, added: Int = 0) {
        if (added > 0) post(RecurringMessage.Added(added))
        when (result) {
            ChangeResult.Saved -> load(userAsked = false)
            ChangeResult.Offline -> post(RecurringMessage.Offline)
            ChangeResult.SignedOut -> Unit
            is ChangeResult.Refused -> status.update { it.copy(problem = InsightsProblem.Failed(result.message)) }
        }
        if (added > 0 && result != ChangeResult.Saved) load(userAsked = false)
    }

    private fun open(id: Long?, suggestion: String?, input: RecurringInput, payments: List<RecurringPayment>, nextDue: LocalDate? = null) {
        _editor.value = RecurringEditor(
            id = id,
            suggestion = suggestion,
            kind = input.kind,
            name = input.name,
            amount = if (input.amount > 0) amountInput(input.amount, exponent) else "",
            months = input.months,
            startDate = nextDue ?: input.startDate,
            categoryId = input.categoryId,
            accountId = input.accountId,
            matches = input.matches.joinToString(", "),
            active = input.active,
            payments = payments,
        )
    }

    override fun create() = open(
        null,
        null,
        RecurringInput("", RecurringKind.Subscription, emptyList(), 0, 1, LocalDate.now(clock), null, null, active = true),
        emptyList(),
    )

    override fun review(suggestion: RecurringSuggestion) = open(null, suggestion.key, suggestion.input, suggestion.payments)

    override fun edit(recurring: Recurring) = open(recurring.id, null, recurring.input, recurring.payments, recurring.nextDue)

    override fun setKind(kind: RecurringKind) = _editor.update { e ->
        // A category of the other kind (an expense's, for income) doesn't fit any more.
        val keep = e?.categoryId?.takeIf { kind.recordKind == e.kind.recordKind }
        e?.copy(kind = kind, categoryId = keep, refused = null)
    }

    override fun setName(text: String) = _editor.update { it?.copy(name = text.take(60), invalidName = false, refused = null) }

    override fun setAmount(text: String) = _editor.update { it?.copy(amount = text, invalidAmount = false, refused = null) }

    override fun setMonths(months: Int) = _editor.update { it?.copy(months = months, refused = null) }

    override fun setStartDate(date: LocalDate) = _editor.update { it?.copy(startDate = date, refused = null) }

    override fun setCategory(categoryId: Long?) = _editor.update { it?.copy(categoryId = categoryId, refused = null) }

    override fun setAccount(accountId: Long?) = _editor.update { it?.copy(accountId = accountId, refused = null) }

    override fun setMatches(text: String) = _editor.update { it?.copy(matches = text, refused = null) }

    override fun setActive(active: Boolean) = _editor.update { it?.copy(active = active, refused = null) }

    override fun save() {
        val e = _editor.value ?: return
        if (e.busy) return
        val minor = parseAmount(e.amount, exponent)?.takeIf { it > 0 }
        val name = e.name.trim()
        if (minor == null || name.isEmpty()) {
            _editor.value = e.copy(invalidAmount = minor == null, invalidName = name.isEmpty())
            return
        }
        val input = RecurringInput(
            name = name,
            kind = e.kind,
            matches = e.matches.split(',', '、').map { it.trim() }.filter { it.isNotEmpty() },
            amount = minor,
            months = e.months,
            startDate = e.startDate,
            categoryId = e.categoryId,
            accountId = e.accountId,
            active = e.active,
        )
        _editor.value = e.copy(busy = true)
        viewModelScope.launch {
            val result = if (e.id == null) recurring.create(input) else recurring.update(e.id, input)
            finish(result)
            if (result == ChangeResult.Saved) load(userAsked = false)
        }
    }

    override fun askDelete(asking: Boolean) = _editor.update { it?.copy(confirmingDelete = asking) }

    override fun delete() {
        val e = _editor.value ?: return
        val id = e.id ?: return
        _editor.value = e.copy(busy = true, confirmingDelete = false)
        viewModelScope.launch {
            val result = recurring.delete(id)
            finish(result)
            if (result == ChangeResult.Saved) {
                post(RecurringMessage.Deleted(e.name))
                load(userAsked = false)
            }
        }
    }

    private fun finish(result: ChangeResult) {
        when (result) {
            ChangeResult.Saved, ChangeResult.SignedOut -> _editor.value = null
            ChangeResult.Offline -> {
                _editor.update { it?.copy(busy = false) }
                post(RecurringMessage.Offline)
            }
            is ChangeResult.Refused -> _editor.update { it?.copy(busy = false, refused = result.message) }
        }
    }

    override fun close() {
        _editor.value = null
    }

    fun messageShown(message: RecurringMessage) = _messages.update { it - message }

    private fun post(message: RecurringMessage) = _messages.update { it + message }
}
