package com.rudnam.wallet.feature.wallet

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import com.rudnam.wallet.core.designsystem.theme.WalletTheme
import com.rudnam.wallet.core.testing.SampleWallet
import com.rudnam.wallet.core.ui.MoneyFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/** Wallet's card stack: a tap opens a card; holding one and dragging rearranges the stack. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class CardStackTest {
    @get:Rule
    val compose = createComposeRule()

    private val accounts = SampleWallet.wallet.activeAccounts // Cash in front, then PayPay, PayPay Card, MUFG at the back

    @Test
    fun `a tap opens the card, and holding the back card and dragging it down brings it forward`() {
        val opened = mutableListOf<Long>()
        val orders = mutableListOf<List<Long>>()
        compose.setContent {
            WalletTheme {
                CardStack(accounts, MoneyFormat(Locale.US, SampleWallet.wallet.settings), onOpen = { opened += it.id }, onReorder = { orders += it })
            }
        }
        // A card behind shows only its top edge: that's where it's tapped.
        compose.onNodeWithContentDescription("PayPay Card, −¥46,770").performTouchInput { click(Offset(centerX, 30f)) }
        assertEquals(listOf(5L), opened)

        // MUFG, at the back, is held and dragged down past two cards.
        // The long press is timed by Compose's clock, so the test moves it on between pressing and dragging.
        val mufg = compose.onNodeWithContentDescription("MUFG, ¥1,001,780")
        mufg.performTouchInput { down(Offset(centerX, 40f)) }
        compose.mainClock.advanceTimeBy(1_000)
        mufg.performTouchInput {
            repeat(12) { moveBy(Offset(0f, 30f)) }
            up()
        }
        compose.waitForIdle()
        assertTrue(orders.isNotEmpty())
        // Front first: MUFG has moved in front of PayPay Card and PayPay.
        assertEquals(listOf(1L, 2L, 3L, 5L), orders.last())
    }
}
