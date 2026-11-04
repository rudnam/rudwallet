package com.rudnam.wallet.screens

import android.content.Context
import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import com.getcapacitor.BridgeActivity
import com.getcapacitor.JSObject
import com.rudnam.wallet.SplashPlugin
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.util.Date
import java.util.Locale

/**
 * Native screens drawn over the web view, one page at a time while the app
 * moves from web to native. The web app stays in charge of where the user
 * is: it calls show() when it reaches a page that has a native version
 * (only Wallet so far, and only when turned on in Settings) and hide() when it
 * leaves it (web/src/native.ts, App.tsx). Taps on what isn't native yet go
 * back to the web app as "open" events (NativeScreensPlugin).
 *
 * The web view keeps running underneath, so going back to a web page is
 * instant, and Android's back gesture keeps moving through the web app's
 * history as before.
 */
class NativeOverlay private constructor(private val activity: BridgeActivity) {
    private val view = ComposeView(activity)
    private val api = WalletApi(activity)

    /** Bumped on every show(), so Wallet reloads what may have changed meanwhile (a record added, say). */
    private var shown by mutableIntStateOf(0)
    private var locale by mutableStateOf(savedLocale(activity))
    /** The web app's Settings › Appearance: true dark, false light, null following the phone. */
    private var dark by mutableStateOf(savedDark(activity))

    init {
        view.setContent { Content() }
        activity.addContentView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // At launch, Wallet is up at once if it's on and someone is signed in;
        // the web app hides it again if it opens elsewhere.
        if (homeEnabled(activity) && api.signedIn) show(null, null) else hide()
    }

    /**
     * Shows Wallet, in the web app's locale (a BCP 47 tag like ja-JP) and
     * appearance (auto, light or dark); null keeps the last one, which is
     * also what the next launch starts with.
     */
    fun show(localeTag: String?, appearance: String?) {
        val edit = prefs(activity).edit()
        if (localeTag != null) {
            locale = Locale.forLanguageTag(localeTag)
            edit.putString(LOCALE, localeTag)
        }
        if (appearance != null) {
            dark = darkFor(appearance)
            edit.putString(APPEARANCE, appearance)
        }
        edit.apply()
        shown++
        view.visibility = View.VISIBLE
        // TalkBack reads the native screen, not the web page under it.
        activity.bridge?.webView?.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    fun hide() {
        view.visibility = View.GONE
        activity.bridge?.webView?.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
    }

    private fun send(event: String, data: JSObject) {
        (activity.bridge?.getPlugin("NativeScreens")?.instance as? NativeScreensPlugin)?.emit(event, data)
    }

    private val actions = object : WalletActions {
        override fun open(page: String, params: Map<String, String>) {
            val p = JSObject()
            for ((k, v) in params) p.put(k, v)
            send("open", JSObject().put("page", page).put("params", p))
        }

        override fun openRecord(t: Transaction) {
            send("open", JSObject().put("record", JSObject(t.json.toString())))
        }

        override fun add() {
            send("open", JSObject().put("page", "add"))
        }

        override fun refresh() {
            refreshes++
        }
    }

    /** Bumped by pulling down to refresh. */
    private var refreshes by mutableIntStateOf(0)

    @Composable
    private fun Content() {
        val month = remember { Format.monthStart(Date()) }
        var state by remember { mutableStateOf<WalletState>(api.cachedHome(month)?.let { WalletState.Ready(it) } ?: WalletState.Loading) }

        suspend fun load(pulled: Boolean) {
            val before = state
            if (pulled && before is WalletState.Ready) state = before.copy(refreshing = true)
            state = try {
                WalletState.Ready(api.home(month, locale.toLanguageTag()))
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                // Offline, or the server is down: keep what's on screen.
                when (val now = state) {
                    is WalletState.Ready -> now.copy(offline = true, refreshing = false)
                    else -> WalletState.Failed
                }
            } catch (e: org.json.JSONException) {
                (state as? WalletState.Ready)?.copy(refreshing = false) ?: WalletState.Failed
            }
        }

        LaunchedEffect(shown) { if (shown > 0) load(false) }
        LaunchedEffect(refreshes) { if (refreshes > 0) load(true) }
        LaunchedEffect(state is WalletState.Ready) { if (state is WalletState.Ready) SplashPlugin.drawn = true }

        Localized(locale) {
            WalletTheme(dark) {
                val ready = state as? WalletState.Ready
                val format = ready?.let { Format(locale, it.data.overview.settings) }
                WalletScreen(state, format, actions)
            }
        }
    }

    companion object {
        private const val PREFS = "native-screens"
        private const val HOME = "home"
        private const val LOCALE = "locale"
        private const val APPEARANCE = "appearance"

        /** The overlay of the activity running now, for NativeScreensPlugin. */
        @Volatile
        @JvmStatic
        var current: NativeOverlay? = null
            private set

        /** Call from MainActivity.onCreate, after super.onCreate has set up the web view. */
        @JvmStatic
        fun attach(activity: BridgeActivity): NativeOverlay = NativeOverlay(activity).also { current = it }

        private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        /** Whether the user turned the native Wallet on (Settings, in the web app). Off by default. */
        @JvmStatic
        fun homeEnabled(c: Context): Boolean = prefs(c).getBoolean(HOME, false)

        @JvmStatic
        fun setHomeEnabled(c: Context, on: Boolean) {
            prefs(c).edit().putBoolean(HOME, on).apply()
        }

        private fun darkFor(appearance: String?): Boolean? = when (appearance) {
            "dark" -> true
            "light" -> false
            else -> null
        }

        private fun savedDark(c: Context): Boolean? = darkFor(prefs(c).getString(APPEARANCE, null))

        private fun savedLocale(c: Context): Locale =
            prefs(c).getString(LOCALE, null)?.let(Locale::forLanguageTag) ?: Locale.getDefault()
    }
}

/** Draws content in the web app's language rather than the phone's, as the web app may differ from it. */
@Composable
fun Localized(locale: Locale, content: @Composable () -> Unit) {
    val base = LocalContext.current
    val parent = LocalConfiguration.current
    val config = remember(locale, parent) { Configuration(parent).apply { setLocale(locale) } }
    val context = remember(config, base) { base.createConfigurationContext(config) }
    CompositionLocalProvider(
        LocalConfiguration provides config,
        LocalContext provides context,
        LocalResources provides context.resources,
        content = content,
    )
}
