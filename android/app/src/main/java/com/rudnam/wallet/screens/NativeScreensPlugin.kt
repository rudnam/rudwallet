package com.rudnam.wallet.screens

import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin

/**
 * The web app's side of the native screens (NativeOverlay; web/src/native.ts):
 * whether the native Wallet (the page at #/home) is on, showing and hiding it, and the "open"
 * event, which asks the web app for a page that isn't native yet.
 */
@CapacitorPlugin(name = "NativeScreens")
class NativeScreensPlugin : Plugin() {
    /** Sends an event to the web app; kept until it listens, so a tap made while it starts isn't lost. */
    fun emit(event: String, data: JSObject) {
        notifyListeners(event, data, true)
    }

    /** { home: true } when the native Wallet is turned on. */
    @PluginMethod
    fun status(call: PluginCall) {
        call.resolve(JSObject().put("home", NativeOverlay.homeEnabled(context)))
    }

    /** Turns the native Wallet on or off: { home: boolean }. */
    @PluginMethod
    fun setEnabled(call: PluginCall) {
        val on = call.getBoolean("home") ?: return call.reject("home is required")
        NativeOverlay.setHomeEnabled(context, on)
        if (!on) activity.runOnUiThread { NativeOverlay.current?.hide() }
        call.resolve()
    }

    /**
     * Shows the native Wallet, if it's on: { locale: 'ja-JP', appearance: 'dark' }, the
     * locale the web app writes dates and money in and its Settings › Appearance (auto, light or dark).
     */
    @PluginMethod
    fun show(call: PluginCall) {
        val locale = call.getString("locale")
        val appearance = call.getString("appearance")
        if (NativeOverlay.homeEnabled(context)) activity.runOnUiThread { NativeOverlay.current?.show(locale, appearance) }
        call.resolve()
    }

    @PluginMethod
    fun hide(call: PluginCall) {
        activity.runOnUiThread { NativeOverlay.current?.hide() }
        call.resolve()
    }
}
