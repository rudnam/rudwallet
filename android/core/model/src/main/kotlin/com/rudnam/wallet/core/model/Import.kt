package com.rudnam.wallet.core.model

import java.time.LocalDate

/** Where an import's records come from. */
enum class ImportSource(val key: String) {
    PayPay("paypay"),
    Mufg("mufg"),
    BudgetBakers("budgetbakers"),
    IcCard("ic"),
    ;

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key }
    }
}

/** What the phone read from a transit IC card: its IDm, and its history blocks in hex, newest first. */
data class IcCardRead(val idm: String, val history: List<String>) {
    /** The newest entry's balance and day, decoded here (the block layout: internal/wallet/importic.go), or null. */
    val newest: Pair<Long, LocalDate>?
        get() {
            for (hex in history) {
                if (hex.length != 32) continue
                val b = { i: Int -> hex.substring(i * 2, i * 2 + 2).toInt(16) }
                if (b(4) == 0 && b(5) == 0) continue
                val day = runCatching { LocalDate.of(2000 + (b(4) shr 1), ((b(4) and 1) shl 3) or (b(5) shr 5), b(5) and 0x1f) }.getOrNull() ?: continue
                return (b(10) or (b(11) shl 8)).toLong() to day
            }
            return null
        }
}

/**
 * A group of an import's records that goes to one account: a PayPay way of
 * paying, an MUFG or IC card role, a BudgetBakers account ([key]). The
 * suggestion is [accountId], or (null) a new account named [newName].
 */
data class ImportAccountGroup(
    val key: String,
    val count: Int,
    val accountId: Long?,
    val newName: String,
    /** Left out last time (PayPay): suggested to leave out again. */
    val skip: Boolean = false,
    /** Other accounts that also look like it (MUFG). */
    val candidates: List<Long> = emptyList(),
)

/**
 * Records that share a category in the file (BudgetBakers) or a shop, and
 * where they're suggested to go: [categoryId], else a new category named
 * [newName], else (empty) none.
 */
data class ImportCategoryGroup(
    val name: String,
    val kind: CategoryKind,
    val count: Int,
    val total: Long,
    val categoryId: Long?,
    val newName: String,
    /** The suggestion is what the user chose for it before. */
    val learned: Boolean,
)

enum class ImportRowStatus { New, Imported, Matched, Start, None }

/** How one row of a statement, or one entry on a card, would be (or was) recorded. */
data class ImportRow(
    val date: LocalDate,
    /** A shop payment's time on a card, HH:MM. */
    val time: String? = null,
    /** What the row says: a statement's summary and detail, a fare's stations or a shop. */
    val text: String,
    /** A card's lines, entry → exit. */
    val lines: String? = null,
    val amount: Long,
    val balance: Long? = null,
    val kind: TransactionKind,
    val status: ImportRowStatus,
    /** A transfer's roles (an MUFG or IC card role's [ImportAccountGroup.key]). */
    val fromRole: String? = null,
    val toRole: String? = null,
    /** A rule knew it (MUFG): false means it needs a category. */
    val rule: Boolean = true,
    val matchedAccountId: Long? = null,
)

/** The statement's or the card's balance against its account in the app (MUFG, IC card). */
data class ImportBalance(
    val accountId: Long?,
    val from: LocalDate?,
    val to: LocalDate?,
    /** On the statement's last day, or on the card. */
    val actual: Long,
    val app: Long,
    val difference: Long,
    /** Rows already recorded in another account (MUFG). */
    val misplaced: Long,
    val initialBalance: Long,
    val suggestedInitialBalance: Long,
    /** What the import changed the starting balance by. */
    val adjusted: Long,
)

/** What an import file or card holds, and where each part is suggested to go, before anything is recorded. */
data class ImportPreview(
    val source: ImportSource,
    val records: Int,
    /** Rows not imported or recorded yet (MUFG, IC card); otherwise [records]. */
    val new: Int,
    val from: LocalDate?,
    val to: LocalDate?,
    val accounts: List<ImportAccountGroup>,
    val categories: List<ImportCategoryGroup>,
    /** Default categories the user doesn't have, which can be made on import. */
    val newCategories: List<Category>,
    val problems: List<String> = emptyList(),
    val rows: List<ImportRow> = emptyList(),
    val balance: ImportBalance? = null,
    /** The card was read before, but none of its entries now were: some in between are lost. */
    val gap: Boolean = false,
    /** The card's last four IDm digits. */
    val card: String? = null,
)

/** What an import did. */
data class ImportResult(
    val imported: Int,
    val duplicates: Int,
    val matched: Int,
    val skipped: Int,
    val accountsCreated: Int,
    val categoriesCreated: Int,
    val problems: List<String>,
    val rows: List<ImportRow> = emptyList(),
    val balance: ImportBalance? = null,
)

/** Where a group of records goes: an account, [NEW] (with [newName]) or [SKIP]. */
data class AccountPick(val key: String, val accountId: Long, val newName: String? = null) {
    companion object {
        const val NEW = 0L
        const val SKIP = -1L
    }
}

/** Where a category group's records go: a category, a new one by [newName], or (neither) none. */
data class CategoryPick(val name: String, val kind: CategoryKind, val categoryId: Long?, val newName: String?)

/** What to import with: the file's text or the card read, and the choices. */
data class ImportChoices(val accounts: List<AccountPick>, val categories: List<CategoryPick>, val matchBalance: Boolean)
