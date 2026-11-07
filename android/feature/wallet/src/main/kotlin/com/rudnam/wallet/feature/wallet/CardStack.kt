package com.rudnam.wallet.feature.wallet

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.rudnam.wallet.core.designsystem.component.rememberHaptics
import com.rudnam.wallet.core.model.Account
import com.rudnam.wallet.core.ui.AccountCard
import com.rudnam.wallet.core.ui.AccountCardHeight
import com.rudnam.wallet.core.ui.MoneyFormat
import com.rudnam.wallet.core.ui.accountCardKey
import com.rudnam.wallet.core.ui.sharedBounds
import kotlin.math.roundToInt

/** How much of each card behind the front one shows. */
private val Edge = 54.dp

/**
 * The accounts as a stack of cards in their own colours (docs/design): the
 * first account in front with its balance in full, each one behind showing
 * its top edge. Tapping a card opens its account. Holding one lifts it (a
 * detent), dragging moves it through the stack (a tick at each place), and
 * letting go keeps the new order ([onReorder], the front card first).
 */
@Composable
internal fun CardStack(accounts: List<Account>, format: MoneyFormat, onOpen: (Account) -> Unit, onReorder: (List<Long>) -> Unit) {
    if (accounts.isEmpty()) return
    val byId = accounts.associateBy { it.id }
    // Top to bottom as drawn: the back card first, the front one (accounts[0]) last. The gesture
    // reads it directly (it's state), so each move sees the last one even between frames.
    var order by remember(accounts) { mutableStateOf(accounts.map { it.id }.asReversed()) }
    var lifted by remember { mutableStateOf<Long?>(null) }
    var from by remember { mutableIntStateOf(0) }
    var dragged by remember { mutableFloatStateOf(0f) }
    val haptics = rememberHaptics()
    val edge = with(LocalDensity.current) { Edge.toPx() }
    val last = order.lastIndex
    val label = stringResource(R.string.feature_wallet_accounts)

    Box(Modifier.fillMaxWidth().height(Edge * last + AccountCardHeight).semantics { contentDescription = label }) {
        order.forEachIndexed { i, id ->
            val account = byId[id] ?: return@forEachIndexed
            key(id) {
                val isLifted = lifted == id
                val top by animateDpAsState(Edge * i, label = "card")
                AccountCard(
                    account = account,
                    format = format,
                    front = i == last,
                    elevation = when {
                        isLifted -> 16.dp
                        i == last -> 10.dp
                        i == 0 -> 0.dp // the back card casts nothing upwards
                        else -> 2.dp
                    },
                    // Tapping and holding are handled here together: a card's own click would claim the
                    // press first, and holding could then never lift it. TalkBack gets the tap as an action.
                    modifier = Modifier
                        .zIndex(if (isLifted) order.size.toFloat() else i.toFloat())
                        .offset { if (isLifted) IntOffset(0, (from * edge + dragged).roundToInt()) else IntOffset(0, top.roundToPx()) }
                        .scale(if (isLifted) 1.03f else 1f)
                        // Opening the account, the card grows into the one at the top of its page.
                        .sharedBounds(accountCardKey(id), MaterialTheme.shapes.medium)
                        .semantics {
                            role = Role.Button
                            onClick { onOpen(account); true }
                        }
                        .pointerInput(id) { detectTapGestures(onTap = { onOpen(account) }) }
                        .pointerInput(id, accounts.size) {
                            if (accounts.size < 2) return@pointerInput
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    lifted = id
                                    from = order.indexOf(id)
                                    dragged = 0f
                                    haptics.threshold()
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    dragged += amount.y
                                    val now = order.indexOf(id)
                                    val slot = ((from * edge + dragged) / edge).roundToInt().coerceIn(0, order.lastIndex)
                                    if (slot != now) {
                                        order = order.toMutableList().apply { add(slot, removeAt(now)) }
                                        haptics.select()
                                    }
                                },
                                onDragEnd = {
                                    lifted = null
                                    val front = order.asReversed()
                                    if (front != accounts.map { it.id }) onReorder(front)
                                },
                                onDragCancel = {
                                    lifted = null
                                    order = accounts.map { it.id }.asReversed()
                                },
                            )
                        },
                )
            }
        }
    }
}
