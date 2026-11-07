package com.rudnam.wallet.core.data

import com.rudnam.wallet.core.data.repository.decode
import com.rudnam.wallet.core.model.IcCardRead
import com.rudnam.wallet.core.model.ImportRowStatus
import com.rudnam.wallet.core.model.ImportSource
import com.rudnam.wallet.core.network.model.NetworkImportPreview
import com.rudnam.wallet.core.network.model.asModel
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class ImportTest {
    @Test
    fun `a bank's Shift_JIS file and a UTF-8 one both read`() {
        val text = "日付,摘要,お支払金額\n2026/11/02,カード,4210\n"
        assertEquals(text, decode(text.toByteArray(charset("windows-31j"))))
        assertEquals(text, decode(("﻿" + text).toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `a card's newest entry gives its balance and day`() {
        // 2026-11-14 (year 26, month 11, day 14), balance ¥3,210 (0x0C8A, little-endian), as importic.go lays it out.
        val y = 26
        val m = 11
        val d = 14
        val b4 = (y shl 1) or (m shr 3)
        val b5 = ((m and 7) shl 5) or d
        val block = "16010000%02X%02X00000000%02X%02X0000000000".format(b4, b5, 0x8A, 0x0C).padEnd(32, '0').take(32)
        assertEquals(3_210L to LocalDate.of(2026, 11, 14), IcCardRead("0123456789ABCDEF", listOf("0".repeat(32), block)).newest)
    }

    @Test
    fun `each source's preview reads into one shape`() {
        val json = Json { ignoreUnknownKeys = true }
        val mufg = json.decodeFromString<NetworkImportPreview>(
            """{"records":2,"new":1,"accounts":[{"role":"bank","count":2,"account_id":2,"new_name":"MUFG","new_kind":"bank","candidates":[]}],
            "categories":[],"new_categories":[],
            "rows":[{"line":2,"date":"2026-11-02","summary":"カード","detail":"ライフ","out":4210,"in":0,"balance":1000000,"kind":"expense","from_role":"bank","rule":true,"status":"new"},
                    {"line":3,"date":"2026-11-10","summary":"振込","detail":"アクメ","out":0,"in":280000,"balance":1280000,"kind":"income","from_role":"bank","rule":false,"status":"imported"}],
            "balance":{"account_id":2,"from":"2026-11-01","to":"2026-11-10","opening":1004210,"closing":1280000,"app":1280000,"difference":0,"misplaced":0,"initial_balance":0,"suggested_initial_balance":0,"adjusted":0}}""",
        ).asModel(ImportSource.Mufg)
        assertEquals("bank", mufg.accounts.single().key)
        assertEquals(-4210L, mufg.rows[0].amount)
        assertEquals("カード ライフ", mufg.rows[0].text)
        assertEquals(ImportRowStatus.Imported, mufg.rows[1].status)
        assertEquals(LocalDate.of(2026, 11, 1), mufg.from)
        assertEquals(1_280_000L, mufg.balance!!.actual)

        val payPay = json.decodeFromString<NetworkImportPreview>(
            """{"records":3,"from":"2026-11-02T08:10:00+09:00","to":"2026-11-14T08:10:00+09:00",
            "methods":[{"method":"PayPay残高","count":3,"account_id":null,"new_name":"PayPay","new_kind":"e_money","skip":false}],
            "categories":[{"name":"ドトール","kind":"expense","count":2,"total":960,"category_id":13,"new_name":"","learned":true}],"new_categories":[],"problems":[]}""",
        ).asModel(ImportSource.PayPay)
        assertEquals("PayPay残高", payPay.accounts.single().key)
        assertEquals(3, payPay.new)
        assertEquals(13L, payPay.categories.single().categoryId)
    }
}
