package com.rudnam.wallet.feature.activity

import androidx.lifecycle.SavedStateHandle
import androidx.navigation.testing.invoke
import com.rudnam.wallet.core.data.repository.SearchResult
import com.rudnam.wallet.core.model.BulkChange
import com.rudnam.wallet.core.model.RecordFilter
import com.rudnam.wallet.core.model.TransactionStatus
import com.rudnam.wallet.core.testing.MainDispatcherRule
import com.rudnam.wallet.core.testing.SampleWallet
import com.rudnam.wallet.core.testing.TestActivityRepository
import com.rudnam.wallet.core.testing.TestRecordRepository
import com.rudnam.wallet.core.testing.TestWalletRepository
import com.rudnam.wallet.feature.activity.navigation.ActivityRoute
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
class ActivityViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val toCheck = SampleWallet.records.filter { it.status == TransactionStatus.Review }
    private val wallet = TestWalletRepository().apply { send(SampleWallet.wallet, SampleWallet.budgets, SampleWallet.records) }
    private val activity = TestActivityRepository().apply { sendReview(toCheck) }
    private val records = TestRecordRepository()
    private val clock = Clock.fixed(SampleWallet.now, SampleWallet.zone)

    private fun viewModel(route: ActivityRoute = ActivityRoute()) = ActivityViewModel(SavedStateHandle(route), wallet, activity, records, clock)

    private suspend fun ActivityViewModel.ready() = uiState.first { it is ActivityUiState.Ready && it.records != null } as ActivityUiState.Ready

    @Test
    fun `the month's records, with those to check above them and not among them`() = runTest {
        val state = viewModel().ready()
        assertEquals(SampleWallet.records.size - toCheck.size, state.records!!.size)
        assertEquals(toCheck.map { it.id }, state.toReview.map { it.record.id })
        assertEquals(19_570, state.spent)
        assertEquals(280_000, state.income)
    }

    @Test
    fun `filters work on the phone, and a link's filters apply`() = runTest {
        val vm = viewModel(ActivityRoute.fromPath("records?account=1"))
        assertTrue(vm.ready().records!!.all { it.accountId == 1L || it.toAccountId == 1L })
        vm.setAccount(null)
        vm.setCategory(RecordFilter.NO_CATEGORY)
        assertEquals(listOf("Flea market"), vm.ready().records!!.map { it.payee })
    }

    @Test
    fun `a search looks through every month on the server, and says when it can't`() = runTest {
        val vm = viewModel()
        vm.ready()
        activity.searchResult = SearchResult.Found(SampleWallet.records.take(2), capped = true)
        vm.search("taro")
        val found = vm.ready()
        assertTrue(found.filter.everyMonth)
        assertTrue(found.capped)
        activity.searchResult = SearchResult.Offline
        vm.search("ramen")
        assertTrue((vm.uiState.first { it is ActivityUiState.Ready && (it as ActivityUiState.Ready).searchOffline } as ActivityUiState.Ready).searchOffline)
        vm.clearSearch()
        assertTrue(!vm.ready().filter.everyMonth)
    }

    @Test
    fun `a category picked for a payment makes it ready, and Confirm all sends the picks first`() = runTest {
        val vm = viewModel()
        val item = vm.ready().toReview.single()
        assertTrue(!item.ready) // no amount and no category yet
        vm.pickCategory(item.record.id, 11)
        assertEquals(11L, vm.ready().toReview.single().categoryId)
        vm.confirm(vm.ready().toReview.single())
        assertEquals(TransactionStatus.Confirmed, records.updated.single().second.status)
        assertEquals(11L, records.updated.single().second.categoryId)
    }

    @Test
    fun `chosen records change together, and a delete can be undone`() = runTest {
        val vm = viewModel()
        vm.ready()
        vm.startChoosing()
        vm.toggle(18)
        vm.toggle(16)
        assertEquals(setOf(18L, 16L), vm.ready().selected)
        vm.bulk(BulkChange.Delete(listOf(18, 16)))
        assertEquals(BulkChange.Delete(listOf(18, 16)), activity.changes.single())
        val message = vm.messages.value.single() as ActivityMessage.Bulk
        assertEquals(2, message.changed)
        vm.restore(listOf(18, 16))
        assertEquals(listOf(listOf(18L, 16L)), activity.restored)
        vm.startChoosing()
        assertNull(vm.ready().selected)
    }
}
