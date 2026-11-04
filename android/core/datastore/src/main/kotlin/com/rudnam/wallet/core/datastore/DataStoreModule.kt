package com.rudnam.wallet.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.rudnam.wallet.core.common.ApplicationScope
import com.rudnam.wallet.core.common.Dispatcher
import com.rudnam.wallet.core.common.WalletDispatchers
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataStoreModule {
    @Provides
    @Singleton
    fun userPreferencesDataStore(
        @ApplicationContext context: Context,
        @Dispatcher(WalletDispatchers.IO) io: CoroutineDispatcher,
        @ApplicationScope scope: CoroutineScope,
    ): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        scope = CoroutineScope(scope.coroutineContext + io),
        produceFile = { context.preferencesDataStoreFile("user_preferences") },
    )
}

@Module
@InstallIn(SingletonComponent::class)
internal interface SessionModule {
    @Binds
    fun sessionStore(impl: CapacitorSessionStore): SessionStore
}
