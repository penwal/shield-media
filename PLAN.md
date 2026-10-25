# Project Plan — Plex + IPTV client for NVIDIA Shield

Working name: **TBD** (avoid "Plex" in the name itself; "for Plex" in descriptions is fine).

## Goals

1. **Plex client** that direct-plays nearly everything the Shield can decode, with correct HDR / Dolby Vision, lossless audio passthrough, and full subtitle support. Transcoding is the last resort.
2. **IPTV section** (Xtream Codes + M3U/XMLTV) at TiviMate level: fast zapping, a smooth EPG grid, catch-up, favorites, recording.
3. **A premium 10-foot UI**: polished, fast, and fully D-pad driven.

Target device: NVIDIA Shield TV / Shield TV Pro (Tegra X1/X1+, Android TV 11). Other Android TV devices are a bonus, not a goal.

---

## Tech stack

| Area | Choice | Why |
|---|---|---|
| Language | Kotlin, coroutines/Flow | Standard for modern Android |
| UI | Jetpack Compose + `androidx.tv:tv-material` | Modern TV components, focus APIs, easy custom layouts (EPG grid) |
| Architecture | MVVM + unidirectional state, multi-module Gradle | Keeps Plex/IPTV/player isolated and testable |
| DI | Hilt | |
| Network | OkHttp + Retrofit + kotlinx.serialization | |
| Images | Coil 3 | Memory/disk caching, sized loads for posters/backdrops |
| Database | Room (SQLite) | EPG, channels, favorites, watch state cache |
| Settings | DataStore | |
| Background | WorkManager + foreground service | EPG refresh, playlist sync, recordings |
| Player (primary) | Media3 ExoPlayer + FFmpeg decoder extension | Best HW decode/passthrough on Shield; FFmpeg covers audio it can't pass through |
| Player (fallback) | libmpv (later milestone) | Edge cases: styled ASS subs, odd containers/codecs |
| Server-side fallback | Plex transcode via decision API | When neither engine can play a file |

minSdk 28, target latest SDK.

---

## Module layout

```
app/                      entry point, navigation host, top-level nav
core/designsystem/        theme, colors, typography, focusable cards, rows, hero, motion
core/common/              result types, dispatchers, utils
core/database/            Room DB, DAOs (shared EPG/channel/watch tables)
core/network/             OkHttp client, interceptors, logging
core/player/              player abstraction, Media3 impl, track selection, AFR, OSD
plex/data/                plex.tv auth, server discovery, library API, timeline reporting
plex/ui/                  home, library, details, seasons, search, user switch
iptv/data/                Xtream API, M3U parser, XMLTV parser, sync workers
iptv/ui/                  live channels, EPG guide, mini guide, VOD, series, recordings
settings/                 settings screens for both sides
```

---

## Playback core (highest risk, built early)

**Video, hardware-decoded on the Shield:** H.264, HEVC (Main/Main10), VP9, MPEG-2, VC-1.
- AV1: no hardware decoder on any Shield. Software decode works for low-res content only; for anything above that, Plex transcodes.
- HDR10 / HLG: native.
- **Dolby Vision**: profiles 5 and 8 play natively. Profile 7 (UHD Blu-ray rips) falls back to its HDR10 base layer. The exact behavior on hardware needs verifying.

**Audio passthrough** over IEC 61937 to an AVR: AC3, E-AC3 (incl. Atmos/JOC), DTS, DTS-HD MA, TrueHD (incl. Atmos). With no AVR, the FFmpeg extension decodes to PCM.

**Subtitles:**
- SRT, VTT, and PGS are supported out of the box.
- ASS/SSA displays as plain text in Media3; full styling comes from libass or mpv later.
- Plex external subs are supported, and subtitle search runs through Plex's OpenSubtitles integration.

**Must-haves:**
- Auto frame rate + resolution matching (`Surface.setFrameRate` / display mode switch). Configurable.
- Audio/subtitle track picker, audio delay, subtitle size/position/offset.
- Resume, chapter list, Plex intro/credits markers → "Skip Intro", auto next-episode.
- Playback stats overlay (codec, bitrate, dropped frames, HDR mode, passthrough status), which is essential for debugging.
- Engine abstraction so mpv can be added later without touching UI code.

**Test matrix:** maintain a folder of sample files (HEVC 10-bit HDR10, DV P5/P7/P8, TrueHD Atmos, DTS-HD MA, PGS, ASS, 23.976/24/25/29.97/50/59.94 fps, interlaced MPEG-2/H.264 for IPTV). Every player change is checked against it on the real Shield.

