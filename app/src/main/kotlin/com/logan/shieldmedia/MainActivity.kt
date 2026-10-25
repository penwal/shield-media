package com.logan.shieldmedia

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.logan.shieldmedia.designsystem.ShieldTheme
import com.logan.shieldmedia.navigation.AppNavigation
import com.logan.shieldmedia.settings.SettingsRepository

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = SettingsRepository(applicationContext)
        setContent {
            ShieldTheme {
                AppNavigation(settings)
            }
        }
    }
}
