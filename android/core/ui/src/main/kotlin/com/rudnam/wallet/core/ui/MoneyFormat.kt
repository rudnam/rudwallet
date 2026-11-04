package com.rudnam.wallet.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.os.ConfigurationCompat
import com.rudnam.wallet.core.model.WalletSettings
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Currency
import java.util.Locale

/**
 * Money and dates in the app's language: amounts are whole minor units of
 * the wallet's currency (1250 is ¥1,250 or $12.50). Negative amounts take
 * a true minus sign (−), and the yen sign is always the plain ¥.
 */
class MoneyFormat(
    val locale: Locale,
    private val settings: WalletSettings,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val currency: NumberFormat = NumberFormat.getCurrencyInstance(locale).apply {
        runCatching { currency = Currency.getInstance(settings.currency) }
        minimumFractionDigits = settings.currencyExponent
        maximumFractionDigits = settings.currencyExponent
    }

    /** e.g. 4994 → "¥4,994", −1200 → "−¥1,200"; [signed] adds a plus to amounts above zero. */
    fun money(amount: Long, signed: Boolean = false): String {
        val text = tidy(currency.format(BigDecimal.valueOf(amount, settings.currencyExponent)))
        return if (signed && amount > 0) "+$text" else text
    }

    /** The currency's sign, as [money] writes it: "¥", "$". */
    val symbol: String get() = tidy(currency.currency?.getSymbol(locale) ?: settings.currency)

    /** A day and time: "Today, 9:30", or e.g. "Thu, Nov 12, 18:05". */
    fun dateTime(at: Instant, today: String? = null, yesterday: String? = null, now: Instant = Instant.now()): String {
        val day = if (today != null && yesterday != null) day(at, today, yesterday, now) else day(at, "", "", Instant.EPOCH)
        return day + ", " + at.atZone(zone).toLocalTime().format(DateTimeFormatter.ofPattern(pattern("jmm"), locale))
    }

    /** The month's name, e.g. "November" or "11月". */
    fun monthName(month: YearMonth): String =
        month.atDay(1).format(DateTimeFormatter.ofPattern(pattern("MMMM"), locale))

    /** A day in a list: Today, Yesterday, or e.g. "Thu, Nov 12" (with the year when it isn't this one). */
    fun day(at: Instant, today: String, yesterday: String, now: Instant = Instant.now()): String {
        val date = at.atZone(zone).toLocalDate()
        val current = now.atZone(zone).toLocalDate()
        return when (date) {
            current -> today
            current.minusDays(1) -> yesterday
            else -> date.format(DateTimeFormatter.ofPattern(pattern(if (date.year == current.year) "EEEMMMd" else "EEEyMMMd"), locale))
        }
    }

    fun date(date: LocalDate): String = date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))

    private fun pattern(skeleton: String) = android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton)

    companion object {
        /** The plain ¥ (Japanese writes ￥) and a true minus sign, as the website writes them. */
        fun tidy(text: String) = text.replace('￥', '¥').replace('-', '−')
    }
}

/** A [MoneyFormat] in the language the screen is drawn in. */
@Composable
fun rememberMoneyFormat(settings: WalletSettings): MoneyFormat {
    val configuration = LocalConfiguration.current
    val locale = ConfigurationCompat.getLocales(configuration)[0] ?: Locale.getDefault()
    return remember(locale, settings) { MoneyFormat(locale, settings) }
}
