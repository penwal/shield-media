package com.logan.shieldmedia.navigation

import android.net.Uri
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.draw.drawWithContent
import androidx.tv.material3.DrawerValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.NavigationDrawerItemDefaults
import androidx.tv.material3.ModalNavigationDrawer
import androidx.tv.material3.NavigationDrawerItem
import androidx.tv.material3.Text
import com.logan.shieldmedia.AppContainer
import com.logan.shieldmedia.designsystem.LocalContentFocusGate
import com.logan.shieldmedia.designsystem.ShieldIcon
import com.logan.shieldmedia.designsystem.ShieldColors
import com.logan.shieldmedia.plex.PlexItem
import com.logan.shieldmedia.plex.ui.PlexDetailsScreen
import com.logan.shieldmedia.plex.ui.PlexGate
import com.logan.shieldmedia.plex.ui.PlexHomeScreen
import com.logan.shieldmedia.plex.ui.PlexLibraryScreen
import com.logan.shieldmedia.plex.ui.PlexShowcaseScreen
import com.logan.shieldmedia.plex.ui.FeatureIntroScreen
import com.logan.shieldmedia.plex.ui.PlexVaultScreen
import com.logan.shieldmedia.plex.ui.VaultMusicEffect
import com.logan.shieldmedia.plex.ui.isPremiumLibrary
import com.logan.shieldmedia.plex.ui.PlexSearchScreen
import com.logan.shieldmedia.plex.ui.PlexProfilesScreen
import com.logan.shieldmedia.plex.ui.PlexSignInScreen
import com.logan.shieldmedia.screens.PlaceholderScreen
import com.logan.shieldmedia.screens.LiveRoute
import com.logan.shieldmedia.screens.VodPlayRoute
import com.logan.shieldmedia.iptv.ui.IptvVodDetailsScreen
import com.logan.shieldmedia.iptv.ui.IptvVodScreen
import com.logan.shieldmedia.iptv.ui.VodPlayHandler
import com.logan.shieldmedia.iptv.ui.VodPlayRequest
import com.logan.shieldmedia.screens.PlexPlayRoute
import com.logan.shieldmedia.screens.PlayerRoute
import com.logan.shieldmedia.iptv.ChannelFilter
import com.logan.shieldmedia.iptv.ui.AddIptvSourceScreen
import com.logan.shieldmedia.iptv.ui.IptvSourcesScreen
import com.logan.shieldmedia.iptv.ui.LiveGuideScreen
import com.logan.shieldmedia.iptv.ui.ReminderHost
import com.logan.shieldmedia.iptv.ui.LivePlayHandler
import com.logan.shieldmedia.screens.PlayerTestScreen
import com.logan.shieldmedia.screens.SettingsScreen
import com.logan.shieldmedia.screens.WelcomeScreen
import com.logan.shieldmedia.update.AppRelease
import com.logan.shieldmedia.update.UpdatePrompt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val MAIN_ROUTE = "main"
private const val PLAYER_ROUTE = "player?uri={uri}&title={title}&start={start}&ua={ua}"
private const val PLEX_SIGN_IN_ROUTE = "plex_sign_in"
private const val PLEX_PROFILES_ROUTE = "plex_profiles"
private const val VAULT_ROUTE = "vault/{key}?title={title}"
private const val VAULT_ITEM_ROUTE = "vault_item/{ratingKey}"
private const val PLEX_PLAY_ROUTE = "plex_play/{ratingKey}?fromStart={fromStart}&theater={theater}"
private const val FEATURE_ROUTE = "feature/{ratingKey}?fromStart={fromStart}"
private const val PLAYER_TEST_ROUTE = "player_test"
private const val PLEX_ITEM_ROUTE = "plex/item/{ratingKey}"
private const val LIBRARY_ROUTE = "library/{key}?title={title}&type={type}"
private const val IPTV_SOURCES_ROUTE = "iptv/sources"
private const val IPTV_ADD_ROUTE = "iptv/add"
private const val VOD_DETAILS_ROUTE = "iptv/vod/details"
private const val VOD_PLAY_ROUTE = "vod_play?kind={kind}&id={id}&ext={ext}&title={title}&series={series}&seriesTitle={seriesTitle}&fromStart={fromStart}"
private const val LIVE_ROUTE = "live?playlist={playlist}&filter={filter}&channel={channel}"

