package com.rudnam.wallet.core.data.di

import com.rudnam.wallet.core.data.repository.AccountRepository
import com.rudnam.wallet.core.data.repository.ActivityRepository
import com.rudnam.wallet.core.data.repository.ImportRepository
import com.rudnam.wallet.core.data.repository.InsightsRepository
import com.rudnam.wallet.core.data.repository.ServerImportRepository
import com.rudnam.wallet.core.data.repository.ServerSettingsRepository
import com.rudnam.wallet.core.data.repository.ServerUserRepository
import com.rudnam.wallet.core.data.repository.SettingsRepository
import com.rudnam.wallet.core.data.repository.UserRepository
import com.rudnam.wallet.core.data.repository.ServerInsightsRepository
import com.rudnam.wallet.core.data.repository.ServerAccountRepository
import com.rudnam.wallet.core.data.repository.OfflineFirstActivityRepository
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
    fun activityRepository(impl: OfflineFirstActivityRepository): ActivityRepository

    @Binds
    fun accountRepository(impl: ServerAccountRepository): AccountRepository

    @Binds
    fun insightsRepository(impl: ServerInsightsRepository): InsightsRepository

    @Binds
    fun settingsRepository(impl: ServerSettingsRepository): SettingsRepository

    @Binds
    fun userRepository(impl: ServerUserRepository): UserRepository

    @Binds
    fun importRepository(impl: ServerImportRepository): ImportRepository

    @Binds
    fun outboxScheduler(impl: WorkManagerOutboxScheduler): OutboxScheduler
}
