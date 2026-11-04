package com.rudnam.wallet.core.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.rudnam.wallet.core.designsystem.theme.WalletTheme

/**
 * A thin outlined panel, the design's container for lists and summaries:
 * Material 3's outlined card on the surface colour.
 */
@Composable
fun WalletPanel(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = CardDefaults.outlinedCardColors(containerColor = WalletTheme.colors.surface)
    val border = BorderStroke(CardDefaults.outlinedCardBorder().width, WalletTheme.colors.border)
    if (onClick != null) {
        OutlinedCard(onClick = onClick, modifier = modifier, shape = MaterialTheme.shapes.medium, colors = colors, border = border, content = content)
    } else {
        OutlinedCard(modifier = modifier, shape = MaterialTheme.shapes.medium, colors = colors, border = border, content = content)
    }
}
