package com.rudnam.wallet.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge as CountBadge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rudnam.wallet.R
import java.util.Calendar
import java.util.Date

/** What Wallet shows: nothing yet, the wallet (maybe from what was kept, while offline), or a failure. */
sealed interface WalletState {
    data object Loading : WalletState
    data class Ready(val data: HomeData, val offline: Boolean = false, val refreshing: Boolean = false) : WalletState
    data object Failed : WalletState
}

/** Where Wallet's taps go. Pages that aren't native yet open in the web app. */
interface WalletActions {
    /** A page of the web app, e.g. account with id=3 (web/src/router.ts). */
    fun open(page: String, params: Map<String, String> = emptyMap())
    fun openRecord(t: Transaction)
    fun add()
    fun refresh()
}

/** The tabs, as the web app has them (App.tsx, pages). */
private val tabs = listOf(
    Triple("home", R.string.tab_wallet, Icons.wallet),
    Triple("records", R.string.tab_activity, Icons.records),
    Triple("stats", R.string.tab_insights, Icons.stats),
    Triple("settings", R.string.tab_settings, Icons.settings),
)

/**
 * Wallet, the app's first tab (web: features/home/HomePage.tsx): the total
 * and the accounts as a stack of cards, this month in one line, and the
 * latest records.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletScreen(state: WalletState, format: Format?, actions: WalletActions, now: Date = Date()) {
    val reviewCount = (state as? WalletState.Ready)?.data?.overview?.reviewCount ?: 0
    Scaffold(
        containerColor = colors.bg,
        bottomBar = { TabBar(reviewCount, actions) },
        floatingActionButton = { AddButton(actions) },
    ) { padding ->
        val ready = state as? WalletState.Ready
        PullToRefreshBox(
            isRefreshing = ready?.refreshing == true,
            onRefresh = actions::refresh,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            when {
                ready != null && format != null -> Wallet(ready, format, actions, now)
                state == WalletState.Failed -> Failed(actions)
                else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.loading), color = colors.muted)
                }
            }
        }
    }
}

@Composable
private fun TabBar(reviewCount: Int, actions: WalletActions) {
    Column {
        HorizontalDivider(color = colors.border)
        NavigationBar(containerColor = colors.bg, tonalElevation = 0.dp) {
            for ((page, label, icon) in tabs) {
                val selected = page == "home"
                val tint = if (selected) colors.text else mix(colors.muted, 0.85f, colors.bg)
                NavigationBarItem(
                    selected = selected,
                    onClick = { if (!selected) actions.open(page) },
                    icon = {
                        if (page == "records" && reviewCount > 0) {
                            BadgedBox(badge = { CountBadge(containerColor = colors.attention, contentColor = colors.bg) { Text(reviewCount.toString()) } }) {
                                LineIcon(icon, tint = tint, size = 22.dp)
                            }
                        } else {
                            LineIcon(icon, tint = tint, size = 22.dp)
                        }
                    },
                    label = { Text(stringResource(label), style = MaterialTheme.typography.labelSmall, maxLines = 1) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedTextColor = colors.text,
                        unselectedTextColor = tint,
                        indicatorColor = Color.Transparent,
                    ),
                )
            }
        }
    }
}

/** The floating plus: the main action, near-black on light and near-white on dark. */
@Composable
private fun AddButton(actions: WalletActions) {
    val label = stringResource(R.string.add_record)
    Surface(
        onClick = actions::add,
        modifier = Modifier.size(56.dp).semantics { contentDescription = label },
        shape = RoundedCornerShape(18.dp),
        color = colors.accent,
        contentColor = colors.accentText,
        shadowElevation = 8.dp,
    ) {
        Box(contentAlignment = Alignment.Center) { LineIcon(Icons.plus, tint = colors.accentText, size = 24.dp, stroke = 2.2f) }
    }
}

