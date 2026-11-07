package com.rudnam.wallet

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.rudnam.wallet.core.data.sync.OutboxScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/** The app. WorkManager's workers get their dependencies from Hilt (HiltWorkerFactory). */
@HiltAndroidApp
class RudWalletApplication : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var outbox: OutboxScheduler

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        // Records left waiting last time go as soon as there's a connection.
        outbox.schedule()
    }
}
