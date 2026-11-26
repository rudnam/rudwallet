package com.rudnam.wallet.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.rudnam.wallet.core.designsystem.theme.WalletTheme
import com.rudnam.wallet.core.designsystem.theme.figures

/**
 * The title over a group on a page (a panel of rows, a list): muted, in the label style, and a
 * heading for TalkBack. It lines up with the panels' text, 4dp in; [trailing] sits at its end.
 */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text,
            Modifier.weight(1f).semantics { heading() },
            style = MaterialTheme.typography.labelLarge,
            color = WalletTheme.colors.muted,
        )
        trailing?.invoke()
    }
}

/** The name over a field in a form or sheet (Date, Colour, How often): smaller than a [SectionHeader]. */
@Composable
fun FieldLabel(text: String, modifier: Modifier = Modifier, color: Color = WalletTheme.colors.muted) {
    Text(text, modifier, style = MaterialTheme.typography.labelMedium, color = color)
}

/** How big a [StatFigure]'s value is: [Large] on its own, [Medium] for a few side by side. */
enum class FigureSize { Medium, Large }

/** A figure with what it is over it (Spent, Paid this month), read out as one. */
@Composable
fun StatFigure(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    size: FigureSize = FigureSize.Large,
    color: Color = Color.Unspecified,
) {
    Column(modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = WalletTheme.colors.muted)
        val style = if (size == FigureSize.Large) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium
        Text(value, style = style.figures(), color = color)
    }
}
