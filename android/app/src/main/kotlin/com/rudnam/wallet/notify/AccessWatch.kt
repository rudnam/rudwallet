package com.rudnam.wallet.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.rudnam.wallet.MainActivity
import com.rudnam.wallet.R
import com.rudnam.wallet.core.datastore.SessionStore
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Notices when notification access goes off after it worked (Android or the
 * user turned it off), and says so with a notification, so payments aren't
 * missed for days with the app closed. A reinstall wipes this too, so that
 * case is Wallet's card, seen on signing in again.
 */
@Singleton
class AccessWatch @Inject constructor(
    @ApplicationContext private val context: Context,
    private val session: SessionStore,
) {
    private val prefs = context.getSharedPreferences("notification-access", Context.MODE_PRIVATE)

    /** Checks every few hours, with the app closed too. */
    fun schedule() {
        val request = PeriodicWorkRequestBuilder<AccessWatchWorker>(6, TimeUnit.HOURS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("notification-access", ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Looks once: remembers access that works, and warns once each time it's lost. */
    fun check() {
        if (NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)) {
            working()
            return
        }
        if (!prefs.getBoolean(HAD_ACCESS, false) || prefs.getBoolean(WARNED, false)) return
        if (session.currentToken() == null) return // signed out: nothing to record payments to
        if (warn()) prefs.edit { putBoolean(WARNED, true) }
    }

    /** Access is on: the next loss warns again, and a warning still showing goes. */
    fun working() {
        prefs.edit { putBoolean(HAD_ACCESS, true).putBoolean(WARNED, false) }
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun warn(): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false // asked for in Settings › Payment notifications; tried again next time
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.notify_access_channel), NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        // Opens Settings › Payment notifications, whose button goes to Android's switch.
        val open = Intent(Intent.ACTION_VIEW, "${context.packageName}://open/settings?section=notifications".toUri(), context, MainActivity::class.java)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(context.getString(R.string.notify_access_lost))
            .setContentText(context.getString(R.string.notify_access_lost_detail))
            .setContentIntent(PendingIntent.getActivity(context, NOTIFICATION_ID, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
        return true
    }

    private companion object {
        const val HAD_ACCESS = "had-access"
        const val WARNED = "warned"
        const val CHANNEL = "notification-access"
        const val NOTIFICATION_ID = 1
    }
}

@HiltWorker
class AccessWatchWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val watch: AccessWatch,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        watch.check()
        return Result.success()
    }
}
