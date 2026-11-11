package com.rudnam.wallet.feature.activity

import com.rudnam.wallet.core.data.repository.SetAsideResult
import com.rudnam.wallet.core.model.SetAsideRecord
import com.rudnam.wallet.core.model.TransactionStatus
import com.rudnam.wallet.core.testing.MainDispatcherRule
import com.rudnam.wallet.core.testing.SampleWallet
import com.rudnam.wallet.core.testing.TestActivityRepository
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
class DuplicatesViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val wallet = TestWalletRepository().apply { send(SampleWallet.wallet, SampleWallet.budgets, SampleWallet.records) }
    private val original = SampleWallet.records.first { it.id == 16L }
    private val caught = SetAsideRecord(original.copy(id = 40, status = TransactionStatus.Review, duplicateOfId = 16), SampleWallet.now, original)
    private val activity = TestActivityRepository().apply { setAsideResult = SetAsideResult.Found(listOf(caught)) }

    private suspend fun DuplicatesViewModel.ready() = uiState.first { it is DuplicatesUiState.Ready } as DuplicatesUiState.Ready

    @Test
    fun `lists what was caught, and Not a duplicate brings one back`() = runTest {
        val vm = DuplicatesViewModel(wallet, activity)
        assertEquals(listOf(caught), vm.ready().records)
        vm.bringBack(caught)
        assertEquals(listOf(listOf(40L)), activity.restored)
        assertTrue(vm.ready().records!!.isEmpty())
        assertTrue(vm.messages.value.single() is DuplicatesMessage.BroughtBack)
    }

    @Test
    fun `says when the server can't be reached`() = runTest {
        activity.setAsideResult = SetAsideResult.Offline
        val state = DuplicatesViewModel(wallet, activity).ready()
        assertNull(state.records)
        assertTrue(state.offline)
    }
}
