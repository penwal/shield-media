package com.logan.shieldmedia.designsystem

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

object ShieldColors {
    val Background = Color(0xFF0A0B0F)
    val Surface = Color(0xFF14161C)
    val SurfaceHigh = Color(0xFF1D2028)
    val SurfaceBright = Color(0xFF292D37)
    val OnSurface = Color(0xFFF2F3F5)
    val OnSurfaceMuted = Color(0xFFA3A9B5)
    val OnSurfaceFaint = Color(0xFF6C7280)
    val Accent = Color(0xFFF0B429)
    val OnAccent = Color(0xFF14110A)

    /** Focus treatment: a thin light outline plus lift, not a heavy colored box. */
    val FocusOutline = Color.White.copy(alpha = 0.92f)
    val Hairline = Color.White.copy(alpha = 0.14f)
}

object Spacing {
    /** TV overscan-safe horizontal margin (~5% of a 960dp-wide 1080p canvas). */
    val ScreenH = 56.dp
    val ScreenV = 32.dp
    val RowGap = 28.dp
    val CardGap = 18.dp
}

/**
 * Motion follows the "strong ease-out, never ease-in" rule. D-pad focus moves are the most
 * repeated action in the app, so anything tied to them stays at or under ~150 ms.
 */
object Motion {
    val EaseOut = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)
    val EaseInOut = CubicBezierEasing(0.77f, 0f, 0.175f, 1f)
    val Drawer = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)

    const val FOCUS_MS = 120
    const val ENTER_MS = 220
    const val EXIT_MS = 150
    const val BACKDROP_MS = 450
    /** Backdrops only change once focus has rested, so fast scrolling doesn't strobe images. */
    const val BACKDROP_SETTLE_MS = 280L

    fun <T> enter(): FiniteAnimationSpec<T> = tween(ENTER_MS, easing = EaseOut)
    fun <T> exit(): FiniteAnimationSpec<T> = tween(EXIT_MS, easing = EaseOut)
}
