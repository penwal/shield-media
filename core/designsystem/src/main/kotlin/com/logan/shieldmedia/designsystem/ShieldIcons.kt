package com.logan.shieldmedia.designsystem

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor

/** Thin single-weight line icons (Lucide, 1.6 stroke) used everywhere instead of Material's filled set. */
object ShieldIcons {
    @DrawableRes val Home = R.drawable.ic_house
    @DrawableRes val Movies = R.drawable.ic_clapperboard
    @DrawableRes val Shows = R.drawable.ic_tv_minimal
    @DrawableRes val Live = R.drawable.ic_radio
    @DrawableRes val Vod = R.drawable.ic_film
    @DrawableRes val Search = R.drawable.ic_search
    @DrawableRes val Settings = R.drawable.ic_settings
    @DrawableRes val Play = R.drawable.ic_play
    @DrawableRes val Pause = R.drawable.ic_pause
    @DrawableRes val Back10 = R.drawable.ic_rotate_ccw
    @DrawableRes val Forward30 = R.drawable.ic_rotate_cw
    @DrawableRes val Audio = R.drawable.ic_audio_lines
    @DrawableRes val Subtitles = R.drawable.ic_captions
    @DrawableRes val Info = R.drawable.ic_info
    @DrawableRes val Tune = R.drawable.ic_sliders_horizontal
    @DrawableRes val Check = R.drawable.ic_check
    @DrawableRes val Star = R.drawable.ic_star
    @DrawableRes val Heart = R.drawable.ic_heart
    @DrawableRes val Channels = R.drawable.ic_list_video
    @DrawableRes val Disc = R.drawable.ic_disc_3
}

@Composable
fun ShieldIcon(
    @DrawableRes icon: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    tint: Color = LocalContentColor.current,
) {
    Icon(painterResource(icon), contentDescription, modifier.size(size), tint = tint)
}
