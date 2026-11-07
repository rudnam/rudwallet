package com.rudnam.wallet.feature.record

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.rudnam.wallet.core.data.repository.RecordEvent
import com.rudnam.wallet.core.data.repository.RecordEvents
import com.rudnam.wallet.core.data.repository.RecordHabits
import com.rudnam.wallet.core.data.repository.RecordRepository
import com.rudnam.wallet.core.data.repository.SaveResult
import com.rudnam.wallet.core.data.repository.WalletRepository
import com.rudnam.wallet.core.model.Category
import com.rudnam.wallet.core.model.Pending
import com.rudnam.wallet.core.model.RecordInput
import com.rudnam.wallet.core.model.Transaction
import com.rudnam.wallet.core.model.TransactionKind
import com.rudnam.wallet.core.model.TransactionStatus
import com.rudnam.wallet.core.model.Wallet
import com.rudnam.wallet.feature.record.navigation.RecordRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import javax.inject.Inject

/** How many categories show before More, and how many recent payees are offered. */
internal const val FEW_CATEGORIES = 7
internal const val FEW_PAYEES = 4

/** Why the sheet can't do what was asked; each has words of its own. */
enum class RecordProblem { NoAmount, Offline, SentMeanwhile, SignedOut }

sealed interface RecordUiState {
    data object Loading : RecordUiState

    /** The record isn't on the phone (deleted meanwhile): the sheet closes. */
    data object Missing : RecordUiState

    data class Ready(
        val wallet: Wallet,
        val form: RecordForm,
        /** The record being changed, or null for a new one. */
        val record: Transaction?,
        /** The categories of the form's kind, the most used first; [showAllCategories] or the first few. */
        val categories: List<Category>,
        val canFoldCategories: Boolean,
        val showAllCategories: Boolean,
        /** Recent payees, offered while a new record has none yet. */
        val recentPayees: List<String>,
        /** Payees matching what's typed, for the field's suggestions. */
        val payeeSuggestions: List<String>,
        val tagSuggestions: List<String>,
        /** Note and tags wait behind one row on a new record until wanted. */
        val showExtras: Boolean,
        val busy: Boolean,
        /** Why the last save didn't happen: a problem of the app's, or the server's own words. */
        val problem: RecordProblem?,
        val serverMessage: String?,
        /** An edit waiting for "Save changes?". */
        val confirming: List<Change>?,
        val confirmingDelete: Boolean,
    ) : RecordUiState {
        val isNew: Boolean get() = record == null
        val reviewing: Boolean get() = record?.status == TransactionStatus.Review
    }
}

