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
