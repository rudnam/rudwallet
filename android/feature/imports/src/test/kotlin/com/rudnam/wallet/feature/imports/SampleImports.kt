package com.rudnam.wallet.feature.imports

import com.rudnam.wallet.core.model.Category
import com.rudnam.wallet.core.model.CategoryKind
import com.rudnam.wallet.core.model.IcCardRead
import com.rudnam.wallet.core.model.ImportAccountGroup
import com.rudnam.wallet.core.model.ImportBalance
import com.rudnam.wallet.core.model.ImportCategoryGroup
import com.rudnam.wallet.core.model.ImportPreview
import com.rudnam.wallet.core.model.ImportRow
import com.rudnam.wallet.core.model.ImportRowStatus
import com.rudnam.wallet.core.model.ImportSource
import com.rudnam.wallet.core.model.TransactionKind
import java.time.LocalDate

/** Imports like the ones the server answers, for tests and screenshots. */
object SampleImports {
    private fun day(d: Int) = LocalDate.of(2026, 11, d)

    val payPay = ImportPreview(
        source = ImportSource.PayPay,
        records = 14,
        new = 14,
        from = day(1),
        to = day(14),
        accounts = listOf(
            ImportAccountGroup("PayPay残高", 11, 3, "PayPay"),
            ImportAccountGroup("PayPayカード", 3, null, "PayPay Card"),
            ImportAccountGroup("PayPayポイント", 1, null, "PayPay Points", skip = true),
        ),
        categories = listOf(
            ImportCategoryGroup("ドトール", CategoryKind.Expense, 4, 1_920, 13, "", learned = true),
            ImportCategoryGroup("ファミリーマート", CategoryKind.Expense, 3, 1_204, null, "Convenience stores", learned = false),
            ImportCategoryGroup("ライフ", CategoryKind.Expense, 2, 7_630, 11, "", learned = false),
            ImportCategoryGroup("ラーメン太郎", CategoryKind.Expense, 2, 2_560, null, "", learned = false),
            ImportCategoryGroup("PayPayポイント付与", CategoryKind.Income, 1, 120, null, "", learned = false),
        ),
        newCategories = listOf(Category(0, "Convenience stores", CategoryKind.Expense, "shopping", "#2a8a8a")),
        problems = listOf("line 9: no amount"),
    )

    val card = IcCardRead("0123456789ABCDEF", emptyList())

    val icCard = ImportPreview(
        source = ImportSource.IcCard,
        records = 4,
        new = 3,
        from = day(10),
        to = day(14),
        accounts = listOf(ImportAccountGroup("card", 3, 5, "Suica"), ImportAccountGroup("top-up", 1, 1, "Cash")),
        categories = listOf(ImportCategoryGroup("Train", CategoryKind.Expense, 2, 420, 12, "", learned = true)),
        newCategories = emptyList(),
        rows = listOf(
            ImportRow(day(10), text = "", amount = 0, balance = 1_210, kind = TransactionKind.Expense, status = ImportRowStatus.Start),
            ImportRow(day(12), text = "新宿 → 渋谷", lines = "JR山手線 → JR山手線", amount = -210, balance = 1_000, kind = TransactionKind.Expense, status = ImportRowStatus.Imported),
            ImportRow(day(13), text = "", amount = 3_000, balance = 4_000, kind = TransactionKind.Transfer, status = ImportRowStatus.New),
            ImportRow(day(14), time = "08:10", text = "Shop", amount = -480, balance = 3_520, kind = TransactionKind.Expense, status = ImportRowStatus.New),
        ),
        balance = ImportBalance(5, null, null, actual = 3_520, app = 1_000, difference = 2_520, misplaced = 0, initialBalance = 0, suggestedInitialBalance = 0, adjusted = 0),
        card = "CDEF",
    )
}
