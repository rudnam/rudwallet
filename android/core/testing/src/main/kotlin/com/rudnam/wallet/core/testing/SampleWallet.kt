package com.rudnam.wallet.core.testing

import com.rudnam.wallet.core.model.Account
import com.rudnam.wallet.core.model.AccountKind
import com.rudnam.wallet.core.model.Budget
import com.rudnam.wallet.core.model.Category
import com.rudnam.wallet.core.model.CategoryKind
import com.rudnam.wallet.core.model.Transaction
import com.rudnam.wallet.core.model.TransactionKind
import com.rudnam.wallet.core.model.TransactionSource
import com.rudnam.wallet.core.model.TransactionStatus
import com.rudnam.wallet.core.model.Wallet
import com.rudnam.wallet.core.model.WalletSettings
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** A wallet like the design's (docs/design/wallet.png), on 14 November 2026 at 09:30 in Tokyo. */
object SampleWallet {
    val zone: ZoneId = ZoneId.of("Asia/Tokyo")
    val now: Instant = at(14, 9, 30)

    fun at(day: Int, hour: Int, minute: Int): Instant = ZonedDateTime.of(2026, 11, day, hour, minute, 0, 0, zone).toInstant()

    val wallet = Wallet(
        settings = WalletSettings("JPY", 0, autoConfirm = true, defaultAccountId = 1),
        accounts = listOf(
            Account(1, "Cash", AccountKind.Cash, 0, 26_450, "#4f8a4c", archived = false),
            Account(3, "PayPay", AccountKind.EMoney, 0, 30_140, "#d4553f", archived = false),
            Account(5, "PayPay Card", AccountKind.CreditCard, 0, -46_770, "#c8463d", archived = false),
            Account(2, "MUFG", AccountKind.Bank, 0, 1_001_780, "#3b6ea8", archived = false),
            Account(4, "Old card", AccountKind.CreditCard, 0, 0, "#888888", archived = true),
        ),
        categories = listOf(
            Category(10, "Food & Drinks", CategoryKind.Expense, "food", "#d9534f"),
            Category(11, "Groceries", CategoryKind.Expense, "grocery", "#e8833a"),
            Category(12, "Transport", CategoryKind.Expense, "transit", "#3b82c4"),
            Category(13, "Coffee", CategoryKind.Expense, "☕", "#8a5a3c"),
            Category(14, "Salary", CategoryKind.Income, "salary", "#2e7d4f"),
        ),
        reviewCount = 2,
    )

    val budgets = listOf(Budget(1, "Everything", null, 60_000), Budget(2, "Eating out", 10, 8_000))

    private fun record(
        id: Long, kind: TransactionKind, amount: Long, account: Long, category: Long?, day: Int, h: Int, m: Int, payee: String,
        status: TransactionStatus = TransactionStatus.Confirmed, source: TransactionSource = TransactionSource.Manual,
        tags: List<String> = emptyList(), to: Long? = null,
    ) = Transaction(id, kind, amount, account, to, category, at(day, h, m), payee, "", status, source, "", null, at(day, h, m), tags)

    /** This month's records, newest first, as the server lists them. */
    val records = listOf(
        record(20, TransactionKind.Expense, 0, 3, null, 14, 8, 55, "FamilyMart", TransactionStatus.Review, TransactionSource.Notification),
        record(19, TransactionKind.Expense, 480, 3, 13, 14, 8, 10, "Doutor", source = TransactionSource.Notification),
        record(18, TransactionKind.Expense, 1_280, 1, 10, 13, 19, 40, "Ramen Taro", tags = listOf("dinner")),
        record(17, TransactionKind.Transfer, 10_000, 2, null, 13, 12, 0, "", to = 3),
        record(16, TransactionKind.Expense, 3_420, 2, 11, 12, 18, 5, "Life Supermarket", source = TransactionSource.Email),
        record(15, TransactionKind.Expense, 2_100, 3, 12, 10, 9, 0, "Suica top-up"),
        record(14, TransactionKind.Income, 280_000, 2, 14, 10, 9, 0, "Acme KK"),
        record(13, TransactionKind.Expense, 5_600, 2, 10, 7, 20, 30, "Izakaya Hana"),
        record(12, TransactionKind.Expense, 980, 1, 13, 5, 8, 15, "Starbucks"),
        record(11, TransactionKind.Expense, 4_210, 2, 11, 3, 18, 45, "Life Supermarket"),
        record(10, TransactionKind.Expense, 1_500, 1, null, 2, 13, 0, "Flea market"),
    )
}
