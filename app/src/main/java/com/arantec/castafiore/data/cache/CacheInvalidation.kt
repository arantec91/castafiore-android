package com.arantec.castafiore.data.cache

/**
 * Cache invalidation utilities
 */
object CacheInvalidation {

    /**
     * Invalidate all cache entries
     */
    fun invalidateAllCache(cacheManager: CacheManager) {
        cacheManager.clearAllCache()
    }

    /**
     * Invalidate home screen related cache entries
     */
    fun invalidateHomeLists(cacheManager: CacheManager) {
        cacheManager.invalidateCache(CacheKeys.NEWEST_ALBUMS)
        cacheManager.invalidateCache(CacheKeys.RECENTLY_PLAYED)
        cacheManager.invalidateCache(CacheKeys.MOST_PLAYED)
        cacheManager.invalidateCache(CacheKeys.RANDOM_SONGS)
    }

    /**
     * Invalidate artist related cache
     */
    fun invalidateArtistCache(cacheManager: CacheManager, artistId: String? = null) {
        if (artistId != null) {
            cacheManager.invalidateCache("${CacheKeys.ARTIST_PREFIX}$artistId")
            cacheManager.invalidateCache("${CacheKeys.ARTIST_ALBUMS_PREFIX}$artistId")
            cacheManager.invalidateCache("${CacheKeys.ARTIST_SONGS_PREFIX}$artistId")
            cacheManager.invalidateCache(CacheKeys.artistDetail(artistId))
            cacheManager.invalidateCache(CacheKeys.artistAlbums(artistId))
            cacheManager.invalidateCache(CacheKeys.artistFavorite(artistId))
            cacheManager.invalidateCache(CacheKeys.similarArtists(artistId))
        } else {
            cacheManager.invalidateCache(CacheKeys.ARTISTS_LIST)
        }
        // También invalidar listas generales que podrían incluir este artista
        cacheManager.invalidateCache(CacheKeys.ALL_ARTISTS)
        cacheManager.invalidateCache(CacheKeys.FAVORITE_ARTISTS)
    }

    /**
     * Invalidate album related cache
     */
    fun invalidateAlbumCache(cacheManager: CacheManager, albumId: String? = null) {
        if (albumId != null) {
            cacheManager.invalidateCache("${CacheKeys.ALBUM_PREFIX}$albumId")
            cacheManager.invalidateCache("${CacheKeys.ALBUM_SONGS_PREFIX}$albumId")
            cacheManager.invalidateCache(CacheKeys.albumDetail(albumId))
            cacheManager.invalidateCache(CacheKeys.albumSongs(albumId))
            cacheManager.invalidateCache(CacheKeys.albumFavorite(albumId))
        } else {
            cacheManager.invalidateCache(CacheKeys.ALBUMS_LIST)
        }
        // También invalidar listas generales
        cacheManager.invalidateCache(CacheKeys.ALL_ALBUMS)
        cacheManager.invalidateCache(CacheKeys.FAVORITE_ALBUMS)
    }

    /**
     * Invalidate song related cache
     */
    fun invalidateSongCache(cacheManager: CacheManager, songId: String) {
        cacheManager.invalidateCache(CacheKeys.songFavorite(songId))
        // Invalidar listas que podrían incluir esta canción
        cacheManager.invalidateCache(CacheKeys.ALL_SONGS)
        cacheManager.invalidateCache(CacheKeys.FAVORITE_SONGS)
        cacheManager.invalidateCache(CacheKeys.RANDOM_SONGS)
    }

    /**
     * Invalidate playlist related cache
     */
    fun invalidatePlaylistCache(cacheManager: CacheManager, playlistId: String? = null) {
        if (playlistId != null) {
            cacheManager.invalidateCache("${CacheKeys.PLAYLIST_PREFIX}$playlistId")
            cacheManager.invalidateCache("${CacheKeys.PLAYLIST_SONGS_PREFIX}$playlistId")
            cacheManager.invalidateCache(CacheKeys.playlistDetail(playlistId))
            cacheManager.invalidateCache(CacheKeys.playlistSongs(playlistId))
        } else {
            cacheManager.invalidateCache(CacheKeys.PLAYLISTS_LIST)
        }
        cacheManager.invalidateCache(CacheKeys.ALL_PLAYLISTS)
    }

    /**
     * Invalidate starred/favorites cache
     */
    fun invalidateStarredCache(cacheManager: CacheManager) {
        cacheManager.invalidateCache(CacheKeys.STARRED_SONGS)
        cacheManager.invalidateCache(CacheKeys.STARRED_ALBUMS)
        cacheManager.invalidateCache(CacheKeys.STARRED_ARTISTS)

        // Clear individual starred status cache
        val allKeys = cacheManager.getAllCacheKeys()
        allKeys.filter {
            it.startsWith(CacheKeys.SONG_STARRED_PREFIX) ||
            it.startsWith(CacheKeys.ALBUM_STARRED_PREFIX) ||
            it.startsWith(CacheKeys.ARTIST_STARRED_PREFIX)
        }.forEach { key ->
            cacheManager.invalidateCache(key)
        }
    }

    /**
     * Invalidate todo el cache relacionado con favoritos
     */
    fun invalidateFavoritesCache(cacheManager: CacheManager) {
        cacheManager.invalidateCache(CacheKeys.FAVORITE_SONGS)
        cacheManager.invalidateCache(CacheKeys.FAVORITE_ALBUMS)
        cacheManager.invalidateCache(CacheKeys.FAVORITE_ARTISTS)
    }

    /**
     * Invalidate search cache
     */
    fun invalidateSearchCache(cacheManager: CacheManager) {
        val allKeys = cacheManager.getAllCacheKeys()
        allKeys.filter { it.startsWith(CacheKeys.SEARCH_PREFIX) }
            .forEach { key ->
                cacheManager.invalidateCache(key)
            }
    }
}
