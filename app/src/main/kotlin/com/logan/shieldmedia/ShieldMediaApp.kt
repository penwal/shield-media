package com.logan.shieldmedia

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.logan.shieldmedia.plex.PlexLibrary
import com.logan.shieldmedia.plex.PlexSession
import com.logan.shieldmedia.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** App-wide singletons. Small enough that a hand-written container beats a DI framework. */
class AppContainer(app: Application) {
    val okHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    val settings = SettingsRepository(app)
    val plexSession = PlexSession(app, okHttp)
    val plexLibrary = PlexLibrary(plexSession)
}

class ShieldMediaApp : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        appScope.launch { container.plexSession.start() }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { container.okHttp })) }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.25).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("images"))
                    .maxSizeBytes(300L * 1024 * 1024)
                    .build()
            }
            .crossfade(180)
            .build()
}
