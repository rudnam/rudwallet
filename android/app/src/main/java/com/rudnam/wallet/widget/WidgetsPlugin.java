package com.rudnam.wallet.widget;

import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/** Lets the web app redraw the widgets, e.g. after signing in or leaving the app (web/src/native.ts). */
@CapacitorPlugin(name = "Widgets")
public class WidgetsPlugin extends Plugin {
    @PluginMethod
    public void refresh(PluginCall call) {
        Widgets.refreshAll(getContext(), null);
        call.resolve();
    }
}
