package com.rudnam.wallet.feature.wallet

import com.rudnam.wallet.core.model.Account
import com.rudnam.wallet.core.model.AppUpdate
import com.rudnam.wallet.core.model.Budget
import com.rudnam.wallet.core.model.Me
import com.rudnam.wallet.core.model.Transaction
import com.rudnam.wallet.core.model.Wallet
import com.rudnam.wallet.core.model.spent
import com.rudnam.wallet.core.model.spentIn
import java.time.LocalDate
import java.time.YearMonth

sealed interface WalletUiState {
    data object Loading : WalletUiState

    /** Nothing on the phone yet, and the server couldn't be asked. */
    data object Unavailable : WalletUiState

    data class Ready(
        val wallet: Wallet,
        /** The accounts in use, the front card first. */
        val accounts: List<Account>,
        val month: YearMonth,
        val spent: Long,
        val headline: MonthHeadline,
        /** The latest five of this month's records. */
        val latest: List<Transaction>,
        val setup: SetupChecklist?,
        val update: AppUpdate?,
        val refreshing: Boolean,
        /** Who's signed in: a demo, or an address still to confirm, says so. */
        val me: Me? = null,
        val confirmation: Confirmation = Confirmation.Idle,
    ) : WalletUiState
}

/** Sending the link to confirm the address again. */
sealed interface Confirmation {
    data object Idle : Confirmation
    data object Sending : Confirmation
    data object Sent : Confirmation

    /** The server's reason, or empty for offline. */
    data class Failed(val message: String) : Confirmation
}

/** The line under this month's spending: what the month means today. */
sealed interface MonthHeadline {
    data object NothingSpent : MonthHeadline

    /** What can still be spent each day, today included, to stay within the overall budget. */
    data class PerDay(val amount: Long) : MonthHeadline

    data class OverBudget(val amount: Long) : MonthHeadline

    data class SpentSoFar(val amount: Long) : MonthHeadline
}

/**
 * From the overall budget (the one with no category) if there is one, else
 * what's been spent so far (as the website's Wallet says it).
 */
fun monthHeadline(records: List<Transaction>, budgets: List<Budget>, month: YearMonth, today: LocalDate): MonthHeadline {
    val spent = records.spent()
    if (spent == 0L) return MonthHeadline.NothingSpent
    val overall = budgets.firstOrNull { it.categoryId == null } ?: return MonthHeadline.SpentSoFar(spent)
    val used = overall.spentIn(records)
    if (used > overall.amount) return MonthHeadline.OverBudget(used - overall.amount)
    val daysLeft = (month.lengthOfMonth() - today.dayOfMonth + 1).coerceAtLeast(1)
    return MonthHeadline.PerDay(Math.floorDiv(overall.amount - used, daysLeft.toLong()))
}

/** The first things a new user does, ticked off as they go, until done or put away. */
data class SetupChecklist(
    val accountsAdded: Boolean,
    val notificationsOn: Boolean,
    val budgetSet: Boolean,
    /** Before the first record: the currency can still change, and history can be imported. */
    val noRecordsYet: Boolean,
) {
    val done: Int get() = listOf(accountsAdded, notificationsOn, budgetSet).count { it }
    val total: Int get() = 3
}
