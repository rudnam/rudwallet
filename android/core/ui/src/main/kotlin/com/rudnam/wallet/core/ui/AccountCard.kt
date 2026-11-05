package com.rudnam.wallet.core.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rudnam.wallet.core.designsystem.theme.WalletTheme
import com.rudnam.wallet.core.designsystem.theme.figures
import com.rudnam.wallet.core.designsystem.theme.ink
import com.rudnam.wallet.core.designsystem.theme.mixedWith
import com.rudnam.wallet.core.designsystem.theme.parseHexColor
import com.rudnam.wallet.core.model.Account
import com.rudnam.wallet.core.model.AccountKind

/** How tall an account's card is (docs/design). */
val AccountCardHeight: Dp = 150.dp

/**
 * An account as a card in its own colour: the front card of Wallet's stack
 * (and the account's own page) shows its kind and its balance in full; one
 * behind it shows its name and balance along its top edge.
 */
@Composable
fun AccountCard(
    account: Account,
    format: MoneyFormat,
    modifier: Modifier = Modifier,
    front: Boolean = true,
    elevation: Dp = if (front) 10.dp else 2.dp,
    onClick: (() -> Unit)? = null,
) {
    val colors = WalletTheme.colors
    val hue = parseHexColor(account.color) ?: colors.muted
    val ink = hue.ink()
    val balance = format.money(account.balance)
    val spoken = stringResource(R.string.core_ui_account_card, account.name, balance)
    val content: @Composable () -> Unit = {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(account.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (front) {
                    val kind = stringResource(kindName(account.kind))
                    if (kind != account.name) Text(kind, style = MaterialTheme.typography.labelMedium, color = ink.copy(alpha = 0.75f), maxLines = 1)
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
    val shape = MaterialTheme.shapes.medium
    val color = hue.mixedWith(Color.Black, colors.cardMix)
    val border = if (colors.isDark) BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)) else null
    val sized = modifier.fillMaxWidth().height(AccountCardHeight)
    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = sized.clearAndSetSemantics {
                contentDescription = spoken
                role = Role.Button
            },
            shape = shape, color = color, contentColor = ink, shadowElevation = elevation, border = border, content = content,
        )
    } else {
        Surface(
            modifier = sized.clearAndSetSemantics { contentDescription = spoken },
            shape = shape, color = color, contentColor = ink, shadowElevation = elevation, border = border, content = content,
        )
    }
}

/** The account kinds' names, as Settings › Accounts calls them. */
fun kindName(kind: AccountKind): Int = when (kind) {
    AccountKind.Bank -> R.string.core_ui_kind_bank
    AccountKind.CreditCard -> R.string.core_ui_kind_credit_card
    AccountKind.EMoney -> R.string.core_ui_kind_e_money
    AccountKind.Cash -> R.string.core_ui_kind_cash
    AccountKind.Savings -> R.string.core_ui_kind_savings
    AccountKind.Other -> R.string.core_ui_kind_other
}
