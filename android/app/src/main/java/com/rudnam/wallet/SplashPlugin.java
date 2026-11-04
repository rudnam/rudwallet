package com.rudnam.wallet;

import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * The web app calls hide() once it has drawn something worth seeing (Home,
 * or the sign-in page; web/src/native.ts), and MainActivity takes the splash
 * screen away. Until then the phone shows the splash, not an empty web view.
 */
@CapacitorPlugin(name = "Splash")
public class SplashPlugin extends Plugin {
    /** Set by hide(), or by a native screen once it has drawn (screens/NativeOverlay.kt). */
    public static volatile boolean drawn = false;

    @PluginMethod
    public void hide(PluginCall call) {
        drawn = true;
        call.resolve();
    }
}
