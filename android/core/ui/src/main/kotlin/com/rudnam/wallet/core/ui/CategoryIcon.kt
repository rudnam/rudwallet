package com.rudnam.wallet.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.rudnam.wallet.core.designsystem.icon.LineIcon
import com.rudnam.wallet.core.designsystem.icon.WalletIcons
import com.rudnam.wallet.core.designsystem.theme.WalletTheme
import com.rudnam.wallet.core.designsystem.theme.mixedWith
import com.rudnam.wallet.core.designsystem.theme.parseHexColor
import com.rudnam.wallet.core.model.Category

/**
 * A category's mark: its line icon (or initial) in the category's colour on
 * a faint tint of it. [waiting]: a record still waiting for a category,
 * which gets a dashed tile with a question mark so it stands out.
 */
@Composable
fun CategoryIcon(category: Category?, modifier: Modifier = Modifier, waiting: Boolean = false) {
    val colors = WalletTheme.colors
    val shape = MaterialTheme.shapes.small
    if (waiting) {
        val dash = colors.muted.copy(alpha = 0.45f)
        Box(
            modifier.size(40.dp).drawBehind {
                val w = 1.5.dp.toPx()
                drawRoundRect(
                    color = dash,
                    topLeft = Offset(w / 2, w / 2),
                    size = Size(size.width - w, size.height - w),
                    cornerRadius = CornerRadius(12.dp.toPx()),
                    style = Stroke(width = w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
                )
            },
            contentAlignment = Alignment.Center,
        ) { LineIcon(WalletIcons.question, contentDescription = null, tint = colors.muted, size = 20.dp) }
        return
    }
    val hue = parseHexColor(category?.color)
    val background = hue?.mixedWith(colors.surface, 0.14f) ?: colors.sunken
    val ink = hue?.mixedWith(colors.text, 0.72f) ?: colors.muted
    val edge = hue?.copy(alpha = 0.26f) ?: Color.Transparent
    Box(
        modifier.size(40.dp).clip(shape).background(background).border(1.dp, edge, shape),
        contentAlignment = Alignment.Center,
    ) {
        val glyph = WalletIcons.glyph(category?.icon)
        when {
            glyph != null -> LineIcon(glyph, contentDescription = null, tint = ink, size = 20.dp)
            !category?.name.isNullOrEmpty() -> Text(category!!.name.take(1).uppercase(), style = MaterialTheme.typography.titleSmall, color = ink)
            else -> LineIcon(WalletIcons.dot, contentDescription = null, tint = ink, size = 20.dp)
        }
    }
}
