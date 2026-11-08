package com.rudnam.wallet.core.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rudnam.wallet.core.data.repository.OfflineFirstRecordRepository
import com.rudnam.wallet.core.data.repository.OfflineFirstWalletRepository
import com.rudnam.wallet.core.data.repository.SaveResult
import com.rudnam.wallet.core.data.repository.SyncResult
import com.rudnam.wallet.core.data.sync.OutboxScheduler
import com.rudnam.wallet.core.data.repository.HabitsCache
import com.rudnam.wallet.core.data.repository.StatsCache
import com.rudnam.wallet.core.database.WalletCache
import com.rudnam.wallet.core.database.WalletDatabase
import com.rudnam.wallet.core.model.Pending
import com.rudnam.wallet.core.model.RecordInput
import com.rudnam.wallet.core.model.TransactionKind
import com.rudnam.wallet.core.model.TransactionStatus
import com.rudnam.wallet.core.network.ApiException
import com.rudnam.wallet.core.network.WalletNetworkDataSource
import com.rudnam.wallet.core.network.model.NetworkAccount
import com.rudnam.wallet.core.network.model.NetworkDownloads
import com.rudnam.wallet.core.network.model.NetworkNotifications
import com.rudnam.wallet.core.network.model.NetworkRecordInput
import com.rudnam.wallet.core.network.model.NetworkSettings
import com.rudnam.wallet.core.network.model.NetworkTag
import com.rudnam.wallet.core.network.model.NetworkTransaction
import com.rudnam.wallet.core.network.model.NetworkWallet
import com.rudnam.wallet.core.testing.TestUserPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** Records added with and without a connection, sent later, refused, changed and deleted on the phone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OfflineFirstRecordRepositoryTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), WalletDatabase::class.java)
        .allowMainThreadQueries().build()

    @After
    fun close() = db.close()

    private class FakeNetwork : WalletNetworkDataSource {
        var offline = false
        var refuse: String? = null
        val created = mutableListOf<NetworkRecordInput>()
        private var nextId = 100L

        private fun check() {
            if (offline) throw IOException("offline")
            refuse?.let { throw ApiException(400, it) }
        }

        override suspend fun wallet() = NetworkWallet(NetworkSettings("JPY"), listOf(NetworkAccount(1, "Cash", "cash", balance = 10_000), NetworkAccount(2, "Bank", "bank", balance = 50_000)), emptyList())
        override suspend fun transactions(from: Instant, to: Instant) = emptyList<NetworkTransaction>()
        override suspend fun budgets() = emptyList<com.rudnam.wallet.core.network.model.NetworkBudget>()
        override suspend fun notifications() = NetworkNotifications()
        override suspend fun downloads() = NetworkDownloads()
        override suspend fun confirmedSince(from: Instant) = emptyList<NetworkTransaction>()
        override suspend fun search(
            from: Instant?, to: Instant?, accountId: Long?, categoryId: String?, query: String?, tag: String?, status: String?,
            withSubcategories: Boolean,
        ) = emptyList<NetworkTransaction>()
        override suspend fun bulk(change: com.rudnam.wallet.core.network.model.NetworkBulkChange) =
            com.rudnam.wallet.core.network.model.NetworkBulkResult(change.ids.size, 0)
        override suspend fun reconcile(accountId: Long, balance: Long) = com.rudnam.wallet.core.network.model.NetworkReconcileResult()
        override suspend fun setAccountOrder(ids: List<Long>) = Unit
        override suspend fun tags() = listOf(NetworkTag("trip", 3))
        override suspend fun widgetSummary(from: Instant, to: Instant) = com.rudnam.wallet.core.network.model.NetworkWidgetSummary()
        override suspend fun sendNotifications(notifications: List<com.rudnam.wallet.core.network.model.NetworkIncomingNotification>) {
            sentNotifications += notifications
        }
        val sentNotifications = mutableListOf<com.rudnam.wallet.core.network.model.NetworkIncomingNotification>()
        override suspend fun authOptions() = com.rudnam.wallet.core.network.model.NetworkAuthOptions()
        override suspend fun login(email: String, password: String) = com.rudnam.wallet.core.network.model.NetworkMe(email, token = "t")
        override suspend fun signup(email: String, password: String) = com.rudnam.wallet.core.network.model.NetworkMe(email, token = "t")
        override suspend fun startDemo(zone: String) = com.rudnam.wallet.core.network.model.NetworkMe("demo@example.com", demo = true, token = "t")
        override suspend fun forgotPassword(email: String) = Unit
        override suspend fun appToken(code: String, verifier: String) = com.rudnam.wallet.core.network.model.NetworkMe("g@example.com", token = "t")
        override suspend fun resendConfirmation() = Unit
        override suspend fun previewImport(source: String, csv: String, tzOffset: Int) = com.rudnam.wallet.core.network.model.NetworkImportPreview()
        override suspend fun import(source: String, body: com.rudnam.wallet.core.network.model.NetworkFileImport, tzOffset: Int) = com.rudnam.wallet.core.network.model.NetworkImportResult()
        override suspend fun previewICCard(body: com.rudnam.wallet.core.network.model.NetworkICCardImport) = com.rudnam.wallet.core.network.model.NetworkImportPreview()
        override suspend fun importICCard(body: com.rudnam.wallet.core.network.model.NetworkICCardImport) = com.rudnam.wallet.core.network.model.NetworkImportResult()
        override suspend fun rules() = com.rudnam.wallet.core.network.model.NetworkRules()
        override suspend fun setPayeeRule(payee: String, categoryId: Long) = Unit
        override suspend fun deletePayeeRule(payee: String) = Unit
        override suspend fun setCardRule(source: String, accountId: Long) = Unit
        override suspend fun deleteCardRule(source: String) = Unit
        override suspend fun mcpTokens() = emptyList<com.rudnam.wallet.core.network.model.NetworkMcpToken>()
        override suspend fun createMcpToken(name: String) = error("not used")
        override suspend fun deleteMcpToken(id: Long) = Unit
        override suspend fun me() = com.rudnam.wallet.core.network.model.NetworkMe("francis@example.com")
        override suspend fun logout() = Unit
        override suspend fun deleteMe(confirm: com.rudnam.wallet.core.network.model.NetworkDeleteMe) = Unit
        override suspend fun updateSettings(input: com.rudnam.wallet.core.network.model.NetworkSettingsInput) = Unit
        override suspend fun createAccount(input: com.rudnam.wallet.core.network.model.NetworkAccountInput) = Unit
        override suspend fun updateAccount(id: Long, input: com.rudnam.wallet.core.network.model.NetworkAccountInput) = Unit
        override suspend fun deleteAccount(id: Long) = Unit
        override suspend fun missingCategories() = emptyList<com.rudnam.wallet.core.network.model.NetworkCategory>()
        override suspend fun createCategory(input: com.rudnam.wallet.core.network.model.NetworkCategoryInput) = Unit
        override suspend fun updateCategory(id: Long, input: com.rudnam.wallet.core.network.model.NetworkCategoryInput) = Unit
        override suspend fun deleteCategory(id: Long) = Unit
        override suspend fun createBudget(input: com.rudnam.wallet.core.network.model.NetworkBudgetInput) = error("not used")
        override suspend fun updateBudget(id: Long, input: com.rudnam.wallet.core.network.model.NetworkBudgetInput) = error("not used")
        override suspend fun deleteBudget(id: Long) = Unit
        override suspend fun stats(from: Instant, to: Instant, zone: String, accountId: Long?) = com.rudnam.wallet.core.network.model.NetworkStats()
        override suspend fun deleteRecord(id: Long) = check()
        override suspend fun restoreRecords(ids: List<Long>) = check()

        override suspend fun createRecord(input: NetworkRecordInput): NetworkTransaction {
            check()
            created += input
            return input.saved(nextId++)
        }

        override suspend fun updateRecord(id: Long, input: NetworkRecordInput): NetworkTransaction {
            check()
            return input.saved(id)
        }

        private fun NetworkRecordInput.saved(id: Long) = NetworkTransaction(
            id, kind, amount, accountId, toAccountId, categoryId, occurredAt, payee, note, status, "manual", "", null, occurredAt, tags,
        )
    }

    private val at = Instant.parse("2026-11-14T00:30:00Z")
    private fun expense(amount: Long, payee: String) =
        RecordInput(TransactionKind.Expense, amount, 1, null, null, at, payee, "", TransactionStatus.Confirmed, emptyList())

    @Test
    fun `offline, a record waits on the phone, counts in the balance, and is sent once`() = runTest {
        val network = FakeNetwork()
        val io = StandardTestDispatcher(testScheduler)
        var scheduled = 0
        val records = OfflineFirstRecordRepository(
            network, db.transactionDao(), db.outboxDao(), TestUserPreferences().repository,
            object : OutboxScheduler { override fun schedule() { scheduled++ } }, Clock.fixed(at, ZoneOffset.UTC), HabitsCache(), io,
        )
        val wallet = OfflineFirstWalletRepository(network, db.walletDao(), db.transactionDao(), db.budgetDao(), db.outboxDao(), WalletCache(db), StatsCache(), HabitsCache(), io)
        val month = Instant.parse("2026-11-01T00:00:00Z") to Instant.parse("2026-12-01T00:00:00Z")
        wallet.refresh(month.first, month.second)

        // Online: saved at once.
        assertTrue(records.add(expense(500, "Bakery")) is SaveResult.Saved)

        // Offline: kept, shown as waiting, and counted in Cash's balance.
        network.offline = true
        assertEquals(SaveResult.Queued, records.add(expense(1_200, "Ramen")))
        assertEquals(1, scheduled)
        val waiting = wallet.transactions(month.first, month.second).first().single { it.pending != null }
        assertEquals(Pending.Waiting, waiting.pending)
        assertTrue(waiting.id < 0)
        assertEquals(10_000L - 1_200, wallet.wallet.first()!!.account(1)!!.balance)

        // Changed on the phone before it went: what's sent is the change.
        assertEquals(SaveResult.Queued, records.update(waiting.id, expense(1_300, "Ramen Taro")))
        assertEquals(SyncResult.Offline, records.sendPending())

        network.offline = false
        assertEquals(SyncResult.Done, records.sendPending())
        assertEquals(listOf(500L, 1_300L), network.created.map { it.amount })
        // The same client_id each time it's tried, so the server adds it once.
        assertTrue(network.created[1].clientId != null)
        val list = wallet.transactions(month.first, month.second).first()
        assertTrue(list.none { it.pending != null })
        assertTrue(list.any { it.payee == "Ramen Taro" })
    }

    @Test
    fun `a refused record waits for the user, who can delete it`() = runTest {
        val network = FakeNetwork().apply { offline = true }
        val io = StandardTestDispatcher(testScheduler)
        val records = OfflineFirstRecordRepository(
            network, db.transactionDao(), db.outboxDao(), TestUserPreferences().repository,
            object : OutboxScheduler { override fun schedule() {} }, Clock.fixed(at, ZoneOffset.UTC), HabitsCache(), io,
        )
        records.add(expense(800, "Shop"))
        network.offline = false
        network.refuse = "account not found"
        assertEquals(SyncResult.Done, records.sendPending())
        val refused = db.outboxDao().all().single()
        assertEquals("account not found", refused.error)
        assertEquals(Pending.Refused("account not found"), records.get(-refused.localId)!!.pending)

        assertEquals(SaveResult.Done, records.delete(-refused.localId))
        assertTrue(db.outboxDao().all().isEmpty())
        assertEquals(listOf("trip"), records.refreshHabits().tags)
    }
}
