package com.rudnam.wallet.core.ui

import com.rudnam.wallet.core.model.WalletSettings
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MoneyFormatTest {
    private val yen = WalletSettings("JPY", 0, autoConfirm = false, defaultAccountId = null)
    private val dollars = WalletSettings("USD", 2, autoConfirm = false, defaultAccountId = null)
    private val tokyo = ZoneId.of("Asia/Tokyo")

    @Test
    fun `money is written as the website writes it`() {
        val f = MoneyFormat(Locale.US, yen, tokyo)
        assertEquals("¥4,994", f.money(4994))
        assertEquals("+¥1,200", f.money(1200, signed = true))
        assertEquals("−¥1,200", f.money(-1200, signed = true))
        assertEquals("¥0", f.money(0, signed = true))
        assertEquals("$12.50", MoneyFormat(Locale.US, dollars, tokyo).money(1250))
        assertEquals("¥4,994", MoneyFormat(Locale.JAPAN, yen, tokyo).money(4994))
    }

    @Test
    fun `days read as today, yesterday, or a short date`() {
        val f = MoneyFormat(Locale.US, yen, tokyo)
        val now = Instant.parse("2026-11-14T00:30:00Z") // 09:30 in Tokyo
        assertEquals("Today", f.day(Instant.parse("2026-11-13T15:30:00Z"), "Today", "Yesterday", now))
        assertEquals("Yesterday", f.day(Instant.parse("2026-11-13T14:59:00Z"), "Today", "Yesterday", now))
        assertEquals("Thu, Nov 12", f.day(Instant.parse("2026-11-12T03:00:00Z"), "Today", "Yesterday", now))
        assertEquals("11月", MoneyFormat(Locale.JAPAN, yen, tokyo).monthName(YearMonth.of(2026, 11)))
    }
}