@Composable
private fun Failed(actions: WalletActions) {
    // In a list, so pulling down to refresh works here too.
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        item {
            Column(
                Modifier.fillMaxWidth().padding(top = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.load_failed), color = colors.muted)
                OutlinedButton(onClick = actions::refresh) { Text(stringResource(R.string.try_again), color = colors.text) }
            }
        }
    }
}

@Composable
private fun Wallet(state: WalletState.Ready, f: Format, actions: WalletActions, now: Date) {
    val d = state.data
    val month = Format.monthStart(now)
    val confirmed = d.records.filter { it.confirmed }
    val spent = confirmed.filter { it.kind == Kind.EXPENSE }.sumOf { it.amount }
    val accounts = d.overview.accounts.filter { !it.archived }
    val total = accounts.sumOf { it.balance }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 21.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(21.dp),
    ) {
        if (state.offline) {
            item("offline") {
                Row(
                    Modifier.fillMaxWidth().clip(PanelShape).padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LineIcon(Icons.warning, size = 16.dp)
                    Text(stringResource(R.string.offline), style = MaterialTheme.typography.bodySmall, color = colors.muted)
                }
            }
        }
        item("balance") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.total_balance), Modifier.weight(1f), color = colors.muted, style = MaterialTheme.typography.bodySmall)
                    SearchButton { actions.open("records") }
                }
                Text(f.money(total), style = MaterialTheme.typography.displayMedium.figures())
                CardStack(accounts, f) { actions.open("account", mapOf("id" to it.id.toString())) }
            }
        }
        item("month") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionLabel(f.monthName(month))
                Panel(Modifier.fillMaxWidth(), onClick = { actions.open("stats") }) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.spent_this_month), Modifier.weight(1f), color = colors.muted, style = MaterialTheme.typography.bodySmall)
                            Text(f.money(spent), style = MaterialTheme.typography.headlineSmall.figures())
                        }
                        Text(headline(spent, d.budgets, confirmed, month, now, f), color = colors.muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        item("latest-head") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SectionLabel(stringResource(R.string.latest_records), Modifier.weight(1f))
                Text(
                    stringResource(R.string.all_records),
                    Modifier.clip(RoundedCornerShape(6.dp)).clickable { actions.open("records") }.padding(horizontal = 4.dp, vertical = 2.dp),
                    color = colors.muted,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        if (d.records.isEmpty()) {
            item("empty") {
                Panel(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.fillMaxWidth().padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(stringResource(R.string.nothing_recorded_yet), color = colors.muted)
                        OutlinedButton(onClick = actions::add, border = BorderStroke(1.dp, colors.border)) {
                            Text(stringResource(R.string.add_a_record), color = colors.text)
                        }
                    }
                }
            }
        } else {
            for ((key, records) in byDay(d.records.take(5))) {
                item("day-$key") { Day(records, d.overview, f, actions, now) }
            }
        }
    }
}

/** A page's small grey label above a section (web: .section-label). */
@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, color = colors.muted, style = MaterialTheme.typography.labelMedium)
}

@Composable
private fun SearchButton(onClick: () -> Unit) {
    val label = stringResource(R.string.search_records)
    Surface(
        onClick = onClick,
        modifier = Modifier.size(38.dp).semantics { contentDescription = label },
        shape = CircleShape,
        color = Color.Transparent,
        border = BorderStroke(1.dp, colors.border),
    ) {
        Box(contentAlignment = Alignment.Center) { LineIcon(Icons.search, tint = colors.text, size = 18.dp) }
    }
}

/** How far down each card behind the front one shows its top edge. */
private val cardEdge = 54.dp
private val cardHeight = 150.dp

/**
 * The accounts as a stack of cards in their own colours (web: CardStack):
 * the first account in front with its balance in full, the others showing
 * their top edge, a name and a balance. Each opens its account. Holding a
 * card to rearrange the stack is still the web Wallet's.
 */
