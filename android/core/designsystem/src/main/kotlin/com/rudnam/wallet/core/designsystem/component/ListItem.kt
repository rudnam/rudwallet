package com.rudnam.wallet.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.rudnam.wallet.core.designsystem.icon.LineIcon
import com.rudnam.wallet.core.designsystem.theme.WalletTheme
import com.rudnam.wallet.core.designsystem.theme.figures

/** How big a row's leading icon or tile is, on every list. */
val ListLeadingSize = 40.dp

/**
 * A row in a panel, the same on every list: Material 3's list item on the panel colour, the
 * headline in the regular text style (15sp), what's under it small and muted, and what's at its
 * end (usually an amount) in figures that line up. Make it tappable with [modifier].
 */
@Composable
fun WalletListItem(
    headline: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    supporting: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    /** Chosen (while choosing several): on the sunken colour. */
    selected: Boolean = false,
) {
    val colors = WalletTheme.colors
    val type = MaterialTheme.typography
    ListItem(
        modifier = modifier,
        colors = ListItemDefaults.colors(
            containerColor = if (selected) colors.sunken else colors.surface,
            headlineColor = colors.text,
            supportingColor = colors.muted,
            trailingIconColor = colors.text,
        ),
        leadingContent = leading,
        headlineContent = { ProvideTextStyle(type.bodyMedium, headline) },
        supportingContent = supporting?.let { { ProvideTextStyle(type.bodySmall, it) } },
        trailingContent = trailing?.let {
            { CompositionLocalProvider(LocalContentColor provides colors.text) { ProvideTextStyle(type.bodyMedium.figures(), it) } }
        },
    )
}

/** A line icon on a sunken tile, a row's leading picture when it has no category. */
@Composable
fun IconTile(glyph: String, modifier: Modifier = Modifier, tint: Color = WalletTheme.colors.text) {
    Box(modifier.size(ListLeadingSize).background(WalletTheme.colors.sunken, MaterialTheme.shapes.small), contentAlignment = Alignment.Center) {
        LineIcon(glyph, contentDescription = null, tint = tint, size = 20.dp)
    }
}
