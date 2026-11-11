package com.rudnam.wallet.feature.activity

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.rudnam.wallet.core.data.repository.ActivityRepository
import com.rudnam.wallet.core.data.repository.BulkOutcome
import com.rudnam.wallet.core.data.repository.RecordRepository
import com.rudnam.wallet.core.data.repository.SaveResult
import com.rudnam.wallet.core.data.repository.SearchResult
import com.rudnam.wallet.core.data.repository.SetAsideResult
import com.rudnam.wallet.core.data.repository.WalletRepository
import com.rudnam.wallet.core.model.BulkChange
import com.rudnam.wallet.core.model.RecordFilter
import com.rudnam.wallet.core.model.Tag
import com.rudnam.wallet.core.model.Transaction
import com.rudnam.wallet.core.model.TransactionKind
import com.rudnam.wallet.core.model.TransactionStatus
import com.rudnam.wallet.core.model.Wallet
import com.rudnam.wallet.core.model.asInput
import com.rudnam.wallet.feature.activity.navigation.ActivityRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.YearMonth
import javax.inject.Inject

/** A record waiting for a check, as "to check" shows it. */
data class ReviewItem(
    val record: Transaction,
    /** The category picked here (not saved yet), or the record's own. */
    val categoryId: Long?,
    /** Changing the category the shop's rule filled in: whether the shop should keep the new one. */
    val overriding: Boolean,
    val remember: Boolean,
    /** The record a possible duplicate matches, once it's found. */
    val match: Transaction?,
) {
    /** Confirmable as it is: an amount, a category, and not a possible duplicate. */
    val ready: Boolean get() = record.amount > 0 && record.duplicateOfId == null && record.kind != TransactionKind.Transfer && categoryId != null
}

sealed interface ActivityUiState {
    data object Loading : ActivityUiState

    data class Ready(
        val wallet: Wallet,
        val month: YearMonth,
        val filter: RecordFilter,
        /** The records shown (those to review are in [toReview] instead); null while a search is under way. */
        val records: List<Transaction>?,
        val capped: Boolean,
        val searchOffline: Boolean,
        val spent: Long,
        val income: Long,
        val tags: List<Tag>,
        val toReview: List<ReviewItem>,
        /** The chosen records while choosing; null when not. */
        val selected: Set<Long>?,
        val refreshing: Boolean,
        val busy: Boolean,
        /** Possible duplicates the server set aside lately, rather than adding them to "to check". */
        val caught: Int = 0,
    ) : ActivityUiState {
        val ready: List<ReviewItem> get() = toReview.filter { it.ready }
    }
}

/** What the snackbar says after something here, with what Undo does if it can. */
sealed interface ActivityMessage {
    data class ConfirmedOne(val payee: String) : ActivityMessage
    data class Confirmed(val count: Int) : ActivityMessage
    data class DeletedOne(val payee: String, val id: Long) : ActivityMessage
    data class Bulk(val change: BulkChange, val changed: Int, val skipped: Int) : ActivityMessage
    data class Restored(val count: Int) : ActivityMessage
    data object Offline : ActivityMessage
    data class Failed(val message: String) : ActivityMessage
}

