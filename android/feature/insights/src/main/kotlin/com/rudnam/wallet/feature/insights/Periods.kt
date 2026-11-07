package com.rudnam.wallet.feature.insights

import com.rudnam.wallet.core.data.repository.StatsQuery
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/** How long a period is: a week (from Monday), a calendar month or year, or any range of days. */
enum class Span(val key: String) {
    Week("week"),
    Month("month"),
    Year("year"),
    Range("custom"),
    ;

    companion object {
        fun fromKey(key: String?): Span = entries.firstOrNull { it.key == key } ?: Month
    }
}

/** A period of whole days: [from] is its first day, [to] the first day after it. */
data class Period(val span: Span, val from: LocalDate, val to: LocalDate) {
    val lengthInDays: Int get() = ChronoUnit.DAYS.between(from, to).toInt()

    /** Its last day. */
    val last: LocalDate get() = to.minusDays(1)

    /** The period [n] steps later (or earlier): the next week, month or year, or a range as long. */
    fun shift(n: Int): Period = when (span) {
        Span.Week -> copy(from = from.plusWeeks(n.toLong()), to = to.plusWeeks(n.toLong()))
        Span.Month -> copy(from = from.plusMonths(n.toLong()), to = to.plusMonths(n.toLong()))
        Span.Year -> copy(from = from.plusYears(n.toLong()), to = to.plusYears(n.toLong()))
        Span.Range -> (lengthInDays.toLong() * n).let { copy(from = from.plusDays(it), to = to.plusDays(it)) }
    }

    /** Days of it so far: one still going counts up to today, included. */
    fun elapsedDays(today: LocalDate): Int {
        val end = minOf(to, today.plusDays(1))
        return maxOf(0, ChronoUnit.DAYS.between(from, end).toInt())
    }

    /** Today falls inside it, after its first day, so it's partly over. */
    fun ongoing(today: LocalDate): Boolean = elapsedDays(today).let { it in 1 until lengthInDays }

    /** It's begun: there can be records in it. */
    fun started(today: LocalDate): Boolean = !from.isAfter(today)

    /** Has today in it (or is still to come): there's no next one to look at yet. */
    fun reachesToday(today: LocalDate): Boolean = to.isAfter(today)

    /** It cut off after its first [days] days. */
    fun samePoint(days: Int): Period = copy(to = minOf(to, from.plusDays(days.toLong())))

    /**
     * The three periods before, each only up to the same point as this one,
     * so half a month is compared with the first halves of earlier months.
     */
    fun comparedWith(today: LocalDate): List<Period> {
        val days = elapsedDays(today)
        return (1..3).map { shift(-it).samePoint(days) }
    }

    /** Its records' stats, of [accountId] or all accounts. */
    fun query(zone: ZoneId, accountId: Long?) =
        StatsQuery(from.atStartOfDay(zone).toInstant(), to.atStartOfDay(zone).toInstant(), zone, accountId)

    companion object {
        /** The week (from Monday), month or year with [day] in it; a range is the last 30 days. */
        fun at(span: Span, day: LocalDate): Period = when (span) {
            Span.Week -> day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).let { Period(span, it, it.plusWeeks(1)) }
            Span.Month -> day.withDayOfMonth(1).let { Period(span, it, it.plusMonths(1)) }
            Span.Year -> day.withDayOfYear(1).let { Period(span, it, it.plusYears(1)) }
            Span.Range -> lastDays(30, day)
        }

        /** The last [n] days, [today] included. */
        fun lastDays(n: Int, today: LocalDate) = Period(Span.Range, today.minusDays(n - 1L), today.plusDays(1))

        /** A range from [first] to [last], both included, whichever way round they come. */
        fun range(first: LocalDate, last: LocalDate): Period {
            val (a, b) = if (last.isBefore(first)) last to first else first to last
            return Period(Span.Range, a, b.plusDays(1))
        }

        /**
         * The period a website path asks for, "stats?span=week&start=2026-11-02"
         * (a range has an end, its last day), or this month.
         */
        fun fromParams(span: String?, start: String?, end: String?, today: LocalDate): Period {
            val s = Span.fromKey(span)
            val first = start?.let(::day)
            if (s != Span.Range) return at(s, first ?: today)
            val last = end?.let(::day)
            return if (first == null || last == null) lastDays(30, today) else range(first, last)
        }

        private fun day(text: String): LocalDate? = runCatching { LocalDate.parse(text) }.getOrNull()
    }
}
