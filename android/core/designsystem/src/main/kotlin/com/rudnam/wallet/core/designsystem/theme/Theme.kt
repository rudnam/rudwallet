package com.rudnam.wallet.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * RudWallet's colours (docs/design; the tokens at the top of
 * web/src/styles.css, which the website still uses): flat grounds, one
 * main-action colour, and orange only for what needs a look. Material 3's
 * roles are filled from these, so M3 components look like the app.
 */
@Immutable
data class WalletColors(
    val isDark: Boolean,
    /** The ground behind everything. */
    val ground: Color,
    /** Panels and sheets. */
    val surface: Color,
    /** Tracks, chips, pressed rows. */
    val sunken: Color,
    val text: Color,
    val muted: Color,
    val border: Color,
    /** The main action (the plus, Save): near-black on light, near-white on dark. */
    val action: Color,
    val onAction: Color,
    /** Something that needs a look: to check, over budget. Never a category's colour. */
    val attention: Color,
    val attentionSoft: Color,
    val danger: Color,
    val positive: Color,
    /** Bars and columns in charts: one series each, so one colour (the website's --chart). */
    val chart: Color,
    /** How much of an account's colour its card keeps, mixed with black. */
    val cardMix: Float,
)

val LightWalletColors = WalletColors(
    isDark = false,
    ground = Color(0xFFF5F5F3),
    surface = Color(0xFFFFFFFF),
    sunken = Color(0xFFEDEDEA),
    text = Color(0xFF111214),
    muted = Color(0xFF696D75),
    border = Color(0xFFE7E7E4),
    action = Color(0xFF111214),
    onAction = Color(0xFFFFFFFF),
    attention = Color(0xFFB4560B),
    attentionSoft = Color(0xFFF6E9DE),
    danger = Color(0xFFC0362C),
    positive = Color(0xFF1C7F4C),
    chart = Color(0xFF33363C),
    cardMix = 1f,
)

val DarkWalletColors = WalletColors(
    isDark = true,
    ground = Color(0xFF0C0D0F),
    surface = Color(0xFF131416),
    sunken = Color(0xFF1A1B1E),
    text = Color(0xFFEEEFF1),
    muted = Color(0xFF8D919A),
    border = Color(0xFF1E2025),
    action = Color(0xFFEEEFF1),
    onAction = Color(0xFF0A0B0D),
    attention = Color(0xFFF0A35E),
    attentionSoft = Color(0xFF2E2117),
    danger = Color(0xFFF2867C),
    positive = Color(0xFF5FC08A),
    chart = Color(0xFFC9CBD0),
    cardMix = 0.84f,
)

private val LocalWalletColors = staticCompositionLocalOf { LightWalletColors }

private fun WalletColors.asColorScheme(): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = action,
        onPrimary = onAction,
        primaryContainer = sunken,
        onPrimaryContainer = text,
        secondary = muted,
        onSecondary = surface,
        secondaryContainer = sunken,
        onSecondaryContainer = text,
        tertiary = attention,
        onTertiary = ground,
        tertiaryContainer = attentionSoft,
        onTertiaryContainer = attention,
        background = ground,
        onBackground = text,
        surface = ground,
        onSurface = text,
        surfaceVariant = sunken,
        onSurfaceVariant = muted,
        surfaceTint = Color.Transparent,
        surfaceBright = surface,
        surfaceDim = ground,
        surfaceContainerLowest = surface,
        surfaceContainerLow = surface,
        surfaceContainer = surface,
        surfaceContainerHigh = surface,
        surfaceContainerHighest = sunken,
        inverseSurface = text,
        inverseOnSurface = ground,
        outline = muted,
        outlineVariant = border,
        error = danger,
        onError = surface,
        scrim = Color.Black.copy(alpha = 0.5f),
    )
}

/** The design's radii: 16 for panels and cards, 12 for tiles. */
private val WalletShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * The app's theme. [darkTheme] follows Settings › Appearance; by default,
 * the phone. Dynamic colour is left out on purpose: RudWallet has its own.
 */
@Composable
fun WalletTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (darkTheme) DarkWalletColors else LightWalletColors
    CompositionLocalProvider(LocalWalletColors provides colors) {
        MaterialTheme(
            colorScheme = colors.asColorScheme(),
            typography = WalletTypography,
            shapes = WalletShapes,
            content = content,
        )
    }
}

/** The app's own colours, for what Material's roles don't name (attention, positive, card mix). */
object WalletTheme {
    val colors: WalletColors
        @Composable @ReadOnlyComposable
        get() = LocalWalletColors.current
}
