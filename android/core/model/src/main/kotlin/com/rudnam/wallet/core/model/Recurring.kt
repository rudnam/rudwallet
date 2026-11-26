package com.rudnam.wallet.core.model

import java.time.LocalDate

/** What a recurring payment is: the same amount each time, an amount that moves, or money coming in. */
enum class RecurringKind(val apiName: String) {
    Subscription("subscription"),
    Bill("bill"),
    Income("income"),
    ;

    /** The kind of record its payments are. */
    val recordKind: CategoryKind get() = if (this == Income) CategoryKind.Income else CategoryKind.Expense

    companion object {
        fun from(apiName: String) = entries.firstOrNull { it.apiName == apiName } ?: Subscription
    }
}

/** A recurring payment as saved (GET, POST and PUT /api/recurring). */
data class RecurringInput(
    val name: String,
    val kind: RecurringKind,
    /** Texts its records' shop (or note) contain. */
    val matches: List<String>,
    val amount: Long,
    /** How often: 1, 2, 3, 6 or 12 months. */
    val months: Int,
    /** When it's due if it has no payments yet. */
    val startDate: LocalDate,
    val categoryId: Long?,
    val accountId: Long?,
    /** False once it stopped (a cancelled subscription). */
    val active: Boolean,
)

/** One of a recurring payment's records. */
data class RecurringPayment(
    val transactionId: Long,
    val date: LocalDate,
    val amount: Long,
    val label: String,
    /** False for a record still to review. */
    val confirmed: Boolean,
)

/** A recurring payment, and what its records say about it. */
data class Recurring(
    val id: Long,
    val input: RecurringInput,
    /** What the next payment should be. */
    val estimate: Long,
    /** Newest first, up to 12. */
    val payments: List<RecurringPayment>,
    val nextDue: LocalDate?,
    /** The next payment was due before today. */
    val late: Boolean,
) {
    val name: String get() = input.name
    val kind: RecurringKind get() = input.kind
    val active: Boolean get() = input.active
    val lastPaid: RecurringPayment? get() = payments.firstOrNull()
}

/** A recurring payment found in the records, ready to add with [input]. */
data class RecurringSuggestion(val key: String, val input: RecurringInput, val payments: List<RecurringPayment>)

/** A payment expected this month and not made yet. */
data class RecurringDue(val id: Long, val name: String, val date: LocalDate, val amount: Long, val late: Boolean)

/**
 * What the active subscriptions and bills do to the month going on: [paid]
 * is their confirmed payments in it (spending already counted), [upcoming]
 * what's still expected.
 */
data class RecurringMonth(val start: LocalDate, val paid: Long, val upcoming: Long, val due: List<RecurringDue>) {
    companion object {
        val Empty = RecurringMonth(LocalDate.MIN, 0, 0, emptyList())
    }
}

data class RecurringOverview(val recurring: List<Recurring>, val suggestions: List<RecurringSuggestion>, val month: RecurringMonth) {
    /** Active subscriptions and bills: those the month's estimate counts. */
    val expensesTracked: Int get() = recurring.count { it.active && it.kind != RecurringKind.Income }
}
