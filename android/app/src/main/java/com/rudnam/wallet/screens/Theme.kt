package com.rudnam.wallet.screens

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.rudnam.wallet.R

/**
 * The web app's design tokens (the top of web/src/styles.css, decided in
 * docs/design), so a native screen and a web one are the same app. Change
 * them together.
 */
@Immutable
data class WalletColors(
    val dark: Boolean,
    val bg: Color,
    val surface: Color,
    val sunken: Color,
    val text: Color,
    val muted: Color,
    val border: Color,
    /** The main action (the plus): near-black on light, near-white on dark. */
    val accent: Color,
    val accentText: Color,
    /** Something that needs a look (to check, over budget). Never a category colour. */
    val attention: Color,
    val positive: Color,
    /** How much of an account's colour a card keeps, mixed with black (--card-mix). */
    val cardMix: Float,
)

val LightWalletColors = WalletColors(
    dark = false,
    bg = Color(0xFFF5F5F3),
    surface = Color(0xFFFFFFFF),
    sunken = Color(0xFFEDEDEA),
    text = Color(0xFF111214),
    muted = Color(0xFF696D75),
    border = Color(0xFFE7E7E4),
    accent = Color(0xFF111214),
    accentText = Color(0xFFFFFFFF),
    attention = Color(0xFFB4560B),
    positive = Color(0xFF1C7F4C),
    cardMix = 1f,
)

val DarkWalletColors = WalletColors(
    dark = true,
    bg = Color(0xFF0C0D0F),
    surface = Color(0xFF131416),
    sunken = Color(0xFF1A1B1E),
    text = Color(0xFFEEEFF1),
    muted = Color(0xFF8D919A),
    border = Color(0xFF1E2025),
    accent = Color(0xFFEEEFF1),
    accentText = Color(0xFF0A0B0D),
    attention = Color(0xFFF0A35E),
    positive = Color(0xFF5FC08A),
    cardMix = 0.84f,
)

val LocalWalletColors = staticCompositionLocalOf { LightWalletColors }

/** Inter for text and figures, as on the web; Japanese falls back to the phone's own font. */
val Sans = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
)

private fun materialColors(c: WalletColors): ColorScheme {
    val base = if (c.dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = c.accent,
        onPrimary = c.accentText,
        primaryContainer = c.sunken,
        onPrimaryContainer = c.text,
        secondaryContainer = c.sunken,
        onSecondaryContainer = c.text,
        background = c.bg,
        onBackground = c.text,
        surface = c.bg,
        onSurface = c.text,
        surfaceVariant = c.sunken,
        onSurfaceVariant = c.muted,
        surfaceContainerLowest = c.surface,
        surfaceContainerLow = c.surface,
        surfaceContainer = c.surface,
        surfaceContainerHigh = c.surface,
        surfaceContainerHighest = c.sunken,
        outline = c.border,
        outlineVariant = c.border,
    )
}

/**
 * The web's type on a phone, where its root is 15px: body 15, small 13,
 * section labels 12.75, headings semibold with tight letter spacing.
 */
private fun typography(): Typography {
    val base = TextStyle(fontFamily = Sans, letterSpacing = (-0.005).em())
    val t = Typography()
    return t.copy(
        displayMedium = base.copy(fontSize = 41.sp, lineHeight = 45.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.035).em()),
        headlineSmall = base.copy(fontSize = 19.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.035).em()),
        titleMedium = base.copy(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium),
        bodyLarge = base.copy(fontSize = 15.sp, lineHeight = 22.sp),
        bodyMedium = base.copy(fontSize = 15.sp, lineHeight = 21.sp),
        bodySmall = base.copy(fontSize = 13.sp, lineHeight = 18.sp),
        labelLarge = base.copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
        labelMedium = base.copy(fontSize = 12.75.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
        labelSmall = base.copy(fontSize = 10.8.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium),
    )
}

private fun Double.em() = androidx.compose.ui.unit.TextUnit(this.toFloat(), androidx.compose.ui.unit.TextUnitType.Em)

/** dark: null follows the phone, as the web app's Appearance does when set to Automatic. */
@Composable
fun WalletTheme(dark: Boolean? = null, content: @Composable () -> Unit) {
    val c = if (dark ?: isSystemInDarkTheme()) DarkWalletColors else LightWalletColors
    CompositionLocalProvider(LocalWalletColors provides c) {
        MaterialTheme(colorScheme = materialColors(c), typography = typography(), content = content)
    }
}

/** The app's own colours, for what Material's roles don't name (muted text, attention, cards). */
val colors: WalletColors
    @Composable get() = LocalWalletColors.current

/** Figures that line up in columns: tabular, lining numerals (the web's font-variant-numeric). */
fun TextStyle.figures(): TextStyle =
    copy(fontFeatureSettings = listOfNotNull(fontFeatureSettings, "lnum", "tnum").joinToString(", "))
