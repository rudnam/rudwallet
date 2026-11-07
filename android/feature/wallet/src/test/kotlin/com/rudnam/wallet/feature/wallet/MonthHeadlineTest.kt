package com.rudnam.wallet.feature.wallet

import com.rudnam.wallet.core.testing.SampleWallet
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class MonthHeadlineTest {
    private val november = YearMonth.of(2026, 11)
    private val today = LocalDate.of(2026, 11, 14)

    @Test
    fun `with an overall budget, it says what's left per day, today included`() {
        // ¥60,000 − ¥19,570 spent = ¥40,430 over the 17 days from the 14th to the 30th.
        assertEquals(MonthHeadline.PerDay(2_378), monthHeadline(SampleWallet.records, SampleWallet.budgets, november, today))
    }

    @Test
    fun `over the overall budget, it says by how much`() {
        val tight = SampleWallet.budgets.map { if (it.categoryId == null) it.copy(amount = 10_000) else it }
        assertEquals(MonthHeadline.OverBudget(9_570), monthHeadline(SampleWallet.records, tight, november, today))
    }

    @Test
    fun `without one, what's been spent, and before any spending, nothing`() {
        val categoryOnly = SampleWallet.budgets.filter { it.categoryId != null }
        assertEquals(MonthHeadline.SpentSoFar(19_570), monthHeadline(SampleWallet.records, categoryOnly, november, today))
        assertEquals(MonthHeadline.NothingSpent, monthHeadline(emptyList(), SampleWallet.budgets, november, today))
    }
}
