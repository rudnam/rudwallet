package com.rudnam.wallet.core.network.model

import com.rudnam.wallet.core.model.Account
import com.rudnam.wallet.core.model.AccountKind
import com.rudnam.wallet.core.model.AccountInput
import com.rudnam.wallet.core.model.Budget
import com.rudnam.wallet.core.model.CategoryInput
import com.rudnam.wallet.core.model.McpToken
import com.rudnam.wallet.core.model.Me
import com.rudnam.wallet.core.model.NotificationStatus
import com.rudnam.wallet.core.model.PayeeRule
import com.rudnam.wallet.core.model.CardRule
import com.rudnam.wallet.core.model.PaymentNotification
import com.rudnam.wallet.core.model.Rules
import com.rudnam.wallet.core.model.BudgetInput
import com.rudnam.wallet.core.model.CategoryTotal
import com.rudnam.wallet.core.model.DayTotal
import com.rudnam.wallet.core.model.Stats
import com.rudnam.wallet.core.model.Category
import com.rudnam.wallet.core.model.RecordInput
import com.rudnam.wallet.core.model.CategoryKind
import com.rudnam.wallet.core.model.Transaction
import com.rudnam.wallet.core.model.TransactionKind
import com.rudnam.wallet.core.model.TransactionSource
import com.rudnam.wallet.core.model.TransactionStatus
import com.rudnam.wallet.core.model.Wallet
import com.rudnam.wallet.core.model.WalletSettings
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.OffsetDateTime

// The API's JSON, as internal/wallet writes it (wallet.go). Fields the app
// doesn't use yet are left out; unknown ones are ignored, so the server can
// add more at any time.

@Serializable
data class NetworkWallet(
    val settings: NetworkSettings,
    val accounts: List<NetworkAccount>,
    val categories: List<NetworkCategory>,
    @SerialName("review_count") val reviewCount: Int = 0,
)

@Serializable
data class NetworkSettings(
    val currency: String,
    @SerialName("currency_exponent") val currencyExponent: Int = 0,
    @SerialName("auto_confirm") val autoConfirm: Boolean = false,
    @SerialName("default_account_id") val defaultAccountId: Long? = null,
)

@Serializable
data class NetworkAccount(
    val id: Long,
    val name: String,
    val kind: String = "other",
    @SerialName("initial_balance") val initialBalance: Long = 0,
    val balance: Long = 0,
    val color: String = "",
    val archived: Boolean = false,
)

@Serializable
data class NetworkCategory(
    val id: Long,
    val name: String,
    val kind: String = "expense",
    val icon: String = "",
    val color: String = "",
)

@Serializable
data class NetworkTransaction(
    val id: Long,
    val kind: String,
    val amount: Long,
    @SerialName("account_id") val accountId: Long,
    @SerialName("to_account_id") val toAccountId: Long? = null,
    @SerialName("category_id") val categoryId: Long? = null,
    /** RFC 3339, as Go writes it, e.g. 2026-11-04T09:30:00.123+09:00. */
    @SerialName("occurred_at") val occurredAt: String,
    val payee: String = "",
    val note: String = "",
    val status: String = "confirmed",
    val source: String = "manual",
    @SerialName("source_label") val sourceLabel: String = "",
    @SerialName("duplicate_of_id") val duplicateOfId: Long? = null,
    @SerialName("created_at") val createdAt: String? = null,
    val tags: List<String> = emptyList(),
)

/** A record as sent to add or change one. */
@Serializable
data class NetworkRecordInput(
    val kind: String,
    val amount: Long,
    @SerialName("account_id") val accountId: Long,
    @SerialName("to_account_id") val toAccountId: Long?,
    @SerialName("category_id") val categoryId: Long?,
    @SerialName("occurred_at") val occurredAt: String,
    val payee: String,
    val note: String,
    val status: String,
    val tags: List<String>,
    val remember: Boolean? = null,
    /** The app's own name for a new record: sending it again answers with the record saved the first time. */
    @SerialName("client_id") val clientId: String? = null,
)

