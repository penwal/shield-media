package com.logan.shieldmedia.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme

// Inter is a variable font: one file, every weight. Large text uses the display optical size,
// which tightens spacing and sharpens details the way the typeface intends at that size.
private fun inter(weight: Int, opticalSize: TextUnit) = Font(
    R.font.inter,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(
        FontVariation.weight(weight),
        FontVariation.opticalSizing(opticalSize),
    ),
)

val InterText = FontFamily(
    inter(400, 14.sp), inter(500, 14.sp), inter(600, 14.sp), inter(700, 14.sp),
)
val InterDisplay = FontFamily(
    inter(500, 32.sp), inter(600, 32.sp), inter(700, 32.sp), inter(800, 32.sp),
)

private fun style(family: FontFamily, weight: Int, size: Int, line: Int, tracking: Double = 0.0) = TextStyle(
    fontFamily = family,
    fontWeight = FontWeight(weight),
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.em,
)

private val ShieldTypography = Typography(
    displayLarge = style(InterDisplay, 700, 57, 64, -0.025),
    displayMedium = style(InterDisplay, 700, 45, 52, -0.02),
    displaySmall = style(InterDisplay, 700, 36, 44, -0.02),
    headlineLarge = style(InterDisplay, 700, 32, 40, -0.015),
    headlineMedium = style(InterDisplay, 600, 28, 36, -0.01),
    headlineSmall = style(InterDisplay, 600, 24, 32, -0.01),
    titleLarge = style(InterText, 600, 20, 28, -0.005),
    titleMedium = style(InterText, 600, 16, 24),
    titleSmall = style(InterText, 600, 14, 20),
    bodyLarge = style(InterText, 400, 16, 24),
    bodyMedium = style(InterText, 400, 14, 20),
    bodySmall = style(InterText, 400, 12, 16),
    labelLarge = style(InterText, 500, 14, 20),
    labelMedium = style(InterText, 600, 12, 16, 0.02),
    labelSmall = style(InterText, 600, 11, 16, 0.06),
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ShieldTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = ShieldColors.Accent,
            onPrimary = ShieldColors.OnAccent,
            background = ShieldColors.Background,
            onBackground = ShieldColors.OnSurface,
            surface = ShieldColors.Surface,
            onSurface = ShieldColors.OnSurface,
            surfaceVariant = ShieldColors.SurfaceHigh,
            onSurfaceVariant = ShieldColors.OnSurfaceMuted,
        ),
        typography = ShieldTypography,
        content = content,
    )
}
