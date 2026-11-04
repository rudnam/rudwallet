package com.rudnam.wallet.core.database.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.rudnam.wallet.core.model.Account
import com.rudnam.wallet.core.model.AccountKind
import com.rudnam.wallet.core.model.Budget
import com.rudnam.wallet.core.model.Category
import com.rudnam.wallet.core.model.CategoryKind
import com.rudnam.wallet.core.model.Transaction
import com.rudnam.wallet.core.model.TransactionKind
import com.rudnam.wallet.core.model.TransactionSource
import com.rudnam.wallet.core.model.TransactionStatus
import com.rudnam.wallet.core.model.WalletSettings
import java.time.Instant

/** The wallet's settings and review count: one row. */
@Entity(tableName = "wallet")
data class WalletEntity(
    @PrimaryKey val id: Int = 0,
    val currency: String,
    val currencyExponent: Int,
    val autoConfirm: Boolean,
    val defaultAccountId: Long?,
    val reviewCount: Int,
)

@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey val id: Long,
    /** Where it is in the user's order (the server's): 0 is the front card. */
    val position: Int,
    val name: String,
    val kind: String,
    val initialBalance: Long,
    val balance: Long,
    val color: String,
    val archived: Boolean,
)

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey val id: Long,
    val position: Int,
    val name: String,
    val kind: String,
    val icon: String,
    val color: String,
)

@Entity(tableName = "transactions", indices = [Index("occurredAt")])
data class TransactionEntity(
    @PrimaryKey val id: Long,
    val kind: String,
    val amount: Long,
    val accountId: Long,
    val toAccountId: Long?,
    val categoryId: Long?,
    val occurredAt: Instant,
    val payee: String,
    val note: String,
    val status: String,
    val source: String,
    val sourceLabel: String,
    val duplicateOfId: Long?,
    val createdAt: Instant,
    val tags: List<String>,
)

@Entity(tableName = "budgets")
data class BudgetEntity(
    @PrimaryKey val id: Long,
    val position: Int,
    val name: String,
    val categoryId: Long?,
    val amount: Long,
)

fun WalletEntity.settings() = WalletSettings(currency, currencyExponent, autoConfirm, defaultAccountId)

fun AccountEntity.asModel() = Account(id, name, AccountKind.fromApi(kind), initialBalance, balance, color, archived)

fun Account.asEntity(position: Int) = AccountEntity(id, position, name, kind.apiName, initialBalance, balance, color, archived)

fun CategoryEntity.asModel() = Category(id, name, CategoryKind.fromApi(kind), icon, color)

fun Category.asEntity(position: Int) = CategoryEntity(id, position, name, kind.apiName, icon, color)

fun BudgetEntity.asModel() = Budget(id, name, categoryId, amount)

fun Budget.asEntity(position: Int) = BudgetEntity(id, position, name, categoryId, amount)

fun TransactionEntity.asModel() = Transaction(
    id = id,
    kind = TransactionKind.fromApi(kind),
    amount = amount,
    accountId = accountId,
    toAccountId = toAccountId,
    categoryId = categoryId,
    occurredAt = occurredAt,
    payee = payee,
    note = note,
    status = TransactionStatus.fromApi(status),
    source = TransactionSource.fromApi(source),
    sourceLabel = sourceLabel,
    duplicateOfId = duplicateOfId,
    createdAt = createdAt,
    tags = tags,
)

fun Transaction.asEntity() = TransactionEntity(
    id, kind.apiName, amount, accountId, toAccountId, categoryId, occurredAt, payee, note,
    status.apiName, source.apiName, sourceLabel, duplicateOfId, createdAt, tags,
)
