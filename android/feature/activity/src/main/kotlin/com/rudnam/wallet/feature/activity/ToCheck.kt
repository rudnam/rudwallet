package com.rudnam.wallet.feature.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rudnam.wallet.core.designsystem.component.WalletPanel
import com.rudnam.wallet.core.designsystem.component.rememberHaptics
import com.rudnam.wallet.core.designsystem.icon.LineIcon
import com.rudnam.wallet.core.designsystem.icon.WalletIcons
import com.rudnam.wallet.core.designsystem.theme.WalletTheme
import com.rudnam.wallet.core.designsystem.theme.figures
import com.rudnam.wallet.core.model.CategoryKind
import com.rudnam.wallet.core.model.TransactionKind
import com.rudnam.wallet.core.ui.CategoryIcon
import com.rudnam.wallet.core.ui.CategoryPickerSheet
import com.rudnam.wallet.core.ui.MoneyFormat
import java.time.Instant

/**
 * "N to check": payments read from the phone's notifications, waiting for
 * the user, above the records. Pick a category and Confirm (or swipe right),
 * all at once when they're ready, or open one to change it. A possible
 * duplicate shows what it matches and can be deleted here.
 */
internal fun LazyListScope.toCheck(state: ActivityUiState.Ready, format: MoneyFormat, actions: ActivityActions, navigation: ActivityNavigation, now: Instant) {
    item(key = "to-check", contentType = "to-check-head") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(WalletTheme.colors.attention))
                Text(stringResource(R.string.feature_activity_to_check, state.toReview.size), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            }
            val ready = state.ready
            if (ready.size > 1) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (ready.size == state.toReview.size) stringResource(R.string.feature_activity_all_ready)
                        else pluralStringResource(R.plurals.feature_activity_some_ready, state.toReview.size, ready.size, state.toReview.size),
                        Modifier.weight(1f).padding(end = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = WalletTheme.colors.muted,
                    )
                    Button(
                        onClick = { actions.confirmAll() },
                        enabled = !state.busy,
                        colors = ButtonDefaults.buttonColors(containerColor = WalletTheme.colors.action, contentColor = WalletTheme.colors.onAction),
                    ) { Text(pluralStringResource(R.plurals.feature_activity_confirm_ready, ready.size, ready.size)) }
                }
            }
        }
    }
    items(state.toReview, key = { "review-${it.record.id}" }, contentType = { "review" }) { item ->
        ReviewRow(item, state, format, actions, navigation, now, Modifier.animateItem())
    }
}

