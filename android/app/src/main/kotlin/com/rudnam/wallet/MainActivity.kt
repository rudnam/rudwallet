package com.rudnam.wallet

import android.content.Intent
import android.graphics.Color
import android.nfc.NfcAdapter
import android.nfc.Tag
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.rudnam.wallet.core.data.ic.IcCardException
import com.rudnam.wallet.core.data.ic.IcCardReader
import com.rudnam.wallet.core.data.ic.IcCardTap
import com.rudnam.wallet.core.data.ic.IcCards
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.compose.setContent
import com.rudnam.wallet.core.datastore.Appearance
import com.rudnam.wallet.core.designsystem.theme.WalletTheme
import com.rudnam.wallet.ui.RudWalletApp
import com.rudnam.wallet.web.WebPageFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * The app's one activity. It draws edge to edge, keeps the splash screen
 * until there's something to show, and routes what opens the app: pages
 * from the widgets and transit IC cards tapped to the phone to the app's
 * navigation, and Google sign-in coming back to the web view, which still
 * handles that.
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    private val viewModel: MainActivityViewModel by viewModels()

    @Inject lateinit var icCards: IcCards

    /** Pages asked for by links (the widgets), for the app's navigation. */
    private val links = Channel<String>(Channel.BUFFERED)

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        val start = SystemClock.uptimeMillis()
        // Until the session is known; signed out, until the sign-in page has drawn. Never more than 3 s.
        splash.setKeepOnScreenCondition {
            val waiting = when (val s = viewModel.uiState.value) {
                MainUiState.Loading -> true
                is MainUiState.Ready -> !s.signedIn && !SplashPlugin.drawn
            }
            waiting && SystemClock.uptimeMillis() - start < 3_000
        }
        enableEdgeToEdge()
        if (savedInstanceState == null) route(intent)

        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            val ready = state as? MainUiState.Ready ?: return@setContent
            val dark = when (ready.appearance) {
                Appearance.System -> isSystemInDarkTheme()
                Appearance.Light -> false
                Appearance.Dark -> true
            }
            // The status and navigation bars' icons follow the app's appearance, not only the phone's.
            DisposableEffect(dark) {
                val style = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            val reviewCount by viewModel.reviewCount.collectAsStateWithLifecycle()
            WalletTheme(darkTheme = dark) {
                val settings by viewModel.settings.collectAsStateWithLifecycle()
                RudWalletApp(
                    signedIn = ready.signedIn,
                    reviewCount = reviewCount,
                    links = links.receiveAsFlow(),
                    recordEvents = viewModel.recordEvents,
                    settings = settings,
                    onUndo = viewModel::undo,
                    appearance = ready.appearance.key,
                    signOuts = viewModel.signOuts,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        route(intent)
    }

    /**
     * <package>://open/<page> (the widgets) opens that page; a transit IC
     * card tapped to the phone is read at once, while it's still there, and
     * opens the card page; the web view handles <package>://auth (Google
     * sign-in) itself. At launch it reads them from the activity's own intent.
     */
    private fun route(intent: Intent) {
        val data = intent.data
        if (data?.scheme == packageName && data.host == "open") {
            links.trySend(data.path.orEmpty().trimStart('/') + (data.query?.let { "?$it" } ?: ""))
            return
        }
        if (intent.action == NfcAdapter.ACTION_TECH_DISCOVERED) {
            val tag = IntentCompat.getParcelableExtra(intent, NfcAdapter.EXTRA_TAG, Tag::class.java)
            intent.action = Intent.ACTION_MAIN // handled: not again if the activity is remade
            if (tag != null) {
                lifecycleScope.launch {
                    val tap = withContext(Dispatchers.IO) {
                        try {
                            IcCardTap.Read(IcCardReader.read(tag))
                        } catch (e: IcCardException) {
                            IcCardTap.Failed(e.problem)
                        }
                    }
                    icCards.tap(tap)
                    links.trySend("card")
                }
            }
            return
        }
        if (data?.scheme == packageName) {
            supportFragmentManager.fragments.filterIsInstance<WebPageFragment>().firstOrNull()?.onNewIntent(intent)
        }
    }
}
