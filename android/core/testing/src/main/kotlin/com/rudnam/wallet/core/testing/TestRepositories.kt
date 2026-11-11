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
    /** What the server answers when asked again; null: the same as [habits]. */
    var refreshed: com.rudnam.wallet.core.data.repository.RecordHabits? = null
    override suspend fun refreshHabits() = refreshed ?: habits
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
    var setAsideResult: com.rudnam.wallet.core.data.repository.SetAsideResult = com.rudnam.wallet.core.data.repository.SetAsideResult.Found(emptyList())

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
    override suspend fun setAside() = setAsideResult
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
    var budgetResult: com.rudnam.wallet.core.data.repository.ChangeResult = com.rudnam.wallet.core.data.repository.ChangeResult.Saved
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

/** A [SettingsRepository] that keeps what it's asked, and answers [result]. */
class TestSettingsRepository : com.rudnam.wallet.core.data.repository.SettingsRepository {
    var result: com.rudnam.wallet.core.data.repository.ChangeResult = com.rudnam.wallet.core.data.repository.ChangeResult.Saved
    var missing = listOf(com.rudnam.wallet.core.model.Category(0, "Pets", com.rudnam.wallet.core.model.CategoryKind.Expense, "pet", "#8a6d3b"))
    val currencies = mutableListOf<String>()
    val accounts = mutableListOf<Pair<Long?, com.rudnam.wallet.core.model.AccountInput>>()
    val deletedAccounts = mutableListOf<Long>()
    val categories = mutableListOf<Pair<Long?, com.rudnam.wallet.core.model.CategoryInput>>()
    val deletedCategories = mutableListOf<Long>()

    override suspend fun setCurrency(currency: String) = result.also { currencies += currency }
    override suspend fun saveAccount(id: Long?, input: com.rudnam.wallet.core.model.AccountInput) = result.also { accounts += id to input }
    override suspend fun deleteAccount(id: Long) = result.also { deletedAccounts += id }
    override suspend fun missingCategories() = missing
    override suspend fun saveCategory(id: Long?, input: com.rudnam.wallet.core.model.CategoryInput) = result.also { categories += id to input }
    override suspend fun deleteCategory(id: Long) = result.also { deletedCategories += id }

    var notificationSettings = mutableListOf<Pair<Boolean, Long?>>()
    var notificationList: com.rudnam.wallet.core.data.repository.Fetched<List<com.rudnam.wallet.core.model.PaymentNotification>> =
        com.rudnam.wallet.core.data.repository.Fetched.Done(emptyList())
    var rules: com.rudnam.wallet.core.data.repository.Fetched<com.rudnam.wallet.core.model.Rules> = com.rudnam.wallet.core.data.repository.Fetched.Done(
        com.rudnam.wallet.core.model.Rules(
            payees = listOf(com.rudnam.wallet.core.model.PayeeRule("doutor", "Doutor", 13, 12), com.rudnam.wallet.core.model.PayeeRule("ramen taro", "Ramen Taro", 10, 3)),
            cards = listOf(com.rudnam.wallet.core.model.CardRule("PayPay Card", 5, 21)),
        ),
    )
    val shops = mutableListOf<Pair<String, Long?>>()
    val cards = mutableListOf<Pair<String, Long?>>()
    var tokens = mutableListOf<com.rudnam.wallet.core.model.McpToken>()
    val deletedTokens = mutableListOf<Long>()

    override suspend fun setNotificationSettings(autoConfirm: Boolean, defaultAccountId: Long?) = result.also { notificationSettings += autoConfirm to defaultAccountId }
    override suspend fun notifications() = notificationList
    override suspend fun rules() = rules
    override suspend fun setShop(payee: String, categoryId: Long) = result.also { shops += payee to categoryId }
    override suspend fun forgetShop(key: String) = result.also { shops += key to null }
    override suspend fun setCard(source: String, accountId: Long) = result.also { cards += source to accountId }
    override suspend fun forgetCard(source: String) = result.also { cards += source to null }
    override val mcpAddress = "https://wallet.example.com/mcp"
    override suspend fun mcpTokens() = com.rudnam.wallet.core.data.repository.Fetched.Done(tokens.map { it.copy(token = null) })
    override suspend fun createMcpToken(name: String): com.rudnam.wallet.core.data.repository.Fetched<com.rudnam.wallet.core.model.McpToken> {
        val token = com.rudnam.wallet.core.model.McpToken(tokens.size + 1L, name, java.time.Instant.EPOCH, null, "wallet_secret", false)
        tokens += token
        return com.rudnam.wallet.core.data.repository.Fetched.Done(token)
    }
    override suspend fun deleteMcpToken(id: Long) = result.also { deletedTokens += id }
}

