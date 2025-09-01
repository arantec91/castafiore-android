package com.arantec.castafiore.data.network

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Interceptor that tracks in-flight requests for Navidrome API endpoints.
 * Only counts requests to the configured host whose path contains "/rest/",
 * excluding streaming, cover art, and playback-related endpoints to avoid UI overlay during playback.
 */
class InFlightInterceptor(private val host: String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        val encodedPath = url.encodedPath
        val shouldTrack = try {
            val matchesHost = url.host.equals(host, ignoreCase = true)
            val isApiPath = encodedPath.contains("/rest/")
            // Exclude endpoints that are not meaningful for UI blocking overlays
            val excluded = encodedPath.contains("/rest/stream", ignoreCase = true) ||
                    encodedPath.contains("/rest/download", ignoreCase = true) ||
                    encodedPath.contains("/rest/hls", ignoreCase = true) ||
                    encodedPath.contains("/rest/getCoverArt", ignoreCase = true) ||
                    encodedPath.contains("/rest/setNowPlaying", ignoreCase = true) ||
                    encodedPath.contains("/rest/scrobble", ignoreCase = true) ||
                    encodedPath.contains("/rest/getSimilarSongs2", ignoreCase = true)
            matchesHost && isApiPath && !excluded
        } catch (_: Exception) {
            false
        }
        if (shouldTrack) InFlightTracker.begin()
        return try {
            chain.proceed(request)
        } finally {
            if (shouldTrack) InFlightTracker.end()
        }
    }
}