@Serializable
data class NetworkIds(val ids: List<Long>)

@Serializable
data class NetworkTag(val name: String, val count: Int = 0)

@Serializable
data class NetworkBulkChange(
    val ids: List<Long>,
    val action: String,
    @SerialName("category_id") val categoryId: Long? = null,
    @SerialName("account_id") val accountId: Long? = null,
    val tag: String? = null,
)

@Serializable
data class NetworkReconcile(val balance: Long)

/** The record a reconcile made, or null when the balance already matched. */
@Serializable
data class NetworkReconcileResult(val transaction: NetworkTransaction? = null)

@Serializable
data class NetworkBulkResult(val changed: Int = 0, val skipped: Int = 0)

/** What the API says when it refuses something: a message for the user. */
@Serializable
data class NetworkError(val error: String = "")

@Serializable
data class NetworkBudget(
    val id: Long,
    val name: String,
    @SerialName("category_id") val categoryId: Long? = null,
    val amount: Long,
)

@Serializable
data class NetworkMe(
    val email: String,
    @SerialName("has_password") val hasPassword: Boolean = true,
    val demo: Boolean = false,
)

/** What deleting the account is confirmed with: the password, or for a Google-only account, the email. */
@Serializable
data class NetworkDeleteMe(val password: String? = null, val email: String? = null)

@Serializable
data class NetworkSettingsInput(
    val currency: String,
    @SerialName("auto_confirm") val autoConfirm: Boolean,
    // All three are saved: send the ones not being changed as they are. Null (left out) is no default account.
    @SerialName("default_account_id") val defaultAccountId: Long?,
)

@Serializable
data class NetworkAccountInput(
    val name: String,
    val kind: String,
    @SerialName("initial_balance") val initialBalance: Long,
    val color: String,
    val archived: Boolean,
)

@Serializable
data class NetworkCategoryInput(val name: String, val kind: String, val icon: String, val color: String)

/** A new budget, or a budget's new name, category or limit. */
@Serializable
data class NetworkBudgetInput(
    val name: String,
    // Null (left out, which the server reads the same): all spending.
    @SerialName("category_id") val categoryId: Long?,
    val amount: Long,
)

@Serializable
data class NetworkStats(
    val spent: Long = 0,
    val income: Long = 0,
    val categories: List<NetworkCategoryTotal> = emptyList(),
    val days: List<NetworkDayTotal> = emptyList(),
)

@Serializable
data class NetworkCategoryTotal(
    @SerialName("category_id") val categoryId: Long? = null,
    val kind: String,
    val amount: Long,
    val count: Int = 0,
)

/** [day] is YYYY-MM-DD in the time zone asked for. */
@Serializable
data class NetworkDayTotal(val day: String, val spent: Long = 0, val income: Long = 0)

@Serializable
data class NetworkNotifications(val notifications: List<NetworkNotification> = emptyList())

@Serializable
data class NetworkNotification(
    val id: Long,
    @SerialName("package") val packageName: String = "",
    val app: String = "",
    val title: String = "",
    val text: String = "",
    @SerialName("posted_at") val postedAt: String? = null,
    val status: String = "unparsed",
    val detail: String = "",
)

@Serializable
data class NetworkRules(val payees: List<NetworkPayeeRule> = emptyList(), val cards: List<NetworkCardRule> = emptyList())

@Serializable
data class NetworkPayeeRule(val key: String, val payee: String = "", @SerialName("category_id") val categoryId: Long, val records: Int = 0)

@Serializable
data class NetworkCardRule(val source: String, @SerialName("account_id") val accountId: Long, val records: Int = 0)

@Serializable
data class NetworkPayeeRuleInput(val payee: String, @SerialName("category_id") val categoryId: Long)

@Serializable
data class NetworkCardRuleInput(val source: String, @SerialName("account_id") val accountId: Long)

