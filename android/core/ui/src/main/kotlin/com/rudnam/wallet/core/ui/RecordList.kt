package com.rudnam.wallet.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rudnam.wallet.core.designsystem.component.WalletPanel
import com.rudnam.wallet.core.designsystem.icon.LineIcon
import com.rudnam.wallet.core.designsystem.icon.WalletIcons
import com.rudnam.wallet.core.designsystem.theme.WalletTheme
import com.rudnam.wallet.core.designsystem.theme.figures
import com.rudnam.wallet.core.model.Pending
import com.rudnam.wallet.core.model.Transaction
import com.rudnam.wallet.core.model.TransactionKind
import com.rudnam.wallet.core.model.Wallet
import java.time.Instant
import java.time.ZoneId

/** Records grouped by local day, newest first, keeping the order they came in. */
fun List<Transaction>.byDay(zone: ZoneId = ZoneId.systemDefault()): List<List<Transaction>> {
    val days = mutableListOf<MutableList<Transaction>>()
    for (t in this) {
        val day = t.occurredAt.atZone(zone).toLocalDate()
        val last = days.lastOrNull()
        if (last != null && last.first().occurredAt.atZone(zone).toLocalDate() == day) last.add(t) else days.add(mutableListOf(t))
    }
    return days
}

/**
 * Records as the app lists them: a heading per day with its net (none when
 * nothing that day is confirmed, rather than ¥0), and a panel of rows.
 */
fun LazyListScope.recordDays(
    records: List<Transaction>,
    wallet: Wallet,
    format: MoneyFormat,
    onOpen: (Transaction) -> Unit,
    now: Instant = Instant.now(),
    /** While choosing records: the chosen ids. Rows then choose rather than open. */
    selected: Set<Long>? = null,
    onToggle: (Transaction) -> Unit = {},
) {
    for (day in records.byDay()) {
        item(key = "day-${day.first().id}", contentType = "day") {
            Column(Modifier.animateItem()) {
                DayHeading(day, format, now)
                WalletPanel(Modifier.fillMaxWidth()) {
                    day.forEachIndexed { i, t ->
                        if (i > 0) HorizontalDivider(color = WalletTheme.colors.border)
                        RecordRow(t, wallet, format, chosen = selected?.let { t.id in it }) { if (selected != null) onToggle(t) else onOpen(t) }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayHeading(day: List<Transaction>, format: MoneyFormat, now: Instant) {
    val net = day.sumOf { it.signedAmount }
    val style = MaterialTheme.typography.labelMedium
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 8.dp).semantics(mergeDescendants = true) {}) {
        Text(
            format.day(day.first().occurredAt, stringResource(R.string.core_ui_today), stringResource(R.string.core_ui_yesterday), now),
            Modifier.weight(1f),
            style = style,
            color = WalletTheme.colors.muted,
        )
        if (net != 0L) Text(format.money(net, signed = true), style = style.figures(), color = WalletTheme.colors.muted)
    }
}

/** One record: its category, what it was, and the amount with the account it was paid from. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecordRow(t: Transaction, wallet: Wallet, format: MoneyFormat, chosen: Boolean? = null, onClick: () -> Unit) {
    val colors = WalletTheme.colors
    val category = wallet.category(t.categoryId)
    val account = wallet.account(t.accountId)
    val title = when {
        t.kind == TransactionKind.Transfer -> stringResource(R.string.core_ui_transfer_to, wallet.account(t.toAccountId)?.name ?: "?")
        t.payee.isNotEmpty() -> t.payee
        category != null -> category.name
        t.kind == TransactionKind.Income -> stringResource(R.string.core_ui_income)
        else -> stringResource(R.string.core_ui_expense)
    }
    // Waiting for a check and for a category: it says so, in the colour for "needs a look".
    val waiting = !t.confirmed && t.kind != TransactionKind.Transfer && category == null
    val subtitle = when {
        t.kind == TransactionKind.Transfer -> stringResource(R.string.core_ui_transfer_from, account?.name ?: "?")
        waiting -> stringResource(R.string.core_ui_choose_category)
        else -> category?.name ?: stringResource(R.string.core_ui_no_category)
    }
    val amount = when {
        t.amount == 0L && !t.confirmed -> stringResource(R.string.core_ui_amount_unknown)
        t.kind == TransactionKind.Transfer -> format.money(t.amount)
        t.kind == TransactionKind.Income -> format.money(t.amount, signed = true)
        else -> format.money(-t.amount, signed = true)
    }
    val status = when (t.pending) {
        is Pending.Refused -> stringResource(R.string.core_ui_refused)
        Pending.Waiting -> stringResource(R.string.core_ui_not_sent)
        null -> null
    }
    val spoken = listOfNotNull(title, status, subtitle, amount, account?.name).joinToString(", ")
    ListItem(
        modifier = if (chosen != null) {
            Modifier.toggleable(chosen, role = Role.Checkbox) { onClick() }.clearAndSetSemantics {
                contentDescription = spoken
                role = Role.Checkbox
                toggleableState = ToggleableState(chosen)
            }
        } else {
            Modifier.clickable(onClick = onClick).clearAndSetSemantics { contentDescription = spoken }
        },
        colors = ListItemDefaults.colors(containerColor = if (chosen == true) colors.sunken else colors.surface),
        leadingContent = {
            if (chosen != null) {
                Checkbox(checked = chosen, onCheckedChange = null)
            } else if (t.kind == TransactionKind.Transfer) {
                Box(Modifier.size(40.dp).clip(MaterialTheme.shapes.small).background(colors.sunken), contentAlignment = Alignment.Center) {
                    LineIcon(WalletIcons.transfer, contentDescription = null, tint = colors.muted, size = 20.dp)
                }
            } else {
                CategoryIcon(category, waiting = waiting)
            }
        },
        headlineContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                // Added on this phone and not on the server yet (or refused by it).
                when (t.pending) {
                    is Pending.Refused -> Badge(stringResource(R.string.core_ui_refused), colors.attentionSoft, colors.attention)
                    Pending.Waiting -> Badge(stringResource(R.string.core_ui_not_sent), colors.sunken, colors.muted)
                    null -> Unit
                }
            }
        },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = if (waiting) colors.attention else colors.muted)
                if (t.tags.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (tag in t.tags) {
                            Surface(shape = CircleShape, color = colors.sunken) {
                                Text("#$tag", Modifier.padding(horizontal = 7.dp), style = MaterialTheme.typography.labelSmall, color = colors.muted)
                            }
                        }
                    }
                }
            }
        },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    amount,
                    style = MaterialTheme.typography.bodyMedium.figures(),
                    color = when (t.kind) {
                        TransactionKind.Income -> colors.positive
                        TransactionKind.Transfer -> colors.muted
                        TransactionKind.Expense -> colors.text
                    },
                    maxLines = 1,
                )
                account?.let { Text(it.name, style = MaterialTheme.typography.bodySmall, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        },
    )
}

@Composable
private fun Badge(text: String, background: androidx.compose.ui.graphics.Color, ink: androidx.compose.ui.graphics.Color) {
    Surface(shape = CircleShape, color = background) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall, color = ink, maxLines = 1)
    }
}
