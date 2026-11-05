package com.rudnam.wallet.feature.wallet

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import com.rudnam.wallet.core.data.repository.SyncResult
import com.rudnam.wallet.core.model.AppUpdate
import com.rudnam.wallet.core.testing.MainDispatcherRule
import com.rudnam.wallet.core.testing.SampleWallet
import com.rudnam.wallet.core.testing.TestAccountRepository
import com.rudnam.wallet.core.testing.TestUpdateRepository
import com.rudnam.wallet.core.testing.TestUserPreferences
import com.rudnam.wallet.core.testing.TestWalletRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Clock

class WalletViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val wallet = TestWalletRepository()
    private val updates = TestUpdateRepository()
    private val preferences = TestUserPreferences()
    private val clock = Clock.fixed(SampleWallet.now, SampleWallet.zone)

    private fun viewModel() = WalletViewModel(wallet, updates, preferences.repository, TestAccountRepository(), clock)

    @Test
    fun `shows what's on the phone, this month's latest five, and refreshes once`() = runTest {
        wallet.send(SampleWallet.wallet, SampleWallet.budgets, SampleWallet.records)
        val vm = viewModel()
        vm.uiState.test {
            val ready = awaitReady()
            assertEquals(4, ready.accounts.size) // the archived card isn't in the stack
            assertEquals(1_011_600, ready.wallet.totalBalance)
            assertEquals(listOf(20L, 19L, 18L, 17L, 16L), ready.latest.map { it.id })
            assertEquals(19_570, ready.spent)
            assertEquals(MonthHeadline.PerDay(2_378), ready.headline)
        }
        assertEquals(1, wallet.refreshes)
    }

    @Test
    fun `with nothing on the phone and no server, it says it couldn't load`() = runTest {
        wallet.nextSync = SyncResult.Offline
        val vm = viewModel()
        vm.uiState.test {
            assertEquals(WalletUiState.Unavailable, awaitSettled())
        }
    }

    @Test
    fun `pulling to refresh while offline says so, once`() = runTest {
        wallet.send(SampleWallet.wallet, SampleWallet.budgets, SampleWallet.records)
        val vm = viewModel()
        wallet.nextSync = SyncResult.Offline
        vm.refresh(userAsked = true)
        assertEquals(listOf(WalletMessage.Offline), vm.messages.value)
        vm.messageShown(WalletMessage.Offline)
        assertTrue(vm.messages.value.isEmpty())
    }

    @Test
    fun `the checklist shows until it's done or put away`() = runTest {
        wallet.notificationsSent = false
        wallet.send(SampleWallet.wallet, emptyList(), emptyList())
        val vm = viewModel()
        vm.uiState.test {
            val setup = awaitReady().setup!!
            assertEquals(1, setup.done) // accounts, yes; notifications and a budget, not yet
            assertTrue(setup.noRecordsYet)
            vm.dismissSetup()
            assertNull(awaitReady().setup)
        }
    }

    @Test
    fun `an update is offered until put off`() = runTest {
        val update = AppUpdate(42, "2.1.42", "https://example.com/download/wallet.apk")
        updates.update = update
        wallet.send(SampleWallet.wallet, SampleWallet.budgets, SampleWallet.records)
        val vm = viewModel()
        vm.uiState.test {
            assertEquals(update, awaitReady().update)
            vm.updateLater()
            assertNull(awaitReady().update)
        }
        assertEquals(listOf(update), updates.skipped)
    }

    /** The next state that isn't still loading (the preferences come from DataStore a moment later). */
    private suspend fun ReceiveTurbine<WalletUiState>.awaitSettled(): WalletUiState {
        var state = awaitItem()
        while (state == WalletUiState.Loading) state = awaitItem()
        return state
    }

    private suspend fun ReceiveTurbine<WalletUiState>.awaitReady(): WalletUiState.Ready = awaitSettled() as WalletUiState.Ready
}
