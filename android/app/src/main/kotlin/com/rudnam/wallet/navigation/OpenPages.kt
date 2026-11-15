package com.rudnam.wallet.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.LifecycleOwner
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState

/**
 * Whether a page is still open, for core/ui's LocalPageIsOpen: a page being closed (back) has
 * left the back stack, one only covered by another hasn't. Asked again whenever the page on
 * top changes. (The navigation's back stack itself isn't public API.)
 */
@Composable
fun rememberPageIsOpen(navController: NavController): (LifecycleOwner) -> Boolean {
    val current by navController.currentBackStackEntryAsState()
    return remember(current) { { owner -> navController.isOpen(owner) } }
}

/**
 * Whether a page is the one on top now, for core/ui's LocalPageIsOnTop. Going back while a
 * page is still opening, Navigation plays the opening backwards rather than a closing, so the
 * transition alone would still say it's on its way to the page being left.
 */
@Composable
fun rememberPageIsOnTop(navController: NavController): (LifecycleOwner) -> Boolean {
    val current by navController.currentBackStackEntryAsState()
    return remember(current) { { owner -> owner === current } }
}

private fun NavController.isOpen(owner: LifecycleOwner): Boolean {
    val entry = owner as? NavBackStackEntry ?: return true
    if (entry === currentBackStackEntry || entry === previousBackStackEntry) return true
    // Deeper down: the topmost entry for its destination is it.
    return try {
        getBackStackEntry(entry.destination.id) === entry
    } catch (e: IllegalArgumentException) {
        false // not in the back stack at all
    }
}