@Composable
private fun CardStack(accounts: List<Account>, f: Format, onOpen: (Account) -> Unit) {
    if (accounts.isEmpty()) return
    val drawn = accounts.reversed() // the back card first, the front card (accounts[0]) last
    val last = drawn.size - 1
    val label = stringResource(R.string.accounts)
    Box(
        Modifier.fillMaxWidth().padding(top = 18.dp).height(cardEdge * last + cardHeight).semantics { contentDescription = label },
    ) {
        drawn.forEachIndexed { i, a ->
            val front = i == last
            val raw = hexColor(a.color) ?: colors.muted
            val bg = mix(raw, colors.cardMix, Color.Black)
            val ink = inkOn(raw)
            val shape = RoundedCornerShape(16.dp)
            Surface(
                onClick = { onOpen(a) },
                modifier = Modifier
                    .offset(y = cardEdge * i)
                    .fillMaxWidth()
                    .height(cardHeight)
                    .shadow(if (front) 14.dp else if (i == 0) 0.dp else 3.dp, shape, clip = false),
                shape = shape,
                color = bg,
                contentColor = ink,
                border = if (colors.dark) BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)) else null,
            ) {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            a.name,
                            Modifier.weight(1f),
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.25.sp,
                            color = ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (front) {
                            val kind = stringResource(kindLabel(a.kind))
                            if (kind != a.name) Text(kind, fontWeight = FontWeight.Medium, fontSize = 12.sp, color = ink.copy(alpha = 0.75f), maxLines = 1)
                        } else {
                            Text(f.money(a.balance), fontWeight = FontWeight.SemiBold, fontSize = 14.25.sp, color = ink, style = MaterialTheme.typography.bodyMedium.figures(), maxLines = 1)
                        }
                    }
                    if (front) {
                        Spacer(Modifier.weight(1f))
                        Text(
                            f.money(a.balance),
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 27.75.sp,
                            letterSpacing = (-0.97).sp,
                            color = ink,
                            style = MaterialTheme.typography.bodyMedium.figures(),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/** The account kinds' names, as Settings › Accounts calls them (web: accountKinds). */
private fun kindLabel(kind: String): Int = when (kind) {
    "bank" -> R.string.kind_bank
    "credit_card" -> R.string.kind_credit_card
    "e_money" -> R.string.kind_e_money
    "cash" -> R.string.kind_cash
    "savings" -> R.string.kind_savings
    else -> R.string.kind_other
}

/**
 * The line under this month's spending: what the month means today, from the
 * overall budget (one with no category) if there is one, else what's been
 * spent so far (web: headline in HomePage.tsx).
 */
@Composable
private fun headline(spent: Long, budgets: List<Budget>, confirmed: List<Transaction>, month: Date, now: Date, f: Format): String {
    if (spent == 0L) return stringResource(R.string.no_spending_yet)
    val overall = budgets.firstOrNull { it.categoryId == null }
    if (overall != null) {
        val used = budgetSpent(overall, confirmed)
        if (used > overall.amount) return stringResource(R.string.over_budget, f.money(used - overall.amount))
        // Days left, today included, as Insights counts them.
        val left = Format.daysInMonth(month) - Calendar.getInstance().apply { time = now }.get(Calendar.DAY_OF_MONTH) + 1
        return stringResource(R.string.a_day_within_budget, f.money(Math.floorDiv(overall.amount - used, left.toLong())))
    }
    return stringResource(R.string.spent_so_far, f.money(spent), f.monthName(month))
}

/** Records grouped by day, newest first, keeping the order they came in. */
fun byDay(records: List<Transaction>): List<Pair<Int, List<Transaction>>> {
    val days = mutableListOf<Pair<Int, MutableList<Transaction>>>()
    for (t in records) {
        val key = Format.dayKey(t.occurredAt)
        if (days.lastOrNull()?.first == key) days.last().second.add(t) else days.add(key to mutableListOf(t))
    }
    return days
}

/** The record's effect on the total: transfers move money, they don't change it. */
fun signed(t: Transaction): Long = when {
    !t.confirmed || t.kind == Kind.TRANSFER -> 0
    t.kind == Kind.INCOME -> t.amount
    else -> -t.amount
}

@Composable
private fun Day(records: List<Transaction>, o: Overview, f: Format, actions: WalletActions, now: Date) {
    val net = records.sumOf(::signed)
    Column {
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
            val style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 12.sp, letterSpacing = 0.24.sp)
            Text(f.day(records.first().occurredAt, stringResource(R.string.today), stringResource(R.string.yesterday), now), Modifier.weight(1f), color = colors.muted, style = style)
            // Nothing confirmed that day: no total, rather than ¥0.
            if (net != 0L) Text(f.money(net, signed = true), color = colors.muted, style = style.figures())
        }
        Panel(Modifier.fillMaxWidth()) {
            records.forEachIndexed { i, t ->
                if (i > 0) HorizontalDivider(color = colors.border)
                RecordRow(t, o, f) { actions.openRecord(t) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecordRow(t: Transaction, o: Overview, f: Format, onClick: () -> Unit) {
    val cat = o.category(t.categoryId)
    val account = o.account(t.accountId)
    val title = when {
        t.kind == Kind.TRANSFER -> stringResource(R.string.transfer_to, o.account(t.toAccountId)?.name ?: "?")
        t.payee.isNotEmpty() -> t.payee
        cat != null -> cat.name
        t.kind == Kind.INCOME -> stringResource(R.string.income)
        else -> stringResource(R.string.expense)
    }
    // Waiting for a check and for a category: it says so, in the colour for "needs a look".
    val waiting = !t.confirmed && t.kind != Kind.TRANSFER && cat == null
    val sub = when {
        t.kind == Kind.TRANSFER -> stringResource(R.string.transfer_from, account?.name ?: "?")
        waiting -> stringResource(R.string.choose_a_category)
        else -> cat?.name ?: stringResource(R.string.no_category)
    }
    val amount = when (t.kind) {
        Kind.TRANSFER -> f.money(t.amount)
        Kind.INCOME -> f.money(t.amount, signed = true)
        Kind.EXPENSE -> f.money(-t.amount, signed = true)
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (t.kind == Kind.TRANSFER) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(colors.sunken), contentAlignment = Alignment.Center) {
                LineIcon(Icons.transfer)
            }
        } else {
            CategoryIcon(cat, waiting = waiting)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
            Text(sub, color = if (waiting) colors.attention else colors.muted, style = MaterialTheme.typography.bodySmall)
            if (t.tags.isNotEmpty()) {
                FlowRow(Modifier.padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (tag in t.tags) {
                        Surface(shape = RoundedCornerShape(50), color = colors.sunken) {
                            Text("#$tag", Modifier.padding(horizontal = 7.dp), style = MaterialTheme.typography.labelSmall, color = colors.muted)
                        }
                    }
                }
            }
        }
        val maxSide = (LocalConfiguration.current.screenWidthDp * 0.4f).dp
        Column(Modifier.widthIn(max = maxSide), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                if (t.amount == 0L && !t.confirmed) stringResource(R.string.amount_unknown) else amount,
                fontWeight = FontWeight.Medium,
                style = MaterialTheme.typography.bodyMedium.figures(),
                maxLines = 1,
                color = when (t.kind) {
                    Kind.INCOME -> colors.positive
                    Kind.TRANSFER -> colors.muted
                    Kind.EXPENSE -> colors.text
                },
            )
            if (account != null) {
                Text(account.name, color = colors.muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** What a budget has spent: its category's confirmed spending, or all of it (web: budgetSpent). */
fun budgetSpent(b: Budget, list: List<Transaction>): Long = list
    .filter { it.kind == Kind.EXPENSE && it.confirmed && (b.categoryId == null || it.categoryId == b.categoryId) }
    .sumOf { it.amount }
