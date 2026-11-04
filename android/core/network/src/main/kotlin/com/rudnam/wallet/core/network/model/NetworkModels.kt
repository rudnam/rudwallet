package com.rudnam.wallet.core.network.model

import com.rudnam.wallet.core.model.Account
import com.rudnam.wallet.core.model.AccountKind
import com.rudnam.wallet.core.model.Budget
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
data class NetworkNotifications(val notifications: List<NetworkNotification> = emptyList())

@Serializable
data class NetworkNotification(val id: Long)

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
