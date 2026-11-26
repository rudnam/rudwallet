package com.rudnam.wallet.core.network.model

import com.rudnam.wallet.core.model.Recurring
import com.rudnam.wallet.core.model.RecurringDue
import com.rudnam.wallet.core.model.RecurringInput
import com.rudnam.wallet.core.model.RecurringKind
import com.rudnam.wallet.core.model.RecurringMonth
import com.rudnam.wallet.core.model.RecurringOverview
import com.rudnam.wallet.core.model.RecurringPayment
import com.rudnam.wallet.core.model.RecurringSuggestion
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate

// GET /api/recurring and what POST and PUT take. Dates are YYYY-MM-DD in the
// time zone asked for.

@Serializable
data class NetworkRecurringInput(
    val name: String,
    val kind: String,
    val matches: List<String>,
    val amount: Long,
    val months: Int,
    @SerialName("start_date") val startDate: String,
    // Null (left out, which the server reads the same): none.
    @SerialName("category_id") val categoryId: Long?,
    @SerialName("account_id") val accountId: Long?,
    val active: Boolean,
)

@Serializable
data class NetworkRecurringPayment(
    @SerialName("transaction_id") val transactionId: Long,
    val date: String,
    val amount: Long,
    val label: String = "",
    val confirmed: Boolean = true,
)

@Serializable
data class NetworkRecurring(
    val id: Long,
    val name: String,
    val kind: String,
    val matches: List<String> = emptyList(),
    val amount: Long,
    val months: Int = 1,
    @SerialName("start_date") val startDate: String,
    @SerialName("category_id") val categoryId: Long? = null,
    @SerialName("account_id") val accountId: Long? = null,
    val active: Boolean = true,
    val estimate: Long = 0,
    val payments: List<NetworkRecurringPayment> = emptyList(),
    @SerialName("next_due") val nextDue: String? = null,
    val late: Boolean = false,
)

@Serializable
data class NetworkRecurringSuggestion(
    val key: String,
    val name: String,
    val kind: String,
    val matches: List<String> = emptyList(),
    val amount: Long,
    val months: Int = 1,
    @SerialName("start_date") val startDate: String,
    @SerialName("category_id") val categoryId: Long? = null,
    @SerialName("account_id") val accountId: Long? = null,
    val payments: List<NetworkRecurringPayment> = emptyList(),
)

@Serializable
data class NetworkRecurringDue(val id: Long, val name: String, val date: String, val amount: Long, val late: Boolean = false)

@Serializable
data class NetworkRecurringMonth(
    val start: String,
    val paid: Long = 0,
    val upcoming: Long = 0,
    val due: List<NetworkRecurringDue> = emptyList(),
)

@Serializable
data class NetworkRecurringOverview(
    val recurring: List<NetworkRecurring> = emptyList(),
    val suggestions: List<NetworkRecurringSuggestion> = emptyList(),
    val month: NetworkRecurringMonth,
)

@Serializable
data class NetworkRecurringDismiss(val key: String)

fun RecurringInput.asNetwork() = NetworkRecurringInput(
    name = name,
    kind = kind.apiName,
    matches = matches,
    amount = amount,
    months = months,
    startDate = startDate.toString(),
    categoryId = categoryId,
    accountId = accountId,
    active = active,
)

private fun NetworkRecurringPayment.asModel() = RecurringPayment(transactionId, LocalDate.parse(date), amount, label, confirmed)

fun NetworkRecurring.asModel() = Recurring(
    id = id,
    input = RecurringInput(name, RecurringKind.from(kind), matches, amount, months, LocalDate.parse(startDate), categoryId, accountId, active),
    estimate = estimate,
    payments = payments.map { it.asModel() },
    nextDue = nextDue?.let(LocalDate::parse),
    late = late,
)

fun NetworkRecurringOverview.asModel() = RecurringOverview(
    recurring = recurring.map { it.asModel() },
    suggestions = suggestions.map { s ->
        RecurringSuggestion(
            key = s.key,
            input = RecurringInput(s.name, RecurringKind.from(s.kind), s.matches, s.amount, s.months, LocalDate.parse(s.startDate), s.categoryId, s.accountId, active = true),
            payments = s.payments.map { it.asModel() },
        )
    },
    month = RecurringMonth(
        start = LocalDate.parse(month.start),
        paid = month.paid,
        upcoming = month.upcoming,
        due = month.due.map { RecurringDue(it.id, it.name, LocalDate.parse(it.date), it.amount, it.late) },
    ),
)