/** Width of the collapsed navigation rail; content starts to its right. */
private val RailWidth = 80.dp

typealias PlayHandler = (uri: String, title: String, startMs: Long) -> Unit

/** Root graph: the drawer-based app, plus full-screen routes (player, sign-in) outside the drawer. */
@Composable
fun AppNavigation(container: AppContainer) {
    val rootNav = rememberNavController()
    // Welcome screen on a fresh start, over the app as it loads underneath (not on coming back).
    var welcomed by rememberSaveable { mutableStateOf(false) }
    // Keys wait until the welcome screen is gone, so nothing moves behind it.
    Box(Modifier.fillMaxSize().onPreviewKeyEvent { !welcomed }) {
        ReminderHost(
            repo = container.iptv,
            onWatch = { r -> rootNav.navigate("live?playlist=${r.playlistId}&filter=all&channel=${r.channelId}") },
        ) {
            RootGraph(container, rootNav)
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = !welcomed,
            enter = androidx.compose.animation.EnterTransition.None,
            exit = fadeOut(tween(700)),
        ) {
            WelcomeScreen(container, onDone = { welcomed = true })
        }
        // A newer version on GitHub is offered once the welcome screen is gone.
        val update by produceState<AppRelease?>(null) { value = container.updater.check() }
        var updateDismissed by rememberSaveable { mutableStateOf(false) }
        val release = update
        if (welcomed && release != null && !updateDismissed) {
            UpdatePrompt(container.updater, release, onDismiss = { updateDismissed = true })
        }
    }
}

