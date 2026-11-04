package com.rudnam.wallet.core.data.di

import com.rudnam.wallet.core.data.repository.OfflineFirstRecordRepository
import com.rudnam.wallet.core.data.repository.OfflineFirstWalletRepository
import com.rudnam.wallet.core.data.repository.RecordRepository
import com.rudnam.wallet.core.data.sync.OutboxScheduler
import com.rudnam.wallet.core.data.sync.WorkManagerOutboxScheduler
import com.rudnam.wallet.core.data.repository.ServerUpdateRepository
import com.rudnam.wallet.core.data.repository.UpdateRepository
import com.rudnam.wallet.core.data.repository.WalletRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
internal interface DataModule {
    @Binds
    fun walletRepository(impl: OfflineFirstWalletRepository): WalletRepository

    @Binds
    fun updateRepository(impl: ServerUpdateRepository): UpdateRepository

    @Binds
    fun recordRepository(impl: OfflineFirstRecordRepository): RecordRepository

    @Binds
    fun outboxScheduler(impl: WorkManagerOutboxScheduler): OutboxScheduler
}
