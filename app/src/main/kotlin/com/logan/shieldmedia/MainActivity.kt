package com.logan.shieldmedia

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.logan.shieldmedia.designsystem.ShieldTheme
import com.logan.shieldmedia.navigation.AppNavigation

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as ShieldMediaApp).container
        setContent {
            val settings by container.settings.playerSettings.collectAsState(initial = null)
            // Wait for the saved interface size (a few ms) so the first frame isn't drawn at the wrong scale.
            val size = settings?.uiSize ?: return@setContent
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

    override fun onStart() {
        super.onStart()
        // The app can sit in the background for days; refresh channels and the guide when it comes
        // back (channels every 12 h, guide every 8 h; a no-op when both are fresh).
        val container = (application as ShieldMediaApp).container
        lifecycleScope.launch { container.iptv.syncAllIfStale() }
    }
}
