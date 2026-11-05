package com.rudnam.wallet.core.testing

import com.rudnam.wallet.core.data.repository.SyncResult
import com.rudnam.wallet.core.data.repository.UpdateRepository
import com.rudnam.wallet.core.data.repository.WalletRepository
import com.rudnam.wallet.core.model.AppUpdate
import com.rudnam.wallet.core.model.Budget
import com.rudnam.wallet.core.model.Transaction
import com.rudnam.wallet.core.model.Wallet
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.time.Instant

/** A [WalletRepository] the test fills in, and whose refreshes it decides. */
class TestWalletRepository : WalletRepository {
    private val walletFlow = MutableStateFlow<Wallet?>(null)
    private val budgetsFlow = MutableStateFlow<List<Budget>>(emptyList())
    private val records = MutableStateFlow<List<Transaction>>(emptyList())

    /** What the next refreshes answer. */
    var nextSync: SyncResult = SyncResult.Done
    var refreshes = 0
        private set
    var notificationsSent: Boolean? = true

    fun send(wallet: Wallet?, budgets: List<Budget> = emptyList(), transactions: List<Transaction> = emptyList()) {
        walletFlow.value = wallet
        budgetsFlow.value = budgets
        records.value = transactions
    }

    override val wallet: Flow<Wallet?> = walletFlow
    override val budgets: Flow<List<Budget>> = budgetsFlow

    override fun transactions(from: Instant, to: Instant): Flow<List<Transaction>> =
        records.map { list -> list.filter { it.occurredAt >= from && it.occurredAt < to } }

    override suspend fun refresh(from: Instant, to: Instant): SyncResult {
        refreshes++
        return nextSync
    }

    override suspend fun hasSentNotifications(): Boolean? = notificationsSent

    override suspend fun forget() = send(null)
}

class TestUpdateRepository(var update: AppUpdate? = null) : UpdateRepository {
    val skipped = mutableListOf<AppUpdate>()

    override suspend fun check(): AppUpdate? = update

    override suspend fun later(update: AppUpdate) {
        skipped += update
        this.update = null
    }
}

/** A [RecordRepository] that keeps what it's asked, and answers as the test says. */
class TestRecordRepository(private val wallet: TestWalletRepository? = null) : com.rudnam.wallet.core.data.repository.RecordRepository {
    val added = mutableListOf<com.rudnam.wallet.core.model.RecordInput>()
    val updated = mutableListOf<Pair<Long, com.rudnam.wallet.core.model.RecordInput>>()
    val deleted = mutableListOf<Long>()
    var next: com.rudnam.wallet.core.data.repository.SaveResult = com.rudnam.wallet.core.data.repository.SaveResult.Done
    var records: Map<Long, Transaction> = emptyMap()
    var habits = com.rudnam.wallet.core.data.repository.RecordHabits(emptyMap(), emptyMap(), emptyList())
    var lastAccount: Long? = null

    override suspend fun add(input: com.rudnam.wallet.core.model.RecordInput) = next.also { added += input }
    override suspend fun update(id: Long, input: com.rudnam.wallet.core.model.RecordInput) = next.also { updated += id to input }
    override suspend fun delete(id: Long) = next.also { deleted += id }
    override suspend fun restore(id: Long) = next
    override suspend fun get(id: Long) = records[id]
    override suspend fun habits() = habits
    override suspend fun lastAccount() = lastAccount
    override suspend fun sendPending() = SyncResult.Done
}

/** An [ActivityRepository] the test fills in; it keeps the changes it's asked for. */
class TestActivityRepository : com.rudnam.wallet.core.data.repository.ActivityRepository {
    private val review = MutableStateFlow<List<Transaction>>(emptyList())
    val changes = mutableListOf<com.rudnam.wallet.core.model.BulkChange>()
    val restored = mutableListOf<List<Long>>()
    var searchResult: com.rudnam.wallet.core.data.repository.SearchResult = com.rudnam.wallet.core.data.repository.SearchResult.Found(emptyList(), false)
    var bulkOutcome: com.rudnam.wallet.core.data.repository.BulkOutcome? = null
    var tagList = listOf(com.rudnam.wallet.core.model.Tag("dinner", 4), com.rudnam.wallet.core.model.Tag("trip", 2))
    var matches: Map<Long, Transaction> = emptyMap()