/** A [UserRepository] with [me] signed in; it keeps what it's asked. */
class TestUserRepository(me: com.rudnam.wallet.core.model.Me? = com.rudnam.wallet.core.model.Me("francis@example.com", hasPassword = true, demo = false)) :
    com.rudnam.wallet.core.data.repository.UserRepository {
    private val meFlow = MutableStateFlow(me)
    var signedOut = false
        private set
    val deletions = mutableListOf<Pair<String, Boolean>>()
    var deleteResult: com.rudnam.wallet.core.data.repository.ChangeResult = com.rudnam.wallet.core.data.repository.ChangeResult.Saved

    override val me: Flow<com.rudnam.wallet.core.model.Me?> = meFlow
    override suspend fun refreshMe() = Unit
    override suspend fun signOut() {
        signedOut = true
        meFlow.value = null
    }
    override suspend fun deleteAccount(confirm: String, withPassword: Boolean) = deleteResult.also {
        deletions += confirm to withPassword
        if (it == com.rudnam.wallet.core.data.repository.ChangeResult.Saved) meFlow.value = null
    }
}

/** An [ImportRepository] that answers with [preview] and [result], and keeps what it's asked to import. */
class TestImportRepository : com.rudnam.wallet.core.data.repository.ImportRepository {
    var files: Map<String, String> = mapOf("content://paypay.csv" to "取引日,出金金額（円）\n")
    var preview: com.rudnam.wallet.core.data.repository.Fetched<com.rudnam.wallet.core.model.ImportPreview>? = null
    var result: com.rudnam.wallet.core.data.repository.Fetched<com.rudnam.wallet.core.model.ImportResult> =
        com.rudnam.wallet.core.data.repository.Fetched.Done(com.rudnam.wallet.core.model.ImportResult(3, 0, 1, 0, 1, 0, emptyList()))
    val imported = mutableListOf<Pair<com.rudnam.wallet.core.model.ImportSource, com.rudnam.wallet.core.model.ImportChoices>>()

    override suspend fun readFile(uri: String) = files[uri]
    override suspend fun preview(source: com.rudnam.wallet.core.model.ImportSource, csv: String) = preview!!
    override suspend fun import(source: com.rudnam.wallet.core.model.ImportSource, csv: String, choices: com.rudnam.wallet.core.model.ImportChoices) =
        result.also { imported += source to choices }
    override suspend fun previewCard(card: com.rudnam.wallet.core.model.IcCardRead) = preview!!
    override suspend fun importCard(card: com.rudnam.wallet.core.model.IcCardRead, choices: com.rudnam.wallet.core.model.ImportChoices) =
        result.also { imported += com.rudnam.wallet.core.model.ImportSource.IcCard to choices }
}

/** An [AuthRepository] that answers [result], and keeps what it's asked. */
class TestAuthRepository : com.rudnam.wallet.core.data.repository.AuthRepository {
    var options = com.rudnam.wallet.core.model.AuthOptions(google = true, demo = true, reset = true)
    var result: com.rudnam.wallet.core.data.repository.SignInResult = com.rudnam.wallet.core.data.repository.SignInResult.SignedIn
    var change: com.rudnam.wallet.core.data.repository.ChangeResult = com.rudnam.wallet.core.data.repository.ChangeResult.Saved
    val signIns = mutableListOf<Pair<String, String>>()
    val signUps = mutableListOf<Pair<String, String>>()
    val resets = mutableListOf<String>()
    val googleReturns = mutableListOf<com.rudnam.wallet.core.data.repository.GoogleReturn>()
    var demos = 0
    var resent = 0

    override suspend fun options() = options
    override suspend fun signIn(email: String, password: String) = result.also { signIns += email to password }
    override suspend fun signUp(email: String, password: String) = result.also { signUps += email to password }
    override suspend fun startDemo() = result.also { demos++ }
    override suspend fun forgotPassword(email: String) = change.also { resets += email }
    override fun googleSignInUrl() = "https://wallet.example.com/api/auth/google?app_challenge=x"
    override suspend fun finishGoogle(back: com.rudnam.wallet.core.data.repository.GoogleReturn) =
        (if (back.code == null) com.rudnam.wallet.core.data.repository.SignInResult.GoogleFailed(back.error ?: "failed") else result).also { googleReturns += back }
    override suspend fun resendConfirmation() = change.also { resent++ }
}
