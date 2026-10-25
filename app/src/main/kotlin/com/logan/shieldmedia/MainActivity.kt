package com.logan.shieldmedia

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.logan.shieldmedia.designsystem.ShieldTheme
import com.logan.shieldmedia.navigation.AppNavigation
import com.logan.shieldmedia.settings.UiSize

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as ShieldMediaApp).container
        setContent {
            val settings by container.settings.playerSettings.collectAsState(initial = null)
            val size = settings?.uiSize ?: UiSize.Standard
            val density = LocalDensity.current
            // Scale the whole interface together: at Android TV's default density everything
            // reads oversized from the couch.
            CompositionLocalProvider(
                LocalDensity provides Density(density.density * size.scale, density.fontScale),
            ) {
                ShieldTheme {
                    AppNavigation(container)
                }
            }
        }
    }
}
