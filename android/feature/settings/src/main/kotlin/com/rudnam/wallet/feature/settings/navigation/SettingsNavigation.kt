package com.rudnam.wallet.feature.settings.navigation

import androidx.compose.material3.SnackbarHostState
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.rudnam.wallet.feature.settings.AccountsDestination
import com.rudnam.wallet.feature.settings.AppearanceDestination
import com.rudnam.wallet.feature.settings.CategoriesDestination
import com.rudnam.wallet.feature.settings.CurrencyDestination
import com.rudnam.wallet.feature.settings.LanguageDestination
import com.rudnam.wallet.feature.settings.SettingsDestination
import com.rudnam.wallet.feature.settings.SettingsNavigation
import kotlinx.serialization.Serializable

/** Settings, the fourth tab: the website's #/settings. */
@Serializable data object SettingsRoute

/**
 * A settings page drawn natively, by the website's ?section=…: accounts,
 * categories, general (the currency), appearance, language. The others
 * are still web pages.
 */
@Serializable
data class SettingsSectionRoute(val section: String) {
    companion object {
        val NATIVE = setOf("accounts", "categories", "general", "appearance", "language")
    }
}

fun NavGraphBuilder.settingsScreen(navigation: SettingsNavigation, snackbar: SnackbarHostState) {
    composable<SettingsRoute> { SettingsDestination(navigation, snackbar) }
    composable<SettingsSectionRoute> { entry ->
        when (entry.arguments?.getString("section")) {
            "accounts" -> AccountsDestination(navigation, snackbar)
            "categories" -> CategoriesDestination(navigation, snackbar)
            "general" -> CurrencyDestination(navigation, snackbar)
            "appearance" -> AppearanceDestination(navigation)
            "language" -> LanguageDestination(navigation)
        }
    }
}