---

## Plex side

- **Auth:** plex.tv PIN flow ("go to plex.tv/link, enter code"), with a stable client identifier and product headers. Supports Plex Home user switching with PINs.
- **Server discovery:** `clients.plex.tv/api/v2/resources`, then pick the best connection (local → remote → relay), with periodic re-probing. Multiple servers.
- **Browsing:**
  - Home hubs (Continue Watching, On Deck, Recently Added)
  - Libraries with sorting/filtering, collections, playlists, Watchlist
  - Global search
- **Details:** backdrop and logo, metadata, cast, extras, seasons/episodes, a picker for multiple versions, and per-file media info (codec, HDR, audio) shown up front.
- **Playback integration:**
  - Timeline reporting (progress, play/pause/stop) and scrobble
  - Mark watched/unwatched
  - Direct play from part URLs, with a decision endpoint for transcode fallback
- Not in scope at first: Plex Live TV/DVR (the IPTV side covers that), music, photos.

---

## IPTV side (TiviMate parity target)

**Sources:**
- Xtream Codes via `player_api.php`: live/VOD/series categories, streams, `get_series_info`, `get_vod_info`, `get_short_epg`
- Full EPG via `xmltv.php`
- Plain M3U + external XMLTV URLs
- Multiple playlists, plus a per-playlist user-agent and refresh schedule

**EPG engine:**
- Streaming XMLTV parse (gzip, XmlPullParser) on a worker, with batched Room inserts in transactions.
- Keeps only the useful window (catch-up days back → N days forward).
- Indexed on `(channel_id, start)`. Target: a 10k-channel/7-day EPG refreshes in the background without UI jank.
- EPG source per playlist, manual channel ↔ EPG ID mapping, time offset.

**Guide grid:**
- Custom virtualized 2D Compose layout: channel column + time ruler + program blocks positioned by time, with shared scroll state.
- Smooth D-pad focus across programs, now-line, jump to now/day, group filter.
- This is the hardest UI component; it gets its own milestone and performance profiling on the Shield.

**Live playback:**
- One reused player instance, tuned low start buffer, aggressive reconnect on stream drop
- Channel up/down, previous-channel toggle, number entry
- Mini guide / channel list overlay without stopping playback

**Features:**
- Favorites (multiple lists), hide/reorder/rename groups
- Catch-up/timeshift via Xtream `timeshift` URLs
- VOD + series browsing with resume and continue watching
- Recording (scheduled and instant, to local/USB storage) via a foreground service
- Reminders, parental PIN lock on groups, search across channels/EPG/VOD
- Later: multiview (2–4 streams), Android TV launcher "Watch Next" channels

---

## UI / design direction

- **Top-level nav:** Home · Movies · TV Shows · Live TV · IPTV VOD · Search · Settings. A side rail that collapses to icons.
- **Look:** dark theme, large backdrops with gradient scrims, logo art instead of title text when available, and clear focus states (scale + glow + elevation, 150–200 ms motion).
- **References:** Plex, Infuse, Apple TV app, TiviMate guide.
- The design system module is built first, and every screen uses only its components. That keeps the app consistent.
- **Performance rules:** sized image requests, no work on the main thread, baseline profiles before release, a 60 fps target on Shield.

---

## Milestones

