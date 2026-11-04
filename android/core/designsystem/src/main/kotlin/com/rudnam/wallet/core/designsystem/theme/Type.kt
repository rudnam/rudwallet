package com.rudnam.wallet.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.rudnam.wallet.core.designsystem.R

/**
 * Inter, as on the website (web/src/fonts, converted by android/fonts.py).
 * It has Latin only: Japanese falls back to the phone's own font, as the
 * design decided.
 */
val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
)

private val base = TextStyle(fontFamily = Inter, letterSpacing = (-0.005).em)

/**
 * Material 3's type scale in Inter, tuned to the design: semibold headings
 * and figures with tight tracking, regular text at 15sp.
 */
val WalletTypography = Typography(
    displayLarge = base.copy(fontSize = 52.sp, lineHeight = 56.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.035).em),
    displayMedium = base.copy(fontSize = 40.sp, lineHeight = 44.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.035).em),
    displaySmall = base.copy(fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.03).em),
    headlineLarge = base.copy(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.025).em),
    headlineMedium = base.copy(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.025).em),
    headlineSmall = base.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.03).em),
    titleLarge = base.copy(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.02).em),
    titleMedium = base.copy(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = base.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = base.copy(fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = base.copy(fontSize = 15.sp, lineHeight = 21.sp),
    bodySmall = base.copy(fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = base.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = base.copy(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
    labelSmall = base.copy(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
)

/** Figures that line up: tabular, lining numerals (the website's font-variant-numeric). */
fun TextStyle.figures(): TextStyle =
    copy(fontFeatureSettings = listOfNotNull(fontFeatureSettings, "lnum", "tnum").joinToString(", "))
