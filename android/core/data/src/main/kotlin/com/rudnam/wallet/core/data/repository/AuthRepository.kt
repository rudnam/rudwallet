package com.rudnam.wallet.core.data.repository

import com.rudnam.wallet.core.common.Dispatcher
import com.rudnam.wallet.core.common.WalletDispatchers
import com.rudnam.wallet.core.datastore.SessionStore
import com.rudnam.wallet.core.datastore.UserPreferencesRepository
import com.rudnam.wallet.core.model.AuthOptions
import com.rudnam.wallet.core.network.ApiException
import com.rudnam.wallet.core.network.Server
import com.rudnam.wallet.core.network.WalletNetworkDataSource
import com.rudnam.wallet.core.network.model.NetworkMe
import com.rudnam.wallet.core.network.model.asModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.ZoneId
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/** How signing in went. */
sealed interface SignInResult {
    data object SignedIn : SignInResult

    data object Offline : SignInResult

    /** The server said no: [message] says why, for the user. */
    data class Refused(val message: String) : SignInResult

    /** Google sign-in failed: [reason] is the server's code (cancelled, expired, closed, unverified, linked, busy, failed). */
    data class GoogleFailed(val reason: String) : SignInResult
}

/** Google sending the browser back to the app: the one-time code, or why not. */
data class GoogleReturn(val code: String?, val error: String?)

/** Google sign-in coming back (MainActivity), until the sign-in page takes it. */
@Singleton
class GoogleReturns @Inject constructor() {
    private val _pending = MutableStateFlow<GoogleReturn?>(null)
    val pending: StateFlow<GoogleReturn?> = _pending.asStateFlow()

    fun send(value: GoogleReturn) {
        _pending.value = value
    }

    fun take(): GoogleReturn? = _pending.value.also { _pending.value = null }
}

/** Signing in: with email and password, a new account, a demo, or Google; and the emails about it. */
interface AuthRepository {
    /** The ways this server offers to sign in; only email and password if it can't be asked. */
    suspend fun options(): AuthOptions

    suspend fun signIn(email: String, password: String): SignInResult

    suspend fun signUp(email: String, password: String): SignInResult

    suspend fun startDemo(): SignInResult

    /** Emails a link to choose a new password, if an account uses [email]. */
    suspend fun forgotPassword(email: String): ChangeResult

    /** Where the phone's browser starts Google sign-in; the secret it's made from is kept until it comes back. */
    fun googleSignInUrl(): String

    /** Finishes Google sign-in with what came back. */
    suspend fun finishGoogle(back: GoogleReturn): SignInResult

    /** Emails the link to confirm the address again. */
    suspend fun resendConfirmation(): ChangeResult
}

internal class ServerAuthRepository @Inject constructor(
    private val network: WalletNetworkDataSource,
    private val server: Server,
    private val session: SessionStore,
    private val preferences: UserPreferencesRepository,
    @Dispatcher(WalletDispatchers.IO) private val io: CoroutineDispatcher,
) : AuthRepository {
    override suspend fun options(): AuthOptions = withContext(io) {
        try {
            network.authOptions().let { AuthOptions(it.google, it.demo, it.reset) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AuthOptions(google = false, demo = false, reset = false) // the email form still works
        }
    }

    override suspend fun signIn(email: String, password: String) = signedIn { network.login(email.trim(), password) }

    override suspend fun signUp(email: String, password: String) = signedIn { network.signup(email.trim(), password) }

    override suspend fun startDemo() = signedIn { network.startDemo(ZoneId.systemDefault().id) }

    override suspend fun forgotPassword(email: String) = change(io) { network.forgotPassword(email.trim()) }

    override fun googleSignInUrl(): String {
        val verifier = base64url(ByteArray(32).also { SecureRandom().nextBytes(it) })
        val challenge = base64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        session.keepGoogleVerifier(verifier)
        return server.resolve("/api/auth/google?app_challenge=$challenge")
    }

    override suspend fun finishGoogle(back: GoogleReturn): SignInResult {
        val verifier = session.takeGoogleVerifier()
        if (back.code == null || verifier == null) return SignInResult.GoogleFailed(back.error ?: "failed")
        return signedIn { network.appToken(back.code, verifier) }
    }

    override suspend fun resendConfirmation() = change(io) { network.resendConfirmation() }

    /** Keeps the new session and who it is; the app follows the session. */
    private suspend fun signedIn(block: suspend () -> NetworkMe): SignInResult = withContext(io) {
        try {
            val me = block()
            val token = me.token ?: return@withContext SignInResult.Refused("")
            preferences.setMe(me.asModel())
            session.save(token)
            SignInResult.SignedIn
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            SignInResult.Offline
        } catch (e: ApiException) {
            SignInResult.Refused(e.message)
        }
    }

    private fun base64url(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
