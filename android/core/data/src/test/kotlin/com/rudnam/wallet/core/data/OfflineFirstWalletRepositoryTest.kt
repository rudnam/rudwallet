package com.rudnam.wallet.core.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rudnam.wallet.core.data.repository.ChangeResult
import com.rudnam.wallet.core.data.repository.Fetched
import com.rudnam.wallet.core.data.repository.OfflineFirstWalletRepository
import com.rudnam.wallet.core.data.repository.ServerSettingsRepository
import com.rudnam.wallet.core.model.AccountInput
import com.rudnam.wallet.core.model.AccountKind
import com.rudnam.wallet.core.model.CategoryInput
import com.rudnam.wallet.core.model.CategoryKind
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
import org.junit.Assert.assertTrue
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
            return NetworkWallet(NetworkSettings(currency), accounts.toList(), categories.toList(), reviewCount = 1)
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
        val payees = mutableListOf(com.rudnam.wallet.core.network.model.NetworkPayeeRule("doutor", "Doutor", 10, 3))
        val cards = mutableListOf(com.rudnam.wallet.core.network.model.NetworkCardRule("PayPay Card", 1, 2))
        val tokens = mutableListOf<com.rudnam.wallet.core.network.model.NetworkMcpToken>()
        override suspend fun rules(): com.rudnam.wallet.core.network.model.NetworkRules {
            check()
            return com.rudnam.wallet.core.network.model.NetworkRules(payees.toList(), cards.toList())
        }
        override suspend fun setPayeeRule(payee: String, categoryId: Long) {
            check()
            val key = payee.lowercase()
            payees.removeAll { it.key == key }
            payees += com.rudnam.wallet.core.network.model.NetworkPayeeRule(key, payee, categoryId, 0)
        }
        override suspend fun deletePayeeRule(payee: String) {
            check()
            payees.removeAll { it.key == payee }
        }
        override suspend fun setCardRule(source: String, accountId: Long) {
            check()
            cards.replaceAll { if (it.source == source) it.copy(accountId = accountId) else it }
        }
        override suspend fun deleteCardRule(source: String) {
            check()
            cards.removeAll { it.source == source }
        }
        override suspend fun mcpTokens() = tokens.map { it.copy(token = null) }
        override suspend fun createMcpToken(name: String) =
            com.rudnam.wallet.core.network.model.NetworkMcpToken(tokens.size + 1L, name, "2026-11-14T09:30:00+09:00", token = "wallet_secret").also { tokens += it }
        override suspend fun deleteMcpToken(id: Long) {
            tokens.removeAll { it.id == id }
        }
        var currency = "JPY"
        val accounts = mutableListOf(NetworkAccount(1, "Cash", "cash", balance = 26450))
        val categories = mutableListOf(com.rudnam.wallet.core.network.model.NetworkCategory(10, "Food", "expense", "food", "#d9534f"))
        var loggedOut = false
        override suspend fun me(): com.rudnam.wallet.core.network.model.NetworkMe {
            check()
            return com.rudnam.wallet.core.network.model.NetworkMe("francis@example.com")
        }
        override suspend fun logout() {
            check()
            loggedOut = true
        }
        override suspend fun deleteMe(confirm: com.rudnam.wallet.core.network.model.NetworkDeleteMe) {
            check()
            if (confirm.password != "secret") throw com.rudnam.wallet.core.network.ApiException(403, "the password is incorrect")
        }
        override suspend fun updateSettings(input: com.rudnam.wallet.core.network.model.NetworkSettingsInput) {
            check()
            currency = input.currency
        }
        override suspend fun createAccount(input: com.rudnam.wallet.core.network.model.NetworkAccountInput) {
            check()
            accounts += NetworkAccount(accounts.maxOf { it.id } + 1, input.name, input.kind, input.initialBalance, input.initialBalance, input.color, input.archived)
        }
        override suspend fun updateAccount(id: Long, input: com.rudnam.wallet.core.network.model.NetworkAccountInput) {
            check()
            accounts.replaceAll { if (it.id == id) it.copy(name = input.name, archived = input.archived) else it }
        }
        override suspend fun deleteAccount(id: Long) {
            check()
            accounts.removeAll { it.id == id }
            records = records.filter { it.accountId != id }
        }
        override suspend fun missingCategories() = listOf(com.rudnam.wallet.core.network.model.NetworkCategory(0, "Pets", "expense", "pet", "#8a6d3b"))
        override suspend fun createCategory(input: com.rudnam.wallet.core.network.model.NetworkCategoryInput) {
            check()
            categories += com.rudnam.wallet.core.network.model.NetworkCategory(categories.maxOf { it.id } + 1, input.name, input.kind, input.icon, input.color)
        }
        override suspend fun updateCategory(id: Long, input: com.rudnam.wallet.core.network.model.NetworkCategoryInput) {
            check()
            categories.replaceAll { if (it.id == id) it.copy(name = input.name) else it }
        }
        override suspend fun deleteCategory(id: Long) {
            check()
            categories.removeAll { it.id == id }
        }
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
        assertEquals(ChangeResult.Saved, insights.createBudget(BudgetInput("Eating out", 10, 8000)))
        assertEquals(listOf("Everything", "Eating out"), wallet.budgets.first().map { it.name })
        assertEquals(ChangeResult.Refused("enter a monthly limit above zero"), insights.createBudget(BudgetInput("Nothing", null, 0)))
        assertEquals(ChangeResult.Saved, insights.updateBudget(1, BudgetInput("All of it", null, 70000)))
        assertEquals(70000L, wallet.budgets.first().first { it.id == 1L }.amount)
        assertEquals(ChangeResult.Saved, insights.deleteBudget(2))
        // Deleted on the website meanwhile: gone, as the user wanted.
        assertEquals(ChangeResult.Saved, insights.deleteBudget(2))
        assertEquals(listOf(1L), wallet.budgets.first().map { it.id })

        wallet.forget()
        assertNull(insights.seen(query))
    }

    @Test
    fun `accounts, categories and the currency change on the server, then on the phone`() = runTest {
        val network = FakeNetwork()
        val io = StandardTestDispatcher(testScheduler)
        val wallet = OfflineFirstWalletRepository(network, db.walletDao(), db.transactionDao(), db.budgetDao(), db.outboxDao(), WalletCache(db), StatsCache(), io)
        val settings = ServerSettingsRepository(network, com.rudnam.wallet.core.network.Server(), db.walletDao(), db.transactionDao(), db.budgetDao(), io)
        wallet.refresh(from, to)

        assertEquals(ChangeResult.Saved, settings.saveAccount(null, AccountInput("PayPay", AccountKind.EMoney, 5000, "#d4553f", false)))
        assertEquals(listOf("Cash", "PayPay"), wallet.wallet.first()!!.accounts.map { it.name })
        assertEquals(ChangeResult.Saved, settings.saveAccount(2, AccountInput("PayPay", AccountKind.EMoney, 5000, "#d4553f", archived = true)))
        assertEquals(listOf("Cash"), wallet.wallet.first()!!.activeAccounts.map { it.name })
        // Its records go with it.
        assertEquals(ChangeResult.Saved, settings.deleteAccount(1))
        assertEquals(emptyList<Long>(), wallet.transactions(from, to).first().map { it.id })

        assertEquals(listOf("Pets"), settings.missingCategories().map { it.name })
        assertEquals(ChangeResult.Saved, settings.saveCategory(null, CategoryInput("Pets", CategoryKind.Expense, "pet", "#8a6d3b")))
        assertEquals(listOf("Food", "Pets"), wallet.wallet.first()!!.categories.map { it.name })
        assertEquals(ChangeResult.Saved, settings.deleteCategory(10))
        assertEquals(listOf("Pets"), wallet.wallet.first()!!.categories.map { it.name })

        assertEquals(ChangeResult.Saved, settings.setCurrency("USD"))
        assertEquals("USD", wallet.wallet.first()!!.settings.currency)

        // Shops and cards.
        assertEquals(listOf("Doutor"), (settings.rules() as Fetched.Done).value.payees.map { it.payee })
        assertEquals(ChangeResult.Saved, settings.setShop("Lawson", 10))
        assertEquals(ChangeResult.Saved, settings.forgetShop("doutor"))
        assertEquals(ChangeResult.Saved, settings.setCard("PayPay Card", 2))
        val rules = (settings.rules() as Fetched.Done).value
        assertEquals(listOf("Lawson"), rules.payees.map { it.payee })
        assertEquals(2L, rules.cards.single().accountId)

        // Claude: the token is in the answer that made it, and never again.
        assertEquals("wallet_secret", (settings.createMcpToken("Claude Desktop") as Fetched.Done).value.token)
        assertEquals(listOf(null), (settings.mcpTokens() as Fetched.Done).value.map { it.token })
        assertTrue(settings.mcpAddress.endsWith("/mcp"))

        network.failure = IOException("offline")
        assertEquals(ChangeResult.Offline, settings.deleteCategory(11))
        assertEquals(Fetched.Offline, settings.rules())
    }
}
