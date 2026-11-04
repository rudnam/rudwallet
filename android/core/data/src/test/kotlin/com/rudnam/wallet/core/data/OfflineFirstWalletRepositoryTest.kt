package com.rudnam.wallet.core.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rudnam.wallet.core.data.repository.OfflineFirstWalletRepository
import com.rudnam.wallet.core.data.repository.SyncResult
import com.rudnam.wallet.core.database.WalletCache
import com.rudnam.wallet.core.database.WalletDatabase
import com.rudnam.wallet.core.network.SignedOutException
import com.rudnam.wallet.core.network.WalletNetworkDataSource
import com.rudnam.wallet.core.network.model.NetworkAccount
import com.rudnam.wallet.core.network.model.NetworkApk
import com.rudnam.wallet.core.network.model.NetworkBudget
import com.rudnam.wallet.core.network.model.NetworkDownloads
import com.rudnam.wallet.core.network.model.NetworkNotifications
import com.rudnam.wallet.core.network.model.NetworkSettings
import com.rudnam.wallet.core.network.model.NetworkTransaction
import com.rudnam.wallet.core.network.model.NetworkWallet
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OfflineFirstWalletRepositoryTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), WalletDatabase::class.java)
        .allowMainThreadQueries().build()

    @After
    fun close() = db.close()

    private class FakeNetwork : WalletNetworkDataSource {
        var failure: Exception? = null
        var records = listOf(
            NetworkTransaction(1, "expense", 480, 1, occurredAt = "2026-11-14T08:10:00+09:00", payee = "Doutor"),
            NetworkTransaction(2, "income", 280000, 1, occurredAt = "2026-11-10T09:00:00+09:00", payee = "Acme KK"),
        )

        private fun check() = failure?.let { throw it }

        override suspend fun wallet(): NetworkWallet {
            check()
            return NetworkWallet(NetworkSettings("JPY"), listOf(NetworkAccount(1, "Cash", "cash", balance = 26450)), emptyList(), reviewCount = 1)
        }

        override suspend fun transactions(from: Instant, to: Instant): List<NetworkTransaction> {
            check()
            return records
        }

        override suspend fun budgets(): List<NetworkBudget> {
            check()
            return listOf(NetworkBudget(1, "Everything", null, 60000))
        }

        override suspend fun notifications() = NetworkNotifications()

        override suspend fun downloads() = NetworkDownloads(NetworkApk("/download/wallet.apk", 5))

        override suspend fun confirmedSince(from: Instant) = records
        override suspend fun createRecord(input: com.rudnam.wallet.core.network.model.NetworkRecordInput) = error("not used")
        override suspend fun updateRecord(id: Long, input: com.rudnam.wallet.core.network.model.NetworkRecordInput) = error("not used")
        override suspend fun deleteRecord(id: Long) = Unit
        override suspend fun restoreRecords(ids: List<Long>) = Unit
        override suspend fun tags() = emptyList<com.rudnam.wallet.core.network.model.NetworkTag>()
    }

    private val from = Instant.parse("2026-10-31T15:00:00Z")
    private val to = Instant.parse("2026-11-30T15:00:00Z")

    @Test
    fun `a refresh fills the phone, offline keeps it, and signing out empties it`() = runTest {
        val network = FakeNetwork()
        val repository = OfflineFirstWalletRepository(network, db.walletDao(), db.transactionDao(), db.budgetDao(), db.outboxDao(), WalletCache(db), StandardTestDispatcher(testScheduler))
        assertNull(repository.wallet.first())

        assertEquals(SyncResult.Done, repository.refresh(from, to))
        assertEquals(26450L, repository.wallet.first()!!.totalBalance)
        assertEquals(listOf(1L, 2L), repository.transactions(from, to).first().map { it.id })
        assertEquals(1, repository.budgets.first().size)

        network.failure = IOException("offline")
        assertEquals(SyncResult.Offline, repository.refresh(from, to))
        assertEquals(2, repository.transactions(from, to).first().size)

        // Deleted elsewhere meanwhile: gone from the phone too.
        network.failure = null
        network.records = network.records.take(1)
        repository.refresh(from, to)
        assertEquals(listOf(1L), repository.transactions(from, to).first().map { it.id })

        network.failure = SignedOutException()
        assertEquals(SyncResult.SignedOut, repository.refresh(from, to))
        repository.forget()
        assertNull(repository.wallet.first())
    }
}
