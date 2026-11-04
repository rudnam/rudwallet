package com.rudnam.wallet.screens

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale
import java.util.TimeZone

/** The native screens write money and read the server's answers as the web app does (web/src/money.ts, api.ts). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FormatTest {
    private val yen = Settings("JPY", 0)
    private val dollars = Settings("USD", 2)

    @Test
    fun money() {
        assertEquals("¥4,994", Format(Locale.US, yen).money(4994))
        assertEquals("+¥1,200", Format(Locale.US, yen).money(1200, signed = true))
        // A true minus sign, as the web writes it.
        assertEquals("\u2212¥1,200", Format(Locale.US, yen).money(-1200, signed = true))
        assertEquals("\u2212¥46,770", Format(Locale.US, yen).money(-46770))
        assertEquals("¥0", Format(Locale.US, yen).money(0, signed = true))
        assertEquals("$12.50", Format(Locale.US, dollars).money(1250))
        // Japanese writes ￥; PT Serif draws the plain ¥.
        assertEquals("¥4,994", Format(Locale.JAPAN, yen).money(4994))
    }

    @Test
    fun compact() {
        assertEquals("¥12K", Format(Locale.US, yen).compact(12000))
        assertEquals("¥1.2万", Format(Locale.JAPAN, yen).compact(12000))
        assertEquals("¥500", Format(Locale.US, yen).compact(500))
    }

    @Test
    fun times() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
        val a = Parse.time("2026-11-04T00:30:00Z")
        val b = Parse.time("2026-11-04T09:30:00.123456+09:00")
        assertEquals(a, b)
        assertEquals("2026-11-04T09:30:00+09:00", Parse.rfc3339(a))
    }

    @Test
    fun budgets() {
        val home = Sample.home()
        val confirmed = home.records.filter { it.confirmed }
        // All expenses, and only the category's; a record to review counts in neither.
        assertEquals(19570, budgetSpent(home.budgets[0], confirmed))
        assertEquals(6880, budgetSpent(home.budgets[1], confirmed))
    }

}
