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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import com.rudnam.wallet.core.model.TransactionKind
import com.rudnam.wallet.core.ui.CategoryIcon
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
                Modifier.fillMaxWidth().background(colors.surface).padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CategoryIcon(state.wallet.category(item.categoryId), waiting = item.categoryId == null)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        title,
                        Modifier.clickable { navigation.openRecord(t) },
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(format.dateTime(t.occurredAt, today, yesterday, now), style = MaterialTheme.typography.bodySmall, color = colors.muted)
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
        // The category and Confirm on a line of their own, so neither is squeezed by the amount.
        Row(
            Modifier.fillMaxWidth().padding(start = 68.dp, end = 16.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CategoryPicker(item, state, actions)
            Spacer(Modifier.weight(1f))
            FilledTonalButton(
                onClick = { if (t.amount > 0) actions.confirm(item) else navigation.openRecord(t) },
                enabled = !state.busy && item.record.duplicateOfId == null && item.categoryId != null,
            ) { Text(stringResource(R.string.feature_activity_confirm), maxLines = 1) }
        }
        if (t.note.isNotEmpty()) Text(t.note, Modifier.padding(start = 68.dp, end = 16.dp, bottom = 8.dp), style = MaterialTheme.typography.bodySmall, color = colors.muted)
        if (t.duplicateOfId != null) Duplicate(item, state, format, actions)
        if (item.overriding) {
            Row(Modifier.padding(start = 56.dp, end = 16.dp, bottom = 8.dp).clickable { actions.setRemember(t.id, !item.remember) }, verticalAlignment = Alignment.CenterVertically) {
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

@Composable
private fun CategoryPicker(item: ReviewItem, state: ActivityUiState.Ready, actions: ActivityActions) {
    var open by rememberSaveable { mutableStateOf(false) }
    val t = item.record
    val name = state.wallet.category(item.categoryId)?.name
    Box {
        FilterChip(
            selected = name != null,
            onClick = { open = true },
            label = { Text(name ?: stringResource(R.string.feature_activity_category), maxLines = 1) },
            trailingIcon = { LineIcon(WalletIcons.expand, contentDescription = null, size = 16.dp) },
            colors = if (name == null) {
                FilterChipDefaults.filterChipColors(labelColor = WalletTheme.colors.attention)
            } else {
                FilterChipDefaults.filterChipColors()
            },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (c in state.wallet.categoriesInOrder().filter { it.kind.apiName == t.kind.apiName }) {
                // Subcategories sit indented under their main category.
                DropdownMenuItem(
                    text = { Text(c.name) },
                    leadingIcon = { CategoryIcon(c, Modifier.padding(start = if (state.wallet.isSubcategory(c)) 20.dp else 0.dp)) },
                    onClick = {
                        open = false
                        actions.pickCategory(t.id, c.id)
                    },
                )
            }
        }
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

