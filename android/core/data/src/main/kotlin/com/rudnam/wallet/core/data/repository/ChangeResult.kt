package com.rudnam.wallet.core.data.repository

import com.rudnam.wallet.core.network.ApiException
import com.rudnam.wallet.core.network.SignedOutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.IOException

/** How a change made on the server went: saving or deleting a budget, an account, a category, a setting. */
sealed interface ChangeResult {
    data object Saved : ChangeResult

    data object Offline : ChangeResult

    data object SignedOut : ChangeResult

    /** The server said no: [message] says why, for the user. */
    data class Refused(val message: String) : ChangeResult
}

/** Runs [block] (calls to the server) on [io], and says how it went. */
internal suspend fun change(io: CoroutineDispatcher, block: suspend () -> Unit): ChangeResult = withContext(io) {
    try {
        block()
        ChangeResult.Saved
    } catch (e: CancellationException) {
        throw e
    } catch (e: SignedOutException) {
        ChangeResult.SignedOut
    } catch (e: IOException) {
        ChangeResult.Offline
    } catch (e: ApiException) {
        if (e.status in 400..499) ChangeResult.Refused(e.message) else ChangeResult.Offline
    }
}

/** What asking the server for something brought: it, or why not. */
sealed interface Fetched<out T> {
    data class Done<T>(val value: T) : Fetched<T>

    data object Offline : Fetched<Nothing>

    data object SignedOut : Fetched<Nothing>

    /** The server refused; [message] is for the user, if it gave one. */
    data class Failed(val message: String) : Fetched<Nothing>
}

/** Runs [block] (a call to the server) on [io], and says what it brought. */
internal suspend fun <T> fetch(io: CoroutineDispatcher, block: suspend () -> T): Fetched<T> = withContext(io) {
    try {
        Fetched.Done(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: SignedOutException) {
        Fetched.SignedOut
    } catch (e: IOException) {
        Fetched.Offline
    } catch (e: ApiException) {
        Fetched.Failed(e.message)
    } catch (e: kotlinx.serialization.SerializationException) {
        Fetched.Failed("")
    } catch (e: IllegalArgumentException) {
        Fetched.Failed("") // a date written in a way it never has been
    }
}
