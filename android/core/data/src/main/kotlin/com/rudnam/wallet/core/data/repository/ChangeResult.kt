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
