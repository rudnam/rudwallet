package com.rudnam.wallet.web

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.rudnam.wallet.core.datastore.Appearance
import com.rudnam.wallet.core.datastore.UserPreferencesRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The web app's side of native navigation (web/src/native.ts, "embedded"
 * mode): it asks to go to pages, says when a sheet is open, and passes on
 * the settings it still owns (Appearance, Language) until they're native.
 */
@CapacitorPlugin(name = "NativeNav")
class NativeNavPlugin : Plugin() {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun preferences(): UserPreferencesRepository
    }

    private val scope = MainScope()

    private val preferences: UserPreferencesRepository by lazy {
        EntryPointAccessors.fromApplication(context, Dependencies::class.java).preferences()
    }

    private val host: WebPageFragment? get() = bridge.fragment as? WebPageFragment

    /** Sends an event to the web app; kept until it listens, so nothing is lost while it starts. */
    fun emit(event: String, data: JSObject) = notifyListeners(event, data, true)

    /** { nativePages, appearance, language }: which pages are native, and the settings the web app should follow. */
    @PluginMethod
    fun config(call: PluginCall) {
        scope.launch {
            val appearance = preferences.preferences.first().appearance
            call.resolve(
                JSObject()
                    .put("nativePages", JSArray(NATIVE_PAGES))
                    .put("appearance", appearance.key)
                    .put("language", AppCompatDelegate.getApplicationLocales().toLanguageTags()),
            )
        }
    }

    /** { path, replace? }: the web app went to a page. */
    @PluginMethod
    fun go(call: PluginCall) {
        val path = call.getString("path") ?: return call.reject("path is required")
        val replace = call.getBoolean("replace") ?: false
        activity.runOnUiThread { host?.onRequest?.invoke(WebRequest.Go(path, replace)) }
        call.resolve()
    }

    /** { open }: a sheet or dialog opened or closed. */
    @PluginMethod
    fun overlay(call: PluginCall) {
        val open = call.getBoolean("open") ?: false
        activity.runOnUiThread { host?.onRequest?.invoke(WebRequest.Overlay(open)) }
        call.resolve()
    }

    /** { appearance: auto | light | dark }, chosen in the web app's Settings. */
    @PluginMethod
    fun setAppearance(call: PluginCall) {
        val appearance = Appearance.fromKey(call.getString("appearance"))
        scope.launch {
            preferences.setAppearance(appearance)
            call.resolve()
        }
    }

    /** { language: ja | en | '' (the phone's) }, chosen in the web app's Settings: the app's per-app language. */
    @PluginMethod
    fun setLanguage(call: PluginCall) {
        val tag = call.getString("language").orEmpty()
        activity.runOnUiThread {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
            call.resolve()
        }
    }

    override fun handleOnDestroy() {
        scope.cancel()
        super.handleOnDestroy()
    }

    companion object {
        /** The website's pages the app draws natively. The web app hands these to native code. */
        val NATIVE_PAGES = listOf("home")
    }
}