@Composable
private fun ReviewRow(
    item: ReviewItem,
    state: ActivityUiState.Ready,
    format: MoneyFormat,
    actions: ActivityActions,
    navigation: ActivityNavigation,
    now: Instant,
    modifier: Modifier = Modifier,
) {
    val today = stringResource(com.rudnam.wallet.core.ui.R.string.core_ui_today)
    val yesterday = stringResource(com.rudnam.wallet.core.ui.R.string.core_ui_yesterday)
    val t = item.record
    val colors = WalletTheme.colors
    val haptics = rememberHaptics()
    val canSwipe = item.ready && !state.busy
    // Swipe right to confirm; past the point where letting go confirms, the phone gives a detent.
    val swipe = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.StartToEnd && canSwipe) {
                actions.confirm(item)
                true
            } else {
                false
            }
        },
    )
    LaunchedEffect(swipe.targetValue) { if (swipe.targetValue == SwipeToDismissBoxValue.StartToEnd) haptics.threshold() }
    val title = t.payee.ifEmpty { stringResource(R.string.feature_activity_payment) }
    WalletPanel(modifier.fillMaxWidth()) {
        SwipeToDismissBox(
            state = swipe,
            enableDismissFromStartToEnd = canSwipe,
            enableDismissFromEndToStart = false,
            backgroundContent = {
                Row(
                    Modifier.fillMaxSize().background(colors.positive.copy(alpha = 0.16f)).padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LineIcon(WalletIcons.check, contentDescription = null, tint = colors.positive)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.feature_activity_confirm), color = colors.positive, style = MaterialTheme.typography.labelLarge)
                }
            },
        ) {
            Row(
                Modifier.fillMaxWidth().background(colors.surface).padding(start = 12.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CategoryPicker(item, state, actions)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        title,
                        Modifier.clickable { navigation.openRecord(t) },
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // The category the tile picks, by name; until there is one, that it's wanted.
                    val category = state.wallet.category(item.categoryId)?.name
                    Text(
                        listOf(category ?: stringResource(R.string.feature_activity_no_category), format.dateTime(t.occurredAt, today, yesterday, now)).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (category == null) colors.attention else colors.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        if (t.amount > 0) format.money(if (t.kind == TransactionKind.Income) t.amount else -t.amount, signed = true)
                        else stringResource(R.string.feature_activity_amount_unknown),
                        style = MaterialTheme.typography.bodyMedium.figures(),
                        color = if (t.kind == TransactionKind.Income) colors.positive else colors.text,
                    )
                    // The card that paid, where it says more than the account's name.
                    val account = state.wallet.account(t.accountId)?.name
                    val paidWith = t.sourceLabel.takeIf { it.isNotEmpty() && it != account } ?: account
                    paidWith?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.muted, maxLines = 1) }
                }
            }
        }
        // Confirm on a line of its own, so it isn't squeezed by the amount.
        Row(
            Modifier.fillMaxWidth().padding(start = 72.dp, end = 16.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalButton(
                onClick = { if (t.amount > 0) actions.confirm(item) else navigation.openRecord(t) },
                enabled = !state.busy && item.record.duplicateOfId == null && item.categoryId != null,
            ) { Text(stringResource(R.string.feature_activity_confirm), maxLines = 1) }
        }
        if (t.note.isNotEmpty()) Text(t.note, Modifier.padding(start = 72.dp, end = 16.dp, bottom = 8.dp), style = MaterialTheme.typography.bodySmall, color = colors.muted)
        if (t.duplicateOfId != null) Duplicate(item, state, format, actions)
        if (item.overriding) {
            Row(Modifier.padding(start = 60.dp, end = 16.dp, bottom = 8.dp).clickable { actions.setRemember(t.id, !item.remember) }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = item.remember, onCheckedChange = { actions.setRemember(t.id, it) })
                Text(
                    stringResource(
                        R.string.feature_activity_remember,
                        state.wallet.category(item.categoryId)?.name.orEmpty(),
                        t.payee,
                        state.wallet.category(t.categoryId)?.name.orEmpty(),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** The card's category tile, which picks the category: a dashed question mark until there is one. */
@Composable
private fun CategoryPicker(item: ReviewItem, state: ActivityUiState.Ready, actions: ActivityActions) {
    var open by rememberSaveable { mutableStateOf(false) }
    val t = item.record
    val category = state.wallet.category(item.categoryId)
    val label = if (category == null) stringResource(R.string.feature_activity_choose_category)
    else stringResource(R.string.feature_activity_change_category, category.name)
    Box(
        Modifier.size(48.dp).clip(MaterialTheme.shapes.small)
            .clickable(role = Role.Button) { open = true }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { CategoryIcon(category, waiting = category == null) }
    if (open) {
        CategoryPickerSheet(
            wallet = state.wallet,
            kind = CategoryKind.entries.firstOrNull { it.apiName == t.kind.apiName },
            selectedId = item.categoryId,
            onPick = { actions.pickCategory(t.id, it.id) },
            onDismiss = { open = false },
            title = stringResource(R.string.feature_activity_category_for, t.payee.ifEmpty { stringResource(R.string.feature_activity_payment) }),
        )
    }
}

@Composable
private fun Duplicate(item: ReviewItem, state: ActivityUiState.Ready, format: MoneyFormat, actions: ActivityActions) {
    val colors = WalletTheme.colors
    Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp), shape = MaterialTheme.shapes.small, color = colors.attentionSoft) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val m = item.match
            if (m != null) {
                Text(stringResource(R.string.feature_activity_duplicate_of), style = MaterialTheme.typography.bodySmall, color = colors.attention)
                Text(
                    listOfNotNull(
                        m.payee.ifEmpty { stringResource(R.string.feature_activity_payment) },
                        format.money(if (m.kind == TransactionKind.Income) m.amount else -m.amount, signed = true),
                        format.dateTime(m.occurredAt),
                        state.wallet.account(m.accountId)?.name,
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Text(stringResource(R.string.feature_activity_duplicate), style = MaterialTheme.typography.bodySmall, color = colors.attention)
            }
            OutlinedButton(onClick = { actions.deleteDuplicate(item) }, enabled = !state.busy) {
                Text(stringResource(R.string.feature_activity_delete_duplicate))
            }
        }
    }
}

