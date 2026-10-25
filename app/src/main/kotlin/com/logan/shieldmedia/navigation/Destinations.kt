package com.logan.shieldmedia.navigation

import androidx.annotation.DrawableRes
import com.logan.shieldmedia.designsystem.ShieldIcons

enum class Destination(val route: String, val label: String, @DrawableRes val icon: Int) {
    Home("home", "Home", ShieldIcons.Home),
    Movies("movies", "Movies", ShieldIcons.Movies),
    Shows("shows", "TV Shows", ShieldIcons.Shows),
    LiveTv("live", "Live TV", ShieldIcons.Live),
    IptvVod("iptv_vod", "Movies & Series", ShieldIcons.Vod),
    Search("search", "Search", ShieldIcons.Search),
    Settings("settings", "Settings", ShieldIcons.Settings),
}
