package com.rudnam.wallet.core.model

/** The user's settings, accounts and categories, and how many records wait for a check (GET /api/wallet). */
data class Wallet(
    val settings: WalletSettings,
    /** In the user's order: the first is the front card on Wallet. */
    val accounts: List<Account>,
    val categories: List<Category>,
    val reviewCount: Int,
) {
    private val accountsById = accounts.associateBy { it.id }
    private val categoriesById = categories.associateBy { it.id }

    fun account(id: Long?): Account? = id?.let(accountsById::get)

    fun category(id: Long?): Category? = id?.let(categoriesById::get)

    /** The accounts in use, archived ones left out. */
    val activeAccounts: List<Account> get() = accounts.filterNot { it.archived }

    /** What's in the accounts in use: the total balance. */
    val totalBalance: Long get() = activeAccounts.sumOf { it.balance }
}

data class WalletSettings(
    /** ISO 4217, e.g. JPY. */
    val currency: String,
    /** Decimal places: amounts are whole minor units, so 1250 is ¥1,250 or $12.50. */
    val currencyExponent: Int,
    val autoConfirm: Boolean,
    val defaultAccountId: Long?,
)

enum class AccountKind(val apiName: String) {
    Cash("cash"),
    Bank("bank"),
    CreditCard("credit_card"),
    EMoney("e_money"),
    Savings("savings"),
    Other("other"),
    ;

    companion object {
        fun fromApi(name: String): AccountKind = entries.firstOrNull { it.apiName == name } ?: Other
    }
}

data class Account(
    val id: Long,
    val name: String,
    val kind: AccountKind,
    val initialBalance: Long,
    /** The initial balance plus confirmed records. */
    val balance: Long,
    /** #rrggbb. */
    val color: String,
    val archived: Boolean,
)

enum class CategoryKind(val apiName: String) {
    Expense("expense"),
    Income("income"),
    ;

    companion object {
        fun fromApi(name: String): CategoryKind = if (name == "income") Income else Expense
    }
}

data class Category(
    val id: Long,
    val name: String,
    val kind: CategoryKind,
    /** A glyph key such as "food", or an emoji from before line icons. */
    val icon: String,
    /** #rrggbb. */
    val color: String,
)

data class Budget(
    val id: Long,
    val name: String,
    /** null: all expenses. */
    val categoryId: Long?,
    /** The monthly limit. */
    val amount: Long,
)

/** A newer build of the app than the one installed (GET /api/downloads). */
data class AppUpdate(val versionCode: Int, val versionName: String, val url: String)
