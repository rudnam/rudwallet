package com.rudnam.wallet.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.rudnam.wallet.core.database.model.OutboxEntity
import kotlinx.coroutines.flow.Flow
import java.time.Instant

@Dao
interface OutboxDao {
    /** Everything waiting, oldest first: the order they're sent in. */
    @Query("SELECT * FROM outbox ORDER BY localId")
    fun observeAll(): Flow<List<OutboxEntity>>

    @Query("SELECT * FROM outbox ORDER BY localId")
    suspend fun all(): List<OutboxEntity>

    @Query("SELECT * FROM outbox WHERE localId = :localId")
    suspend fun get(localId: Long): OutboxEntity?

    @Insert
    suspend fun insert(record: OutboxEntity): Long

    /** Changes one on the phone: what's sent is the new version, and a refusal is forgotten (it's tried again). */
    @Query(
        """UPDATE outbox SET kind = :kind, amount = :amount, accountId = :accountId, toAccountId = :toAccountId,
           categoryId = :categoryId, occurredAt = :occurredAt, payee = :payee, note = :note, status = :status,
           tags = :tags, version = version + 1, error = NULL WHERE localId = :localId""",
    )
    suspend fun update(
        localId: Long, kind: String, amount: Long, accountId: Long, toAccountId: Long?, categoryId: Long?,
        occurredAt: Instant, payee: String, note: String, status: String, tags: List<String>,
    ): Int

    @Query("UPDATE outbox SET error = :error WHERE localId = :localId")
    suspend fun refuse(localId: Long, error: String)

    @Query("DELETE FROM outbox WHERE localId = :localId")
    suspend fun delete(localId: Long): Int
}
