package com.rudnam.wallet.core.model

/** Who is signed in (GET /api/me). */
data class Me(
    val email: String,
    /** False for an account that only signs in with Google: deleting it asks for the email instead. */
    val hasPassword: Boolean,
    /** A throwaway demo full of sample data, deleted within a day. */
    val demo: Boolean,
)

/** A new account, or an account's new details. [initialBalance] is what's owed, negative, for a card. */
data class AccountInput(
    val name: String,
    val kind: AccountKind,
    val initialBalance: Long,
    /** #rrggbb. */
    val color: String,
    val archived: Boolean,
)

/** A new category, or a category's new details. [icon] is a glyph key, or empty for its initial. */
data class CategoryInput(val name: String, val kind: CategoryKind, val icon: String, val color: String)
