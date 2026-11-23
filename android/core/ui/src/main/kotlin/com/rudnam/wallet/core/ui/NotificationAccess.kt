package com.rudnam.wallet.core.ui

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect

/** The app's NotificationListenerService (the app module's notify/), by name: feature modules can't see its class. */
private const val PAYMENT_LISTENER = "com.rudnam.wallet.notify.PaymentListener"

/**
 * Whether RudWallet may read notifications, checked again each time the
 * screen comes back (from Android's settings, say). Null until first checked.
 */
@Composable
fun rememberNotificationAccess(): Boolean? {
    val context = LocalContext.current
    var access by remember { mutableStateOf<Boolean?>(null) }
    LifecycleResumeEffect(Unit) {
        access = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
        onPauseOrDispose {}
    }
    return access
}

/** Android's notification access page, at RudWallet's own switch where it can. */
fun openNotificationAccess(context: Context) {
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, ComponentName(context.packageName, PAYMENT_LISTENER).flattenToString())
    } else {
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    }
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }
}