    fun sendReview(list: List<Transaction>) {
        review.value = list
    }

    override val toReview: Flow<List<Transaction>> = review
    override suspend fun refreshReview() = SyncResult.Done
    override suspend fun search(filter: com.rudnam.wallet.core.model.RecordFilter) = searchResult
    override suspend fun bulk(change: com.rudnam.wallet.core.model.BulkChange) =
        bulkOutcome ?: com.rudnam.wallet.core.data.repository.BulkOutcome.Done(com.rudnam.wallet.core.model.BulkResult(change.ids.size, 0)).also { changes += change }
    override suspend fun restore(ids: List<Long>) = com.rudnam.wallet.core.data.repository.BulkOutcome.Done(com.rudnam.wallet.core.model.BulkResult(ids.size, 0)).also { restored += ids }
    override suspend fun tags() = tagList
    override suspend fun match(id: Long, near: java.time.Instant) = matches[id]
}

/** An [AccountRepository] that keeps what it's asked. */
class TestAccountRepository : com.rudnam.wallet.core.data.repository.AccountRepository {
    val counted = mutableListOf<Pair<Long, Long>>()
    val orders = mutableListOf<List<Long>>()
    var countResult: com.rudnam.wallet.core.data.repository.CountResult = com.rudnam.wallet.core.data.repository.CountResult.AlreadyRight

    override suspend fun count(accountId: Long, balance: Long) = countResult.also { counted += accountId to balance }
    override suspend fun setOrder(ids: List<Long>) = true.also { orders += ids }
}

/**
 * An [InsightsRepository] that answers each period with [answer], or with [failure]; budgets saved go to [wallet] as the real one's
 * go to the database.
 */
class TestInsightsRepository(private val wallet: TestWalletRepository? = null) : com.rudnam.wallet.core.data.repository.InsightsRepository {
    var answer: (com.rudnam.wallet.core.data.repository.StatsQuery) -> com.rudnam.wallet.core.model.Stats = { com.rudnam.wallet.core.model.Stats.Empty }
    var failure: com.rudnam.wallet.core.data.repository.StatsResult? = null
    var budgetResult: com.rudnam.wallet.core.data.repository.BudgetResult = com.rudnam.wallet.core.data.repository.BudgetResult.Saved
    val asked = mutableListOf<com.rudnam.wallet.core.data.repository.StatsQuery>()
    val seen = HashMap<com.rudnam.wallet.core.data.repository.StatsQuery, com.rudnam.wallet.core.model.Stats>()
    val created = mutableListOf<com.rudnam.wallet.core.model.BudgetInput>()
    val updated = mutableListOf<Pair<Long, com.rudnam.wallet.core.model.BudgetInput>>()
    val deleted = mutableListOf<Long>()

    override fun seen(query: com.rudnam.wallet.core.data.repository.StatsQuery) = seen[query]

    override suspend fun stats(query: com.rudnam.wallet.core.data.repository.StatsQuery): com.rudnam.wallet.core.data.repository.StatsResult {
        asked += query
        failure?.let { return it }
        return com.rudnam.wallet.core.data.repository.StatsResult.Done(answer(query).also { seen[query] = it })
    }

    override suspend fun createBudget(input: com.rudnam.wallet.core.model.BudgetInput) = budgetResult.also { created += input }

    override suspend fun updateBudget(id: Long, input: com.rudnam.wallet.core.model.BudgetInput) = budgetResult.also { updated += id to input }

    override suspend fun deleteBudget(id: Long) = budgetResult.also { deleted += id }
}
