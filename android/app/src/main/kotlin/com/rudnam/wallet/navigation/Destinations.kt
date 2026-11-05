package com.rudnam.wallet.navigation

import androidx.annotation.StringRes
import com.rudnam.wallet.R
import com.rudnam.wallet.core.designsystem.icon.WalletIcons
import com.rudnam.wallet.feature.activity.navigation.ActivityRoute
import com.rudnam.wallet.feature.insights.navigation.InsightsRoute
import com.rudnam.wallet.feature.settings.navigation.SettingsRoute
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

/** The four tabs (docs/design). [page]: the website's page each one is. */
enum class TopLevelDestination(@StringRes val label: Int, val icon: String, val page: String, val route: Any) {
    Wallet(R.string.tab_wallet, WalletIcons.wallet, "home", WalletRoute),
    Activity(R.string.tab_activity, WalletIcons.records, "records", ActivityRoute()),
    Insights(R.string.tab_insights, WalletIcons.stats, "stats", InsightsRoute()),
    Settings(R.string.tab_settings, WalletIcons.settings, "settings", SettingsRoute),
    ;
}
