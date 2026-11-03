package com.rudnam.wallet;

import android.os.Build;
import android.view.HapticFeedbackConstants;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * The phone's own haptics for the web app (web/src/native.ts), one kind per
 * moment so each feels the same every time: a key on the number pad, a
 * choice, a swipe passing the point where it acts, something saved, and
 * something refused. Each is the system's feedback for that moment on
 * Android versions that have one, and the nearest older one before; the
 * view's feedback follows the user's touch feedback setting and needs no
 * permission.
 */
@CapacitorPlugin(name = "Haptics")
public class HapticsPlugin extends Plugin {
    /** A key on the app's number pad, as the phone's keyboard feels. */
    @PluginMethod
    public void key(PluginCall call) {
        perform(HapticFeedbackConstants.KEYBOARD_TAP);
        call.resolve();
    }

    /** A choice: a category, an account, expense or income. */
    @PluginMethod
    public void select(PluginCall call) {
        perform(Build.VERSION.SDK_INT >= 34 ? HapticFeedbackConstants.SEGMENT_TICK : HapticFeedbackConstants.CLOCK_TICK);
        call.resolve();
    }

    /** A swipe passing the point where letting go acts. */
    @PluginMethod
    public void threshold(PluginCall call) {
        perform(Build.VERSION.SDK_INT >= 34
                ? HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE
                : HapticFeedbackConstants.CONTEXT_CLICK);
        call.resolve();
    }

    /** Something saved or confirmed. */
    @PluginMethod
    public void success(PluginCall call) {
        perform(Build.VERSION.SDK_INT >= 30 ? HapticFeedbackConstants.CONFIRM : HapticFeedbackConstants.CONTEXT_CLICK);
        call.resolve();
    }

    /** Something that couldn't be done, such as saving without an amount. */
    @PluginMethod
    public void error(PluginCall call) {
        perform(Build.VERSION.SDK_INT >= 30 ? HapticFeedbackConstants.REJECT : HapticFeedbackConstants.LONG_PRESS);
        call.resolve();
    }

    private void perform(int feedback) {
        getActivity().runOnUiThread(() -> getBridge().getWebView().performHapticFeedback(feedback));
    }
}