| # | Milestone | Done when |
|---|---|---|
| 0 | **Environment** | Android Studio + SDK installed, Shield connected via network ADB, Android TV emulator working, git repo initialized |
| 1 | **Skeleton + design system** ✅ | Multi-module project builds; app installs on Shield; nav rail, theme, focusable cards/rows, settings storage. *Done on the emulator; modules `app` + `core:designsystem`. Hilt/Room/Retrofit are deferred until first needed (milestones 3 and 5).* |
| 2 | **Player core** 🟡 | Plays local/URL test matrix with HW decode, passthrough, AFR, track selection, stats overlay. *Done and verified on the emulator (12/14 test streams; the 2 failures are emulator decoder limits: VP9 4K, 10-bit HLG HEVC). **Still to verify on the Shield:** hardware decode, HDR10/DV output, frame-rate switching, passthrough with an AVR.* |
| 3 | **Plex: auth + browse** ✅ | PIN login, server discovery, home hubs, libraries, details pages, search. *Done and tested against the real server on the emulator: sign-in, auto server/connection pick, Home (hero + backdrop + hubs), Movies/TV grids with library tabs, show/movie/episode details (seasons, episodes, cast, spec badges), search, direct play with resume, per-screen focus memory, TV Back convention (content → drawer → Home → exit). **Not yet:** Plex Home (managed user) switching.* |
| 4 | **Plex: playback integration** ✅ | Direct play, resume, timeline/scrobble, skip intro/credits, next episode, transcode fallback. *Done on the emulator against the real server: progress/watched sync via /:/timeline (positions clamped to duration), Skip Intro/Credits from server markers, Up Next card with 10 s countdown over the credits (or last 30 s) + autoplay, back to the previous screen when a movie ends, coalesced scrubbing (one network seek per burst of presses). Transcode fallback triggers on decoder/format errors; the server accepts the request, but it has not been exercised in-app (no file failed to direct-play on the emulator). Plex Home profile switching with PIN pad also done.* |
| 5 | **IPTV: sources + live** ✅ | Xtream + M3U login, categories/channels, live playback, fast zapping, favorites. *Done and tested on the emulator against `tools/iptv-mock` (fake Xtream panel + M3U, backed by free Pluto TV channels): add/refresh/remove sources, SQLite store, Xtream + M3U parsers, one-retry downloads, live player with Up/Down + CH± zapping, number entry, last-channel, info banner, mini channel list, audio/subtitle tracks, auto-reconnect, favorites (hold OK in the guide). **Not yet verified:** a real provider account.* |
| 6 | **IPTV: EPG + guide grid** ✅ | XMLTV sync worker, guide grid smooth on Shield, mini guide overlay. *Done on the emulator: streaming XMLTV import (gzip, name matching for channels without tvg-id, -1/+7 day window), 8 h refresh; logical-focus guide grid (2 h window, now-line, programme header, group filter panel, page up/down). **Later:** small video preview in the guide, reminders (M7), Shield performance check with a 10k-channel provider.* |
| 7 | **IPTV: advanced** 🟡 | Catch-up, VOD/series, recording, reminders, parental lock, multi-playlist. *Done on the emulator: catch-up (Xtream timeshift, verified on the real provider), movies & series with resume/next-episode (verified against the mock; the real provider has no VOD), reminders (pop-up with Watch now), parental PIN on channel groups, guide long-press menu, channel-direction setting, multiple sources. **Not yet:** recording (deferred by choice), multiview.* |
| 8 | **Polish** | mpv fallback engine, styled ASS, launcher channels, baseline profiles, crash reporting |
| 9 | **Release** | Signed build; sideload or Play Store prep |

Milestones 2 and 6 carry the most technical risk. Plan for the most iteration there.

---

## Model / effort guidance for this project

- **Opus, high effort:** milestone planning, player core (2), EPG engine + guide grid (6), hard bugs (stutter, focus, sync).
- **Sonnet or Opus medium:** screens, API models, settings, routine features once patterns exist.

## Design direction (applied in M3; final polish pass in M8)

- Inter variable font with display optical sizing; strong ease-out motion; focus = lift + thin light outline + soft shadow; gold reserved for primary actions/progress.
- Backdrop crossfades only after focus settles (~280 ms) and only once the new image has loaded.
- Rows pivot (focused card stays put); detail pages scroll only when the target is off-screen.
- Overlay (modal) drawer so artwork runs full-bleed and opening the menu never reflows content.

## Decisions

- [x] Distribution: **personal sideload**. No Play Store policy work; GPL components (libmpv) are fine.
- [x] App name: not needed yet. Package id `com.logan.shieldmedia` (placeholder).
- [x] Test hardware: TV *may* support Dolby Vision; an AVR may come later. Passthrough and DV must be configurable and degrade gracefully (PCM decode, HDR10 fallback), and the stats overlay must show what's actually happening.
- [x] Recording: **deferred to milestone 7**, not v1.

## Dev environment (milestone 0)

- JDK: Temurin 17 (`JAVA_HOME` set at user level)
- Android SDK: `%LOCALAPPDATA%\Android\Sdk` (platform 36, build-tools 36.0.0, platform-tools, emulator)
- Emulator: AVD `ShieldTV_API30` (Android TV 11, 1080p, matches Shield OS)
- Android Studio: installed via winget
- Shield: enable Developer options → Network debugging, then `adb connect <shield-ip>:5555`
