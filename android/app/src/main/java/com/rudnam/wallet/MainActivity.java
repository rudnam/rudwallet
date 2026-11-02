package com.rudnam.wallet;

import android.os.Bundle;
import android.os.SystemClock;

import androidx.core.splashscreen.SplashScreen;

import com.getcapacitor.BridgeActivity;
import com.rudnam.wallet.notify.NotificationsPlugin;
import com.rudnam.wallet.widget.WidgetsPlugin;

public class MainActivity extends BridgeActivity {
    /** The longest the splash waits for the web app, so it can never stick. */
    private static final long SPLASH_MAX_MS = 3000;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        // The splash screen (styles.xml) stays until the web app has drawn
        // (SplashPlugin), instead of giving way to an empty web view.
        SplashPlugin.drawn = false;
        long start = SystemClock.uptimeMillis();
        SplashScreen.installSplashScreen(this)
                .setKeepOnScreenCondition(() -> !SplashPlugin.drawn && SystemClock.uptimeMillis() - start < SPLASH_MAX_MS);
        // Before super.onCreate, which starts the bridge.
        registerPlugin(WidgetsPlugin.class);
        registerPlugin(NotificationsPlugin.class);
        registerPlugin(UpdatesPlugin.class);
        registerPlugin(SplashPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
