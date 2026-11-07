package com.rudnam.wallet.web

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import com.getcapacitor.Bridge
import com.getcapacitor.CapConfig
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginManager
import com.rudnam.wallet.HapticsPlugin
import com.rudnam.wallet.SplashPlugin
import com.rudnam.wallet.UpdatesPlugin
import com.rudnam.wallet.notify.NotificationsPlugin
import com.rudnam.wallet.widget.WidgetsPlugin

/**
 * The web view, for the pages that aren't native yet: Capacitor's bridge,
 * running the website's code built into the app, inside a fragment so it
 * can sit in the native layout under the native tabs (WebHost). There is
 * one, kept for the life of the activity, so going back to a web page is
 * instant and the web app keeps its state.
 *
 * Native code owns navigation: the web app runs in "embedded" mode
 * (web/src/native.ts), asks for every page change with NativeNav.go, and
 * shows the page native code tells it to ([show]).
 */
class WebPageFragment : Fragment(com.getcapacitor.android.R.layout.capacitor_bridge_layout_main) {
    var bridge: Bridge? = null
        private set

    /** What the web app asks of the native app (NativeNavPlugin). Set by WebHost. */
    var onRequest: (WebRequest) -> Unit = {}

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val activity = requireActivity()
        val plugins: List<Class<out Plugin>> = PluginManager(activity.assets).loadPluginClasses() + listOf(
            NativeNavPlugin::class.java,
            WidgetsPlugin::class.java,
            NotificationsPlugin::class.java,
            UpdatesPlugin::class.java,
            SplashPlugin::class.java,
            HapticsPlugin::class.java,
        )
        bridge = Bridge.Builder(this)
            .setInstanceState(savedInstanceState)
            .setConfig(CapConfig.loadDefault(activity))
            .addPlugins(plugins)
            .create()
        // The native layout keeps the page clear of the system bars and the keyboard, so the page itself sees none.
        bridge?.webView?.let { web -> ViewCompat.setOnApplyWindowInsetsListener(web) { _, _ -> WindowInsetsCompat.CONSUMED } }
    }

    /** Shows a page: the web app replaces its route with [path] (e.g. "records?account=3"). */
    fun show(path: String) = emit("show", JSObject().put("path", path))

    /** Signed out in the app's Settings (or the account deleted): the web app forgets the session too. */
    fun signedOut(deleted: Boolean) = emit("signedOut", JSObject().put("deleted", deleted))

    /** Appearance chosen in the app's Settings: auto, light or dark. */
    fun setAppearance(appearance: String) = emit("appearance", JSObject().put("appearance", appearance))

    /** Android's back gesture, while the web app has a sheet or dialog open: it closes that. */
    fun back() = emit("back", JSObject())

    /** An intent the web app handles: Google sign-in returning, or a transit IC card tapped to the phone. */
    fun onNewIntent(intent: Intent) {
        bridge?.onNewIntent(intent)
    }

    private fun emit(event: String, data: JSObject) {
        (bridge?.getPlugin("NativeNav")?.instance as? NativeNavPlugin)?.emit(event, data)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        bridge?.saveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        bridge?.onStart()
    }

    override fun onResume() {
        super.onResume()
        bridge?.let {
            it.app.fireStatusChange(true)
            it.onResume()
        }
    }

    override fun onPause() {
        super.onPause()
        bridge?.onPause()
    }

    override fun onStop() {
        super.onStop()
        bridge?.let {
            it.app.fireStatusChange(false)
            it.onStop()
        }
    }

    override fun onDestroyView() {
        bridge?.onDetachedFromWindow()
        bridge?.onDestroy()
        bridge = null
        super.onDestroyView()
    }
}

/** What the web app asks of the native app. */
sealed interface WebRequest {
    /** Go to a page (the web app navigated). [replace]: instead of the page shown now, not on top of it. */
    data class Go(val path: String, val replace: Boolean) : WebRequest

    /** A sheet or dialog opened or closed over the page: while one is open, back closes it. */
    data class Overlay(val open: Boolean) : WebRequest
}
