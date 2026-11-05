package com.rudnam.wallet.feature.insights

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rudnam.wallet.core.designsystem.component.rememberHaptics
import com.rudnam.wallet.core.designsystem.icon.LineIcon
import com.rudnam.wallet.core.designsystem.icon.WalletIcons
import com.rudnam.wallet.core.designsystem.theme.WalletTheme
import com.rudnam.wallet.core.designsystem.theme.figures
import com.rudnam.wallet.core.model.Category
import com.rudnam.wallet.core.ui.CategoryIcon
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

// Small charts, one series each, so one chart colour; status colours appear
// only in meters, always with an icon and words. Values are written out in
// text, so nothing is told by colour alone (docs/design).

/** One bar: a category's total. [category] null is "no category". */
data class BarItem(val key: Long?, val category: Category?, val label: String, val amount: Long)

/** Spending (or income) by category: a ranked list, each with its bar, its amount and its share. */
@Composable
internal fun CategoryBars(items: List<BarItem>, money: (Long) -> String, share: (String, Int) -> String, onSelect: ((BarItem) -> Unit)? = null) {
    val max = maxOf(items.maxOfOrNull { it.amount } ?: 1, 1)
    val total = items.sumOf { it.amount }
    Column {
        for (item in items) {
            val percent = if (total > 0) (item.amount * 100.0 / total).roundToInt() else 0
            val fraction by animateFloatAsState(maxOf(item.amount.toFloat() / max, 0.01f), label = "bar")
            Row(
                Modifier.fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .then(if (onSelect != null) Modifier.clickable { onSelect(item) } else Modifier)
                    .padding(vertical = 8.dp)
                    .semantics(mergeDescendants = true) { contentDescription = share("${item.label}: ${money(item.amount)}", percent) },
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CategoryIcon(item.category, Modifier.size(36.dp), waiting = item.key == null)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(item.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(money(item.amount), style = MaterialTheme.typography.bodyMedium.figures(), fontWeight = FontWeight.SemiBold)
                        Text("$percent%", Modifier.width(44.dp), style = MaterialTheme.typography.bodySmall.figures(), color = WalletTheme.colors.muted, textAlign = TextAlign.End)
                    }
                    Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(WalletTheme.colors.sunken)) {
                        Box(Modifier.fillMaxWidth(fraction).height(8.dp).clip(RoundedCornerShape(4.dp)).background(WalletTheme.colors.chart))
                    }
                }
            }
        }
    }
}

/**
 * Spending per day, month or year: columns, with the amount of the one
 * tapped (or dragged across) written under them. One column far above the
 * rest (rent, a commuter pass) would flatten every other one, so then the
 * scale fits the rest and that column runs off the top, broken, with its
 * amount above it.
 */
@Composable
internal fun ColumnChart(
    values: List<Long>,
    labelFor: (Int) -> String,
    tick: (index: Int, slotDp: Float) -> String?,
    money: (Long) -> String,
    compact: (Long) -> String,
    description: String,
    hint: String,
    modifier: Modifier = Modifier,
) {
    var selected by rememberSaveable(values.size) { mutableStateOf<Int?>(null) }
    val haptics = rememberHaptics()
    val measurer = rememberTextMeasurer()
    val colors = WalletTheme.colors
    val tickStyle = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, color = colors.muted).figures()
    val overStyle = tickStyle.copy(color = colors.text, fontWeight = FontWeight.SemiBold)

    val sorted = values.sortedDescending()
    val first = sorted.getOrElse(0) { 0 }
    val second = sorted.getOrElse(1) { 0 }
    val capped = second > 0 && first > 3 * second
    val max = niceMax(if (capped) (second * 1.1).toLong() else maxOf(values.maxOrNull() ?: 0, 0))
    val axis = remember(max, compact) { listOf(0L, max / 2, max).map { it to compact(it) } }

    Column(modifier) {
        Canvas(
            Modifier.fillMaxWidth().height(180.dp)
                .semantics { contentDescription = "$description. $hint" }
                .pointerInput(values) {
                    val left = 44.dp.toPx()
                    fun at(x: Float): Int? {
                        if (values.isEmpty() || x < left) return null
                        return ((x - left) / ((size.width - left) / values.size)).toInt().coerceIn(values.indices)
                    }
                    detectTapGestures { offset ->
                        val i = at(offset.x)
                        selected = if (i == selected) null else i
                        if (i != null) haptics.select()
                    }
                }
                .pointerInput(values) {
                    val left = 44.dp.toPx()
                    fun at(x: Float): Int = ((x - left) / ((size.width - left) / values.size)).toInt().coerceIn(values.indices)
                    detectHorizontalDragGestures { change, _ ->
                        if (values.isEmpty()) return@detectHorizontalDragGestures
                        change.consume()
                        val i = at(change.position.x)
                        if (i != selected) {
                            selected = i
                            haptics.select()
                        }
                    }
                },
        ) {
            drawColumns(values, max, capped, axis, selected, tick, compact, measurer, tickStyle, overStyle, ChartColors(colors.chart, colors.text, colors.surface, colors.border))
        }
        val s = selected
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (s != null && s in values.indices) {
                Text(labelFor(s), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                Text(money(values[s]), style = MaterialTheme.typography.bodySmall.figures())
            } else {
                Text(hint, style = MaterialTheme.typography.bodySmall, color = colors.muted)
            }
        }
    }
}

