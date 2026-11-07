package com.rudnam.wallet.feature.signin

import androidx.lifecycle.SavedStateHandle
import androidx.navigation.testing.invoke
import com.rudnam.wallet.core.data.repository.ChangeResult
import com.rudnam.wallet.core.data.repository.GoogleReturn
import com.rudnam.wallet.core.data.repository.GoogleReturns
import com.rudnam.wallet.core.data.repository.SignInResult
import com.rudnam.wallet.core.testing.MainDispatcherRule
import com.rudnam.wallet.core.testing.TestAuthRepository
import com.rudnam.wallet.feature.signin.navigation.SignInRoute
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SignInViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val auth = TestAuthRepository()
    private val google = GoogleReturns()

    private fun viewModel(route: SignInRoute = SignInRoute()) = SignInViewModel(SavedStateHandle(route), auth, google)

    @Test
    fun `signing in needs an email and a password, and a refusal says why`() = runTest {
        val vm = viewModel()
        assertTrue(vm.state.first { it.options.google }.options.demo)
        vm.submit()
        assertTrue(auth.signIns.isEmpty())
        vm.setEmail("francis@example.com")
        vm.setPassword("wrong")
        auth.result = SignInResult.Refused("email or password is incorrect")
        vm.submit()
        assertEquals(SignInNotice.Server("email or password is incorrect"), vm.state.value.notice)
        assertFalse(vm.state.value.busy)
        auth.result = SignInResult.SignedIn
        vm.setPassword("right")
        vm.submit()
        assertEquals(listOf("francis@example.com" to "wrong", "francis@example.com" to "right"), auth.signIns)
        assertEquals("", vm.state.value.password)
    }

    @Test
    fun `leaving a demo opens on creating an account, and a deleted account says so`() = runTest {
        assertEquals(SignInMode.SignUp, viewModel(SignInRoute("sign-up")).state.value.mode)
        assertEquals(SignInNotice.Deleted, viewModel(SignInRoute(deleted = true)).state.value.notice)
        val vm = viewModel(SignInRoute("sign-up"))
        vm.setEmail("new@example.com")
        vm.setPassword("a long password")
        vm.submit()
        assertEquals(listOf("new@example.com" to "a long password"), auth.signUps)
    }

    @Test
    fun `a forgotten password sends a link, and says so whether or not the account exists`() = runTest {
        val vm = viewModel()
        vm.setMode(SignInMode.Forgot)
        vm.setEmail("francis@example.com")
        vm.submit()
        assertEquals(SignInNotice.ResetSent, vm.state.value.notice)
        auth.change = ChangeResult.Offline
        vm.submit()
        assertEquals(SignInNotice.Offline, vm.state.value.notice)
        assertEquals(2, auth.resets.size)
    }

    @Test
    fun `Google coming back signs in, or says why not`() = runTest {
        val vm = viewModel()
        google.send(GoogleReturn(code = null, error = "cancelled"))
        assertEquals(SignInNotice.Google("cancelled"), vm.state.first { it.notice != null }.notice)
        google.send(GoogleReturn(code = "c0de", error = null))
        vm.state.first { auth.googleReturns.size == 2 }
        assertEquals(null, google.pending.value) // taken once
    }

    @Test
    fun `the demo shows its own wait`() = runTest {
        auth.result = SignInResult.Offline
        val vm = viewModel()
        vm.startDemo()
        assertEquals(1, auth.demos)
        assertFalse(vm.state.value.startingDemo)
        assertEquals(SignInNotice.Offline, vm.state.value.notice)
    }
}
