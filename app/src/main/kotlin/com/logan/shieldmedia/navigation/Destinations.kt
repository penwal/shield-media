package com.logan.shieldmedia.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.ui.graphics.vector.ImageVector

enum class Destination(val route: String, val label: String, val icon: ImageVector) {
    Home("home", "Home", Icons.Filled.Home),
    Movies("movies", "Movies", Icons.Filled.Movie),
    Shows("shows", "TV Shows", Icons.Filled.Tv),
    LiveTv("live", "Live TV", Icons.Filled.LiveTv),
    IptvVod("iptv_vod", "IPTV VOD", Icons.Filled.VideoLibrary),
    Search("search", "Search", Icons.Filled.Search),
    Settings("settings", "Settings", Icons.Filled.Settings),
}
