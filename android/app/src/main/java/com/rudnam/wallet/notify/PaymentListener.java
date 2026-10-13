package com.rudnam.wallet.notify;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Sends payment apps' notifications to the server, which turns payments into
 * records to review (internal/notify). Android runs this while the user has
 * given Wallet notification access (Settings › Payment notifications in the
 * app). Every other app's notifications are ignored here and never leave the
 * phone.
 */
public class PaymentListener extends NotificationListenerService {
    /** The apps whose notifications are sent. The server knows the same list. */
    static final Set<String> APPS = new HashSet<>(Arrays.asList(
            "com.google.android.apps.walletnfcrel" // Google Wallet
    ));

    @Override
    public void onListenerConnected() {
        // Catch up on what arrived while the listener wasn't running; the
        // server recognises any it has already seen.
        try {
            StatusBarNotification[] active = getActiveNotifications();
            if (active != null) for (StatusBarNotification sbn : active) keep(sbn);
        } catch (SecurityException ignored) {
            // Access was withdrawn as we connected.
        }
        Outbox.flush(this);
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (keep(sbn)) Outbox.flush(this);
    }

    private boolean keep(StatusBarNotification sbn) {
        if (!APPS.contains(sbn.getPackageName())) return false;
        Notification n = sbn.getNotification();
        if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0 || (n.flags & Notification.FLAG_ONGOING_EVENT) != 0) {
            return false;
        }
        Bundle extras = n.extras;
        CharSequence title = extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
        if (text == null) text = extras.getCharSequence(Notification.EXTRA_TEXT);
        if (title == null && text == null) return false;
        // The time the notification carries is when the payment was made,
        // and stays the same when the app updates it.
        long when = n.when != 0 ? n.when : sbn.getPostTime();
        Outbox.add(this, sbn.getPackageName(), when, str(title), str(text));
        return true;
    }

    private static String str(CharSequence s) {
        return s == null ? "" : s.toString();
    }
}
