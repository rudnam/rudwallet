package com.rudnam.wallet.core.model

import java.time.LocalDate

/** Confirmed income and expenses over a period (GET /api/stats); transfers count as neither. */
data class Stats(
    val spent: Long,
    val income: Long,
    /** Totals per category and kind, largest first. */
    val categories: List<CategoryTotal>,
    /** Totals per day, oldest first; days without records are left out. */
    val days: List<DayTotal>,
) {
    /** What was spent in [categoryId]'s category, or in all of them with null. */
    fun spentOn(categoryId: Long?): Long =
        if (categoryId == null) spent else categories.filter { it.kind == CategoryKind.Expense && it.categoryId == categoryId }.sumOf { it.amount }

    /** What was spent in [categoryId]'s category and its subcategories, or in all of them with null. */
    fun spentOn(categoryId: Long?, wallet: Wallet): Long =
        if (categoryId == null) spent else wallet.withSubcategories(categoryId).sumOf(::spentOn)

    /** The totals with each subcategory's added into its main category's: what charts of main categories show. */
    fun byMainCategory(wallet: Wallet): Stats {
        val merged = LinkedHashMap<Pair<Long?, CategoryKind>, CategoryTotal>()
        for (t in categories) {
            val key = wallet.mainCategoryId(t.categoryId) to t.kind
            merged[key] = merged[key]?.let { it.copy(amount = it.amount + t.amount, count = it.count + t.count) } ?: t.copy(categoryId = key.first)
        }
        return copy(categories = merged.values.sortedByDescending { it.amount })
    }

    companion object {
        val Empty = Stats(0, 0, emptyList(), emptyList())
    }
}

/** [categoryId] is null for records without a category. */
data class CategoryTotal(val categoryId: Long?, val kind: CategoryKind, val amount: Long, val count: Int)

data class DayTotal(val day: LocalDate, val spent: Long, val income: Long)

/** A new budget, or a budget's new name, category (null: all spending) and monthly limit. */
data class BudgetInput(val name: String, val categoryId: Long?, val amount: Long)
