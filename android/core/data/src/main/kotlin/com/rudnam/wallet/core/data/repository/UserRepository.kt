package com.rudnam.wallet.core.data.repository

import com.rudnam.wallet.core.common.Dispatcher
import com.rudnam.wallet.core.common.WalletDispatchers
import com.rudnam.wallet.core.datastore.SessionStore
import com.rudnam.wallet.core.datastore.UserPreferencesRepository
import com.rudnam.wallet.core.model.Me
import com.rudnam.wallet.core.network.WalletNetworkDataSource
import com.rudnam.wallet.core.network.model.NetworkDeleteMe
import com.rudnam.wallet.core.network.model.asModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Why the session ended here: the user signed out, or deleted the account. */
enum class SignOutReason { SignedOut, Deleted }

/** Sign-outs made in the app, and why: an account deleted, the sign-in page says so. */
@Singleton
class SessionEvents @Inject constructor() {
    private val events = MutableSharedFlow<SignOutReason>(extraBufferCapacity = 4)

    val signedOut: SharedFlow<SignOutReason> = events.asSharedFlow()

    fun send(reason: SignOutReason) {
        events.tryEmit(reason)
    }
}

/** Who is signed in, and ending that: signing out, or deleting the account. */
interface UserRepository {
    /** The last the server said; null once signed out. */
    val me: Flow<Me?>

    suspend fun refreshMe()

    /** Sends what's waiting in the outbox if it can, ends the session on the server, and forgets it here. */
    suspend fun signOut()

    /**
     * Deletes the account and everything in it, confirmed with the password
     * (or, for an account that signs in with Google, its email).
     */
    suspend fun deleteAccount(confirm: String, withPassword: Boolean): ChangeResult
}

internal class ServerUserRepository @Inject constructor(
    private val network: WalletNetworkDataSource,
    private val session: SessionStore,
    private val preferences: UserPreferencesRepository,
    private val records: RecordRepository,
    private val events: SessionEvents,
    @Dispatcher(WalletDispatchers.IO) private val io: CoroutineDispatcher,
) : UserRepository {
    override val me: Flow<Me?> = preferences.me

    override suspend fun refreshMe() = withContext(io) {
        try {
            preferences.setMe(network.me().asModel())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Offline, or signed out meanwhile: what was kept stays until sign-out.
        }
    }

    override suspend fun signOut() = withContext(io) {
        // Records not sent yet would be lost with the session: one last try.
        records.sendPending()
        try {
            network.logout()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Offline: the token is forgotten here all the same.
        }
        forget(SignOutReason.SignedOut)
    }

    override suspend fun deleteAccount(confirm: String, withPassword: Boolean): ChangeResult {
        val result = change(io) { network.deleteMe(if (withPassword) NetworkDeleteMe(password = confirm) else NetworkDeleteMe(email = confirm.trim())) }
        if (result == ChangeResult.Saved) forget(SignOutReason.Deleted)
        return result
    }

    private suspend fun forget(reason: SignOutReason) {
        preferences.setMe(null)
        session.clear()
        events.send(reason)
    }
}
