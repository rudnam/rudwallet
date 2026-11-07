package com.rudnam.wallet.feature.wallet.navigation

import androidx.compose.material3.SnackbarHostState
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.rudnam.wallet.feature.wallet.WalletNavigation
import com.rudnam.wallet.feature.wallet.WalletDestination
import com.rudnam.wallet.core.ui.SharedPage
import kotlinx.serialization.Serializable

/** Wallet, the app's start: the website's #/home. */
@Serializable
data object WalletRoute

fun NavGraphBuilder.walletScreen(navigation: WalletNavigation, snackbar: SnackbarHostState) {
    composable<WalletRoute> {
        SharedPage { WalletDestination(navigation, snackbar) }
    }
}
