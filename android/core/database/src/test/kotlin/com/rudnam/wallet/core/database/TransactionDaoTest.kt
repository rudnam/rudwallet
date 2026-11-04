package com.rudnam.wallet.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rudnam.wallet.core.database.model.TransactionEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TransactionDaoTest {
    private lateinit var db: WalletDatabase

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), WalletDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun close() = db.close()

    private fun record(id: Long, at: String, tags: List<String> = emptyList()) = TransactionEntity(
        id, "expense", 100, 1, null, null, Instant.parse(at), "Shop", "", "confirmed", "manual", "", null, Instant.parse(at), tags,
    )

    @Test
    fun `replacing a month leaves the others alone and drops what the server no longer has`() = runTest {
        val dao = db.transactionDao()
        val nov = Instant.parse("2026-11-01T00:00:00Z")
        val dec = Instant.parse("2026-12-01T00:00:00Z")
        dao.upsert(listOf(record(1, "2026-10-31T12:00:00Z"), record(2, "2026-11-02T12:00:00Z"), record(3, "2026-11-03T12:00:00Z")))

        dao.replaceRange(nov, dec, listOf(record(3, "2026-11-03T12:00:00Z", tags = listOf("dinner")), record(4, "2026-11-05T12:00:00Z")))

        assertEquals(listOf(4L, 3L), dao.observeRange(nov, dec).first().map { it.id })
        assertEquals(listOf("dinner"), dao.observeRange(nov, dec).first()[1].tags)
        assertEquals(listOf(1L), dao.observeRange(Instant.EPOCH, nov).first().map { it.id })
    }
}
