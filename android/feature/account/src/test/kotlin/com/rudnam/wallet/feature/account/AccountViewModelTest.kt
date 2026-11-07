package com.rudnam.wallet.feature.account

import androidx.lifecycle.SavedStateHandle
import androidx.navigation.testing.invoke
import com.rudnam.wallet.core.data.repository.CountResult
import com.rudnam.wallet.core.testing.MainDispatcherRule
import com.rudnam.wallet.core.testing.SampleWallet
import com.rudnam.wallet.core.testing.TestAccountRepository
import com.rudnam.wallet.core.testing.TestWalletRepository
import com.rudnam.wallet.feature.account.navigation.AccountRoute
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
import java.time.Clock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AccountViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val wallet = TestWalletRepository().apply { send(SampleWallet.wallet, SampleWallet.budgets, SampleWallet.records) }
    private val accounts = TestAccountRepository()
    private val clock = Clock.fixed(SampleWallet.now, SampleWallet.zone)

    private fun viewModel(id: Long) = AccountViewModel(SavedStateHandle(AccountRoute(id)), wallet, accounts, clock)

    private suspend fun AccountViewModel.ready() = uiState.first { it is AccountUiState.Ready } as AccountUiState.Ready

    @Test
    fun `an account's month shows what went out and came in, transfers included`() = runTest {
        // PayPay: ¥480 + ¥0 to review (not counted) + ¥2,100 out; ¥10,000 in from MUFG.
        val paypay = viewModel(3).ready()
        assertEquals(2_580, paypay.out)
        assertEquals(10_000, paypay.into)
        assertTrue(paypay.records.all { it.accountId == 3L || it.toAccountId == 3L })
    }

    @Test
    fun `counting cash records the difference, or says it was right`() = runTest {
        val vm = viewModel(1)
        vm.ready()
        vm.startCounting()
        assertEquals("26450", vm.counting.value!!.text)
        vm.typeCount("abc")
        vm.saveCount()
        assertTrue(vm.counting.value!!.invalid)
        vm.typeCount("２５，０００円")
        vm.saveCount()
        assertEquals(listOf(1L to 25_000L), accounts.counted)
        assertNull(vm.counting.value)
        assertEquals(AccountMessage.AlreadyRight(25_000), vm.messages.value.single())

        accounts.countResult = CountResult.Offline
        vm.startCounting()
        vm.saveCount()
        assertEquals(AccountMessage.Offline, vm.messages.value.last())
    }

    @Test
    fun `an account deleted elsewhere says so`() = runTest {
        assertEquals(AccountUiState.Gone, viewModel(99).uiState.first { it != AccountUiState.Loading })
    }
}
