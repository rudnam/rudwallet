package com.rudnam.wallet.feature.signin

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.rudnam.wallet.core.data.repository.AuthRepository
import com.rudnam.wallet.core.data.repository.ChangeResult
import com.rudnam.wallet.core.data.repository.GoogleReturns
import com.rudnam.wallet.core.data.repository.SignInResult
import com.rudnam.wallet.core.model.AuthOptions
import com.rudnam.wallet.feature.signin.navigation.SignInRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class SignInMode { SignIn, SignUp, Forgot }

/** What the page says above the form: a reason (a server's message, or one of its own), or good news. */
sealed interface SignInNotice {
    /** The server's words, for the user. */
    data class Server(val message: String) : SignInNotice

    data object Offline : SignInNotice

    data class Google(val reason: String) : SignInNotice

    data object Deleted : SignInNotice

    /** The link to choose a new password was sent (if the account exists). */
    data object ResetSent : SignInNotice
}

data class SignInUiState(
    val mode: SignInMode,
    val email: String = "",
    val password: String = "",
    val options: AuthOptions = AuthOptions(google = false, demo = false, reset = false),
    val busy: Boolean = false,
    /** Setting up a demo takes a few seconds: the whole page says so. */
    val startingDemo: Boolean = false,
    val notice: SignInNotice? = null,
) {
    val ready: Boolean get() = email.isNotBlank() && (mode == SignInMode.Forgot || password.isNotEmpty())
}

/** What the sign-in page's controls do. */
interface SignInActions {
    fun setMode(mode: SignInMode)
    fun setEmail(email: String)
    fun setPassword(password: String)
    fun submit()
    fun startDemo()
    /** Where the phone's browser starts Google sign-in. */
    fun googleUrl(): String
}

@HiltViewModel
class SignInViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val auth: AuthRepository,
    private val google: GoogleReturns,
) : ViewModel(), SignInActions {
    private val route = savedState.toRoute<SignInRoute>()

    private val _state = MutableStateFlow(
        SignInUiState(
            mode = if (route.mode == "sign-up") SignInMode.SignUp else SignInMode.SignIn,
            notice = if (route.deleted) SignInNotice.Deleted else null,
        ),
    )
    val state: StateFlow<SignInUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { _state.update { it.copy(options = auth.options()) } }
        // Google sending the browser back (MainActivity), now or later.
        viewModelScope.launch {
            google.pending.filterNotNull().collect {
                google.take()?.let { back ->
                    _state.update { s -> s.copy(busy = true, notice = null) }
                    finish(auth.finishGoogle(back))
                }
            }
        }
    }

    override fun setMode(mode: SignInMode) = _state.update { it.copy(mode = mode, notice = null) }

    override fun setEmail(email: String) = _state.update { it.copy(email = email, notice = it.notice.takeIf { n -> n == SignInNotice.ResetSent }) }

    override fun setPassword(password: String) = _state.update { it.copy(password = password) }

    override fun submit() {
        val s = _state.value
        if (s.busy || !s.ready) return
        _state.value = s.copy(busy = true, notice = null)
        viewModelScope.launch {
            when (s.mode) {
                SignInMode.SignIn -> finish(auth.signIn(s.email, s.password))
                SignInMode.SignUp -> finish(auth.signUp(s.email, s.password))
                SignInMode.Forgot -> _state.update {
                    it.copy(
                        busy = false,
                        notice = when (val r = auth.forgotPassword(s.email)) {
                            ChangeResult.Saved -> SignInNotice.ResetSent
                            is ChangeResult.Refused -> SignInNotice.Server(r.message)
                            else -> SignInNotice.Offline
                        },
                    )
                }
            }
        }
    }

    override fun startDemo() {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, startingDemo = true, notice = null) }
        viewModelScope.launch { finish(auth.startDemo()) }
    }

    override fun googleUrl(): String = auth.googleSignInUrl()

    /** Signed in, the app moves on by itself (it follows the session); otherwise, why not. */
    private fun finish(result: SignInResult) = _state.update {
        when (result) {
            SignInResult.SignedIn -> it.copy(busy = false, startingDemo = false, password = "")
            SignInResult.Offline -> it.copy(busy = false, startingDemo = false, notice = SignInNotice.Offline)
            is SignInResult.Refused -> it.copy(busy = false, startingDemo = false, notice = SignInNotice.Server(result.message))
            is SignInResult.GoogleFailed -> it.copy(busy = false, startingDemo = false, notice = SignInNotice.Google(result.reason))
        }
    }
}
