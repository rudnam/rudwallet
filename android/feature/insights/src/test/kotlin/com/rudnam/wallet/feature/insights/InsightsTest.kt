package com.rudnam.wallet.feature.insights

import com.rudnam.wallet.core.model.Budget
import com.rudnam.wallet.core.model.CategoryKind
import com.rudnam.wallet.core.model.CategoryTotal
import com.rudnam.wallet.core.model.DayTotal
import com.rudnam.wallet.core.model.Stats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** The periods, trends, month ahead and chart columns, as the website works them out. */
class InsightsTest {
    private val today = LocalDate.of(2026, 11, 14) // a Saturday

    private fun stats(spent: Long, vararg categories: Pair<Long?, Long>, income: Long = 0, days: List<DayTotal> = listOf(DayTotal(today, spent, 0))) =
        Stats(spent, income, categories.map { (id, amount) -> CategoryTotal(id, CategoryKind.Expense, amount, 1) }, days)

    @Test
    fun `weeks start on Monday, and months and years on their first day`() {
        assertEquals(Period(Span.Week, LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 16)), Period.at(Span.Week, today))
        assertEquals(Period(Span.Month, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 12, 1)), Period.at(Span.Month, today))
        assertEquals(Period(Span.Year, LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1)), Period.at(Span.Year, today))
        assertEquals(30, Period.at(Span.Range, today).lengthInDays)
        assertEquals(LocalDate.of(2026, 10, 1), Period.at(Span.Month, today).shift(-1).from)
        // A range steps by its own length.
        val range = Period.range(LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 1))
        assertEquals(Period(Span.Range, LocalDate.of(2026, 10, 22), LocalDate.of(2026, 11, 1)), range.shift(-1))
    }

    @Test
    fun `a month still going counts up to today and is compared at the same point`() {
        val month = Period.at(Span.Month, today)
        assertEquals(14, month.elapsedDays(today))
        assertTrue(month.ongoing(today))
        assertTrue(month.reachesToday(today))
        assertFalse(month.shift(-1).reachesToday(today))
        // The first 14 days of October, September and August.
        assertEquals(
            listOf(LocalDate.of(2026, 10, 15), LocalDate.of(2026, 9, 15), LocalDate.of(2026, 8, 15)),
            month.comparedWith(today).map { it.to },
        )
    }

    @Test
    fun `a website path's period, or this month for one that makes no sense`() {
        assertEquals(Period.at(Span.Week, LocalDate.of(2026, 11, 4)), Period.fromParams("week", "2026-11-04", null, today))
        assertEquals(Period.range(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 15)), Period.fromParams("custom", "2026-10-01", "2026-10-15", today))
        assertEquals(Period.lastDays(30, today), Period.fromParams("custom", "2026-10-01", null, today))
        assertEquals(Period.at(Span.Month, today), Period.fromParams("fortnight", "nonsense", null, today))
    }

    @Test
    fun `spending well above the usual average says so, with what moved most`() {
        val now = stats(30_000, 10L to 20_000, 11L to 10_000)
        val past = listOf(stats(20_000, 10L to 8_000, 11L to 12_000), stats(20_000, 10L to 8_000, 11L to 12_000), stats(0, days = emptyList()))
        val t = trend(now, past) as Trend.Compared
        assertEquals(Verdict.More, t.verdict)
        assertEquals(50, t.percent)
        assertEquals(20_000, t.usual)
        // The period without records doesn't count towards usual.
        assertEquals(2, t.periods)
        assertEquals(listOf(Mover(10, 20_000, 8_000)), t.higher)
        assertTrue(t.lower.isEmpty()) // 10,000 against 12,000 is under a quarter
        assertEquals(null, t.shareOfIncome)
    }

    @Test
    fun `within a tenth of usual is usual, and nothing before is not enough`() {
        assertEquals(Verdict.Usual, (trend(stats(10_500), listOf(stats(10_000))) as Trend.Compared).verdict)
        assertEquals(Verdict.Less, (trend(stats(5_000, income = 20_000), listOf(stats(10_000))) as Trend.Compared).verdict)
        assertEquals(25, (trend(stats(5_000, income = 20_000), listOf(stats(10_000))) as Trend.Compared).shareOfIncome)
        assertEquals(Trend.NotEnough, trend(stats(5_000), listOf(stats(0, days = emptyList()))))
    }

    @Test
    fun `the rest of the month says where it's heading and what each budget allows a day`() {
        val month = Period.at(Span.Month, today)
        val ahead = monthAhead(stats(28_000, 10L to 9_000), listOf(Budget(1, "Everything", null, 60_000), Budget(2, "Eating out", 10, 8_000)), month, today)
        assertEquals(60_000, ahead.heading) // 28,000 over 14 days, for 30
        assertEquals(17, ahead.daysLeft) // today included
        val (everything, eating) = ahead.budgets
        assertEquals(1_882, everything.allowedPerDay) // 32,000 over 17 days, rounded down
        assertEquals(2_000, everything.perDayNow)
        assertTrue(everything.tooFast)
        assertTrue(eating.over)
    }

    @Test
    fun `columns are days for two months, then months, then years`() {
        val month = Period.at(Span.Month, today)
        val days = series(month, stats(480, days = listOf(DayTotal(LocalDate.of(2026, 11, 3), 480, 0))))
        assertEquals(Step.Day, days.step)
        assertEquals(30, days.values.size)
        assertEquals(480L, days.values[2])

        val year = series(Period.at(Span.Year, today), stats(900, days = listOf(DayTotal(LocalDate.of(2026, 2, 3), 400, 0), DayTotal(LocalDate.of(2026, 2, 20), 500, 0))))
        assertEquals(Step.Month, year.step)
        assertEquals(12, year.values.size)
        assertEquals(900L, year.values[1])

        val long = series(Period.range(LocalDate.of(2020, 6, 1), LocalDate.of(2026, 11, 14)), stats(100, days = listOf(DayTotal(LocalDate.of(2021, 3, 1), 100, 0))))
        assertEquals(Step.Year, long.step)
        assertEquals(7, long.values.size)
        assertEquals(100L, long.values[1])
    }

    @Test
    fun `the scale's top is a round number`() {
        assertEquals(1L, niceMax(0))
        assertEquals(5_000L, niceMax(4_210))
        assertEquals(25_000L, niceMax(21_000))
        assertEquals(100_000L, niceMax(100_000))
    }
}
