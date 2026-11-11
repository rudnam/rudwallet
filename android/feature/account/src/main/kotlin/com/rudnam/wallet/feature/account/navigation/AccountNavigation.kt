package com.rudnam.wallet.feature.account.navigation

import androidx.compose.material3.SnackbarHostState
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.rudnam.wallet.core.ui.Page
import com.rudnam.wallet.feature.account.AccountDestination
import com.rudnam.wallet.feature.account.AccountNavigation
import kotlinx.serialization.Serializable

/** One account, the website's #/account?id=…, opened from its card on Wallet. */
@Serializable
data class AccountRoute(val id: Long)

fun NavGraphBuilder.accountScreen(navigation: AccountNavigation, snackbar: SnackbarHostState) {
    composable<AccountRoute> { Page { AccountDestination(navigation, snackbar) } }
}
