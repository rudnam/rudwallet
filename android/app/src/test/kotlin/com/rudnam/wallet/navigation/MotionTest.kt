package com.rudnam.wallet.navigation

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.testing.TestNavHostController
import com.rudnam.wallet.core.designsystem.theme.WalletTheme
import com.rudnam.wallet.core.ui.LocalSharedTransitionScope
import com.rudnam.wallet.core.ui.Page
import com.rudnam.wallet.core.ui.accountCardKey
import com.rudnam.wallet.core.ui.sharedBounds
import com.rudnam.wallet.core.ui.sharedBoundsInStack
import com.rudnam.wallet.feature.account.navigation.AccountRoute
import com.rudnam.wallet.feature.wallet.navigation.WalletRoute
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The pages' motion, frame by frame, on stand-ins for the real pages: Wallet is blue with its
 * card, an account's page red around the same card. A pixel below the card tells which page
 * shows.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h640dp-mdpi")
class MotionTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var nav: TestNavHostController

    @Before
    fun setUp() {
        compose.setContent {
            WalletTheme(darkTheme = false) {
                val context = LocalContext.current
                nav = remember { TestNavHostController(context).apply { navigatorProvider.addNavigator(ComposeNavigator()) } }
                SharedTransitionLayout(Modifier.fillMaxSize().background(Color.Black)) {
                    CompositionLocalProvider(LocalSharedTransitionScope provides this) {
                        NavHost(
                            nav,
                            startDestination = WalletRoute,
                            enterTransition = { enter() },
                            exitTransition = { exit() },
                            popEnterTransition = { popEnter() },
                            popExitTransition = { popExit() },
                            predictivePopEnterTransition = { popEnter() },
                            predictivePopExitTransition = { popExit() },
                        ) {
                            composable<WalletRoute> {
                                Page {
                                    Box(Modifier.fillMaxSize().background(Color.Blue)) {
                                        Box(Modifier.padding(16.dp).size(200.dp, 120.dp).sharedBoundsInStack(accountCardKey(1), RectangleShape, 0f).background(Color.Green))
                                    }
                                }
                            }
                            composable<AccountRoute> {
                                Page {
                                    Column(Modifier.fillMaxSize().background(Color.Red)) {
                                        Box(Modifier.fillMaxWidth().height(160.dp).sharedBounds(accountCardKey(1), RectangleShape).background(Color.Green))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /** How red the bottom of the screen is: 1 for the account's page, 0 for Wallet. */
    private fun redness(): Float {
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        val pixel = image.getPixel(image.width / 2, image.height - 20)
        val red = android.graphics.Color.red(pixel)
        val blue = android.graphics.Color.blue(pixel)
        return if (red + blue == 0) 0f else red.toFloat() / (red + blue)
    }

    @Test
    fun `back from an account, its page is gone as the card lands`() {
        compose.runOnUiThread { nav.navigate(AccountRoute(1)) }
        compose.waitForIdle()
        assertTrue("the account's page shows", redness() > 0.9f)

        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { nav.popBackStack() }
        val timeline = mutableListOf<Pair<Long, Float>>()
        var elapsed = 0L
        while (elapsed <= 1500) {
            compose.mainClock.advanceTimeBy(16)
            elapsed += 16
            timeline += elapsed to redness()
        }
        val shown = timeline.joinToString { (ms, r) -> "$ms:${"%.2f".format(r)}" }
        println("card back, redness by ms: $shown")
        // The page fades out over a quarter of a second or so; well before half a second, it's
        // Wallet that shows.
        val at400 = timeline.first { it.first >= 400 }.second
        assertTrue("the account's page still shows 400 ms after going back: $shown", at400 < 0.1f)
    }
}
