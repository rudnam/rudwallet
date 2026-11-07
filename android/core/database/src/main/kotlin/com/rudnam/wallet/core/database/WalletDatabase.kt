package com.rudnam.wallet.core.database

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.rudnam.wallet.core.database.dao.BudgetDao
import com.rudnam.wallet.core.database.dao.OutboxDao
import com.rudnam.wallet.core.database.dao.TransactionDao
import com.rudnam.wallet.core.database.dao.WalletDao
import com.rudnam.wallet.core.database.model.AccountEntity
import com.rudnam.wallet.core.database.model.BudgetEntity
import com.rudnam.wallet.core.database.model.CategoryEntity
import com.rudnam.wallet.core.database.model.OutboxEntity
import com.rudnam.wallet.core.database.model.TransactionEntity
import com.rudnam.wallet.core.database.model.WalletEntity

/**
 * The phone's copy of the signed-in user's wallet. Everything in it but
 * the outbox (records not sent yet) can be fetched again; it's all emptied
 * on signing out. Each version's schema is
 * kept in schemas/ (exportSchema): a new version adds a migration, tested
 * against them, rather than dropping what's there.
 */
@Database(
    entities = [
        WalletEntity::class, AccountEntity::class, CategoryEntity::class, TransactionEntity::class, BudgetEntity::class,
        OutboxEntity::class,
    ],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)], // 2: the outbox
)
@TypeConverters(Converters::class)
abstract class WalletDatabase : RoomDatabase() {
    abstract fun walletDao(): WalletDao

    abstract fun transactionDao(): TransactionDao

    abstract fun budgetDao(): BudgetDao

    abstract fun outboxDao(): OutboxDao
}
