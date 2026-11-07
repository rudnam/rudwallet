package com.rudnam.wallet.feature.record

import com.rudnam.wallet.core.model.TransactionKind
import com.rudnam.wallet.core.model.TransactionStatus
import com.rudnam.wallet.core.testing.SampleWallet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordFormTest {
    private fun type(vararg keys: PadKey, exponent: Int = 0) = keys.fold("") { a, k -> pressKey(a, k, exponent) }

    private fun d(s: String) = PadKey.Digits(s)

    @Test
    fun `the pad writes amounts as they're tapped, thousands marked`() {
        assertEquals("1,200", type(d("1"), d("2"), d("0"), d("0")))
        assertEquals("120", type(d("1"), d("2"), d("0"), d("0"), PadKey.Back))
        assertEquals("12,000", type(d("12"), d("000")))
        // No leading zeros, no point for yen, and at most 12 digits.
        assertEquals("", type(d("0"), d("000")))
        assertEquals("5", type(PadKey.Point, d("5")))
        assertEquals("999,999,999,999", type(*Array(13) { d("9") }))
    }

    @Test
    fun `cents take a point and two places at most`() {
        assertEquals("12.50", type(d("1"), d("2"), PadKey.Point, d("5"), d("0"), d("7"), exponent = 2))
        assertEquals("0.5", type(PadKey.Point, d("5"), exponent = 2))
        val form = RecordForm(occurredAt = SampleWallet.now, amount = "12.50")
        assertEquals(1250L, form.minorAmount(2))
        assertEquals(1200L, form.copy(amount = "1,200").minorAmount(0))
        assertNull(form.copy(amount = "").minorAmount(0))
    }

    @Test
    fun `an edit says what it changes, and an untouched date isn't a change`() {
        val record = SampleWallet.records.first { it.id == 18L }
        val form = RecordForm.from(record, 0).copy(amount = "1,500", tags = record.tags + "friday")
        val input = form.toInput(0, record)!!
        assertEquals(record.occurredAt, input.occurredAt)
        assertEquals(TransactionStatus.Confirmed, input.status)
        val changes = describeChanges(record, input)
        assertEquals(listOf(Change.Field.Amount, Change.Field.Tags), changes.map { it.field })
        assertEquals(Change.Value.Money(1500), changes[0].after)
    }

    @Test
    fun `a transfer has no category or payee, and an amount is required`() {
        val form = RecordForm(occurredAt = SampleWallet.now, kind = TransactionKind.Transfer, amount = "10,000", categoryId = 10, payee = "x", accountId = 2, toAccountId = 3)
        val input = form.toInput(0, null)!!
        assertNull(input.categoryId)
        assertEquals("", input.payee)
        assertEquals(3L, input.toAccountId)
        assertNull(form.copy(amount = "").toInput(0, null))
    }

    @Test
    fun `tags are tidied and kept once`() {
        assertEquals(listOf("trip"), addTag(emptyList(), " #trip "))
        assertEquals(listOf("Trip"), addTag(listOf("Trip"), "trip"))
        assertEquals(List(MAX_TAGS) { "t$it" }, addTag(List(MAX_TAGS) { "t$it" }, "more"))
    }
}
