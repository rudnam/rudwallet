package com.rudnam.wallet.core.database.di

import android.content.Context
import androidx.room.Room
import com.rudnam.wallet.core.database.WalletDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): WalletDatabase =
        Room.databaseBuilder(context, WalletDatabase::class.java, "wallet.db").build()

    @Provides
    fun walletDao(db: WalletDatabase) = db.walletDao()

    @Provides
    fun transactionDao(db: WalletDatabase) = db.transactionDao()

    @Provides
    fun budgetDao(db: WalletDatabase) = db.budgetDao()

    @Provides
    fun outboxDao(db: WalletDatabase) = db.outboxDao()
}
