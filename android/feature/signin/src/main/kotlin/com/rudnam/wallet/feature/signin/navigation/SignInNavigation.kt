package com.rudnam.wallet.feature.signin.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.rudnam.wallet.feature.signin.SignInDestination
import kotlinx.serialization.Serializable

/**
 * Signing in, the app's first page when nobody is: the website's #/signin.
 * [mode] is sign-in or sign-up (leaving a demo for an account of one's own);
 * [deleted]: the account was just deleted, and it says so.
 */
@Serializable
data class SignInRoute(val mode: String = "sign-in", val deleted: Boolean = false)

fun NavGraphBuilder.signInScreen() {
    composable<SignInRoute> { SignInDestination() }
}