@Composable
private fun RootGraph(container: AppContainer, rootNav: androidx.navigation.NavHostController) {
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
                // Catch-up: the provider sees the same user agent as its live streams.
                onPlayArchive = { uri, title, userAgent ->
                    rootNav.navigate("player?uri=${Uri.encode(uri)}&title=${Uri.encode(title)}&ua=${Uri.encode(userAgent)}")
                },
                onPlexSignIn = { rootNav.navigate(PLEX_SIGN_IN_ROUTE) },
                onPlexProfiles = { rootNav.navigate(PLEX_PROFILES_ROUTE) },
                onOpenVault = { target -> rootNav.navigate(target) },
                onPlayPlex = { ratingKey, fromStart -> rootNav.navigate("plex_play/$ratingKey?fromStart=$fromStart") },
                onPlayVod = { r ->
                    val e = { v: String? -> Uri.encode(v.orEmpty()) }
                    rootNav.navigate(
                        "vod_play?kind=${r.kind}&id=${e(r.id)}&ext=${e(r.extension)}&title=${e(r.title)}" +
                            "&series=${e(r.seriesId)}&seriesTitle=${e(r.seriesTitle)}&fromStart=${r.fromStart}",
                    )
                },
                onPlayLive = { channel, filter ->
                    rootNav.navigate("live?playlist=${channel.playlistId}&filter=${Uri.encode(filter.key)}&channel=${channel.id}")
                },
            )
        }
        composable(
            VOD_PLAY_ROUTE,
            arguments = listOf("kind", "id", "ext", "title", "series", "seriesTitle").map { name ->
                navArgument(name) { type = NavType.StringType; defaultValue = "" }
            } + navArgument("fromStart") { type = NavType.BoolType; defaultValue = false },
        ) { entry ->
            val a = entry.arguments
            VodPlayRoute(
                container = container,
                request = VodPlayRequest(
                    kind = a?.getString("kind").orEmpty(),
                    id = a?.getString("id").orEmpty(),
                    extension = a?.getString("ext").orEmpty(),
                    title = a?.getString("title").orEmpty(),
                    seriesId = a?.getString("series")?.ifEmpty { null },
                    seriesTitle = a?.getString("seriesTitle")?.ifEmpty { null },
                    fromStart = a?.getBoolean("fromStart") ?: false,
                ),
                onExit = { rootNav.popBackStack() },
            )
        }
        composable(
            LIVE_ROUTE,
            arguments = listOf(
                navArgument("playlist") { type = NavType.LongType },
                navArgument("filter") { type = NavType.StringType; defaultValue = "all" },
                navArgument("channel") { type = NavType.LongType },
            ),
        ) { entry ->
            LiveRoute(
                repo = container.iptv,
                settingsRepository = container.settings,
                playlistId = entry.arguments?.getLong("playlist") ?: 0L,
                filter = ChannelFilter.fromKey(entry.arguments?.getString("filter")),
                channelId = entry.arguments?.getLong("channel") ?: 0L,
                onExit = { rootNav.popBackStack() },
            )
        }
        composable(
            PLAYER_ROUTE,
            arguments = listOf(
                navArgument("uri") { type = NavType.StringType },
                navArgument("title") { type = NavType.StringType; defaultValue = "" },
                navArgument("start") { type = NavType.LongType; defaultValue = 0L },
                navArgument("ua") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry ->
            PlayerRoute(
                uri = entry.arguments?.getString("uri").orEmpty(),
                title = entry.arguments?.getString("title").orEmpty(),
                startMs = entry.arguments?.getLong("start") ?: 0L,
                userAgent = entry.arguments?.getString("ua")?.ifEmpty { null },
                settingsRepository = container.settings,
                onExit = { rootNav.popBackStack() },
            )
        }
        composable(
            PLEX_PLAY_ROUTE,
            arguments = listOf(
                navArgument("ratingKey") { type = NavType.StringType },
                navArgument("fromStart") { type = NavType.BoolType; defaultValue = false },
                navArgument("theater") { type = NavType.BoolType; defaultValue = false },
            ),
        ) { entry ->
            PlexPlayRoute(
                container = container,
                ratingKey = entry.arguments?.getString("ratingKey").orEmpty(),
                fromStart = entry.arguments?.getBoolean("fromStart") ?: false,
                onExit = { rootNav.popBackStack() },
                theater = entry.arguments?.getBoolean("theater") ?: false,
            )
        }
        // Blu-Ray pre-show: narration, then the film in theater mode (it replaces this screen).
        composable(
            FEATURE_ROUTE,
            arguments = listOf(
                navArgument("ratingKey") { type = NavType.StringType },
                navArgument("fromStart") { type = NavType.BoolType; defaultValue = false },
            ),
            enterTransition = { fadeIn(tween(700)) },
            exitTransition = { fadeOut(tween(300)) },
        ) { entry ->
            val ratingKey = entry.arguments?.getString("ratingKey").orEmpty()
            val fromStart = entry.arguments?.getBoolean("fromStart") ?: false
            PlexGate(container.plexSession, container.plexLibrary, onSignIn = { rootNav.navigate(PLEX_SIGN_IN_ROUTE) }) { env ->
                FeatureIntroScreen(
                    env = env,
                    ratingKey = ratingKey,
                    onStart = {
                        rootNav.navigate("plex_play/$ratingKey?fromStart=$fromStart&theater=true") {
                            popUpTo(FEATURE_ROUTE) { inclusive = true }
                        }
                    },
                    onCancel = { rootNav.popBackStack() },
                )
            }
        }
        // Premium libraries (Blu-Ray) run full screen, outside the menu, with their own intro.
        composable(
            VAULT_ROUTE,
            arguments = listOf(
                navArgument("key") { type = NavType.StringType },
                navArgument("title") { type = NavType.StringType; defaultValue = "" },
            ),
            enterTransition = { fadeIn(tween(400)) },
            exitTransition = { fadeOut(tween(200)) },
        ) { entry ->
            val key = entry.arguments?.getString("key").orEmpty()
            val title = entry.arguments?.getString("title").orEmpty()
            PlexGate(container.plexSession, container.plexLibrary, onSignIn = { rootNav.navigate(PLEX_SIGN_IN_ROUTE) }) { env ->
                PlexVaultScreen(
                    env = env,
                    sectionKey = key,
                    title = title,
                    onOpen = { rootNav.navigate("vault_item/${it.ratingKey}") },
                    onExit = { rootNav.popBackStack() },
                )
            }
        }
        composable(VAULT_ITEM_ROUTE, arguments = listOf(navArgument("ratingKey") { type = NavType.StringType })) { entry ->
            val ratingKey = entry.arguments?.getString("ratingKey").orEmpty()
            VaultMusicEffect() // still in the vault: keep the music going
            PlexGate(container.plexSession, container.plexLibrary, onSignIn = { rootNav.navigate(PLEX_SIGN_IN_ROUTE) }) { env ->
                PlexDetailsScreen(
                    env,
                    ratingKey,
                    onOpen = { rootNav.navigate("vault_item/${it.ratingKey}") },
                    onPlay = { key, fromStart -> rootNav.navigate("feature/$key?fromStart=$fromStart") },
                )
            }
        }
        composable(PLEX_PROFILES_ROUTE) {
            PlexProfilesScreen(container.plexSession, onDone = { rootNav.popBackStack() })
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
private fun MainScaffold(
    container: AppContainer,
    onPlay: PlayHandler,
    onPlayArchive: (uri: String, title: String, userAgent: String) -> Unit,
    onPlexSignIn: () -> Unit,
    onPlexProfiles: () -> Unit,
    onOpenVault: (String) -> Unit,
    onPlayPlex: com.logan.shieldmedia.plex.ui.PlexPlayHandler,
    onPlayVod: VodPlayHandler,
    onPlayLive: LivePlayHandler,
) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    // Library screens share one route pattern; identify them by their section key.
    val currentRoute = backStack?.destination?.route?.let { route ->
        if (route == LIBRARY_ROUTE) "library/${backStack?.arguments?.getString("key")}" else route
    }

    // Sub-screens (details, player test) keep highlighting the section they were opened from.
    var lastSection by rememberSaveable { mutableStateOf(Destination.Home.route) }
    val isTopLevel = currentRoute != null &&
        (Destination.entries.any { it.route == currentRoute } || currentRoute.startsWith("library/"))
    LaunchedEffect(currentRoute) { if (isTopLevel) lastSection = currentRoute!! }

    // Picking a drawer entry keeps focus in the drawer (TV convention). Any other navigation —
    // app start, opening a sub-screen, Back — must land focus in the content, not on "Home".
    val contentFocus = remember { FocusRequester() }
    var contentHasFocus by remember { mutableStateOf(false) }
    var drawerHasFocus by remember { mutableStateOf(false) }
    // Focus can land in the drawer by itself (e.g. when a loading screen loses its only focusable
    // item). Only a drawer the user moved into with the remote should block content from taking focus.
    var drawerEnteredByUser by remember { mutableStateOf(false) }
    var lastKeyAt by remember { mutableLongStateOf(0L) }
    var navigatedFromDrawer by remember { mutableStateOf(false) }
    LaunchedEffect(currentRoute) {
        if (navigatedFromDrawer) {
            navigatedFromDrawer = false
            return@LaunchedEffect
        }
        delay(250) // let the screen transition finish so focus doesn't land on the outgoing screen
        if (!contentHasFocus) runCatching { contentFocus.requestFocus() }
    }

    val drawerFocus = remember { FocusRequester() }
    val plexState by container.plexSession.state.collectAsState()
    // Current profile's name and avatar for the menu (Home profile if switched, else the account).
    // Like the libraries, the last one found stays put through reconnects and failed requests.
    val profile by androidx.compose.runtime.produceState<Pair<String, String?>?>(null, plexState) {
        when (plexState) {
            is com.logan.shieldmedia.plex.PlexState.SignedOut -> value = null
            is com.logan.shieldmedia.plex.PlexState.Ready -> {
                val session = container.plexSession
                val account = session.user()
                val name = session.currentProfileName() ?: account?.let { it.title ?: it.username }
                if (name != null) value = name to (session.homeUsers().firstOrNull { it.title == name }?.thumb ?: account?.thumb)
            }
            else -> Unit
        }
    }
    val scope = rememberCoroutineScope()
    // Plex libraries for the menu. The last good list is kept while the session reconnects or a
    // request fails, so the menu never collapses to just Home and Live TV; it only clears on sign-out.
    var libraries by remember { mutableStateOf(emptyList<MenuEntry>()) }
    LaunchedEffect(plexState) {
        if (plexState is com.logan.shieldmedia.plex.PlexState.SignedOut) libraries = emptyList()
        val server = (plexState as? com.logan.shieldmedia.plex.PlexState.Ready)?.server ?: return@LaunchedEffect
        var attempt = 0
        while (true) {
            val fetched = runCatching {
                // Order: regular movie libraries, then premium ones (Blu-Ray/4K), then TV.
                container.plexLibrary.sections(server)
                    .filter { it.type == "movie" || it.type == "show" }
                    .sortedBy { s -> if (s.type == "show") 2 else if (isPremiumLibrary(s.title)) 1 else 0 }
                    .map { s ->
                        val icons = com.logan.shieldmedia.designsystem.ShieldIcons
                        MenuEntry(
                            id = "library/${s.key}",
                            label = s.title,
                            icon = when {
                                s.type == "show" -> icons.Shows
                                isPremiumLibrary(s.title) -> icons.Disc
                                else -> icons.Movies
                            },
                            target = if (s.type == "movie" && isPremiumLibrary(s.title)) {
                                "vault/${s.key}?title=${Uri.encode(s.title)}"
                            } else {
                                "library/${s.key}?title=${Uri.encode(s.title)}&type=${s.type}"
                            },
                        )
                    }
            }.getOrNull()
            if (fetched != null) {
                libraries = fetched
                break
            }
            delay(2_000L shl attempt.coerceAtMost(4))
            attempt++
        }
    }

    val openItem: (PlexItem) -> Unit = { navController.navigate("plex/item/${it.ratingKey}") }
    val plex: @Composable (@Composable (com.logan.shieldmedia.plex.ui.PlexEnv) -> Unit) -> Unit = { screen ->
        PlexGate(container.plexSession, container.plexLibrary, onSignIn = onPlexSignIn, content = screen)
    }

    CompositionLocalProvider(LocalContentFocusGate provides { !(drawerHasFocus && drawerEnteredByUser) }) {
        ModalNavigationDrawer(
            modifier = Modifier.onPreviewKeyEvent {
                lastKeyAt = SystemClock.uptimeMillis()
                false
            },
            // Even dim behind the open menu; the menu itself is a solid panel, so nothing shows through.
            scrimBrush = SolidColor(Color.Black.copy(alpha = 0.6f)),
            drawerContent = { drawerValue ->
                SideMenu(
                    drawerValue = drawerValue,
                    currentSection = lastSection,
                    libraries = libraries,
                    profileName = profile?.first,
                    profileImage = profile?.second,
                    currentFocus = drawerFocus,
                    modifier = Modifier
                        .onFocusChanged {
                            if (it.hasFocus && !drawerHasFocus) {
                                drawerEnteredByUser = SystemClock.uptimeMillis() - lastKeyAt < 500
                            }
                            drawerHasFocus = it.hasFocus
                        }
                        // Entering the menu always lands on the current section, not the nearest item.
                        .focusProperties { onEnter = { runCatching { drawerFocus.requestFocus() } } }
                        .focusGroup(),
                    // Choosing a section closes the menu and drops you into it.
                    onSelect = { dest ->
                        // Let the new screen take focus as soon as its content is ready.
                        drawerEnteredByUser = false
                        if (dest.target.startsWith("vault/")) {
                            // The Blu-Ray room opens over the app; leaving it lands on Home.
                            lastSection = Destination.Home.route
                            if (currentRoute != Destination.Home.route) navController.showSection(Destination.Home.route)
                            onOpenVault(dest.target)
                            return@SideMenu
                        }
                        if (currentRoute != dest.id) navController.showSection(dest.target)
                        // Hand focus to the screen (closing the menu). The outgoing screen is still
                        // fading out and can briefly hold focus, so keep at it until focus has stayed
                        // in the content for a moment — unless the viewer starts pressing keys.
                        val selectedAt = SystemClock.uptimeMillis()
                        scope.launch {
                            var stable = 0
                            repeat(40) {
                                delay(100)
                                if (lastKeyAt > selectedAt + 50) return@launch
                                if (contentHasFocus) {
                                    if (++stable >= 5) return@launch
                                } else {
                                    stable = 0
                                    runCatching { contentFocus.requestFocus() }
                                }
                            }
                        }
                    },
                    onProfile = onPlexProfiles,
                )
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
                        plex { env -> PlexHomeScreen(env, onOpen = openItem, onPlay = onPlayPlex) }
                    }
                    composable(
                        LIBRARY_ROUTE,
                        arguments = listOf(
                            navArgument("key") { type = NavType.StringType },
                            navArgument("title") { type = NavType.StringType; defaultValue = "" },
                            navArgument("type") { type = NavType.StringType; defaultValue = "" },
                        ),
                    ) { entry ->
                        val key = entry.arguments?.getString("key").orEmpty()
                        val title = entry.arguments?.getString("title").orEmpty()
                        val movies = entry.arguments?.getString("type") == "movie"
                        plex { env ->
                            if (isPremiumLibrary(title)) {
                                PlexShowcaseScreen(env, sectionKey = key, title = title, onOpen = openItem)
                            } else {
                                PlexLibraryScreen(env, sectionKey = key, title = title, onOpen = openItem, movies = movies)
                            }
                        }
                    }
                    composable(Destination.LiveTv.route) {
                        LiveGuideScreen(
                            container.iptv,
                            onPlay = onPlayLive,
                            onAddSource = { navController.navigate(IPTV_ADD_ROUTE) },
                            onPlayArchive = { uri, title ->
                                val ua = container.iptv.userAgent(container.iptv.activePlaylistId.value)
                                onPlayArchive(uri, title, ua ?: com.logan.shieldmedia.iptv.DEFAULT_USER_AGENT)
                            },
                        )
                    }
                    composable(IPTV_SOURCES_ROUTE) {
                        IptvSourcesScreen(container.iptv, onAdd = { navController.navigate(IPTV_ADD_ROUTE) })
                    }
                    composable(IPTV_ADD_ROUTE) {
                        AddIptvSourceScreen(container.iptv, onDone = { navController.popBackStack() })
                    }
                    composable(Destination.IptvVod.route) {
                        IptvVodScreen(
                            container.iptvVod,
                            onOpen = { item ->
                                container.iptvVod.selected = item
                                navController.navigate(VOD_DETAILS_ROUTE)
                            },
                            onPlay = onPlayVod,
                        )
                    }
                    composable(VOD_DETAILS_ROUTE) {
                        container.iptvVod.selected?.let { IptvVodDetailsScreen(container.iptvVod, it, onPlay = onPlayVod) }
                    }
                    composable(Destination.Search.route) {
                        plex { env -> PlexSearchScreen(env, onOpen = openItem) }
                    }
                    composable(Destination.Settings.route) {
                        SettingsScreen(
                            repository = container.settings,
                            plexSession = container.plexSession,
                            onPlexSignIn = onPlexSignIn,
                            onPlexProfiles = onPlexProfiles,
                            onOpenIptvSources = { navController.navigate(IPTV_SOURCES_ROUTE) },
                            onOpenPlayerTest = { navController.navigate(PLAYER_TEST_ROUTE) },
                            iptv = container.iptv,
                        )
                    }
                    composable(PLAYER_TEST_ROUTE) { PlayerTestScreen(onPlay) }
                    composable(
                        PLEX_ITEM_ROUTE,
                        arguments = listOf(navArgument("ratingKey") { type = NavType.StringType }),
                    ) { entry ->
                        val ratingKey = entry.arguments?.getString("ratingKey").orEmpty()
                        plex { env -> PlexDetailsScreen(env, ratingKey, onOpen = openItem, onPlay = onPlayPlex) }
                    }
                }
            }
            // Android TV Back convention: content -> drawer -> Home -> exit.
            BackHandler(enabled = isTopLevel && !drawerHasFocus) {
                runCatching { drawerFocus.requestFocus() }
            }
            BackHandler(enabled = drawerHasFocus && currentRoute != Destination.Home.route) {
                navigatedFromDrawer = true
                lastSection = Destination.Home.route
                navController.showSection(Destination.Home.route)
                // Move the drawer's focus to Home once the requester has followed the selection.
                scope.launch {
                    withFrameNanos { }
                    runCatching { drawerFocus.requestFocus() }
                }
            }
        }
    }
}

/**
 * Opens a menu section on its own: everything before it is cleared, so every section starts fresh
 * (at the top, default sort) each time it's chosen, instead of where it was last left.
 */
private fun androidx.navigation.NavController.showSection(target: String) {
    navigate(target) {
        popUpTo(graph.id) { inclusive = true }
        launchSingleTop = true
    }
}
