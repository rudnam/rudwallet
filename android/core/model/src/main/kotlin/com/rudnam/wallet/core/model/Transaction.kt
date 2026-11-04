package com.rudnam.wallet.core.model

import java.time.Instant

enum class TransactionKind(val apiName: String) {
    Expense("expense"),
    Income("income"),
    Transfer("transfer"),
    ;

    companion object {
        fun fromApi(name: String): TransactionKind = entries.firstOrNull { it.apiName == name } ?: Expense
    }
}

enum class TransactionStatus(val apiName: String) {
    Confirmed("confirmed"),

    /** Made from an email or a notification, waiting for the user to check it. */
    Review("review"),
    ;

    companion object {
        fun fromApi(name: String): TransactionStatus = if (name == "review") Review else Confirmed
    }
}

enum class TransactionSource(val apiName: String) {
    Manual("manual"),
    Email("email"),
    Import("import"),
    Notification("notification"),
    ;

    companion object {
        fun fromApi(name: String): TransactionSource = entries.firstOrNull { it.apiName == name } ?: Manual
    }
}

/** A record: an expense, an income, or a transfer between the user's accounts. */
data class Transaction(
    val id: Long,
    val kind: TransactionKind,
    /** Minor units, never negative: the kind says which way it goes. */
    val amount: Long,
    val accountId: Long,
    val toAccountId: Long?,
    val categoryId: Long?,
    val occurredAt: Instant,
    val payee: String,
    val note: String,
    val status: TransactionStatus,
    val source: TransactionSource,
    /** What paid, as the email or notification named it (e.g. a card). */
    val sourceLabel: String,
    val duplicateOfId: Long?,
    val createdAt: Instant,
    /** The user's own labels, in the order they were added. */
    val tags: List<String>,
    /** Added on this phone without a connection, and not on the server yet (its id is below zero). */
    val pending: Pending? = null,
) {
    val confirmed: Boolean get() = status == TransactionStatus.Confirmed

    /** The record's effect on the total: transfers move money between accounts and change nothing. */
    val signedAmount: Long
        get() = when {
            !confirmed || kind == TransactionKind.Transfer -> 0
            kind == TransactionKind.Income -> amount
            else -> -amount
        }
}

/** What a budget has spent among these records: its category's confirmed spending, or all of it. */
fun Budget.spentIn(records: List<Transaction>): Long = records
    .filter { it.kind == TransactionKind.Expense && it.confirmed && (categoryId == null || it.categoryId == categoryId) }
    .sumOf { it.amount }

/** Confirmed spending among these records. */
fun List<Transaction>.spent(): Long =
    filter { it.kind == TransactionKind.Expense && it.confirmed }.sumOf { it.amount }

/** A record kept on the phone until the server has it. */
sealed interface Pending {
    /** Waiting for a connection. */
    data object Waiting : Pending

    /** The server refused it ([message] says why): it waits for the user to change or delete it. */
    data class Refused(val message: String) : Pending
}

/** A record as the user enters it: what's sent to add or change one (POST, PUT /api/transactions). */
data class RecordInput(
    val kind: TransactionKind,
    val amount: Long,
    val accountId: Long,
    val toAccountId: Long?,
    val categoryId: Long?,
    val occurredAt: java.time.Instant,
    val payee: String,
    val note: String,
    val status: TransactionStatus,
    val tags: List<String>,
    /** false: confirm without changing what the shop's category was learned as. null: learn it. */
    val remember: Boolean? = null,
) {
    /** The record as it would read, for showing one that isn't on the server yet. */
    fun asTransaction(id: Long, createdAt: java.time.Instant, pending: Pending?) = Transaction(
        id = id, kind = kind, amount = amount, accountId = accountId, toAccountId = toAccountId, categoryId = categoryId,
        occurredAt = occurredAt, payee = payee, note = note, status = status, source = TransactionSource.Manual,
        sourceLabel = "", duplicateOfId = null, createdAt = createdAt, tags = tags, pending = pending,
    )
}

/** The record as entered, to put it back (undo) or to start an edit from. */
fun Transaction.asInput(remember: Boolean? = null) = RecordInput(
    kind, amount, accountId, toAccountId, categoryId, occurredAt, payee, note, status, tags, remember,
)
