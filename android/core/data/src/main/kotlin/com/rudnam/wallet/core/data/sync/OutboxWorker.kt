package com.rudnam.wallet.core.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.rudnam.wallet.core.data.repository.RecordRepository
import com.rudnam.wallet.core.data.repository.SyncResult
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sends the records kept on the phone (the outbox) once there's a
 * connection, oldest first, and tries again later if it's lost meanwhile.
 * WorkManager runs it even if the app has closed since.
 */
@HiltWorker
class OutboxWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val records: RecordRepository,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = when (records.sendPending()) {
        SyncResult.Done -> Result.success()
        // Signed out: what's waiting goes with the session (the database is emptied).
        SyncResult.SignedOut -> Result.success()
        SyncResult.Offline, SyncResult.Failed -> Result.retry()
    }
}

/** Asks for the outbox to be sent. */
interface OutboxScheduler {
    fun schedule()
}

@Singleton
internal class WorkManagerOutboxScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : OutboxScheduler {
    override fun schedule() {
        val request = OneTimeWorkRequestBuilder<OutboxWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        // One at a time: a send already waiting covers this record too.
        WorkManager.getInstance(context).enqueueUniqueWork("outbox", ExistingWorkPolicy.KEEP, request)
    }
}
