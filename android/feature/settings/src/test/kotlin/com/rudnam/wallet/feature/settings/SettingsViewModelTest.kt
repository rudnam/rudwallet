package com.rudnam.wallet.feature.settings

import com.rudnam.wallet.core.data.repository.ChangeResult
import com.rudnam.wallet.core.datastore.Appearance
import com.rudnam.wallet.core.model.AccountInput
import com.rudnam.wallet.core.model.AccountKind
import com.rudnam.wallet.core.model.CategoryInput
import com.rudnam.wallet.core.model.CategoryKind
import com.rudnam.wallet.core.testing.MainDispatcherRule
import com.rudnam.wallet.core.testing.SampleWallet
import com.rudnam.wallet.core.testing.TestSettingsRepository
import com.rudnam.wallet.core.testing.TestUserPreferences
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
class SettingsViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val wallet = TestWalletRepository().apply { send(SampleWallet.wallet, SampleWallet.budgets, SampleWallet.records) }
    private val settings = TestSettingsRepository()
    private val users = TestUserRepository()
    private val preferences = TestUserPreferences().repository

    @Test
    fun `the menu shows who's signed in, and appearance and currency change`() = runTest {
        val vm = SettingsViewModel(wallet, users, settings, preferences)
        val state = vm.uiState.first { it.me != null && it.wallet != null }
        assertEquals("francis@example.com", state.me!!.email)
        vm.setAppearance(Appearance.Dark)
        assertEquals(Appearance.Dark, vm.uiState.first { it.appearance == Appearance.Dark }.appearance)
        vm.setCurrency("JPY") // already
        vm.setCurrency("USD")
        assertEquals(listOf("USD"), settings.currencies)
        settings.result = ChangeResult.Refused("currency can't change once there are records")
        vm.setCurrency("EUR")
        assertEquals(SettingsMessage.Refused("currency can't change once there are records"), vm.messages.value.single())
    }

    @Test
    fun `deleting the account asks for the password, and says why it didn't`() = runTest {
        val vm = SettingsViewModel(wallet, users, settings, preferences)
        vm.uiState.first { it.me != null }
        vm.startDeleting()
        vm.deleteAccount() // nothing typed
        assertTrue(users.deletions.isEmpty())
        users.deleteResult = ChangeResult.Refused("the password is incorrect")
        vm.typeConfirmation("wrong")
        vm.deleteAccount()
        assertEquals("the password is incorrect", vm.deleting.value!!.refused)
        users.deleteResult = ChangeResult.Saved
        vm.typeConfirmation("secret")
        vm.deleteAccount()
        assertEquals(listOf("wrong" to true, "secret" to true), users.deletions)
        assertNull(vm.deleting.value)
    }

    @Test
    fun `signing out`() = runTest {
        val vm = SettingsViewModel(wallet, users, settings, preferences)
        vm.signOut()
        assertTrue(users.signedOut)
    }

    @Test
    fun `an account needs a kind and a balance that's a number, and a card's is what's owed`() = runTest {
        val vm = AccountsViewModel(wallet, settings)
        vm.wallet.first { it != null }
        vm.create()
        vm.save("Bank account", "#3b6ea8")
        assertEquals(AccountProblem.NoKind, vm.editor.value!!.problem)
        vm.setKind(AccountKind.CreditCard)
        vm.setBalance("lots")
        vm.save("PayPay Card", "#c8463d")
        assertEquals(AccountProblem.BadBalance, vm.editor.value!!.problem)
        vm.setBalance("１２，０００")
        vm.save("PayPay Card", "#c8463d")
        assertEquals(null to AccountInput("PayPay Card", AccountKind.CreditCard, -12_000, "#c8463d", false), settings.accounts.single())
        assertNull(vm.editor.value)
        assertEquals(ChangeMessage.Added("PayPay Card"), vm.messages.value.single())

        // A card owing ¥46,770 at the start shows as 46770.
        vm.edit(SampleWallet.wallet.account(5)!!.copy(initialBalance = -46_770))
        assertEquals("46770", vm.editor.value!!.balance)
        vm.setArchived(true)
        vm.save("PayPay Card", "#c8463d")
        assertEquals(5L to AccountInput("PayPay Card", AccountKind.CreditCard, -46_770, "#c8463d", true), settings.accounts.last())

        vm.edit(SampleWallet.wallet.account(1)!!)
        vm.askDelete(true)
        vm.delete()
        assertEquals(listOf(1L), settings.deletedAccounts)
    }

    @Test
    fun `a category needs a name, and the missing ones add with a tap`() = runTest {
        val vm = CategoriesViewModel(wallet, settings)
        assertEquals("Pets", vm.missing.first { it.isNotEmpty() }.single().name)
        vm.create()
        vm.save()
        assertTrue(vm.editor.value!!.noName)
        vm.setName(" Rent ")
        vm.setIcon("home")
        vm.save()
        assertEquals(null to CategoryInput("Rent", CategoryKind.Expense, "home", "#66706b"), settings.categories.single())

        // An emoji from before line icons opens as its glyph.
        vm.edit(SampleWallet.wallet.category(13)!!)
        assertEquals("coffee", vm.editor.value!!.icon)
        vm.close()

        vm.addMissing(settings.missing.single())
        assertEquals("Pets", settings.categories.last().second.name)

        settings.result = ChangeResult.Offline
        vm.edit(SampleWallet.wallet.category(10)!!)
        vm.askDelete(true)
        vm.delete()
        assertEquals(ChangeMessage.Offline, vm.messages.value.last())
        assertTrue(vm.editor.value != null) // kept, to try again
    }

    @Test
    fun `an old wallet's categories group like a new one's with a tap`() = runTest {
        settings.regroupOffer = listOf(SampleWallet.wallet.category(11)!!.copy(parentId = 10))
        val vm = CategoriesViewModel(wallet, settings)
        assertEquals(listOf(11L), vm.regroup.first { it.isNotEmpty() }.map { it.id })
        vm.regroup()
        assertEquals(listOf(listOf(11L)), settings.regrouped)
        assertEquals(ChangeMessage.Saved, vm.messages.value.last())
    }
}
