package com.rudnam.wallet.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.compose.runtime.collectAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.rudnam.wallet.feature.account.navigation.AccountRoute
import com.rudnam.wallet.feature.activity.navigation.ActivityRoute
import com.rudnam.wallet.feature.record.navigation.RecordRoute
import com.rudnam.wallet.feature.wallet.navigation.WalletRoute
import com.rudnam.wallet.navigation.TopLevelDestination
import com.rudnam.wallet.navigation.WebRoute
import com.rudnam.wallet.web.WebPageFragment

/** The app's navigation and the web view's state, in one place the app's UI reads. */
@Stable
class RudWalletAppState(val navController: NavHostController) {
    /** The web view, once it's made. */
    var web: WebPageFragment? by mutableStateOf(null)

    /** A sheet or dialog is open in the web view: back closes it rather than leaving the page. */
    var webOverlayOpen: Boolean by mutableStateOf(false)

    /** The page on screen: the top entry that isn't a dialog (a sheet over a page leaves the page showing). */
    private val page: NavBackStackEntry?
        @Composable get() = navController.visibleEntries.collectAsState().value.lastOrNull { it.destination.navigatorName != "dialog" }

    /** The web page shown now, or null on a native screen. */
    val webPath: String?
        @Composable get() = page?.let(::webPathOf)

    /** The tab whose page is shown now: the tabs show only there. A page under a tab has its own way back. */
    val currentTab: TopLevelDestination?
        @Composable get() {
            val destination = page?.destination ?: return null
            return TopLevelDestination.entries.firstOrNull { destination.hasRoute(it.route::class) }
        }

    fun navigateToTab(tab: TopLevelDestination) {
        navController.navigate(tab.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    /**
     * Opens a page by its website path: natively if it's native (Wallet,
     * for "home"), else in the web view. [replace]: instead of the page
     * shown now (a redirect, say), not on top of it.
     */
    fun openPath(path: String, replace: Boolean = false) {
        val page = path.substringBefore('?').ifEmpty { "home" }
        // The record sheet, over the page shown now.
        if (page == "add") {
            navController.navigate(RecordRoute())
            return
        }
        // Home is Wallet, where the app starts: back to it, whatever was opened over it.
        if (page == "home") {
            if (!navController.popBackStack(WalletRoute, inclusive = false)) showWallet()
            return
        }
        // An account, opened from its card.
        if (page == "account") {
            PathParams.id(path)?.let { navController.navigate(AccountRoute(it)) }
            return
        }
        // Activity (records, and review, which opens the same page), with its filters.
        if (page == "records" || page == "review") {
            if (path == page) navigateToTab(TopLevelDestination.Activity) else navController.navigate(ActivityRoute.fromPath(path))
            return
        }
        // Another tab's own page, as the tab itself shows it.
        TopLevelDestination.entries.firstOrNull { it.page == path }?.let {
            navigateToTab(it)
            return
        }
        val current = navController.currentBackStackEntry ?: return
        if (current.destination.hasRoute<WebRoute>() && current.toRoute<WebRoute>().path == path) return
        navController.navigate(WebRoute(path)) {
            if (replace) popUpTo(current.destination.id) { inclusive = true }
        }
    }

    /** The sign-in page is up (signed out). */
    val onSignIn: Boolean
        get() = navController.currentBackStackEntry?.let { it.destination.hasRoute<WebRoute>() && it.toRoute<WebRoute>().page == "signin" } == true

    fun showSignIn() {
        navController.navigate(WebRoute("signin")) { popUpTo(0) { inclusive = true } }
    }

    fun showWallet() {
        navController.navigate(WalletRoute) { popUpTo(0) { inclusive = true } }
    }
}

/** Reading a website path's parameters. */
internal object PathParams {
    fun id(path: String): Long? = path.substringAfter('?', "").split('&').firstOrNull { it.startsWith("id=") }?.substringAfter('=')?.toLongOrNull()
}

/** The web path a back stack entry shows: a web page's, or a web tab's page; null for a native screen. */
internal fun webPathOf(entry: NavBackStackEntry): String? {
    val destination = entry.destination
    if (destination.hasRoute<WebRoute>()) return entry.toRoute<WebRoute>().path
    // The tabs still on the web.
    return TopLevelDestination.entries.firstOrNull { it.web && destination.hasRoute(it.route::class) }?.page
}

@Composable
fun rememberRudWalletAppState(navController: NavHostController = rememberNavController()) =
    remember(navController) { RudWalletAppState(navController) }
