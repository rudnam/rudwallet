package com.rudnam.wallet.core.designsystem.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * RudWallet's line icons: drawn on a 24-unit grid with one round 1.7
 * stroke, so they read as one set ("h.01" segments draw as dots). They're
 * the website's (web/src/components/Icon.tsx), so the two stay alike.
 */
object WalletIcons {
    private val cache = HashMap<String, ImageVector>()

    fun line(d: String, stroke: Float = 1.7f): ImageVector = cache.getOrPut("$stroke:$d") {
        ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .addPath(
                pathData = PathParser().parsePathString(d).toNodes(),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = stroke,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            .build()
    }

    const val home = "M3 11l9-7 9 7M5 9.5V20h5v-6h4v6h5V9.5"
    const val wallet = "M5.5 6h13A2.5 2.5 0 0 1 21 8.5v7a2.5 2.5 0 0 1-2.5 2.5h-13A2.5 2.5 0 0 1 3 15.5v-7A2.5 2.5 0 0 1 5.5 6zM3 10h18"
    const val search = "M11 4.5a6.5 6.5 0 1 0 0 13 6.5 6.5 0 1 0 0-13M20 20l-4.4-4.4"
    const val question = "M9.5 9a2.5 2.5 0 1 1 3.5 2.3c-.6.3-1 .9-1 1.6V14M12 17.5h.01"
    const val records = "M4 6h16M4 12h16M4 18h10"
    const val stats = "M4 20h16M7 16v-4M12 16V6M17 16v-7"
    const val settings =
        "M12 8.5a3.5 3.5 0 1 0 0 7 3.5 3.5 0 0 0 0-7zM12 2v3M12 19v3M2 12h3M19 12h3M4.9 4.9l2.1 2.1M17 17l2.1 2.1M4.9 19.1L7 17M17 7l2.1-2.1"
    const val warning = "M12 4l9.5 16h-19zM12 10v4M12 17h.01"
    const val transfer = "M4 8h15l-3.5-3.5M20 16H5l3.5 3.5"
    const val check = "M5 12.5l4.5 4.5L19 7"
    const val download = "M12 4v11M7 10.5l5 5 5-5M5 20h14"
    const val chevron = "M9 6l6 6-6 6"
    const val dot = "M12 12h.01"
    const val plus = "M12 5v14M5 12h14"

    /** Icons a category can wear, by the key stored in its icon field. */
    val glyphs: Map<String, String> = mapOf(
        "food" to "M4 12h16a8 8 0 0 1-16 0zM9 20.5h6M13 4l-2.5 8M18.5 5L14 12",
        "grocery" to "M3 4h2.5l2.2 11h10.6L20.5 8H6.4M9 19.5h.01M17 19.5h.01",
        "shopping" to "M5 8h14l-1 12.5H6zM9 10V6.5a3 3 0 0 1 6 0V10",
        "transit" to "M6 4h12v11a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2zM6 11h12M9 14h.01M15 14h.01M9 21l1.5-4M15 21l-1.5-4",
        "home" to home,
        "bills" to "M9 18h6M10 21h4M12 3a6 6 0 0 0-3.6 10.8c.7.6 1.1 1.3 1.1 2.2h5c0-.9.4-1.6 1.1-2.2A6 6 0 0 0 12 3z",
        "health" to "M12 20s-7.5-4.6-7.5-10.2A4.2 4.2 0 0 1 12 7.2a4.2 4.2 0 0 1 7.5 2.6C19.5 15.4 12 20 12 20z",
        "fun" to "M4 9h16v10H4zM4 9l1.6-4h12.8L20 9M9.5 5l-1.5 4M15 5l-1.5 4",
        "travel" to "M21 3L3 10.5l7.5 3L13.5 21zM10.5 13.5L21 3",
        "other" to "M4 8l8-4 8 4v8.5l-8 4-8-4zM4 8l8 4 8-4M12 12v8.5",
        "salary" to "M3.5 8h17v11.5h-17zM9 8V5h6v3M3.5 13h17",
        "money" to "M3 7h18v10H3zM12 9.5a2.5 2.5 0 1 0 0 5 2.5 2.5 0 1 0 0-5M6.5 12h.01M17.5 12h.01",
        "coffee" to "M5 9h11v5a5 5 0 0 1-5 5h-1a5 5 0 0 1-5-5zM16 10.5h1.5a2.5 2.5 0 0 1 0 5H16M9 3.5v2.5M12.5 3.5v2.5",
        "gift" to "M4 9h16v4H4zM5.5 13h13v7.5h-13zM12 9v11.5M12 9C10 5 6.5 5 6.5 7.5S12 9 12 9zm0 0c2-4 5.5-4 5.5-1.5S12 9 12 9z",
        "school" to "M4 19.5V5a2 2 0 0 1 2-2h14v15H6a2 2 0 0 0-2 2 2 2 0 0 0 2 2h14",
        "phone" to "M7.5 3h9v18h-9zM11 18h2",
        "car" to "M4 16v-4l2-5h12l2 5v4zM4 12h16M5 16v2.5h2.5V16M16.5 16v2.5H19V16M7.5 14h.01M16.5 14h.01",
        "sport" to "M6.5 7v10M3.5 9.5v5M17.5 7v10M20.5 9.5v5M6.5 12h11",
        "beauty" to "M12 3l2 7 7 2-7 2-2 7-2-7-7-2 7-2z",
        "card" to "M3 6h18v12H3zM3 10h18M6.5 14.5h4",
        "bank" to "M3 9.5L12 4l9 5.5M5 10v8M9.5 10v8M14.5 10v8M19 10v8M3 20.5h18",
        "savings" to "M9 3.5h6M7 6.5h10l1 3V19a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2V9.5zM10 13.5h4",
        "kids" to "M12 4a3 3 0 1 0 0 6 3 3 0 1 0 0-6M6 21v-3a6 6 0 0 1 12 0v3",
        "pet" to "M5 14.5a7 5 0 0 0 14 0c0-2.5-3-5-7-5s-7 2.5-7 5zM6 8h.01M10 5h.01M14 5h.01M18 8h.01",
    )

    /** Categories made before line icons stored an emoji; each maps to its closest glyph. */
    private val fromEmoji: Map<String, String> = mapOf(
        "🍜" to "food", "🍔" to "food", "🍽" to "food", "🍱" to "food", "🍣" to "food", "🍕" to "food",
        "🛒" to "grocery", "🛍" to "shopping", "👕" to "shopping",
        "🚆" to "transit", "🚃" to "transit", "🚌" to "transit", "🚇" to "transit", "🚕" to "car", "🚗" to "car", "⛽" to "car",
        "🏠" to "home", "🏡" to "home", "💡" to "bills", "🧾" to "bills", "💊" to "health", "🏥" to "health", "❤" to "health",
        "🎬" to "fun", "🎮" to "fun", "🎵" to "fun", "✈" to "travel", "🧳" to "travel", "📦" to "other",
        "💼" to "salary", "💰" to "money", "💴" to "money", "💵" to "money", "☕" to "coffee", "🎁" to "gift",
        "📚" to "school", "🎓" to "school", "📱" to "phone", "🏋" to "sport", "⚽" to "sport", "💄" to "beauty",
        "💳" to "card", "🏦" to "bank", "🐷" to "savings", "👶" to "kids", "🐶" to "pet", "🐱" to "pet",
    )

    /** The glyph's path for a category's stored icon, or null if it has none. */
    fun glyph(icon: String?): String? {
        if (icon.isNullOrEmpty()) return null
        glyphs[icon]?.let { return it }
        return fromEmoji[icon.replace("️", "").replace("‍", "")]?.let { glyphs[it] }
    }
}
