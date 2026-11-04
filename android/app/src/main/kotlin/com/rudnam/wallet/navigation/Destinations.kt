package com.rudnam.wallet.navigation

import androidx.annotation.StringRes
import com.rudnam.wallet.R
import com.rudnam.wallet.core.designsystem.icon.WalletIcons
import com.rudnam.wallet.feature.wallet.navigation.WalletRoute
import kotlinx.serialization.Serializable

/**
 * A page the app doesn't draw natively yet, shown by the website's code in
 * the web view (WebHost), by its path there: "records", "account?id=3",
 * "settings?section=notifications". Each page that becomes native gets its
 * own route instead, and its paths stop coming here (see [openPath]).
 */
@Serializable
data class WebRoute(val path: String) {
    /** The page's name: "settings" for "settings?section=notifications". */
    val page: String get() = path.substringBefore('?')
}

// The tabs that are still web pages, each a destination of its own (so
// moving between them is a change of destination, and each keeps its own
// back stack); each becomes its feature's route as it turns native.
@Serializable data object ActivityTabRoute

@Serializable data object InsightsTabRoute

@Serializable data object SettingsTabRoute

/** The four tabs (docs/design). [page]: the website's page each one is. */
enum class TopLevelDestination(@StringRes val label: Int, val icon: String, val page: String, val route: Any) {
    Wallet(R.string.tab_wallet, WalletIcons.wallet, "home", WalletRoute),
    Activity(R.string.tab_activity, WalletIcons.records, "records", ActivityTabRoute),
    Insights(R.string.tab_insights, WalletIcons.stats, "stats", InsightsTabRoute),
    Settings(R.string.tab_settings, WalletIcons.settings, "settings", SettingsTabRoute),
    ;
}
