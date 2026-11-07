package com.rudnam.wallet.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.compose.runtime.collectAsState
import androidx.navigation.compose.rememberNavController
import com.rudnam.wallet.feature.account.navigation.AccountRoute
import com.rudnam.wallet.feature.activity.navigation.ActivityRoute
import com.rudnam.wallet.feature.imports.navigation.CardRoute
import com.rudnam.wallet.feature.imports.navigation.ImportRoute
import com.rudnam.wallet.feature.insights.navigation.BudgetsRoute
import com.rudnam.wallet.feature.signin.navigation.SignInRoute
import com.rudnam.wallet.feature.insights.navigation.InsightsRoute
import com.rudnam.wallet.feature.settings.navigation.SettingsSectionRoute
import com.rudnam.wallet.feature.record.navigation.RecordRoute
import com.rudnam.wallet.feature.wallet.navigation.WalletRoute
import com.rudnam.wallet.navigation.TopLevelDestination

/** The app's navigation, in one place the app's UI reads. */
@Stable
class RudWalletAppState(val navController: NavHostController) {
    /** The page on screen: the top entry that isn't a dialog (a sheet over a page leaves the page showing). */
    private val page: NavBackStackEntry?
        @Composable get() = navController.visibleEntries.collectAsState().value.lastOrNull { it.destination.navigatorName != "dialog" }

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
     * Opens a page by the website's path for it (the widgets' links, and
     * pages that link to each other): "home", "records?account=3",
     * "settings?section=rules". A page the app hasn't got opens Wallet.
     */
    fun openPath(path: String) {
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
        // Insights with a period or an account chosen, and the budgets, opened from it.
        if (page == "stats" && path != page) {
            navController.navigate(InsightsRoute.fromPath(path))
            return
        }
        if (page == "budgets") {
            navController.navigate(BudgetsRoute)
            return
        }
        // Importing records, and a transit IC card's page.
        if (page == "settings" && PathParams.get(path, "section") == "import") {
            navController.navigate(ImportRoute)
            return
        }
        if (page == "card") {
            // A card tapped while its page is open shows there.
            if (navController.currentBackStackEntry?.destination?.hasRoute<CardRoute>() != true) navController.navigate(CardRoute)
            return
        }
        // A settings page.
        if (page == "settings") {
            val section = PathParams.get(path, "section")
            if (section in SettingsSectionRoute.NATIVE) {
                navController.navigate(SettingsSectionRoute(section!!))
                return
            }
        }
        // Another tab's own page, as the tab itself shows it.
        TopLevelDestination.entries.firstOrNull { it.page == page }?.let {
            navigateToTab(it)
            return
        }
        // Nothing the app has (a link from an older build, say): Wallet.
        openPath("home")
    }

    /** The sign-in page is up (signed out). */
    val onSignIn: Boolean
        get() = navController.currentBackStackEntry?.destination?.hasRoute<SignInRoute>() == true

    /** Leaving a demo for an account of one's own: the next sign-in page opens on creating one. */
    var signUpNext: Boolean = false

    /** Only the sign-in page; [deleted]: the account was just deleted, and it says so. */
    fun showSignIn(deleted: Boolean = false) {
        val mode = if (signUpNext) "sign-up" else "sign-in"
        signUpNext = false
        navController.navigate(SignInRoute(mode, deleted)) { popUpTo(0) { inclusive = true } }
    }

    fun showWallet() {
        navController.navigate(WalletRoute) { popUpTo(0) { inclusive = true } }
    }
}

/** Reading a website path's parameters. */
internal object PathParams {
    fun id(path: String): Long? = get(path, "id")?.toLongOrNull()

    fun get(path: String, name: String): String? =
        path.substringAfter('?', "").split('&').firstOrNull { it.startsWith("$name=") }?.substringAfter('=')
}

@Composable
fun rememberRudWalletAppState(navController: NavHostController = rememberNavController()) =
    remember(navController) { RudWalletAppState(navController) }
