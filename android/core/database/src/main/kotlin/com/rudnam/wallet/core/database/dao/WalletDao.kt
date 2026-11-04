package com.rudnam.wallet.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.rudnam.wallet.core.database.model.AccountEntity
import com.rudnam.wallet.core.database.model.BudgetEntity
import com.rudnam.wallet.core.database.model.CategoryEntity
import com.rudnam.wallet.core.database.model.TransactionEntity
import com.rudnam.wallet.core.database.model.WalletEntity
import kotlinx.coroutines.flow.Flow
import java.time.Instant

@Dao
interface WalletDao {
    @Query("SELECT * FROM wallet WHERE id = 0")
    fun observeWallet(): Flow<WalletEntity?>

    @Query("SELECT * FROM accounts ORDER BY position")
    fun observeAccounts(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM categories ORDER BY position")
    fun observeCategories(): Flow<List<CategoryEntity>>

    /** Replaces the settings, accounts and categories with the server's, in one go. */
    @Transaction
    suspend fun replaceWallet(wallet: WalletEntity, accounts: List<AccountEntity>, categories: List<CategoryEntity>) {
        upsertWallet(wallet)
        deleteAccounts()
        insertAccounts(accounts)
        deleteCategories()
        insertCategories(categories)
    }

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun upsertWallet(wallet: WalletEntity)

    @Query("DELETE FROM accounts")
    suspend fun deleteAccounts()

    @Insert
    suspend fun insertAccounts(accounts: List<AccountEntity>)

    @Query("DELETE FROM categories")
    suspend fun deleteCategories()

    @Insert
    suspend fun insertCategories(categories: List<CategoryEntity>)
}

@Dao
interface TransactionDao {
    /** Records in [from, to), newest first, as the server lists them. */
    @Query("SELECT * FROM transactions WHERE occurredAt >= :from AND occurredAt < :to ORDER BY occurredAt DESC, id DESC")
    fun observeRange(from: Instant, to: Instant): Flow<List<TransactionEntity>>

    /**
     * Makes [from, to) what the server says it is: records deleted or moved
     * out of the period elsewhere go, and the rest are replaced.
     */
    @Transaction
    suspend fun replaceRange(from: Instant, to: Instant, records: List<TransactionEntity>) {
        deleteRange(from, to)
        upsert(records)
    }

    @Query("DELETE FROM transactions WHERE occurredAt >= :from AND occurredAt < :to")
    suspend fun deleteRange(from: Instant, to: Instant)

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun upsert(records: List<TransactionEntity>)

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun get(id: Long): TransactionEntity?

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun delete(id: Long)

    /** Records waiting for a check, from any month, newest first. */
    @Query("SELECT * FROM transactions WHERE status = 'review' ORDER BY occurredAt DESC, id DESC")
    fun observeReview(): Flow<List<TransactionEntity>>

    /** Makes the records to review what the server says they are (some were confirmed or deleted elsewhere). */
    @Transaction
    suspend fun replaceReview(records: List<TransactionEntity>) {
        deleteReview()
        upsert(records)
    }

    @Query("DELETE FROM transactions WHERE status = 'review'")
    suspend fun deleteReview()

    @Query("DELETE FROM transactions WHERE id IN (:ids)")
    suspend fun deleteAll(ids: List<Long>)

    /** Records since [from] the phone has, newest first. */
    @Query("SELECT * FROM transactions WHERE occurredAt >= :from ORDER BY occurredAt DESC, id DESC")
    suspend fun since(from: Instant): List<TransactionEntity>
}

@Dao
interface BudgetDao {
    @Query("SELECT * FROM budgets ORDER BY position")
    fun observeBudgets(): Flow<List<BudgetEntity>>

    @Transaction
    suspend fun replaceAll(budgets: List<BudgetEntity>) {
        deleteAll()
        insert(budgets)
    }

    @Query("DELETE FROM budgets")
    suspend fun deleteAll()

    @Insert
    suspend fun insert(budgets: List<BudgetEntity>)
}
