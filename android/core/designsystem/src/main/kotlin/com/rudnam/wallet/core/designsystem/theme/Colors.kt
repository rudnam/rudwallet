package com.rudnam.wallet.core.designsystem.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/** #rrggbb as a colour, or null for anything else. */
fun parseHexColor(hex: String?): Color? {
    if (hex == null || !Regex("^#[0-9a-fA-F]{6}$").matches(hex)) return null
    return Color(0xFF000000 or hex.substring(1).toLong(16))
}

/** CSS's color-mix(in srgb, a p, b): [fraction] of this colour, the rest [other]. */
fun Color.mixedWith(other: Color, fraction: Float): Color = lerp(other, this, fraction)

/** Text on this colour: white unless the colour is too light for it (3:1 is enough at card sizes). */
fun Color.ink(): Color = if (1.05f / (luminance() + 0.05f) >= 3f) Color.White else Color(0xFF111214)
