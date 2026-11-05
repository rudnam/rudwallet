package com.rudnam.wallet.core.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rudnam.wallet.core.data.repository.BudgetResult
import com.rudnam.wallet.core.data.repository.OfflineFirstWalletRepository
import com.rudnam.wallet.core.data.repository.ServerInsightsRepository
import com.rudnam.wallet.core.data.repository.StatsQuery
import com.rudnam.wallet.core.data.repository.StatsResult
import com.rudnam.wallet.core.model.BudgetInput
import com.rudnam.wallet.core.data.repository.SyncResult
import com.rudnam.wallet.core.data.repository.StatsCache
import com.rudnam.wallet.core.database.WalletCache
import com.rudnam.wallet.core.database.WalletDatabase
import com.rudnam.wallet.core.network.SignedOutException
import com.rudnam.wallet.core.network.WalletNetworkDataSource
import com.rudnam.wallet.core.network.model.NetworkAccount
import com.rudnam.wallet.core.network.model.NetworkApk
import com.rudnam.wallet.core.network.model.NetworkBudget
import com.rudnam.wallet.core.network.model.NetworkDownloads
import com.rudnam.wallet.core.network.model.NetworkNotifications
import com.rudnam.wallet.core.network.model.NetworkSettings
import com.rudnam.wallet.core.network.model.NetworkTransaction
import com.rudnam.wallet.core.network.model.NetworkWallet
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OfflineFirstWalletRepositoryTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), WalletDatabase::class.java)
        .allowMainThreadQueries().build()

    @After
    fun close() = db.close()

    private class FakeNetwork : WalletNetworkDataSource {
        var failure: Exception? = null
        var records = listOf(
            NetworkTransaction(1, "expense", 480, 1, occurredAt = "2026-11-14T08:10:00+09:00", payee = "Doutor"),
            NetworkTransaction(2, "income", 280000, 1, occurredAt = "2026-11-10T09:00:00+09:00", payee = "Acme KK"),
        )

        private fun check() = failure?.let { throw it }

        override suspend fun wallet(): NetworkWallet {
            check()
            return NetworkWallet(NetworkSettings("JPY"), listOf(NetworkAccount(1, "Cash", "cash", balance = 26450)), emptyList(), reviewCount = 1)
        }

        override suspend fun transactions(from: Instant, to: Instant): List<NetworkTransaction> {
            check()
            return records
        }

        override suspend fun budgets(): List<NetworkBudget> {
            check()
            return budgetList.toList()
        }

        override suspend fun notifications() = NetworkNotifications()

        override suspend fun downloads() = NetworkDownloads(NetworkApk("/download/wallet.apk", 5))

        override suspend fun confirmedSince(from: Instant) = records
        override suspend fun createRecord(input: com.rudnam.wallet.core.network.model.NetworkRecordInput) = error("not used")
        override suspend fun updateRecord(id: Long, input: com.rudnam.wallet.core.network.model.NetworkRecordInput) = error("not used")
        override suspend fun deleteRecord(id: Long) = Unit
        override suspend fun restoreRecords(ids: List<Long>) = Unit
        override suspend fun search(
            from: Instant?, to: Instant?, accountId: Long?, categoryId: String?, query: String?, tag: String?, status: String?,
        ) = emptyList<NetworkTransaction>()
        override suspend fun bulk(change: com.rudnam.wallet.core.network.model.NetworkBulkChange) =
            com.rudnam.wallet.core.network.model.NetworkBulkResult(change.ids.size, 0)
        override suspend fun reconcile(accountId: Long, balance: Long) = com.rudnam.wallet.core.network.model.NetworkReconcileResult()
        override suspend fun setAccountOrder(ids: List<Long>) = Unit
        override suspend fun tags() = emptyList<com.rudnam.wallet.core.network.model.NetworkTag>()
        var budgetList = mutableListOf(NetworkBudget(1, "Everything", null, 60000))
        var asked = 0
        override suspend fun createBudget(input: com.rudnam.wallet.core.network.model.NetworkBudgetInput): NetworkBudget {
            check()
            if (input.amount <= 0) throw com.rudnam.wallet.core.network.ApiException(400, "enter a monthly limit above zero")
            return NetworkBudget(budgetList.maxOf { it.id } + 1, input.name, input.categoryId, input.amount).also { budgetList += it }
        }
        override suspend fun updateBudget(id: Long, input: com.rudnam.wallet.core.network.model.NetworkBudgetInput): NetworkBudget {
            check()
            return NetworkBudget(id, input.name, input.categoryId, input.amount)
        }
        override suspend fun deleteBudget(id: Long) {
            check()
            if (budgetList.none { it.id == id }) throw com.rudnam.wallet.core.network.ApiException(404, "not found")
            budgetList.removeAll { it.id == id }
        }
        override suspend fun stats(from: Instant, to: Instant, zone: String, accountId: Long?): com.rudnam.wallet.core.network.model.NetworkStats {
            check()
            asked++
            return com.rudnam.wallet.core.network.model.NetworkStats(
                spent = 480,
                income = 280000,
                categories = listOf(com.rudnam.wallet.core.network.model.NetworkCategoryTotal(10, "expense", 480, 1)),
                days = listOf(com.rudnam.wallet.core.network.model.NetworkDayTotal("2026-11-14", 480, 0)),
            )
        }
    }

    private val from = Instant.parse("2026-10-31T15:00:00Z")
    private val to = Instant.parse("2026-11-30T15:00:00Z")

    @Test
    fun `a refresh fills the phone, offline keeps it, and signing out empties it`() = runTest {
        val network = FakeNetwork()
        val repository = OfflineFirstWalletRepository(network, db.walletDao(), db.transactionDao(), db.budgetDao(), db.outboxDao(), WalletCache(db), StatsCache(), StandardTestDispatcher(testScheduler))
        assertNull(repository.wallet.first())

        assertEquals(SyncResult.Done, repository.refresh(from, to))
        assertEquals(26450L, repository.wallet.first()!!.totalBalance)
        assertEquals(listOf(1L, 2L), repository.transactions(from, to).first().map { it.id })
        assertEquals(1, repository.budgets.first().size)

        network.failure = IOException("offline")
        assertEquals(SyncResult.Offline, repository.refresh(from, to))
        assertEquals(2, repository.transactions(from, to).first().size)

        // Deleted elsewhere meanwhile: gone from the phone too.
        network.failure = null
        network.records = network.records.take(1)
        repository.refresh(from, to)
        assertEquals(listOf(1L), repository.transactions(from, to).first().map { it.id })

        network.failure = SignedOutException()
        assertEquals(SyncResult.SignedOut, repository.refresh(from, to))
        repository.forget()
        assertNull(repository.wallet.first())
    }

    @Test
    fun `stats are kept in memory until sign-out, and budgets saved here show at once`() = runTest {
        val network = FakeNetwork()
        val io = StandardTestDispatcher(testScheduler)
        val stats = StatsCache()
        val wallet = OfflineFirstWalletRepository(network, db.walletDao(), db.transactionDao(), db.budgetDao(), db.outboxDao(), WalletCache(db), stats, io)
        val insights = ServerInsightsRepository(network, db.budgetDao(), stats, io)
        val query = StatsQuery(from, to, ZoneId.of("Asia/Tokyo"))

        assertNull(insights.seen(query))
        val done = insights.stats(query) as StatsResult.Done
        assertEquals(480L, done.stats.spent)
        assertEquals(LocalDate.of(2026, 11, 14), done.stats.days.single().day)
        assertEquals(done.stats, insights.seen(query))
        // Another account is another question.
        assertNull(insights.seen(query.copy(accountId = 1)))

        network.failure = IOException("offline")
        assertEquals(StatsResult.Offline, insights.stats(query))
        assertEquals(done.stats, insights.seen(query))
        network.failure = null

        wallet.refresh(from, to)
        assertEquals(BudgetResult.Saved, insights.createBudget(BudgetInput("Eating out", 10, 8000)))
        assertEquals(listOf("Everything", "Eating out"), wallet.budgets.first().map { it.name })
        assertEquals(BudgetResult.Refused("enter a monthly limit above zero"), insights.createBudget(BudgetInput("Nothing", null, 0)))
        assertEquals(BudgetResult.Saved, insights.updateBudget(1, BudgetInput("All of it", null, 70000)))
        assertEquals(70000L, wallet.budgets.first().first { it.id == 1L }.amount)
        assertEquals(BudgetResult.Saved, insights.deleteBudget(2))
        // Deleted on the website meanwhile: gone, as the user wanted.
        assertEquals(BudgetResult.Saved, insights.deleteBudget(2))
        assertEquals(listOf(1L), wallet.budgets.first().map { it.id })

        wallet.forget()
        assertNull(insights.seen(query))
    }
}
