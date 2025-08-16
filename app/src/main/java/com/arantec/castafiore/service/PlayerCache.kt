package com.arantec.castafiore.service

import android.content.Context
import com.google.android.exoplayer2.database.ExoDatabaseProvider
import com.google.android.exoplayer2.upstream.DataSource
import com.google.android.exoplayer2.upstream.DefaultDataSource
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource
import com.google.android.exoplayer2.upstream.cache.Cache
import com.google.android.exoplayer2.upstream.cache.LeastRecentlyUsedCacheEvictor
import com.google.android.exoplayer2.upstream.cache.SimpleCache
import com.google.android.exoplayer2.upstream.cache.CacheDataSource
import java.io.File

/**
 * Holds a singleton media cache and DataSource factories for ExoPlayer playback and prefetching.
 */
object PlayerCache {
    @Volatile private var initialized = false

    lateinit var cache: Cache
        private set

    lateinit var httpDataSourceFactory: DefaultHttpDataSource.Factory
        private set

    lateinit var upstreamFactory: DefaultDataSource.Factory
        private set

    lateinit var cacheDataSourceFactory: CacheDataSource.Factory
        private set

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return

            val appContext = context.applicationContext
            val cacheDir = File(appContext.cacheDir, "audio_cache")
            if (!cacheDir.exists()) cacheDir.mkdirs()

            val evictor = LeastRecentlyUsedCacheEvictor(128L * 1024L * 1024L) // 128 MB
            val databaseProvider = ExoDatabaseProvider(appContext)
            cache = SimpleCache(cacheDir, evictor, databaseProvider)

            httpDataSourceFactory = DefaultHttpDataSource.Factory()
                .setUserAgent("Castafiore/1.0")
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(30_000)
                .setAllowCrossProtocolRedirects(true)

            upstreamFactory = DefaultDataSource.Factory(appContext, httpDataSourceFactory)

            cacheDataSourceFactory = CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(upstreamFactory)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

            initialized = true
        }
    }
}

