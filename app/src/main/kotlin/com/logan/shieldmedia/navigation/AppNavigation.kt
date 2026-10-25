package com.logan.shieldmedia.navigation

import android.net.Uri
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.delay
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.NavigationDrawer
import androidx.tv.material3.NavigationDrawerItem
import androidx.tv.material3.Text
import com.logan.shieldmedia.designsystem.ShieldColors
import com.logan.shieldmedia.screens.HomeScreen
import com.logan.shieldmedia.screens.PlaceholderScreen
import com.logan.shieldmedia.screens.PlayerRoute
import com.logan.shieldmedia.screens.PlayerTestScreen
import com.logan.shieldmedia.screens.SettingsScreen
import com.logan.shieldmedia.settings.SettingsRepository

private const val MAIN_ROUTE = "main"
private const val PLAYER_ROUTE = "player?uri={uri}&title={title}"
private const val PLAYER_TEST_ROUTE = "player_test"

typealias PlayHandler = (uri: String, title: String) -> Unit

/** Root graph: the drawer-based app, plus full-screen routes (the player) that sit outside the drawer. */
@Composable
fun AppNavigation(settingsRepository: SettingsRepository) {
    val rootNav = rememberNavController()
    NavHost(
        navController = rootNav,
        startDestination = MAIN_ROUTE,
        enterTransition = { fadeIn(tween(200)) },
        exitTransition = { fadeOut(tween(150)) },
    ) {
        composable(MAIN_ROUTE) {
            MainScaffold(settingsRepository) { uri, title ->
                rootNav.navigate("player?uri=${Uri.encode(uri)}&title=${Uri.encode(title)}")
            }
        }
        composable(
            PLAYER_ROUTE,
            arguments = listOf(
                navArgument("uri") { type = NavType.StringType },
                navArgument("title") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry ->
            PlayerRoute(
                uri = entry.arguments?.getString("uri").orEmpty(),
                title = entry.arguments?.getString("title").orEmpty(),
                settingsRepository = settingsRepository,
                onExit = { rootNav.popBackStack() },
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun MainScaffold(settingsRepository: SettingsRepository, onPlay: PlayHandler) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    // Sub-screens highlight the drawer entry they belong to.
    val selectedRoute = when (currentRoute) {
        PLAYER_TEST_ROUTE -> Destination.Settings.route
        else -> currentRoute
    }

    // Picking a drawer entry keeps focus in the drawer (TV convention). Any other navigation —
    // app start, opening a sub-screen, Back — must land focus in the content, not on "Home".
    val contentFocus = remember { FocusRequester() }
    var contentHasFocus by remember { mutableStateOf(false) }
    var navigatedFromDrawer by remember { mutableStateOf(false) }
    LaunchedEffect(currentRoute) {
        if (navigatedFromDrawer) {
            navigatedFromDrawer = false
            return@LaunchedEffect
        }
        delay(250) // let the screen transition finish so focus doesn't land on the outgoing screen
        if (!contentHasFocus) runCatching { contentFocus.requestFocus() }
    }

    NavigationDrawer(
        modifier = Modifier.background(ShieldColors.Background),
        drawerContent = {
            Column(
                Modifier
                    .fillMaxHeight()
                    .background(ShieldColors.Surface)
                    .padding(12.dp)
                    .focusRestorer()
                    .focusGroup(),
                verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
            ) {
                Destination.entries.forEach { dest ->
                    NavigationDrawerItem(
                        selected = selectedRoute == dest.route,
                        onClick = {
                            if (currentRoute != dest.route) {
                                navigatedFromDrawer = true
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
        Box(
            Modifier
                .fillMaxSize()
                .background(ShieldColors.Background)
                .onFocusChanged { contentHasFocus = it.hasFocus }
                .focusRequester(contentFocus),
        ) {
            NavHost(
                navController = navController,
                startDestination = Destination.Home.route,
                enterTransition = { fadeIn(tween(200)) },
                exitTransition = { fadeOut(tween(150)) },
            ) {
                composable(Destination.Home.route) { HomeScreen() }
                composable(Destination.Movies.route) { PlaceholderScreen("Movies", "Plex movie libraries") }
                composable(Destination.Shows.route) { PlaceholderScreen("TV Shows", "Plex TV libraries") }
                composable(Destination.LiveTv.route) { PlaceholderScreen("Live TV", "IPTV channels and guide") }
                composable(Destination.IptvVod.route) { PlaceholderScreen("IPTV VOD", "Xtream movies and series") }
                composable(Destination.Search.route) { PlaceholderScreen("Search", "Search Plex and IPTV") }
                composable(Destination.Settings.route) {
                    SettingsScreen(settingsRepository, onOpenPlayerTest = { navController.navigate(PLAYER_TEST_ROUTE) })
                }
                composable(PLAYER_TEST_ROUTE) { PlayerTestScreen(onPlay) }
            }
        }
    }
}
