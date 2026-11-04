package com.rudnam.wallet.core.model

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class TransactionTest {
    private fun record(kind: TransactionKind, amount: Long, category: Long? = null, status: TransactionStatus = TransactionStatus.Confirmed) = Transaction(
        id = 1, kind = kind, amount = amount, accountId = 1, toAccountId = null, categoryId = category,
        occurredAt = Instant.EPOCH, payee = "", note = "", status = status, source = TransactionSource.Manual,
        sourceLabel = "", duplicateOfId = null, createdAt = Instant.EPOCH, tags = emptyList(),
    )

    @Test
    fun `transfers and records to review don't change the total`() {
        assertEquals(-500, record(TransactionKind.Expense, 500).signedAmount)
        assertEquals(800, record(TransactionKind.Income, 800).signedAmount)
        assertEquals(0, record(TransactionKind.Transfer, 1000).signedAmount)
        assertEquals(0, record(TransactionKind.Expense, 500, status = TransactionStatus.Review).signedAmount)
    }

    @Test
    fun `a budget counts its category's confirmed spending, or all of it`() {
        val records = listOf(
            record(TransactionKind.Expense, 1000, category = 1),
            record(TransactionKind.Expense, 300, category = 2),
            record(TransactionKind.Expense, 99, category = 1, status = TransactionStatus.Review),
            record(TransactionKind.Income, 5000, category = 1),
        )
        assertEquals(1000, Budget(1, "Food", 1, 8000).spentIn(records))
        assertEquals(1300, Budget(2, "Everything", null, 60000).spentIn(records))
        assertEquals(1300, records.spent())
    }
}