@Serializable
data class NetworkMcpToken(
    val id: Long,
    val name: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("last_used_at") val lastUsedAt: String? = null,
    val token: String? = null,
    @SerialName("signed_in") val signedIn: Boolean = false,
)

@Serializable
data class NetworkName(val name: String)

@Serializable
data class NetworkDownloads(val android: NetworkApk? = null)

@Serializable
data class NetworkApk(
    val url: String,
    val version: Int? = null,
    @SerialName("version_name") val versionName: String? = null,
)

fun NetworkWallet.asModel() = Wallet(
    settings = WalletSettings(settings.currency, settings.currencyExponent, settings.autoConfirm, settings.defaultAccountId),
    accounts = accounts.map { Account(it.id, it.name, AccountKind.fromApi(it.kind), it.initialBalance, it.balance, it.color, it.archived) },
    categories = categories.map { Category(it.id, it.name, CategoryKind.fromApi(it.kind), it.icon, it.color) },
    reviewCount = reviewCount,
)

fun NetworkTransaction.asModel(): Transaction {
    // Go writes an offset (+09:00 or Z), which OffsetDateTime reads either way.
    val occurred = OffsetDateTime.parse(occurredAt).toInstant()
    return Transaction(
        id = id,
        kind = TransactionKind.fromApi(kind),
        amount = amount,
        accountId = accountId,
        toAccountId = toAccountId,
        categoryId = categoryId,
        occurredAt = occurred,
        payee = payee,
        note = note,
        status = TransactionStatus.fromApi(status),
        source = TransactionSource.fromApi(source),
        sourceLabel = sourceLabel,
        duplicateOfId = duplicateOfId,
        createdAt = createdAt?.let { OffsetDateTime.parse(it).toInstant() } ?: occurred,
        tags = tags,
    )
}

fun RecordInput.asNetwork(clientId: String? = null) = NetworkRecordInput(
    kind = kind.apiName,
    amount = amount,
    accountId = accountId,
    toAccountId = toAccountId,
    categoryId = categoryId,
    occurredAt = occurredAt.toString(),
    payee = payee,
    note = note,
    status = status.apiName,
    tags = tags,
    remember = remember,
    clientId = clientId,
)

fun NetworkBudget.asModel() = Budget(id, name, categoryId, amount)

fun BudgetInput.asNetwork() = NetworkBudgetInput(name, categoryId, amount)

fun NetworkMe.asModel() = Me(email, hasPassword, demo)

fun NetworkRules.asModel() = Rules(
    payees = payees.map { PayeeRule(it.key, it.payee.ifEmpty { it.key }, it.categoryId, it.records) },
    cards = cards.map { CardRule(it.source, it.accountId, it.records) },
)

fun NetworkNotification.asModel() = PaymentNotification(
    id = id,
    app = app.ifEmpty { packageName },
    title = title,
    text = text,
    postedAt = postedAt?.let { OffsetDateTime.parse(it).toInstant() } ?: java.time.Instant.EPOCH,
    status = NotificationStatus.fromApi(status),
    detail = detail,
)

fun NetworkMcpToken.asModel() = McpToken(
    id = id,
    name = name,
    createdAt = OffsetDateTime.parse(createdAt).toInstant(),
    lastUsedAt = lastUsedAt?.let { OffsetDateTime.parse(it).toInstant() },
    token = token,
    signedIn = signedIn,
)

fun AccountInput.asNetwork() = NetworkAccountInput(name, kind.apiName, initialBalance, color, archived)

fun CategoryInput.asNetwork() = NetworkCategoryInput(name, kind.apiName, icon, color)

fun NetworkCategory.asModel() = Category(id, name, CategoryKind.fromApi(kind), icon, color)

fun NetworkStats.asModel() = Stats(
    spent = spent,
    income = income,
    categories = categories.map { CategoryTotal(it.categoryId, CategoryKind.fromApi(it.kind), it.amount, it.count) },
    days = days.map { DayTotal(LocalDate.parse(it.day), it.spent, it.income) },
)
