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

    private val subsByParent: Map<Long, List<Category>> = categories.filter { it.parentId != null }.groupBy { it.parentId!! }

    /** A main category's subcategories, in the user's order. */
    fun subcategories(id: Long?): List<Category> = id?.let(subsByParent::get).orEmpty()

    /** The main category [id] is, or is under; null for no category. */
    fun mainCategoryId(id: Long?): Long? = category(id)?.let { it.parentId ?: it.id } ?: id

    /** [id] and its subcategories' ids: what a main category's totals and records take in. */
    fun withSubcategories(id: Long): Set<Long> = setOf(id) + subcategories(id).map { it.id }

    /** Under a main category the phone has (else it's shown as a main one). */
    fun isSubcategory(c: Category): Boolean = c.parentId != null && category(c.parentId) != null

    /** Every category, each main one followed by its subcategories: the order lists of them show. */
    fun categoriesInOrder(): List<Category> {
        val mains = categories.filterNot(::isSubcategory)
        return mains.flatMap { listOf(it) + subcategories(it.id) }
    }

    /** "Food & Drinks › Cafés" for a subcategory; a main category's own name. */
    fun categoryPath(id: Long?): String? {
        val c = category(id) ?: return null
        val main = category(c.parentId) ?: return c.name
        return "${main.name} › ${c.name}"
    }

    /**
     * The wallet with records not on the server yet counted in, as the user
     * expects to see them: each changes its account's balance as it will
     * once it's sent.
     */
    fun withPending(records: List<Transaction>): Wallet {
        if (records.isEmpty()) return this
        val change = HashMap<Long, Long>()
        for (t in records) {
            if (!t.confirmed) continue
            when (t.kind) {
                TransactionKind.Expense -> change.merge(t.accountId, -t.amount, Long::plus)
                TransactionKind.Income -> change.merge(t.accountId, t.amount, Long::plus)
                TransactionKind.Transfer -> {
                    change.merge(t.accountId, -t.amount, Long::plus)
                    t.toAccountId?.let { change.merge(it, t.amount, Long::plus) }
                }
            }
        }
        return copy(accounts = accounts.map { a -> change[a.id]?.let { a.copy(balance = a.balance + it) } ?: a })
    }

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
    /** The main category a subcategory is under (same kind, one level deep); null for a main category. */
    val parentId: Long? = null,
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

/**
 * The categories as the app shows them: a subcategory wears its main
 * category's colour, whatever it was saved with, so a main category and its
 * subcategories always match (the icons tell them apart). One whose main
 * category isn't in the list keeps its own.
 */
fun List<Category>.wearingMainColours(): List<Category> {
    val colours = associate { it.id to it.color }
    return map { c -> c.parentId?.let(colours::get)?.let { c.copy(color = it) } ?: c }
}