@HiltViewModel
class RecordViewModel @Inject constructor(
    savedState: SavedStateHandle,
    wallet: WalletRepository,
    private val records: RecordRepository,
    private val events: RecordEvents,
    private val clock: Clock,
) : ViewModel() {
    private val route = savedState.toRoute<RecordRoute>()

    private data class Sheet(
        val form: RecordForm? = null,
        val record: Transaction? = null,
        val missing: Boolean = false,
        // Null until known: the sheet waits for it (a moment, from the phone), so the
        // categories open in their order and stay there.
        val habits: RecordHabits? = null,
        val showAllCategories: Boolean = false,
        val showExtras: Boolean = false,
        val busy: Boolean = false,
        val problem: RecordProblem? = null,
        val serverMessage: String? = null,
        val confirming: Pair<List<Change>, RecordInput>? = null,
        val confirmingDelete: Boolean = false,
    )

    private val sheet = MutableStateFlow(Sheet())

    /** True once the sheet is done (saved, deleted): it closes. */
    private val _finished = MutableStateFlow(false)
    val finished: StateFlow<Boolean> = _finished

    val uiState: StateFlow<RecordUiState> = combine(wallet.wallet.filterNotNull(), sheet) { w, s ->
        val form = s.form
        when {
            s.missing -> RecordUiState.Missing
            form == null || s.habits == null -> RecordUiState.Loading
            else -> ready(w, form, s, s.habits)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecordUiState.Loading)

    init {
        viewModelScope.launch {
            val w = wallet.wallet.filterNotNull().first()
            val exponent = w.settings.currencyExponent
            val record = route.id?.let { records.get(it) }
            if (route.id != null && record == null) {
                sheet.update { it.copy(missing = true) }
                return@launch
            }
            val form = if (record != null) {
                RecordForm.from(record, exponent)
            } else {
                val active = w.activeAccounts
                val last = records.lastAccount()?.takeIf { id -> active.any { it.id == id } }
                val account = last ?: active.firstOrNull()?.id ?: 0
                RecordForm(
                    occurredAt = clock.instant(),
                    accountId = account,
                    toAccountId = active.firstOrNull { it.id != account }?.id ?: 0,
                    payee = route.payee.orEmpty(),
                )
            }
            sheet.update { it.copy(form = form, record = record, showExtras = record != null || form.note.isNotEmpty() || form.tags.isNotEmpty()) }
        }
        viewModelScope.launch {
            val habits = records.habits()
            sheet.update { it.copy(habits = habits) }
            // The server's latest, for the next sheet: this one keeps its order.
            records.refreshHabits()
        }
    }

    private fun ready(w: Wallet, form: RecordForm, s: Sheet, habits: RecordHabits): RecordUiState.Ready {
        val ofKind = w.categories
            .filter { form.kind != TransactionKind.Transfer && it.kind.apiName == form.kind.apiName }
            .sortedByDescending { habits.categoryUse[it.id] ?: 0 }
        val folding = ofKind.size > FEW_CATEGORIES + 1
        val chosenHidden = ofKind.indexOfFirst { it.id == form.categoryId } >= FEW_CATEGORIES
        val shown = when {
            !folding || s.showAllCategories -> ofKind
            // A chosen category from further down takes the last place, so More stays where it is.
            else -> ofKind.filterIndexed { i, c -> i < FEW_CATEGORIES - (if (chosenHidden) 1 else 0) || c.id == form.categoryId }
        }
        val typed = form.payee.trim()
        return RecordUiState.Ready(
            wallet = w,
            form = form,
            record = s.record,
            categories = shown,
            canFoldCategories = folding,
            showAllCategories = s.showAllCategories,
            recentPayees = if (s.record == null && typed.isEmpty()) habits.payees.keys.take(FEW_PAYEES) else emptyList(),
            payeeSuggestions = if (typed.isEmpty()) emptyList() else habits.payees.keys.filter { it.contains(typed, ignoreCase = true) && it != typed }.take(5),
            tagSuggestions = habits.tags.filter { t -> form.tags.none { it.equals(t, ignoreCase = true) } },
            showExtras = s.showExtras,
            busy = s.busy,
            problem = s.problem,
            serverMessage = s.serverMessage,
            confirming = s.confirming?.first,
            confirmingDelete = s.confirmingDelete,
        )
    }

    private fun edit(change: (RecordForm) -> RecordForm) {
        sheet.update { s -> s.form?.let { s.copy(form = change(it), problem = null, serverMessage = null) } ?: s }
    }

    fun setKind(kind: TransactionKind) = edit { f ->
        val keep = (uiState.value as? RecordUiState.Ready)?.wallet?.category(f.categoryId)?.kind?.apiName == kind.apiName
        f.copy(kind = kind, categoryId = if (keep) f.categoryId else null)
    }

    fun press(key: PadKey) {
        val exponent = (uiState.value as? RecordUiState.Ready)?.wallet?.settings?.currencyExponent ?: 0
        edit { it.copy(amount = pressKey(it.amount, key, exponent)) }
    }

    /** Choosing the chosen category again clears it. */
    fun chooseCategory(id: Long) = edit { it.copy(categoryId = if (it.categoryId == id) null else id) }

    fun showAllCategories(show: Boolean) = sheet.update { it.copy(showAllCategories = show) }

    /** A payee typed or picked; one paid before brings what was chosen there last (on a new record). */
    fun setPayee(payee: String) {
        val s = sheet.value
        val habit = s.habits?.payees?.get(payee)?.takeIf { s.record == null }
        val active = (uiState.value as? RecordUiState.Ready)?.wallet?.activeAccounts.orEmpty()
        edit { f ->
            if (habit == null) return@edit f.copy(payee = payee)
            f.copy(
                payee = payee,
                kind = if (habit.kind != TransactionKind.Transfer) habit.kind else f.kind,
                categoryId = f.categoryId ?: habit.categoryId,
                accountId = if (active.any { it.id == habit.accountId }) habit.accountId else f.accountId,
            )
        }
    }

    fun setAccount(id: Long) = edit { it.copy(accountId = id) }

    fun setToAccount(id: Long) = edit { it.copy(toAccountId = id) }

    fun setTime(at: java.time.Instant) = edit { it.copy(occurredAt = at) }

    fun setNote(note: String) = edit { it.copy(note = note.take(500)) }

    fun addTag(text: String) = edit { it.copy(tags = addTag(it.tags, text)) }

    fun removeTag(tag: String) = edit { it.copy(tags = it.tags - tag) }

    fun showExtras() = sheet.update { it.copy(showExtras = true) }

    /** Save (or Confirm). An edit first shows what it changes. */
    fun save(): Boolean {
        val state = uiState.value as? RecordUiState.Ready ?: return false
        val input = state.form.toInput(state.wallet.settings.currencyExponent, state.record)
        if (input == null) {
            sheet.update { it.copy(problem = RecordProblem.NoAmount) }
            return false
        }
        val record = state.record
        if (record != null && !state.reviewing) {
            val changes = describeChanges(record, input)
            if (changes.isEmpty()) {
                _finished.value = true
                return true
            }
            sheet.update { it.copy(confirming = changes to input) }
            return true
        }
        commit(input)
        return true
    }

    fun confirmChanges() {
        val input = sheet.value.confirming?.second ?: return
        commit(input)
    }

    fun cancelChanges() = sheet.update { it.copy(confirming = null) }

    private fun commit(input: RecordInput) {
        val record = sheet.value.record
        sheet.update { it.copy(busy = true, confirming = null) }
        viewModelScope.launch {
            val result = if (record == null) records.add(input) else records.update(record.id, input)
            when (result) {
                is SaveResult.Saved, SaveResult.Queued, SaveResult.Done -> {
                    events.send(
                        when {
                            record == null && result == SaveResult.Queued -> RecordEvent.Queued
                            record == null -> RecordEvent.Added(input.amount)
                            record.status == TransactionStatus.Review -> RecordEvent.Confirmed
                            record.pending != null -> RecordEvent.Queued
                            // Putting the old one back mustn't teach its category to the shop again.
                            else -> RecordEvent.Changed(record.id, record.toInputForUndo())
                        },
                    )
                    _finished.value = true
                }
                else -> fail(result)
            }
        }
    }

    fun askDelete() = sheet.update { it.copy(confirmingDelete = true) }

    fun cancelDelete() = sheet.update { it.copy(confirmingDelete = false) }

    fun delete() {
        val record = sheet.value.record ?: return
        sheet.update { it.copy(busy = true, confirmingDelete = false) }
        viewModelScope.launch {
            when (val result = records.delete(record.id)) {
                SaveResult.Done -> {
                    events.send(RecordEvent.Deleted(record.id, restorable = record.pending == null))
                    _finished.value = true
                }
                else -> fail(result)
            }
        }
    }

    private fun fail(result: SaveResult) = sheet.update {
        it.copy(
            busy = false,
            problem = when (result) {
                SaveResult.Offline -> RecordProblem.Offline
                SaveResult.SentMeanwhile -> RecordProblem.SentMeanwhile
                SaveResult.SignedOut -> RecordProblem.SignedOut
                else -> null
            },
            serverMessage = (result as? SaveResult.Refused)?.message,
        )
    }
}

private fun Transaction.toInputForUndo() = RecordInput(
    kind, amount, accountId, toAccountId, categoryId, occurredAt, payee, note, status, tags, remember = false,
)

/** Why a record from the outbox wasn't taken, if the server said. */
internal val Transaction.refusal: String? get() = (pending as? Pending.Refused)?.message
