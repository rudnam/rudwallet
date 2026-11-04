package com.rudnam.wallet.screens

import android.text.format.DateFormat
import java.math.BigDecimal
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Currency
import java.util.Date
import java.util.Locale

/**
 * Money and dates as the web app writes them (web/src/money.ts), in the
 * locale the web app uses (language.ts, locale()).
 */
class Format(val locale: Locale, private val settings: Settings) {
    private val money: NumberFormat = NumberFormat.getCurrencyInstance(locale).apply {
        try {
            currency = Currency.getInstance(settings.currency)
        } catch (e: IllegalArgumentException) {
            // A code Java doesn't know: the amount still shows, with the locale's sign.
        }
        minimumFractionDigits = settings.exponent
        maximumFractionDigits = settings.exponent
    }

    /** Minor units as money, e.g. 4994 JPY → "¥4,994", −1200 → "−¥1,200"; signed adds + to amounts above zero. */
    fun money(amount: Long, signed: Boolean = false): String {
        val s = trueMinus(plainYen(money.format(BigDecimal.valueOf(amount, settings.exponent))))
        return if (signed && amount > 0) "+$s" else s
    }

    /** The currency's sign as money() writes it: "¥", "$". */
    val symbol: String get() = plainYen(money.currency?.getSymbol(locale) ?: settings.currency)

    /** Short form for an axis: ¥12K, or in Japanese ¥1.2万. */
    fun compact(amount: Long): String {
        val value = BigDecimal.valueOf(amount, settings.exponent).toDouble()
        val japanese = locale.language == "ja"
        val (scaled, suffix) = when {
            japanese && value >= 100_000_000 -> value / 100_000_000 to "億"
            japanese && value >= 10_000 -> value / 10_000 to "万"
            !japanese && value >= 1_000_000_000 -> value / 1_000_000_000 to "B"
            !japanese && value >= 1_000_000 -> value / 1_000_000 to "M"
            !japanese && value >= 1_000 -> value / 1_000 to "K"
            else -> value to ""
        }
        val n = NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 1 }.format(scaled)
        return trueMinus(symbol + n + suffix)
    }

    /** The month's name, e.g. "November" or "11月". */
    fun monthName(d: Date): String = pattern("MMMM").format(d)

    /** A day heading in a list: Today, Yesterday, or e.g. "Mon, Nov 2". */
    fun day(d: Date, today: String, yesterday: String, now: Date = Date()): String {
        val day = dayKey(d)
        if (day == dayKey(now)) return today
        if (day == dayKey(Date(now.time - 86_400_000))) return yesterday
        val sameYear = year(d) == year(now)
        return pattern(if (sameYear) "EEEMMMd" else "EEEyMMMd").format(d)
    }

    /** e.g. "Nov 3" or "11月3日". */
    fun shortDate(d: Date): String = pattern("MMMd").format(d)

    fun time(d: Date): String = pattern("jmm").format(d)

    private fun pattern(skeleton: String) = SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, skeleton), locale)

    companion object {
        /**
         * Japanese writes the yen sign full-width (￥); the web app uses the
         * plain ¥ everywhere, and so do these.
         */
        fun plainYen(s: String) = s.replace('￥', '¥')

        /** A true minus sign (−), the width of a plus, not a hyphen (-), as the web writes it. */
        fun trueMinus(s: String) = s.replace('-', '\u2212')

        fun dayKey(d: Date): Int {
            val c = Calendar.getInstance().apply { time = d }
            return c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
        }

        private fun year(d: Date) = Calendar.getInstance().apply { time = d }.get(Calendar.YEAR)

        /** First moment of the month containing d, in local time. */
        fun monthStart(d: Date): Date = Calendar.getInstance().apply {
            time = d
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.time

        fun daysInMonth(d: Date): Int = Calendar.getInstance().apply { time = d }.getActualMaximum(Calendar.DAY_OF_MONTH)

        fun dayOfMonth(d: Date): Int = Calendar.getInstance().apply { time = d }.get(Calendar.DAY_OF_MONTH)
    }
}