/** What Activity's controls do. */
interface ActivityActions {
    fun refresh(userAsked: Boolean = true)
    fun previousMonth()
    fun nextMonth()
    fun setAccount(id: Long?)
    fun setCategory(id: Long?)
    fun search(query: String)
    fun setTag(tag: String?)
    fun clearSearch()
    fun pickCategory(record: Long, category: Long?)
    fun setRemember(record: Long, on: Boolean)
    fun confirm(item: ReviewItem)
    fun confirmAll()
    fun deleteDuplicate(item: ReviewItem)
    fun startChoosing()
    fun toggle(id: Long)
    fun chooseAll(all: Boolean)
    fun bulk(change: BulkChange)
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ActivityViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val wallet: WalletRepository,
    private val activity: ActivityRepository,
    private val records: RecordRepository,
    private val clock: Clock,
) : ViewModel(), ActivityActions {
    private val route = savedState.toRoute<ActivityRoute>()

    private val month = MutableStateFlow(route.month?.let { runCatching { YearMonth.parse(it) }.getOrNull() } ?: YearMonth.now(clock))
    private val filter = MutableStateFlow(route.filter)
    private val search = MutableStateFlow<SearchResult?>(null)
    private val tags = MutableStateFlow<List<Tag>>(emptyList())
    private val picked = MutableStateFlow<Map<Long, Long?>>(emptyMap())
    private val remember = MutableStateFlow<Map<Long, Boolean>>(emptyMap())
    private val matches = MutableStateFlow<Map<Long, Transaction>>(emptyMap())
    private val selected = MutableStateFlow<Set<Long>?>(null)
    private val refreshing = MutableStateFlow(false)
    private val busy = MutableStateFlow(false)
    private val caught = MutableStateFlow(0)

    private val _messages = MutableStateFlow<List<ActivityMessage>>(emptyList())
    val messages: StateFlow<List<ActivityMessage>> = _messages.asStateFlow()

    /** The month's records from the phone, or the search's from the server. */
    private val shown: Flow<Pair<List<Transaction>?, SearchResult?>> = combine(month, filter, search) { m, f, s -> Triple(m, f, s) }
        .flatMapLatest { (m, f, s) ->
            if (f.everyMonth) {
                flowOf(((s as? SearchResult.Found)?.records) to s)
            } else {
                val zone = clock.zone
                // The wallet tells which categories are under the one filtered by.
                combine(
                    wallet.transactions(m.atDay(1).atStartOfDay(zone).toInstant(), m.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant()),
                    wallet.wallet,
                ) { list, w -> list.filter { f.matches(it, w) } to null }
            }
        }

    private val review: Flow<List<ReviewItem>> = combine(activity.toReview, picked, remember, matches) { list, p, r, m ->
        list.map { t ->
            val chosen = if (t.id in p) p[t.id] else t.categoryId
            ReviewItem(
                record = t,
                categoryId = chosen,
                overriding = t.payee.isNotEmpty() && t.categoryId != null && chosen != null && chosen != t.categoryId,
                remember = r[t.id] ?: false,
                match = t.duplicateOfId?.let(m::get),
            )
        }
    }

    private val view = combine(month, filter, shown, tags, review) { m, f, s, t, r -> View(m, f, s.first, s.second, t, r) }

    private data class View(
        val month: YearMonth, val filter: RecordFilter, val records: List<Transaction>?, val search: SearchResult?,
        val tags: List<Tag>, val review: List<ReviewItem>,
    )

    private val flags = combine(selected, refreshing, busy, caught) { sel, ref, b, c -> Flags(sel, ref, b, c) }

    private data class Flags(val selected: Set<Long>?, val refreshing: Boolean, val busy: Boolean, val caught: Int)

    val uiState: StateFlow<ActivityUiState> = combine(wallet.wallet, view, flags) { w, v, (sel, ref, b, c) ->
        if (w == null) return@combine ActivityUiState.Loading
        // Records to review are in "to check", above the list.
        val list = v.records?.filter { it.status != TransactionStatus.Review }
        val confirmed = v.records.orEmpty().filter { it.confirmed }
        ActivityUiState.Ready(
            wallet = w,
            month = v.month,
            filter = v.filter,
            records = list,
            capped = (v.search as? SearchResult.Found)?.capped == true,
            searchOffline = v.search == SearchResult.Offline,
            spent = confirmed.filter { it.kind == TransactionKind.Expense }.sumOf { it.amount },
            income = confirmed.filter { it.kind == TransactionKind.Income }.sumOf { it.amount },
            tags = v.tags,
            toReview = v.review,
            selected = sel?.let { s -> list.orEmpty().map { it.id }.filter { it in s }.toSet() },
            refreshing = ref,
            busy = b,
            caught = c,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ActivityUiState.Loading)

    init {
        refresh(userAsked = false)
        viewModelScope.launch {
            // Possible duplicates show the record they match.
            activity.toReview.collect { list ->
                for (t in list) {
                    val id = t.duplicateOfId ?: continue
                    if (id !in matches.value) activity.match(id, t.occurredAt)?.let { found -> matches.update { it + (id to found) } }
                }
            }
        }
    }

    override fun refresh(userAsked: Boolean) {
        viewModelScope.launch {
            refreshing.value = userAsked
            val m = month.value
            val zone = clock.zone
            val sync = wallet.refresh(m.atDay(1).atStartOfDay(zone).toInstant(), m.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant())
            activity.refreshReview()
            if (filter.value.everyMonth) search.value = activity.search(filter.value)
            tags.value = activity.tags()
            (activity.setAside() as? SetAsideResult.Found)?.let { caught.value = it.records.size }
            refreshing.value = false
            if (userAsked && sync == com.rudnam.wallet.core.data.repository.SyncResult.Offline) post(ActivityMessage.Offline)
        }
    }

    private fun applyFilter(f: RecordFilter) {
        filter.value = f
        selected.update { it?.let { emptySet() } }
        search.value = null
        if (f.everyMonth) viewModelScope.launch { search.value = activity.search(f) } else refresh(userAsked = false)
    }

    override fun previousMonth() = setMonth(month.value.minusMonths(1))

    override fun nextMonth() = setMonth(month.value.plusMonths(1))

    private fun setMonth(m: YearMonth) {
        month.value = m
        selected.update { it?.let { emptySet() } }
        refresh(userAsked = false)
    }

    override fun setAccount(id: Long?) = applyFilter(filter.value.copy(accountId = id))

    override fun setCategory(id: Long?) = applyFilter(filter.value.copy(categoryId = id))

    override fun search(query: String) = applyFilter(filter.value.copy(query = query.trim()))

    override fun setTag(tag: String?) = applyFilter(filter.value.copy(tag = tag.orEmpty()))

    /** Back to the month: the search and the tag go. */
    override fun clearSearch() = applyFilter(filter.value.copy(query = "", tag = ""))

    override fun pickCategory(record: Long, category: Long?) = picked.update { it + (record to category) }

    override fun setRemember(record: Long, on: Boolean) = remember.update { it + (record to on) }

    /** Confirms one record to review, with the category picked here. */
    override fun confirm(item: ReviewItem) {
        val t = item.record
        viewModelScope.launch {
            busy.value = true
            val input = t.asInput(remember = if (item.overriding) item.remember else null).copy(
                categoryId = item.categoryId,
                status = TransactionStatus.Confirmed,
            )
            when (val result = records.update(t.id, input)) {
                is SaveResult.Saved, SaveResult.Done -> {
                    post(ActivityMessage.ConfirmedOne(t.payee))
                    afterChange()
                }
                else -> fail(result)
            }
            busy.value = false
        }
    }

    /** Confirms every record to review that's ready, with the categories picked here. */
    override fun confirmAll() {
        val ready = (uiState.value as? ActivityUiState.Ready)?.ready ?: return
        viewModelScope.launch {
            busy.value = true
            // Categories picked here first, one change per category.
            for ((category, items) in ready.filter { it.categoryId != it.record.categoryId }.groupBy { it.categoryId }) {
                val outcome = activity.bulk(BulkChange.Categorize(items.map { it.record.id }, category))
                if (outcome !is BulkOutcome.Done) {
                    failBulk(outcome)
                    busy.value = false
                    return@launch
                }
            }
            when (val outcome = activity.bulk(BulkChange.Confirm(ready.map { it.record.id }))) {
                is BulkOutcome.Done -> {
                    picked.value = emptyMap()
                    post(ActivityMessage.Confirmed(outcome.result.changed))
                    afterChange()
                }
                else -> failBulk(outcome)
            }
            busy.value = false
        }
    }

    /** Deletes a possible duplicate; Undo brings it back. */
    override fun deleteDuplicate(item: ReviewItem) {
        viewModelScope.launch {
            busy.value = true
            when (val result = records.delete(item.record.id)) {
                SaveResult.Done -> {
                    post(ActivityMessage.DeletedOne(item.record.payee, item.record.id))
                    afterChange()
                }
                else -> fail(result)
            }
            busy.value = false
        }
    }

    override fun startChoosing() = selected.update { if (it == null) emptySet() else null }

    override fun toggle(id: Long) = selected.update { s -> s?.let { if (id in it) it - id else it + id } }

    override fun chooseAll(all: Boolean) {
        val list = (uiState.value as? ActivityUiState.Ready)?.records.orEmpty()
        selected.value = if (all) list.map { it.id }.toSet() else emptySet()
    }

    /** A change to every chosen record. */
    override fun bulk(change: BulkChange) {
        viewModelScope.launch {
            busy.value = true
            when (val outcome = activity.bulk(change)) {
                is BulkOutcome.Done -> {
                    post(ActivityMessage.Bulk(change, outcome.result.changed, outcome.result.skipped))
                    if (change is BulkChange.Delete) selected.value = emptySet()
                    afterChange()
                }
                else -> failBulk(outcome)
            }
            busy.value = false
        }
    }

    /** Undo for a delete: the records come back. */
    fun restore(ids: List<Long>) {
        viewModelScope.launch {
            when (val outcome = activity.restore(ids)) {
                is BulkOutcome.Done -> {
                    post(ActivityMessage.Restored(ids.size))
                    afterChange()
                }
                else -> failBulk(outcome)
            }
        }
    }

    fun messageShown(message: ActivityMessage) = _messages.update { it - message }

    private fun post(message: ActivityMessage) = _messages.update { it + message }

    private fun afterChange() = refresh(userAsked = false)

    private fun fail(result: SaveResult) = post(
        when (result) {
            is SaveResult.Refused -> ActivityMessage.Failed(result.message)
            else -> ActivityMessage.Offline
        },
    )

    private fun failBulk(outcome: BulkOutcome) = post(
        when (outcome) {
            is BulkOutcome.Refused -> ActivityMessage.Failed(outcome.message)
            else -> ActivityMessage.Offline
        },
    )
}
