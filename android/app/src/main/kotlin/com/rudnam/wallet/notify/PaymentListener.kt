package com.rudnam.wallet.notify

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.rudnam.wallet.core.data.notify.PAYMENT_APPS
import com.rudnam.wallet.core.data.notify.PaymentNotificationOutbox
import com.rudnam.wallet.core.data.notify.PaymentNotificationSender
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Sends payment apps' notifications to the server, which turns payments
 * into records (internal/notify). Android runs this while RudWallet has
 * notification access (Settings › Payment notifications). Every other
 * app's notifications are ignored here and never leave the phone.
 */
@AndroidEntryPoint
class PaymentListener : NotificationListenerService() {
    @Inject lateinit var outbox: PaymentNotificationOutbox

    @Inject lateinit var sender: PaymentNotificationSender

    override fun onListenerConnected() {
        // Catch up on what arrived while the listener wasn't running; the server recognises any it has seen.
        try {
            activeNotifications?.forEach(::keep)
        } catch (e: SecurityException) {
            // Access was withdrawn as it connected.
        }
        sender.schedule()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (keep(sbn)) sender.schedule()
    }

    private fun keep(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName !in PAYMENT_APPS) return false
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0 || n.flags and Notification.FLAG_ONGOING_EVENT != 0) return false
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)
        val text = n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: n.extras.getCharSequence(Notification.EXTRA_TEXT)
        if (title == null && text == null) return false
        // The time the notification carries is when the payment was made, and stays the same when the app updates it.
        val whenMillis = if (n.`when` != 0L) n.`when` else sbn.postTime
        outbox.add(sbn.packageName, whenMillis, title?.toString().orEmpty(), text?.toString().orEmpty())
        return true
    }
}