private fun DrawScope.drawColumns(
    values: List<Long>,
    max: Long,
    capped: Boolean,
    axis: List<Pair<Long, String>>,
    selected: Int?,
    tick: (Int, Float) -> String?,
    compact: (Long) -> String,
    measurer: TextMeasurer,
    tickStyle: TextStyle,
    overStyle: TextStyle,
    colors: ChartColors,
) {
    val left = 44.dp.toPx()
    val bottom = 20.dp.toPx()
    val top = (if (capped) 26 else 12).dp.toPx()
    val h = size.height
    val w = size.width
    fun y(v: Long) = top + (h - top - bottom) * (1 - v.toFloat() / max)
    for ((v, label) in axis) {
        drawLine(colors.grid, Offset(left, y(v)), Offset(w, y(v)), strokeWidth = 1.dp.toPx())
        val text = measurer.measure(label, tickStyle)
        drawText(text, topLeft = Offset(left - 6.dp.toPx() - text.size.width, y(v) - text.size.height / 2))
    }
    if (values.isEmpty()) return
    val slot = (w - left) / values.size
    val slotDp = slot / density
    // A 2dp gap between columns, and none wider than 24dp.
    val barW = min(slot - 2.dp.toPx(), 24.dp.toPx()).coerceAtLeast(1f)
    val radius = min(4.dp.toPx(), barW / 2)
    values.forEachIndexed { i, v ->
        val x = left + i * slot + (slot - barW) / 2
        val over = v > max
        val vy = if (over) top - 6.dp.toPx() else y(v)
        if (v > 0) drawColumn(x, vy, barW, y(0) - vy, radius, if (i == selected) colors.selected else colors.column)
        if (over) {
            val gap = Path().apply {
                moveTo(x - 2.dp.toPx(), top + 8.dp.toPx()); lineTo(x + barW + 2.dp.toPx(), top + 3.dp.toPx())
                moveTo(x - 2.dp.toPx(), top + 13.dp.toPx()); lineTo(x + barW + 2.dp.toPx(), top + 8.dp.toPx())
            }
            drawPath(gap, colors.surface, style = Stroke(3.dp.toPx(), cap = StrokeCap.Butt))
            val text = measurer.measure(compact(v), overStyle)
            val cx = maxOf(x + barW / 2, left + 18.dp.toPx())
            drawText(text, topLeft = Offset(cx - text.size.width / 2, top - 10.dp.toPx() - text.size.height))
        }
        tick(i, slotDp)?.let { label ->
            val text = measurer.measure(label, tickStyle)
            drawText(text, topLeft = Offset(left + i * slot + slot / 2 - text.size.width / 2, h - text.size.height))
        }
    }
}

/** The column chart's colours: [column]s, the [selected] one, the [surface] it's drawn on, and the [grid]. */
private data class ChartColors(val column: Color, val selected: Color, val surface: Color, val grid: Color)

private fun DrawScope.drawColumn(x: Float, y: Float, width: Float, height: Float, radius: Float, color: Color) {
    val r = min(radius, height)
    val path = Path().apply {
        addRoundRect(RoundRect(x, y, x + width, y + height, topLeftCornerRadius = CornerRadius(r), topRightCornerRadius = CornerRadius(r)))
    }
    drawPath(path, color)
}

/** A round top for the scale: 1, 2, 2.5 or 5 of a power of ten at or above [v]. */
internal fun niceMax(v: Long): Long {
    if (v <= 0) return 1
    val p = 10.0.pow(floor(log10(v.toDouble())))
    for (m in listOf(1.0, 2.0, 2.5, 5.0, 10.0)) if (v <= m * p) return (m * p).toLong()
    return (10 * p).toLong()
}

/** A budget's meter: the fill turns to attention at 80% and to danger past 100%, with an icon and words. */
@Composable
internal fun Meter(spent: Long, limit: Long, money: (Long) -> String, of: (String, String) -> String, left: (String) -> String, overBy: (String) -> String) {
    val colors = WalletTheme.colors
    val ratio = if (limit > 0) spent.toFloat() / limit else 0f
    val over = ratio > 1f
    val warn = !over && ratio >= 0.8f
    val tint = when {
        over -> colors.danger
        warn -> colors.attention
        else -> colors.chart
    }
    val fill by animateFloatAsState(ratio.coerceIn(0f, 1f), label = "meter")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(colors.sunken)) {
            Box(Modifier.fillMaxWidth(fill).height(4.dp).clip(RoundedCornerShape(2.dp)).background(tint))
        }
        Row(Modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
            Text(of(money(spent), money(limit)), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall.figures(), color = colors.muted)
            if (over || warn) {
                LineIcon(if (over) WalletIcons.stop else WalletIcons.warning, contentDescription = null, tint = if (over) colors.danger else colors.text, size = 14.dp)
                Spacer(Modifier.width(4.dp))
            }
            Text(
                if (over) overBy(money(spent - limit)) else left(money(limit - spent)),
                style = MaterialTheme.typography.bodySmall.figures(),
                fontWeight = FontWeight.SemiBold,
                color = if (over) colors.danger else colors.text,
            )
        }
    }
}
