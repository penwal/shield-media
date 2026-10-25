package com.logan.shieldmedia.navigation

import androidx.annotation.DrawableRes
import com.logan.shieldmedia.designsystem.ShieldIcons

/** One row in the side menu. [id] identifies the section; [target] is where it navigates. */
data class MenuEntry(val id: String, val label: String, @DrawableRes val icon: Int, val target: String = id)

fun Destination.asEntry() = MenuEntry(route, label, icon)

enum class Destination(val route: String, val label: String, @DrawableRes val icon: Int) {
    Home("home", "Home", ShieldIcons.Home),
    LiveTv("live", "Live TV", ShieldIcons.Live),
    IptvVod("iptv_vod", "Movies & Series", ShieldIcons.Vod),
    Search("search", "Search", ShieldIcons.Search),
    Settings("settings", "Settings", ShieldIcons.Settings),
}
