package com.logan.shieldmedia

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.logan.shieldmedia.designsystem.ShieldTheme
import com.logan.shieldmedia.navigation.AppNavigation

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as ShieldMediaApp).container
        setContent {
            ShieldTheme {
                AppNavigation(container)
            }
        }
    }
}
