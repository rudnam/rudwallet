package com.rudnam.wallet.feature.record

import androidx.lifecycle.SavedStateHandle
import androidx.navigation.testing.invoke
import com.rudnam.wallet.core.data.repository.RecordEvent
import com.rudnam.wallet.core.data.repository.RecordEvents
import com.rudnam.wallet.core.data.repository.RecordHabits
import com.rudnam.wallet.core.data.repository.SaveResult
import com.rudnam.wallet.core.model.TransactionKind
import com.rudnam.wallet.core.testing.MainDispatcherRule
import com.rudnam.wallet.core.testing.SampleWallet
import com.rudnam.wallet.core.testing.TestRecordRepository
import com.rudnam.wallet.core.testing.TestWalletRepository
import com.rudnam.wallet.feature.record.navigation.RecordRoute
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RecordViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val wallet = TestWalletRepository().apply { send(SampleWallet.wallet, SampleWallet.budgets, SampleWallet.records) }
    private val records = TestRecordRepository()
    private val events = RecordEvents()
    private val clock = Clock.fixed(SampleWallet.now, SampleWallet.zone)

    private fun viewModel(route: RecordRoute = RecordRoute()) =
        RecordViewModel(SavedStateHandle(route), wallet, records, events, clock)

    private suspend fun RecordViewModel.ready() = uiState.first { it is RecordUiState.Ready } as RecordUiState.Ready

    @Test
    fun `a new record starts in the last account, and a shop paid before fills in its category`() = runTest {
        records.lastAccount = 3
        records.habits = RecordHabits(mapOf("Doutor" to SampleWallet.records.first { it.payee == "Doutor" }), mapOf(13L to 5), emptyList())
        val vm = viewModel()
        assertEquals(3L, vm.ready().form.accountId)
        // The most used category comes first.
        assertEquals(13L, vm.ready().categories.first().id)
        vm.setPayee("Doutor")
        assertEquals(13L, vm.ready().form.categoryId)
    }

    @Test
    fun `the categories keep their order when the server's habits come in`() = runTest {
        records.habits = RecordHabits(emptyMap(), mapOf(13L to 5), emptyList())
        val other = SampleWallet.wallet.categories.first { it.id != 13L && it.kind == SampleWallet.wallet.category(13L)!!.kind }.id
        records.refreshed = RecordHabits(emptyMap(), mapOf(other to 50), emptyList())
        val vm = viewModel()
        assertEquals(13L, vm.ready().categories.first().id)
    }

    @Test
    fun `saving without an amount says so, and with one it's added and the sheet closes`() = runTest {
        val vm = viewModel()
        vm.ready()
        assertTrue(!vm.save())
        assertEquals(RecordProblem.NoAmount, vm.ready().problem)

        val sent = mutableListOf<RecordEvent>()
        val listening = launch(UnconfinedTestDispatcher(testScheduler)) { events.all.collect { sent += it } }
        vm.press(PadKey.Digits("1"))
        vm.press(PadKey.Digits("200"))
        vm.chooseCategory(10)
        vm.save()
        assertEquals(1200L, records.added.single().amount)
        assertEquals(10L, records.added.single().categoryId)
        assertTrue(vm.finished.value)
        assertEquals(listOf<RecordEvent>(RecordEvent.Added(1200)), sent)
        listening.cancel()
    }

    @Test
    fun `an edit asks before saving what it changes, and offline it says a connection is needed`() = runTest {
        val record = SampleWallet.records.first { it.id == 18L }
        records.records = mapOf(18L to record)
        val vm = viewModel(RecordRoute(id = 18))
        vm.ready()
        vm.setKind(TransactionKind.Income)
        vm.save()
        // An expense's category doesn't fit an income, so it goes too.
        assertEquals(listOf(Change.Field.Type, Change.Field.Category), vm.ready().confirming!!.map { it.field })
        records.next = SaveResult.Offline
        vm.confirmChanges()
        assertEquals(RecordProblem.Offline, vm.ready().problem)
        assertEquals(18L, records.updated.single().first)
    }
}
