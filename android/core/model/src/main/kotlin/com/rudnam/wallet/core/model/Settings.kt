package com.rudnam.wallet.core.model

/** Who is signed in (GET /api/me). */
data class Me(
    val email: String,
    /** False for an account that only signs in with Google: deleting it asks for the email instead. */
    val hasPassword: Boolean,
    /** A throwaway demo full of sample data, deleted within a day. */
    val demo: Boolean,
    /** The server emailed a link to confirm the address, and it hasn't been opened yet. */
    val confirmEmail: Boolean = false,
)

/** The ways the server offers to sign in, besides email and password. */
data class AuthOptions(val google: Boolean, val demo: Boolean, val reset: Boolean)

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

/** What confirmed payments taught the app (GET /api/rules): each shop's category and each card's account. */
data class Rules(val payees: List<PayeeRule>, val cards: List<CardRule>)

/** [key] is the shop's name as matched (lower case, half-width); [payee] as its records spell it. */
data class PayeeRule(val key: String, val payee: String, val categoryId: Long, val records: Int)

/** [source] is the card as emails and notifications name it. */
data class CardRule(val source: String, val accountId: Long, val records: Int)

/** A payment app's notification the phone sent, and what became of it. */
data class PaymentNotification(
    val id: Long,
    /** The app's name, e.g. Google Wallet, or failing that its package. */
    val app: String,
    val title: String,
    val text: String,
    val postedAt: java.time.Instant,
    val status: NotificationStatus,
    /** Why, in the server's words (English). */
    val detail: String,
)

enum class NotificationStatus(val apiName: String) {
    Payment("payment"),

    /** The payment was already recorded (by hand, say), so nothing was added. */
    Matched("matched"),
    Ignored("ignored"),
    Unparsed("unparsed"),
    ;

    companion object {
        fun fromApi(name: String) = entries.firstOrNull { it.apiName == name } ?: Unparsed
    }
}

/** A connection for an MCP client such as Claude Desktop. [token] is only in the answer that made it. */
data class McpToken(
    val id: Long,
    val name: String,
    val createdAt: java.time.Instant,
    val lastUsedAt: java.time.Instant?,
    val token: String?,
    /** Made by signing in from the Claude app, rather than a token made here. */
    val signedIn: Boolean,
)
