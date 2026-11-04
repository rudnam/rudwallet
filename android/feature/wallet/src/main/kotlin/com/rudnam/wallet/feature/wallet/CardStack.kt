package com.rudnam.wallet.feature.wallet

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rudnam.wallet.core.designsystem.theme.WalletTheme
import com.rudnam.wallet.core.designsystem.theme.figures
import com.rudnam.wallet.core.designsystem.theme.ink
import com.rudnam.wallet.core.designsystem.theme.mixedWith
import com.rudnam.wallet.core.designsystem.theme.parseHexColor
import com.rudnam.wallet.core.model.Account
import com.rudnam.wallet.core.model.AccountKind
import com.rudnam.wallet.core.ui.MoneyFormat

/** How much of each card behind the front one shows. */
private val Edge = 54.dp
private val CardHeight = 150.dp

/**
 * The accounts as a stack of cards in their own colours (docs/design): the
 * first account in front with its balance in full, each one behind showing
 * its top edge, a name and a balance. Tapping a card opens its account.
 */
@Composable
internal fun CardStack(accounts: List<Account>, format: MoneyFormat, onOpen: (Account) -> Unit) {
    if (accounts.isEmpty()) return
    val drawn = accounts.asReversed() // the back card first; accounts[0], the front one, last
    val last = drawn.lastIndex
    val label = stringResource(R.string.feature_wallet_accounts)
    Box(Modifier.fillMaxWidth().height(Edge * last + CardHeight).semantics { contentDescription = label }) {
        drawn.forEachIndexed { i, account ->
            WalletCard(
                account = account,
                front = i == last,
                back = i == 0,
                format = format,
                modifier = Modifier.offset(y = Edge * i),
                onClick = { onOpen(account) },
            )
        }
    }
}

@Composable
private fun WalletCard(account: Account, front: Boolean, back: Boolean, format: MoneyFormat, modifier: Modifier, onClick: () -> Unit) {
    val colors = WalletTheme.colors
    val hue = parseHexColor(account.color) ?: colors.muted
    val ink = hue.ink()
    val balance = format.money(account.balance)
    val spoken = stringResource(R.string.feature_wallet_card, account.name, balance)
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(CardHeight).clearAndSetSemantics {
            contentDescription = spoken
            role = Role.Button
        },
        shape = MaterialTheme.shapes.medium,
        color = hue.mixedWith(Color.Black, colors.cardMix),
        contentColor = ink,
        // The front card lifts off the stack; the back one casts nothing upwards.
        shadowElevation = when {
            front -> 10.dp
            back -> 0.dp
            else -> 2.dp
        },
        border = if (colors.isDark) BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)) else null,
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    account.name,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (front) {
                    val kind = stringResource(kindName(account.kind))
                    if (kind != account.name) {
                        Text(kind, style = MaterialTheme.typography.labelMedium, color = ink.copy(alpha = 0.75f), maxLines = 1)
                    }
                } else {
                    Text(balance, style = MaterialTheme.typography.titleSmall.figures(), maxLines = 1)
                }
            }
            if (front) {
                Spacer(Modifier.weight(1f))
                Text(balance, style = MaterialTheme.typography.headlineMedium.figures().copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
            }
        }
    }
}

private fun kindName(kind: AccountKind): Int = when (kind) {
    AccountKind.Bank -> R.string.feature_wallet_kind_bank
    AccountKind.CreditCard -> R.string.feature_wallet_kind_credit_card
    AccountKind.EMoney -> R.string.feature_wallet_kind_e_money
    AccountKind.Cash -> R.string.feature_wallet_kind_cash
    AccountKind.Savings -> R.string.feature_wallet_kind_savings
    AccountKind.Other -> R.string.feature_wallet_kind_other
}
