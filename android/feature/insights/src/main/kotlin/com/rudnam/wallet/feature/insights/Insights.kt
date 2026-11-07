package com.rudnam.wallet.feature.insights

import com.rudnam.wallet.core.model.Budget
import com.rudnam.wallet.core.model.CategoryKind
import com.rudnam.wallet.core.model.Stats
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.roundToLong

// What Insights works out from the stats: the trend against the periods
// before, where the month is heading, and the columns of the chart. Plain
// functions, as the website works them out (web/src/features/stats).

/** How this period's spending compares with usual. */
enum class Verdict { Usual, More, Less }

/** A category that moved against its own usual: [categoryId] null is "no category". */
data class Mover(val categoryId: Long?, val amount: Long, val before: Long) {
    val diff: Long get() = amount - before
}

sealed interface Trend {
    /** None of the periods before has records yet. */
    data object NotEnough : Trend

    data class Compared(
        val verdict: Verdict,
        /** How far from usual, in percent (always positive). */
        val percent: Int,
        val spent: Long,
        /** The average of [periods] periods before, each up to the same point. */
        val usual: Long,
        val periods: Int,
        /** Spending as a share of income, in percent; null without income. */
        val shareOfIncome: Int?,
        val income: Long,
        val higher: List<Mover>,
        val lower: List<Mover>,
    ) : Trend
}

/** Within this share of usual counts as "about usual". */
private const val USUAL_BAND = 0.1

/**
 * Is this period's spending high? Compared with the average of the periods
 * before that have any records (up to three), each up to the same point.
 */
fun trend(now: Stats, past: List<Stats>): Trend {
    val active = past.filter { it.days.isNotEmpty() }
    if (active.isEmpty()) return Trend.NotEnough
    val usual = (active.sumOf { it.spent }.toDouble() / active.size).roundToLong()
    val ratio = when {
        usual > 0 -> now.spent.toDouble() / usual - 1
        now.spent > 0 -> 1.0
        else -> 0.0
    }
    val verdict = when {
        abs(ratio) <= USUAL_BAND -> Verdict.Usual
        ratio > 0 -> Verdict.More
        else -> Verdict.Less
    }

    // Categories that moved most against their own usual.
    val usualBy = HashMap<Long?, Double>()
    for (s in active) for (c in s.categories) {
        if (c.kind == CategoryKind.Expense) usualBy.merge(c.categoryId, c.amount.toDouble() / active.size, Double::plus)
    }
    val nowBy = now.categories.filter { it.kind == CategoryKind.Expense }.associate { it.categoryId to it.amount }
    // Ignore changes too small to matter: under 5% of usual spending.
    val floor = maxOf(usual * 0.05, 1.0)
    val moves = (usualBy.keys + nowBy.keys).map { k -> Mover(k, nowBy[k] ?: 0, (usualBy[k] ?: 0.0).roundToLong()) }
        .filter { abs(it.diff) >= floor && (it.before == 0L || abs(it.diff).toDouble() / it.before > 0.25) }

    return Trend.Compared(
        verdict = verdict,
        percent = (abs(ratio) * 100).roundToInt(),
        spent = now.spent,
        usual = usual,
        periods = active.size,
        shareOfIncome = if (now.income > 0) (now.spent.toDouble() / now.income * 100).roundToInt() else null,
        income = now.income,
        higher = moves.filter { it.diff > 0 }.sortedByDescending { it.diff }.take(3),
        lower = moves.filter { it.diff < 0 }.sortedBy { it.diff }.take(3),
    )
}

/** One budget in the month still going: the most a day that keeps within it, against the rate so far. */
data class BudgetPace(val budget: Budget, val spent: Long, val allowedPerDay: Long, val perDayNow: Long) {
    val over: Boolean get() = spent >= budget.amount

    /** Spending faster than the budget allows. */
    val tooFast: Boolean get() = !over && perDayNow > allowedPerDay
}

/** The rest of the month still going: where its spending is heading, and each budget's pace. */
data class MonthAhead(val heading: Long, val daysLeft: Int, val budgets: List<BudgetPace>)

/** Where the month is heading at the same rate per day as so far, and what each budget allows a day. */
fun monthAhead(now: Stats, budgets: List<Budget>, period: Period, today: LocalDate): MonthAhead {
    val days = period.elapsedDays(today)
    val len = period.lengthInDays
    val left = len - days + 1 // today included
    return MonthAhead(
        heading = (now.spent.toDouble() / days * len).roundToLong(),
        daysLeft = left,
        budgets = budgets.map { b ->
            val spent = now.spentOn(b.categoryId)
            BudgetPace(b, spent, floor((b.amount - spent).toDouble() / left).toLong(), (spent.toDouble() / days).roundToLong())
        },
    )
}

/** What each column of the chart is: a day, a month or a year. */
enum class Step { Day, Month, Year }

/** The chart's columns: spending in each step from [starts]' day on. */
data class Series(val step: Step, val starts: List<LocalDate>, val values: List<Long>)

/** Spending per column: days for up to two months, then months for up to three years, then years. */
fun series(period: Period, stats: Stats): Series {
    val len = period.lengthInDays
    if (len <= 62) {
        val spentOn = stats.days.associate { it.day to it.spent }
        val dates = (0 until len).map { period.from.plusDays(it.toLong()) }
        return Series(Step.Day, dates, dates.map { spentOn[it] ?: 0 })
    }
    val first = period.from.withDayOfMonth(1)
    val months = ChronoUnit.MONTHS.between(first, period.last.withDayOfMonth(1)).toInt() + 1
    val byYear = months > 36
    val starts = if (byYear) {
        (period.from.year..period.last.year).map { LocalDate.of(it, 1, 1) }
    } else {
        (0 until months).map { first.plusMonths(it.toLong()) }
    }
    val values = LongArray(starts.size)
    for (d in stats.days) {
        val i = if (byYear) d.day.year - starts[0].year else ChronoUnit.MONTHS.between(first, d.day.withDayOfMonth(1)).toInt()
        if (i in values.indices) values[i] += d.spent
    }
    return Series(if (byYear) Step.Year else Step.Month, starts, values.toList())
}
