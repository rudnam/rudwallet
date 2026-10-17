package com.rudnam.wallet.notify;

import android.content.ComponentName;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;

import androidx.core.app.NotificationManagerCompat;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/** Lets the web app see and change whether payment notifications are read (web/src/native.ts). */
@CapacitorPlugin(name = "PaymentNotifications")
public class NotificationsPlugin extends Plugin {
    /** {enabled}: whether RudWallet has notification access. */
    @PluginMethod
    public void status(PluginCall call) {
        boolean enabled = NotificationManagerCompat.getEnabledListenerPackages(getContext())
                .contains(getContext().getPackageName());
        JSObject r = new JSObject();
        r.put("enabled", enabled);
        call.resolve(r);
    }

    /** Opens the system page where the user gives (or withdraws) notification access. */
    @PluginMethod
    public void openSettings(PluginCall call) {
        Intent i;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Straight to RudWallet's own switch.
            i = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS);
            i.putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    new ComponentName(getContext(), PaymentListener.class).flattenToString());
        } else {
            i = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            getContext().startActivity(i);
        } catch (android.content.ActivityNotFoundException e) {
            getContext().startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
        call.resolve();
    }

    /** Sends notifications still waiting, e.g. after signing in or leaving the app. */
    @PluginMethod
    public void flush(PluginCall call) {
        Outbox.flush(getContext());
        call.resolve();
    }
}
