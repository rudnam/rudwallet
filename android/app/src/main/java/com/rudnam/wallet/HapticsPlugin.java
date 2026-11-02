package com.rudnam.wallet;

import android.os.Build;
import android.view.HapticFeedbackConstants;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * Short taps of the phone's own haptics, for the web app (web/src/native.ts):
 * tick() when a choice is made, confirm() when something is saved. The view's
 * haptic feedback follows the user's system setting and needs no permission.
 */
@CapacitorPlugin(name = "Haptics")
public class HapticsPlugin extends Plugin {
    @PluginMethod
    public void tick(PluginCall call) {
        perform(Build.VERSION.SDK_INT >= 27 ? HapticFeedbackConstants.KEYBOARD_PRESS : HapticFeedbackConstants.VIRTUAL_KEY);
        call.resolve();
    }

    @PluginMethod
    public void confirm(PluginCall call) {
        perform(Build.VERSION.SDK_INT >= 30 ? HapticFeedbackConstants.CONFIRM : HapticFeedbackConstants.LONG_PRESS);
        call.resolve();
    }

    private void perform(int feedback) {
        getActivity().runOnUiThread(() -> getBridge().getWebView().performHapticFeedback(feedback));
    }
}
