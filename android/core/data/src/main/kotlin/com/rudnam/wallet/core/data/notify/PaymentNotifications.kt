package com.rudnam.wallet.core.data.notify

import android.content.Context
import androidx.core.content.edit
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.rudnam.wallet.core.datastore.SessionStore
import com.rudnam.wallet.core.network.ApiException
import com.rudnam.wallet.core.network.SignedOutException
import com.rudnam.wallet.core.network.WalletNetworkDataSource
import com.rudnam.wallet.core.network.model.NetworkIncomingNotification
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import java.io.IOException
import java.time.Clock
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** The payment apps whose notifications are sent. The server knows the same list (internal/notify). */
val PAYMENT_APPS = setOf(
    "com.google.android.apps.walletnfcrel", // Google Wallet
    "jp.ne.paypay.android.app", // PayPay
)

/**
 * Payment notifications waiting to reach the server, kept on the phone until
 * it has them: a payment made offline, or signed out, is sent later. Sending
 * one twice is harmless; the server records each payment once. They're kept
 * where the Java listener before this kept them ("payment-notifications"),
 * in the same form, so an update loses none.
 */
@Singleton
class PaymentNotificationOutbox @Inject constructor(
    @ApplicationContext context: Context,
    private val network: WalletNetworkDataSource,
    private val session: SessionStore,
    private val clock: Clock,
) {
    private val prefs = context.getSharedPreferences("payment-notifications", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private fun load(): List<NetworkIncomingNotification> =
        prefs.getString(QUEUE, null)?.let { runCatching { json.decodeFromString<List<NetworkIncomingNotification>>(it) }.getOrNull() }.orEmpty()

    private fun save(queue: List<NetworkIncomingNotification>) = prefs.edit(commit = true) { putString(QUEUE, json.encodeToString(queue)) }

    /** What's waiting, oldest first. */
    val waiting: List<NetworkIncomingNotification> @Synchronized get() = load()

    /** Keeps a notification; an update of one still waiting replaces it. Old ones go: they're no longer worth recording. */
    @Synchronized
    fun add(packageName: String, whenMillis: Long, title: String, text: String) {
        val cutoff = clock.millis() - MAX_AGE_MS
        val kept = load().filter { it.whenMillis >= cutoff && !(it.packageName == packageName && it.whenMillis == whenMillis) }
        save((kept + NetworkIncomingNotification(packageName, whenMillis, title, text)).takeLast(MAX_KEPT))
    }

    /** How sending went. */
    enum class Sent { Done, Offline, SignedOut }

    /** Sends what's waiting, a batch at a time; what was sent is dropped (anything added meanwhile stays). */
    suspend fun send(): Sent {
        if (session.currentToken() == null) return Sent.SignedOut // kept until someone signs in
        while (true) {
            val batch = synchronized(this) { load().take(BATCH) }
            if (batch.isEmpty()) return Sent.Done
            try {
                network.sendNotifications(batch)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SignedOutException) {
                return Sent.SignedOut
            } catch (e: IOException) {
                return Sent.Offline
            } catch (e: ApiException) {
                return Sent.Offline // a server problem: try again later
            }
            synchronized(this) { save(load().filterNot { it in batch }) }
        }
    }

    private companion object {
        const val QUEUE = "queue"

        /** Kept at most a week, and this many. */
        const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
        const val MAX_KEPT = 200

        /** The most the server takes in one request (internal/notify). */
        const val BATCH = 50
    }
}

/** Sends the waiting payment notifications once there's a connection; WorkManager runs it even with the app closed. */
@HiltWorker
class PaymentNotificationsWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val outbox: PaymentNotificationOutbox,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = when (outbox.send()) {
        PaymentNotificationOutbox.Sent.Done -> Result.success()
        // Kept until someone signs in, which asks again.
        PaymentNotificationOutbox.Sent.SignedOut -> Result.success()
        PaymentNotificationOutbox.Sent.Offline -> Result.retry()
    }
}

/** Asks for the waiting payment notifications to be sent. */
@Singleton
class PaymentNotificationSender @Inject constructor(@ApplicationContext private val context: Context) {
    fun schedule() {
        val request = OneTimeWorkRequestBuilder<PaymentNotificationsWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        // After a send under way, not instead of it: what arrived meanwhile goes too.
        WorkManager.getInstance(context).enqueueUniqueWork("payment-notifications", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }
}
