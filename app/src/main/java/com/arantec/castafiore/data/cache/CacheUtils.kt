package com.arantec.castafiore.data.cache

import com.arantec.castafiore.data.models.*
import com.google.gson.reflect.TypeToken
import java.lang.reflect.Type

/**
 * Utilidades para manejar tipos de datos en el sistema de cache
 */
object CacheTypes {

    // Tipos para listas
    val SONG_LIST_TYPE: Type = object : TypeToken<List<Song>>() {}.type
    val ALBUM_LIST_TYPE: Type = object : TypeToken<List<Album>>() {}.type
    val ARTIST_LIST_TYPE: Type = object : TypeToken<List<Artist>>() {}.type
    val PLAYLIST_LIST_TYPE: Type = object : TypeToken<List<Playlist>>() {}.type

    // Tipos para objetos individuales
    val SONG_TYPE: Type = object : TypeToken<Song>() {}.type
    val ALBUM_TYPE: Type = object : TypeToken<Album>() {}.type
    val ARTIST_TYPE: Type = object : TypeToken<Artist>() {}.type
    val PLAYLIST_TYPE: Type = object : TypeToken<Playlist>() {}.type

    // Tipos para respuestas de búsqueda
    val SEARCH_RESULT_TYPE: Type = object : TypeToken<Triple<List<Song>, List<Album>, List<Artist>>>() {}.type

    // Tipos para respuestas específicas
    val TOP_SONGS_TYPE: Type = object : TypeToken<List<Song>>() {}.type
    val ARTIST_ALBUMS_TYPE: Type = object : TypeToken<List<Album>>() {}.type

    // Tipos para datos booleanos (favoritos, etc.)
    val BOOLEAN_TYPE: Type = object : TypeToken<Boolean>() {}.type
}

/**
 * Generador de claves de cache consistentes
 */
object CacheKeys {

    // Claves para listas generales
    const val ALL_SONGS = "all_songs"
    const val ALL_ALBUMS = "all_albums"
    const val ALL_ARTISTS = "all_artists"
    const val ALL_PLAYLISTS = "all_playlists"
    const val RANDOM_SONGS = "random_songs"

    // Claves para datos específicos de entidades
    fun albumDetail(albumId: String) = "album_detail_$albumId"
    fun artistDetail(artistId: String) = "artist_detail_$artistId"
    fun playlistDetail(playlistId: String) = "playlist_detail_$playlistId"

    // Claves para relaciones
    fun artistAlbums(artistId: String) = "artist_albums_$artistId"
    fun artistTopSongs(artistName: String, count: Int) = "artist_top_songs_${artistName}_$count"
    fun albumSongs(albumId: String) = "album_songs_$albumId"
    fun playlistSongs(playlistId: String) = "playlist_songs_$playlistId"

    // Claves para búsquedas
    fun search(query: String) = "search_$query"

    // Claves para estados de favoritos
    fun songFavorite(songId: String) = "song_favorite_$songId"
    fun albumFavorite(albumId: String) = "album_favorite_$albumId"
    fun artistFavorite(artistId: String) = "artist_favorite_$artistId"

    // Claves para listas de favoritos
    const val FAVORITE_SONGS = "favorite_songs"
    const val FAVORITE_ALBUMS = "favorite_albums"
    const val FAVORITE_ARTISTS = "favorite_artists"

    // Claves para estadísticas y metadata
    fun albumsByGenre(genre: String) = "albums_by_genre_$genre"
    fun songsByGenre(genre: String) = "songs_by_genre_$genre"
    fun recentlyPlayed(userId: String) = "recently_played_$userId"
    fun mostPlayed(userId: String) = "most_played_$userId"

    // Clave para artistas similares (versionada para evitar entradas antiguas vacías)
    fun similarArtists(artistId: String) = "similar_artists_v2_$artistId"

    // Clave para canciones similares (versionada para evitar entradas antiguas vacías)
    fun similarSongs(songId: String, size: Int) = "similar_songs_v1_${songId}_${size}"
}

/**
 * Estrategias de invalidación de cache
 */
object CacheInvalidation {

    /**
     * Invalidar cache relacionado con un artista específico
     */
    fun invalidateArtistCache(cacheManager: CacheManager, artistId: String) {
        cacheManager.invalidateCache(CacheKeys.artistDetail(artistId))
        cacheManager.invalidateCache(CacheKeys.artistAlbums(artistId))
        cacheManager.invalidateCache(CacheKeys.artistFavorite(artistId))
        cacheManager.invalidateCache(CacheKeys.similarArtists(artistId))
        // También invalidar listas generales que podrían incluir este artista
        cacheManager.invalidateCache(CacheKeys.ALL_ARTISTS)
        cacheManager.invalidateCache(CacheKeys.FAVORITE_ARTISTS)
    }

    /**
     * Invalidar cache relacionado con un álbum específico
     */
    fun invalidateAlbumCache(cacheManager: CacheManager, albumId: String) {
        cacheManager.invalidateCache(CacheKeys.albumDetail(albumId))
        cacheManager.invalidateCache(CacheKeys.albumSongs(albumId))
        cacheManager.invalidateCache(CacheKeys.albumFavorite(albumId))
        // También invalidar listas generales
        cacheManager.invalidateCache(CacheKeys.ALL_ALBUMS)
        cacheManager.invalidateCache(CacheKeys.FAVORITE_ALBUMS)
    }

    /**
     * Invalidar cache relacionado con una canción específica
     */
    fun invalidateSongCache(cacheManager: CacheManager, songId: String) {
        cacheManager.invalidateCache(CacheKeys.songFavorite(songId))
        // Invalidar listas que podrían incluir esta canción
        cacheManager.invalidateCache(CacheKeys.ALL_SONGS)
        cacheManager.invalidateCache(CacheKeys.FAVORITE_SONGS)
        cacheManager.invalidateCache(CacheKeys.RANDOM_SONGS)
    }

    /**
     * Invalidar cache relacionado con playlists
     */
    fun invalidatePlaylistCache(cacheManager: CacheManager, playlistId: String) {
        cacheManager.invalidateCache(CacheKeys.playlistDetail(playlistId))
        cacheManager.invalidateCache(CacheKeys.playlistSongs(playlistId))
        cacheManager.invalidateCache(CacheKeys.ALL_PLAYLISTS)
    }

    /**
     * Invalidar todo el cache relacionado con favoritos
     */
    fun invalidateFavoritesCache(cacheManager: CacheManager) {
        cacheManager.invalidateCache(CacheKeys.FAVORITE_SONGS)
        cacheManager.invalidateCache(CacheKeys.FAVORITE_ALBUMS)
        cacheManager.invalidateCache(CacheKeys.FAVORITE_ARTISTS)
    }

    /**
     * Invalidar cache al cambiar de servidor o usuario
     */
    fun invalidateAllCache(cacheManager: CacheManager) {
        cacheManager.clearAllCache()
    }
}
