package com.logan.shieldmedia.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

object ShieldColors {
    val Background = Color(0xFF0B0D12)
    val Surface = Color(0xFF151922)
    val SurfaceHigh = Color(0xFF1F2430)
    val Accent = Color(0xFFE5A00D)
    val OnSurface = Color(0xFFEDEFF4)
    val OnSurfaceMuted = Color(0xFF9AA3B5)
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ShieldTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = ShieldColors.Accent,
            onPrimary = Color.Black,
            background = ShieldColors.Background,
            onBackground = ShieldColors.OnSurface,
            surface = ShieldColors.Surface,
            onSurface = ShieldColors.OnSurface,
            surfaceVariant = ShieldColors.SurfaceHigh,
            onSurfaceVariant = ShieldColors.OnSurfaceMuted,
        ),
        content = content,
    )
}
