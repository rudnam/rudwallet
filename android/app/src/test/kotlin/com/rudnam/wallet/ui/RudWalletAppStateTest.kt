package com.rudnam.wallet.ui

import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.testing.TestNavHostController
import com.rudnam.wallet.feature.wallet.navigation.WalletRoute
import com.rudnam.wallet.navigation.TopLevelDestination
import com.rudnam.wallet.navigation.ActivityTabRoute
import com.rudnam.wallet.navigation.InsightsTabRoute
import com.rudnam.wallet.navigation.SettingsTabRoute
import com.rudnam.wallet.navigation.WebRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** How the app moves between native pages and web ones, and which tab shows. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RudWalletAppStateTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var state: RudWalletAppState
    private var tab: TopLevelDestination? = null
    private var webPath: String? = null

    @Before
    fun setUp() {
        compose.setContent {
            val context = LocalContext.current
            val nav = remember { TestNavHostController(context).apply { navigatorProvider.addNavigator(ComposeNavigator()) } }
            state = rememberRudWalletAppState(nav)
            NavHost(nav, startDestination = WalletRoute) {
                composable<WalletRoute> {}
                composable<WebRoute> {}
                composable<ActivityTabRoute> {}
                composable<InsightsTabRoute> {}
                composable<SettingsTabRoute> {}
            }
            tab = state.currentTab
            webPath = state.webPath
        }
    }

    private fun currentWebPath(): String? = state.navController.currentBackStackEntry?.let(::webPathOf)

    @Test
    fun `a web page opens over Wallet, and home goes back to it`() {
        compose.runOnIdle { state.openPath("account?id=3") }
        compose.runOnIdle {
            assertEquals("account?id=3", webPath)
            assertNull(tab) // a page under a tab has its own way back
            state.openPath("home")
        }
        compose.runOnIdle {
            assertNull(webPath)
            assertEquals(TopLevelDestination.Wallet, tab)
            assertFalse(state.navController.popBackStack()) // Wallet is the start: back leaves the app
        }
    }

    @Test
    fun `tabs replace each other, and a redirect replaces its page`() {
        compose.runOnIdle { state.navigateToTab(TopLevelDestination.Insights) }
        compose.runOnIdle {
            assertEquals(TopLevelDestination.Insights, tab)
            state.navigateToTab(TopLevelDestination.Settings)
        }
        compose.runOnIdle {
            assertEquals("settings", currentWebPath())
            state.openPath("settings?section=accounts")
        }
        compose.runOnIdle {
            assertNull(tab)
            state.openPath("settings?section=import", replace = true)
        }
        compose.runOnIdle {
            assertTrue(state.navController.popBackStack())
            assertEquals("settings", currentWebPath()) // the replaced page isn't in between
        }
    }

    @Test
    fun `pages open one after another, each its own`() {
        compose.runOnIdle { state.openPath("account?id=3") }
        compose.runOnIdle { state.openPath("budgets") }
        compose.runOnIdle { state.openPath("records?account=3") }
        compose.runOnIdle {
            assertEquals("records?account=3", currentWebPath())
            assertTrue(state.navController.popBackStack())
            assertEquals("budgets", currentWebPath())
        }
    }

    @Test
    fun `signing out leaves only the sign-in page`() {
        compose.runOnIdle { state.openPath("records") }
        compose.runOnIdle { state.showSignIn() }
        compose.runOnIdle {
            assertTrue(state.onSignIn)
            assertNull(tab)
            assertFalse(state.navController.popBackStack())
        }
    }
}
