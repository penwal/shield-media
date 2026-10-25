package com.logan.shieldmedia.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.NavigationDrawer
import androidx.tv.material3.NavigationDrawerItem
import androidx.tv.material3.Text
import com.logan.shieldmedia.designsystem.ShieldColors
import com.logan.shieldmedia.screens.HomeScreen
import com.logan.shieldmedia.screens.PlaceholderScreen
import com.logan.shieldmedia.screens.SettingsScreen
import com.logan.shieldmedia.settings.SettingsRepository

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AppNavigation(settingsRepository: SettingsRepository) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    NavigationDrawer(
        modifier = Modifier.background(ShieldColors.Background),
        drawerContent = {
            Column(
                Modifier
                    .fillMaxHeight()
                    .background(ShieldColors.Surface)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
            ) {
                Destination.entries.forEach { dest ->
                    NavigationDrawerItem(
                        selected = currentRoute == dest.route,
                        onClick = {
                            if (currentRoute != dest.route) {
                                navController.navigate(dest.route) {
                                    popUpTo(Destination.Home.route)
                                    launchSingleTop = true
                                }
                            }
                        },
                        leadingContent = { Icon(dest.icon, contentDescription = dest.label) },
                    ) {
                        Text(dest.label)
                    }
                }
            }
        },
    ) {
        Box(Modifier.fillMaxSize().background(ShieldColors.Background)) {
            NavHost(navController = navController, startDestination = Destination.Home.route) {
                composable(Destination.Home.route) { HomeScreen() }
                composable(Destination.Movies.route) { PlaceholderScreen("Movies", "Plex movie libraries") }
                composable(Destination.Shows.route) { PlaceholderScreen("TV Shows", "Plex TV libraries") }
                composable(Destination.LiveTv.route) { PlaceholderScreen("Live TV", "IPTV channels and guide") }
                composable(Destination.IptvVod.route) { PlaceholderScreen("IPTV VOD", "Xtream movies and series") }
                composable(Destination.Search.route) { PlaceholderScreen("Search", "Search Plex and IPTV") }
                composable(Destination.Settings.route) { SettingsScreen(settingsRepository) }
            }
        }
    }
}
