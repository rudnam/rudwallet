package com.rudnam.wallet.ui

import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.testing.TestNavHostController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.toRoute
import com.rudnam.wallet.feature.wallet.navigation.WalletRoute
import com.rudnam.wallet.navigation.TopLevelDestination
import com.rudnam.wallet.feature.account.navigation.AccountRoute
import com.rudnam.wallet.feature.activity.navigation.ActivityRoute
import com.rudnam.wallet.feature.imports.navigation.CardRoute
import com.rudnam.wallet.feature.imports.navigation.ImportRoute
import com.rudnam.wallet.feature.insights.navigation.BudgetsRoute
import com.rudnam.wallet.feature.signin.navigation.SignInRoute
import com.rudnam.wallet.feature.insights.navigation.InsightsRoute
import com.rudnam.wallet.feature.settings.navigation.SettingsRoute
import com.rudnam.wallet.feature.settings.navigation.SettingsSectionRoute
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

/** How the app moves between its pages, and which tab shows. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RudWalletAppStateTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var state: RudWalletAppState
    private var tab: TopLevelDestination? = null

    @Before
    fun setUp() {
        compose.setContent {
            val context = LocalContext.current
            val nav = remember { TestNavHostController(context).apply { navigatorProvider.addNavigator(ComposeNavigator()) } }
            state = rememberRudWalletAppState(nav)
            NavHost(nav, startDestination = WalletRoute) {
                composable<WalletRoute> {}
                composable<ActivityRoute> {}
                composable<AccountRoute> {}
                composable<InsightsRoute> {}
                composable<BudgetsRoute> {}
                composable<ImportRoute> {}
                composable<CardRoute> {}
                composable<SignInRoute> {}
                composable<SettingsRoute> {}
                composable<SettingsSectionRoute> {}
            }
            tab = state.currentTab
        }
    }

    private inline fun <reified T : Any> showing() = state.navController.currentBackStackEntry!!.destination.hasRoute<T>()

    @Test
    fun `a page opens over Wallet, and home goes back to it`() {
        compose.runOnIdle { state.openPath("account?id=3") }
        compose.runOnIdle {
            // The account's own page, without the tabs (it has its own way back).
            assertNull(tab)
            assertEquals(3L, state.navController.currentBackStackEntry!!.toRoute<AccountRoute>().id)
            state.openPath("home")
        }
        compose.runOnIdle {
            assertEquals(TopLevelDestination.Wallet, tab)
            assertFalse(state.navController.popBackStack()) // Wallet is the start: back leaves the app
        }
    }

    @Test
    fun `tabs replace each other, and a page the app hasn't got opens Wallet`() {
        compose.runOnIdle { state.navigateToTab(TopLevelDestination.Insights) }
        compose.runOnIdle {
            assertEquals(TopLevelDestination.Insights, tab)
            state.navigateToTab(TopLevelDestination.Settings)
        }
        compose.runOnIdle {
            assertEquals(TopLevelDestination.Settings, tab)
            // A link from an older build, say.
            state.openPath("connect?client=claude")
        }
        compose.runOnIdle { assertEquals(TopLevelDestination.Wallet, tab) }
    }

    @Test
    fun `pages open one after another, each its own`() {
        compose.runOnIdle { state.openPath("budgets") }
        compose.runOnIdle { state.openPath("settings?section=rules") }
        compose.runOnIdle {
            assertTrue(showing<SettingsSectionRoute>())
            assertTrue(state.navController.popBackStack())
            assertTrue(showing<BudgetsRoute>())
            assertTrue(state.navController.popBackStack())
            assertTrue(showing<WalletRoute>())
        }
    }

    @Test
    fun `insights is native, with its period and account`() {
        compose.runOnIdle { state.navigateToTab(TopLevelDestination.Insights) }
        compose.runOnIdle {
            assertEquals(TopLevelDestination.Insights, tab)
            state.openPath("stats?span=custom&start=2026-10-01&end=2026-10-15&account=3")
        }
        compose.runOnIdle {
            assertEquals(TopLevelDestination.Insights, tab)
            val route = state.navController.currentBackStackEntry!!.toRoute<InsightsRoute>()
            assertEquals(InsightsRoute("custom", "2026-10-01", "2026-10-15", 3), route)
        }
    }

    @Test
    fun `records and review are native, with their filters`() {
        compose.runOnIdle { state.openPath("records?account=3&category=none") }
        compose.runOnIdle {
            assertEquals(TopLevelDestination.Activity, tab)
            val route = state.navController.currentBackStackEntry!!.toRoute<ActivityRoute>()
            assertEquals(3L, route.account)
            assertEquals(com.rudnam.wallet.core.model.RecordFilter.NO_CATEGORY, route.category)
            state.openPath("review")
        }
        compose.runOnIdle { assertEquals(TopLevelDestination.Activity, tab) }
    }

    @Test
    fun `signing out leaves only the sign-in page`() {
        compose.runOnIdle { state.openPath("records") }
        compose.runOnIdle {
            state.signUpNext = true
            state.showSignIn()
        }
        compose.runOnIdle {
            assertTrue(state.onSignIn)
            // Leaving a demo: the page opens on creating an account, once.
            assertEquals("sign-up", state.navController.currentBackStackEntry!!.toRoute<SignInRoute>().mode)
            assertFalse(state.signUpNext)
            assertNull(tab)
            assertFalse(state.navController.popBackStack())
        }
    }

    @Test
    fun `a settings page opens by its section, and an unknown section opens Settings`() {
        compose.runOnIdle { state.openPath("settings?section=accounts") }
        compose.runOnIdle {
            assertEquals(SettingsSectionRoute("accounts"), state.navController.currentBackStackEntry!!.toRoute<SettingsSectionRoute>())
            state.openPath("settings?section=nonsense")
        }
        compose.runOnIdle { assertEquals(TopLevelDestination.Settings, tab) }
    }

    @Test
    fun `importing and a tapped card are native`() {
        compose.runOnIdle { state.openPath("settings?section=import") }
        compose.runOnIdle {
            assertTrue(state.navController.currentBackStackEntry!!.destination.hasRoute<ImportRoute>())
            state.openPath("card")
        }
        compose.runOnIdle {
            assertTrue(state.navController.currentBackStackEntry!!.destination.hasRoute<CardRoute>())
            // Another card tapped while its page is open shows there.
            state.openPath("card")
        }
        compose.runOnIdle {
            assertTrue(state.navController.popBackStack())
            assertTrue(state.navController.currentBackStackEntry!!.destination.hasRoute<ImportRoute>())
        }
    }
}
