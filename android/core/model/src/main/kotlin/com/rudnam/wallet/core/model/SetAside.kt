package com.rudnam.wallet.core.model

import java.time.Instant

/**
 * A payment from a notification that looked like a record the user already
 * has, so the server set it aside rather than adding it to "to check"
 * (GET /api/transactions/duplicates). Bringing it back (restore) puts it in
 * "to check", no longer flagged.
 */
data class SetAsideRecord(
    val record: Transaction,
    val setAsideAt: Instant,
    /** The record it looked like, if that one is still there. */
    val duplicateOf: Transaction?,
)
