package com.rudnam.wallet.navigation

import androidx.annotation.StringRes
import com.rudnam.wallet.R
import com.rudnam.wallet.core.designsystem.icon.WalletIcons
import com.rudnam.wallet.feature.activity.navigation.ActivityRoute
import com.rudnam.wallet.feature.insights.navigation.InsightsRoute
import com.rudnam.wallet.feature.settings.navigation.SettingsRoute
import com.rudnam.wallet.feature.wallet.navigation.WalletRoute

/** The four tabs (docs/design). [page]: the website's page each one is. */
enum class TopLevelDestination(@StringRes val label: Int, val icon: String, val page: String, val route: Any) {
    Wallet(R.string.tab_wallet, WalletIcons.wallet, "home", WalletRoute),
    Activity(R.string.tab_activity, WalletIcons.records, "records", ActivityRoute()),
    Insights(R.string.tab_insights, WalletIcons.stats, "stats", InsightsRoute()),
    Settings(R.string.tab_settings, WalletIcons.settings, "settings", SettingsRoute),
    ;
}
