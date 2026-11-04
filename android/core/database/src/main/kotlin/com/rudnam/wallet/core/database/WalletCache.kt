package com.rudnam.wallet.core.database

import javax.inject.Inject
import javax.inject.Singleton

/** The database as a whole, for what isn't one table's business. */
@Singleton
class WalletCache @Inject constructor(private val database: WalletDatabase) {
    /** Empties every table (signing out). Blocks: call it off the main thread. */
    fun clear() = database.clearAllTables()
}
