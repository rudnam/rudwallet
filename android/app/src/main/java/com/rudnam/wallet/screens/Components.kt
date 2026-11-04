package com.rudnam.wallet.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The web app's building blocks (web/src/styles.css, components/Icon.tsx), drawn natively.

val PanelShape = RoundedCornerShape(16.dp)

/** The web's .card: a thin outlined panel on the surface colour. */
@Composable
fun Panel(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val body: @Composable () -> Unit = { Column(content = content) }
    val border = BorderStroke(1.dp, colors.border)
    if (onClick != null) {
        Surface(onClick = onClick, modifier = modifier, shape = PanelShape, color = colors.surface, border = border, content = body)
    } else {
        Surface(modifier = modifier, shape = PanelShape, color = colors.surface, border = border, content = body)
    }
}

@Composable
fun LineIcon(d: String, modifier: Modifier = Modifier, tint: Color = colors.muted, size: Dp = 20.dp, stroke: Float = 1.7f) {
    Icon(Icons.line(d, stroke), contentDescription = null, modifier = modifier.size(size), tint = tint)
}

/** #rrggbb as a colour, or null. */
fun hexColor(hex: String?): Color? {
    if (hex == null || !Regex("^#[0-9a-fA-F]{6}$").matches(hex)) return null
    return Color(0xFF000000 or hex.substring(1).toLong(16))
}

/** color-mix(in srgb, a p, b). */
fun mix(a: Color, p: Float, b: Color): Color = lerp(b, a, p)

/**
 * A category's mark (web: CategoryIcon and .cat-icon): its line icon, or its
 * initial, in the category's colour on a faint tint of it. A record still
 * waiting for a category gets a dashed tile with a question mark.
 */
@Composable
fun CategoryIcon(category: Category?, waiting: Boolean = false) {
    val shape = RoundedCornerShape(12.dp)
    val c = colors
    if (waiting) {
        val dash = c.muted.copy(alpha = 0.45f)
        Box(
            Modifier.size(40.dp).drawBehind {
                val w = 1.5.dp.toPx()
                drawRoundRect(
                    dash,
                    topLeft = Offset(w / 2, w / 2),
                    size = Size(size.width - w, size.height - w),
                    cornerRadius = CornerRadius(12.dp.toPx()),
                    style = Stroke(width = w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
                )
            },
            contentAlignment = Alignment.Center,
        ) { LineIcon(Icons.question) }
        return
    }
    val hex = hexColor(category?.color)
    val bg = hex?.let { mix(it, 0.14f, c.surface) } ?: c.sunken
    val fg = hex?.let { mix(it, 0.72f, c.text) } ?: c.muted
    val edge = hex?.copy(alpha = 0.26f) ?: Color.Transparent
    Box(Modifier.size(40.dp).clip(shape).background(bg).border(1.dp, edge, shape), contentAlignment = Alignment.Center) {
        val glyph = Icons.glyph(category?.icon)
        when {
            glyph != null -> LineIcon(glyph, tint = fg)
            !category?.name.isNullOrEmpty() ->
                Text(category!!.name.take(1).uppercase(), fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = fg)
            else -> LineIcon(Icons.dot, tint = fg)
        }
    }
}

/** Ink for text on a card's colour: white unless the colour is too light for it (3:1 is enough at that size). */
fun inkOn(card: Color): Color = if (1.05f / (card.luminance() + 0.05f) >= 3f) Color.White else Color(0xFF111214)
