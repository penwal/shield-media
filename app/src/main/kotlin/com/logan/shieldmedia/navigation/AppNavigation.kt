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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.ModalNavigationDrawer
import androidx.tv.material3.NavigationDrawerItem
import androidx.tv.material3.Text
import com.logan.shieldmedia.AppContainer
import com.logan.shieldmedia.designsystem.LocalContentFocusGate
import com.logan.shieldmedia.designsystem.ShieldColors
import com.logan.shieldmedia.plex.PlexItem
import com.logan.shieldmedia.plex.ui.PlexDetailsScreen
import com.logan.shieldmedia.plex.ui.PlexGate
import com.logan.shieldmedia.plex.ui.PlexHomeScreen
import com.logan.shieldmedia.plex.ui.PlexLibraryScreen
import com.logan.shieldmedia.plex.ui.PlexSearchScreen
import com.logan.shieldmedia.plex.ui.PlexSignInScreen
import com.logan.shieldmedia.screens.PlaceholderScreen
import com.logan.shieldmedia.screens.PlayerRoute
import com.logan.shieldmedia.screens.PlayerTestScreen
import com.logan.shieldmedia.screens.SettingsScreen
import kotlinx.coroutines.delay

private const val MAIN_ROUTE = "main"
private const val PLAYER_ROUTE = "player?uri={uri}&title={title}&start={start}"
private const val PLEX_SIGN_IN_ROUTE = "plex_sign_in"
private const val PLAYER_TEST_ROUTE = "player_test"
private const val PLEX_ITEM_ROUTE = "plex/item/{ratingKey}"

/** Width of the collapsed navigation rail; content starts to its right. */
private val RailWidth = 80.dp

typealias PlayHandler = (uri: String, title: String, startMs: Long) -> Unit

/** Root graph: the drawer-based app, plus full-screen routes (player, sign-in) outside the drawer. */
@Composable
fun AppNavigation(container: AppContainer) {
    val rootNav = rememberNavController()
    NavHost(
        navController = rootNav,
        startDestination = MAIN_ROUTE,
        enterTransition = { fadeIn(tween(200)) },
        exitTransition = { fadeOut(tween(150)) },
    ) {
        composable(MAIN_ROUTE) {
            MainScaffold(
                container = container,
                onPlay = { uri, title, startMs ->
                    rootNav.navigate("player?uri=${Uri.encode(uri)}&title=${Uri.encode(title)}&start=$startMs")
                },
                onPlexSignIn = { rootNav.navigate(PLEX_SIGN_IN_ROUTE) },
            )
        }
        composable(
            PLAYER_ROUTE,
            arguments = listOf(
                navArgument("uri") { type = NavType.StringType },
                navArgument("title") { type = NavType.StringType; defaultValue = "" },
                navArgument("start") { type = NavType.LongType; defaultValue = 0L },
            ),
        ) { entry ->
            PlayerRoute(
                uri = entry.arguments?.getString("uri").orEmpty(),
                title = entry.arguments?.getString("title").orEmpty(),
                startMs = entry.arguments?.getLong("start") ?: 0L,
                settingsRepository = container.settings,
                onExit = { rootNav.popBackStack() },
            )
        }
        composable(PLEX_SIGN_IN_ROUTE) {
            PlexSignInScreen(
                session = container.plexSession,
                onDone = { rootNav.popBackStack() },
                onBack = { rootNav.popBackStack() },
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun MainScaffold(container: AppContainer, onPlay: PlayHandler, onPlexSignIn: () -> Unit) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    // Sub-screens (details, player test) keep highlighting the section they were opened from.
    var lastSection by rememberSaveable { mutableStateOf(Destination.Home.route) }
    val topLevel = Destination.entries.firstOrNull { it.route == currentRoute }
    LaunchedEffect(topLevel) { topLevel?.let { lastSection = it.route } }

    // Picking a drawer entry keeps focus in the drawer (TV convention). Any other navigation —
    // app start, opening a sub-screen, Back — must land focus in the content, not on "Home".
    val contentFocus = remember { FocusRequester() }
    var contentHasFocus by remember { mutableStateOf(false) }
    var drawerHasFocus by remember { mutableStateOf(false) }
    var navigatedFromDrawer by remember { mutableStateOf(false) }
    LaunchedEffect(currentRoute) {
        if (navigatedFromDrawer) {
            navigatedFromDrawer = false
            return@LaunchedEffect
        }
        delay(250) // let the screen transition finish so focus doesn't land on the outgoing screen
        if (!contentHasFocus) runCatching { contentFocus.requestFocus() }
    }

    val openItem: (PlexItem) -> Unit = { navController.navigate("plex/item/${it.ratingKey}") }
    val plex: @Composable (@Composable (com.logan.shieldmedia.plex.ui.PlexEnv) -> Unit) -> Unit = { screen ->
        PlexGate(container.plexSession, container.plexLibrary, onSignIn = onPlexSignIn, content = screen)
    }

    CompositionLocalProvider(LocalContentFocusGate provides { !drawerHasFocus }) {
        ModalNavigationDrawer(
            scrimBrush = Brush.horizontalGradient(
                listOf(ShieldColors.Background.copy(alpha = 0.92f), ShieldColors.Background.copy(alpha = 0.4f)),
            ),
            drawerContent = {
                Column(
                    Modifier
                        .fillMaxHeight()
                        .onFocusChanged { drawerHasFocus = it.hasFocus }
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                ) {
                    Destination.entries.forEach { dest ->
                        NavigationDrawerItem(
                            selected = lastSection == dest.route,
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
                    .padding(start = RailWidth)
                    .onFocusChanged { contentHasFocus = it.hasFocus }
                    .focusRequester(contentFocus),
            ) {
                NavHost(
                    navController = navController,
                    startDestination = Destination.Home.route,
                    enterTransition = { fadeIn(tween(220)) },
                    exitTransition = { fadeOut(tween(150)) },
                ) {
                    composable(Destination.Home.route) {
                        plex { env -> PlexHomeScreen(env, onOpen = openItem, onPlay = onPlay) }
                    }
                    composable(Destination.Movies.route) {
                        plex { env -> PlexLibraryScreen(env, sectionType = "movie", title = "Movies", onOpen = openItem) }
                    }
                    composable(Destination.Shows.route) {
                        plex { env -> PlexLibraryScreen(env, sectionType = "show", title = "TV Shows", onOpen = openItem) }
                    }
                    composable(Destination.LiveTv.route) { PlaceholderScreen("Live TV", "IPTV channels and guide") }
                    composable(Destination.IptvVod.route) { PlaceholderScreen("IPTV VOD", "Xtream movies and series") }
                    composable(Destination.Search.route) {
                        plex { env -> PlexSearchScreen(env, onOpen = openItem) }
                    }
                    composable(Destination.Settings.route) {
                        SettingsScreen(
                            repository = container.settings,
                            plexSession = container.plexSession,
                            onPlexSignIn = onPlexSignIn,
                            onOpenPlayerTest = { navController.navigate(PLAYER_TEST_ROUTE) },
                        )
                    }
                    composable(PLAYER_TEST_ROUTE) { PlayerTestScreen(onPlay) }
                    composable(
                        PLEX_ITEM_ROUTE,
                        arguments = listOf(navArgument("ratingKey") { type = NavType.StringType }),
                    ) { entry ->
                        val ratingKey = entry.arguments?.getString("ratingKey").orEmpty()
                        plex { env -> PlexDetailsScreen(env, ratingKey, onOpen = openItem, onPlay = onPlay) }
                    }
                }
            }
        }
    }
}
