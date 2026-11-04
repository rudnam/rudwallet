package com.rudnam.wallet.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.core.view.isInvisible
import androidx.fragment.compose.AndroidFragment
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.rudnam.wallet.R
import com.rudnam.wallet.core.designsystem.icon.LineIcon
import com.rudnam.wallet.core.designsystem.theme.WalletTheme
import com.rudnam.wallet.core.model.Transaction
import com.rudnam.wallet.feature.wallet.WalletNavigation
import com.rudnam.wallet.feature.wallet.navigation.WalletRoute
import com.rudnam.wallet.feature.wallet.navigation.walletScreen
import com.rudnam.wallet.navigation.TopLevelDestination
import com.rudnam.wallet.navigation.ActivityTabRoute
import com.rudnam.wallet.navigation.InsightsTabRoute
import com.rudnam.wallet.navigation.SettingsTabRoute
import com.rudnam.wallet.navigation.WebRoute
import com.rudnam.wallet.web.WebPageFragment
import com.rudnam.wallet.web.WebRequest
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.ZoneId

/**
 * The app: the four tabs along the bottom, and the pages above them. Native
 * pages are destinations of the NavHost; the rest are [WebRoute]s, shown by
 * the one web view underneath, which shows through their empty destination.
 */
@Composable
fun RudWalletApp(
    signedIn: Boolean,
    reviewCount: Int,
    links: Flow<String>,
    appState: RudWalletAppState = rememberRudWalletAppState(),
) {
    val snackbar = remember { SnackbarHostState() }
    val tab = appState.currentTab
    val webPath = appState.webPath

    // Signing in and out happens in the web view for now; the app follows the session.
    LaunchedEffect(signedIn) {
        if (!signedIn) appState.showSignIn() else if (appState.onSignIn) appState.showWallet()
    }
    // Links into the app (the widgets: <package>://open/<page>).
    LaunchedEffect(links) { links.collect { appState.openPath(it) } }

    Scaffold(
        containerColor = WalletTheme.colors.ground,
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = { if (tab != null) TabBar(tab, reviewCount, appState::navigateToTab) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            WebHost(appState, signedIn, visible = webPath != null, path = webPath)
            NavHost(appState.navController, startDestination = WalletRoute) {
                walletScreen(
                    navigation = object : WalletNavigation {
                        override fun openPage(path: String) = appState.openPath(path)
                        override fun openRecord(record: Transaction) = appState.openPath(recordPath(record))
                        override fun addRecord() = appState.openPath("add")
                    },
                    snackbar = snackbar,
                )
                // Web pages and the tabs still on the web: the web view underneath shows them (WebHost).
                composable<WebRoute> { }
                composable<ActivityTabRoute> { }
                composable<InsightsTabRoute> { }
                composable<SettingsTabRoute> { }
            }
        }
    }

    // A sheet or dialog in the web view closes with back before its page does.
    BackHandler(enabled = webPath != null && appState.webOverlayOpen) { appState.web?.back() }
}

/** The page that opens a record in the web app's form, until records are native (web/src/App.tsx, #/record). */
private fun recordPath(record: Transaction): String {
    val day = record.occurredAt.atZone(ZoneId.systemDefault()).toLocalDate()
    val from = day.atStartOfDay(ZoneId.systemDefault()).toInstant()
    val to = day.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant()
    return "record?id=${record.id}&from=${Instant.from(from)}&to=${Instant.from(to)}"
}

@Composable
private fun TabBar(current: TopLevelDestination, reviewCount: Int, onSelect: (TopLevelDestination) -> Unit) {
    NavigationBar(containerColor = WalletTheme.colors.ground) {
        for (tab in TopLevelDestination.entries) {
            val selected = tab == current
            val label = stringResource(tab.label)
            NavigationBarItem(
                selected = selected,
                onClick = { if (!selected) onSelect(tab) },
                icon = {
                    if (tab == TopLevelDestination.Activity && reviewCount > 0) {
                        val badge = pluralStringResource(R.plurals.to_review, reviewCount, reviewCount)
                        BadgedBox(badge = {
                            Badge(Modifier.semantics { contentDescription = badge }, containerColor = WalletTheme.colors.attention, contentColor = WalletTheme.colors.ground) {
                                Text(reviewCount.toString())
                            }
                        }) { LineIcon(tab.icon, contentDescription = null) }
                    } else {
                        LineIcon(tab.icon, contentDescription = null)
                    }
                },
                label = { Text(label, style = MaterialTheme.typography.labelMedium) },
            )
        }
    }
}

/**
 * The web view, made once and kept: shown for web pages, hidden (and out of
 * reach of touch and TalkBack) on native ones. It lies in the space the
 * app's layout leaves it, clear of the system bars and the keyboard.
 */
@Composable
private fun WebHost(appState: RudWalletAppState, signedIn: Boolean, visible: Boolean, path: String?) {
    // Signed out, the web app may still ask for pages as it leaves them (Wallet, after signing out): the sign-in page stays.
    val session by rememberUpdatedState(signedIn)
    AndroidFragment<WebPageFragment>(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding(),
        onUpdate = { fragment ->
            appState.web = fragment
            fragment.onRequest = { request ->
                when (request) {
                    is WebRequest.Go -> if (session) appState.openPath(request.path, request.replace)
                    WebRequest.Back -> appState.navController.popBackStack()
                    is WebRequest.Overlay -> appState.webOverlayOpen = request.open
                }
            }
        },
    )
    val web = appState.web
    LaunchedEffect(web, visible) { web?.view?.isInvisible = !visible }
    LaunchedEffect(web, path) { if (web != null && path != null) web.show(path) }
}
