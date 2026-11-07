package com.rudnam.wallet.feature.settings

import com.rudnam.wallet.core.data.repository.ChangeResult
import com.rudnam.wallet.core.data.repository.Fetched
import com.rudnam.wallet.core.testing.MainDispatcherRule
import com.rudnam.wallet.core.testing.SampleWallet
import com.rudnam.wallet.core.testing.TestSettingsRepository
import com.rudnam.wallet.core.testing.TestUserRepository
import com.rudnam.wallet.core.testing.TestWalletRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MoreSettingsViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val wallet = TestWalletRepository().apply { send(SampleWallet.wallet, SampleWallet.budgets, SampleWallet.records) }
    private val settings = TestSettingsRepository()

    @Test
    fun `shops and cards are changed, forgotten and added, and say so`() = runTest {
        val vm = RulesViewModel(wallet, settings)
        val rules = (vm.rules.first { it is Loadable.Ready } as Loadable.Ready).value
        assertEquals(listOf("Doutor", "Ramen Taro"), rules.payees.map { it.payee })

        vm.setShop("doutor", "Doutor", 10)
        vm.forgetShop("ramen taro", "Ramen Taro")
        vm.addShop("  ローソン ", 11)
        vm.addShop("   ", 11) // nothing to add
        vm.setCard("PayPay Card", 3)
        assertEquals(listOf("doutor" to 10L, "ramen taro" to null, "ローソン" to 11L), settings.shops)
        assertEquals(listOf("PayPay Card" to 3L), settings.cards)
        assertEquals(PageMessage.Said(R.string.feature_settings_shop_added, "ローソン"), vm.messages.value[2])

        settings.result = ChangeResult.Refused("not found")
        vm.forgetCard("Old card")
        assertEquals(PageMessage.Refused("not found"), vm.messages.value.last())
    }

    @Test
    fun `offline, the shops seen stay`() = runTest {
        val vm = RulesViewModel(wallet, settings)
        vm.rules.first { it is Loadable.Ready }
        settings.rules = Fetched.Offline
        vm.reload()
        assertTrue(vm.rules.value is Loadable.Ready)
    }

    @Test
    fun `payment notifications' settings keep each other`() = runTest {
        val vm = NotificationsViewModel(wallet, settings)
        vm.wallet.first { it != null }
        vm.setAutoConfirm(false)
        vm.setDefaultAccount(3)
        // The default account (1) stays when auto-confirm changes, and auto-confirm (on) when the account does.
        assertEquals(listOf(false to 1L, true to 3L), settings.notificationSettings)
        assertNull(vm.autoConfirm.value)
    }

    @Test
    fun `a new connection shows its token once, and disconnecting asks first`() = runTest {
        val vm = ClaudeViewModel(TestUserRepository(), settings)
        vm.state.first { it.tokens is Loadable.Ready }
        vm.create("  ")
        val made = vm.state.value.fresh!!
        assertEquals("Claude", made.name)
        assertEquals("wallet_secret", made.token)
        assertEquals(listOf(null), (vm.state.value.tokens as Loadable.Ready).value.map { it.token })

        vm.askDisconnect(made)
        assertEquals(made, vm.state.value.disconnecting)
        vm.disconnect()
        assertEquals(listOf(made.id), settings.deletedTokens)
        assertNull(vm.state.value.fresh)
        assertNull(vm.state.value.disconnecting)
    }
}
