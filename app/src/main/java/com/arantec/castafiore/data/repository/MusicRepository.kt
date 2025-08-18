package com.arantec.castafiore.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.arantec.castafiore.data.models.*
import com.arantec.castafiore.data.network.NavidromeClient
import com.arantec.castafiore.data.cache.CacheManager
import com.arantec.castafiore.data.cache.CacheKeys
import com.arantec.castafiore.data.cache.CacheTypes
import com.arantec.castafiore.data.cache.CacheInvalidation
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.google.gson.JsonObject

class MusicRepository private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var INSTANCE: MusicRepository? = null

        fun getInstance(context: Context): MusicRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: MusicRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences("castafiore_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val cacheManager = CacheManager.getInstance(context)

    // Cache interno de auth para mantener token/salt consistentes
    @Volatile
    private var cachedAuth: Triple<String, String, String>? = null

    var serverUrl: String?
        get() = prefs.getString("server_url", null)
        set(value) {
            val oldValue = prefs.getString("server_url", null)
            prefs.edit().putString("server_url", value).apply()
            // Si cambió el servidor, limpiar todo el cache y auth
            if (oldValue != value) {
                CacheInvalidation.invalidateAllCache(cacheManager)
                cachedAuth = null
            }
        }

    var username: String?
        get() = prefs.getString("username", null)
        set(value) {
            val oldValue = prefs.getString("username", null)
            prefs.edit().putString("username", value).apply()
            // Si cambió el usuario, limpiar todo el cache y auth
            if (oldValue != value) {
                CacheInvalidation.invalidateAllCache(cacheManager)
                cachedAuth = null
            }
        }

    var password: String?
        get() = prefs.getString("password", null)
        set(value) {
            val old = prefs.getString("password", null)
            prefs.edit().putString("password", value).apply()
            if (old != value) {
                cachedAuth = null
            }
        }

    var continueWithSimilarEnabled: Boolean
        get() = prefs.getBoolean("continue_with_similar", true)
        set(value) {
            prefs.edit().putBoolean("continue_with_similar", value).apply()
        }

    // Preferencia de calidad de audio: true = Alta calidad (original), false = Básica (128 kbps)
    var highQualityEnabled: Boolean
        get() = prefs.getBoolean("high_quality_enabled", true)
        set(value) {
            prefs.edit().putBoolean("high_quality_enabled", value).apply()
        }

    fun isConfigured(): Boolean {
        return !serverUrl.isNullOrEmpty() && !username.isNullOrEmpty() && !password.isNullOrEmpty()
    }

    fun getAuthParams(): Triple<String, String, String> {
        // Devuelve auth cacheado para mantener el mismo token/salt
        cachedAuth?.let { return it }
        val user = username ?: throw IllegalStateException("Username not configured")
        val pass = password ?: throw IllegalStateException("Password not configured")
        val triple = NavidromeClient.generateAuthParams(user, pass)
        cachedAuth = triple
        return triple
    }

    suspend fun searchMusic(query: String): Result<Triple<List<Song>, List<Album>, List<Artist>>> {
        return cacheManager.getOrFetchSearch(
            query = query,
            type = CacheTypes.SEARCH_RESULT_TYPE
        ) {
            try {
                val (username, token, salt) = getAuthParams()
                val response = NavidromeClient.getApiService().search(
                    username = username,
                    token = token,
                    salt = salt,
                    version = "1.16.1",
                    client = "Castafiore",
                    query = query
                )

                if (response.isSuccessful) {
                    response.body()?.let { searchResponse ->
                        val responseData = searchResponse.subsonicResponse
                        val songs = responseData.searchResult3?.song ?: emptyList()
                        val albums = responseData.searchResult3?.album ?: emptyList()
                        val artists = responseData.searchResult3?.artist ?: emptyList()
                        Result.success(Triple(songs, albums, artists))
                    } ?: Result.failure(Exception("Empty response"))
                } else {
                    Result.failure(Exception("Error: ${response.code()}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun getRandomSongs(size: Int = 20): Result<List<Song>> {
        return cacheManager.getRandomSongs(
            key = CacheKeys.RANDOM_SONGS,
            type = CacheTypes.SONG_LIST_TYPE
        ) {
            try {
                val (username, token, salt) = getAuthParams()
                val response = NavidromeClient.getApiService().getRandomSongs(
                    username = username,
                    token = token,
                    salt = salt,
                    version = "1.16.1",
                    client = "Castafiore",
                    size = size
                )

                if (response.isSuccessful) {
                    response.body()?.let { songsResponse ->
                        val songs = songsResponse.subsonicResponse.randomSongs?.song ?: emptyList()
                        Result.success(songs)
                    } ?: Result.failure(Exception("Empty response"))
                } else {
                    Result.failure(Exception("HTTP Error: ${response.code()} - ${response.message()}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun getAlbums(): Result<List<Album>> {
        return cacheManager.getAlbums(
            key = CacheKeys.ALL_ALBUMS,
            type = CacheTypes.ALBUM_LIST_TYPE
        ) {
            try {
                val (username, token, salt) = getAuthParams()
                val response = NavidromeClient.getApiService().getAlbums(
                    username = username,
                    token = token,
                    salt = salt,
                    version = "1.16.1",
                    client = "Castafiore"
                )

                if (response.isSuccessful) {
                    response.body()?.let { albumsResponse ->
                        val albums = albumsResponse.subsonicResponse.albumList2?.album ?: emptyList()
                        Result.success(albums)
                    } ?: Result.failure(Exception("Empty response"))
                } else {
                    Result.failure(Exception("HTTP Error: ${response.code()} - ${response.message()}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun getAlbumDetail(albumId: String): Result<Album> {
        return cacheManager.getAlbumDetail(
            albumId = albumId,
            type = CacheTypes.ALBUM_TYPE
        ) {
            try {
                val (username, token, salt) = getAuthParams()
                val response = NavidromeClient.getApiService().getAlbum(
                    username = username,
                    token = token,
                    salt = salt,
                    version = "1.16.1",
                    client = "Castafiore",
                    id = albumId
                )

                if (response.isSuccessful) {
                    response.body()?.let { albumResponse ->
                        val album = albumResponse.subsonicResponse.album
                        if (album != null) {
                            Result.success(album)
                        } else {
                            Result.failure(Exception("Album not found"))
                        }
                    } ?: Result.failure(Exception("Empty response"))
                } else {
                    Result.failure(Exception("HTTP Error: ${response.code()} - ${response.message()}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun getNewestAlbums(): Result<List<Album>> {
        return cacheManager.getAlbums(
            key = "newest_albums",
            type = CacheTypes.ALBUM_LIST_TYPE
        ) {
            try {
                val (username, token, salt) = getAuthParams()
                val response = NavidromeClient.getApiService().getAlbums(
                    username = username,
                    token = token,
                    salt = salt,
                    version = "1.16.1",
                    client = "Castafiore",
                    type = "newest",
                    size = 40
                )

                if (response.isSuccessful) {
                    response.body()?.let { albumsResponse ->
                        val albums = albumsResponse.subsonicResponse.albumList2?.album ?: emptyList()
                        Result.success(albums)
                    } ?: Result.failure(Exception("Empty response"))
                } else {
                    Result.failure(Exception("HTTP Error: ${response.code()} - ${response.message()}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun getRecentlyPlayedAlbums(): Result<List<Album>> {
        return cacheManager.getAlbums(
            key = "recent_albums",
            type = CacheTypes.ALBUM_LIST_TYPE
        ) {
            try {
                val (username, token, salt) = getAuthParams()
                val response = NavidromeClient.getApiService().getAlbums(
                    username = username,
                    token = token,
                    salt = salt,
                    version = "1.16.1",
                    client = "Castafiore",
                    type = "recent",
                    size = 40
                )

                if (response.isSuccessful) {
                    response.body()?.let { albumsResponse ->
                        val albums = albumsResponse.subsonicResponse.albumList2?.album ?: emptyList()
                        Result.success(albums)
                    } ?: Result.failure(Exception("Empty response"))
                } else {
                    Result.failure(Exception("HTTP Error: ${response.code()} - ${response.message()}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun getMostPlayedAlbums(): Result<List<Album>> {
        return cacheManager.getAlbums(
            key = "frequent_albums",
            type = CacheTypes.ALBUM_LIST_TYPE
        ) {
            try {
                val (username, token, salt) = getAuthParams()
                val response = NavidromeClient.getApiService().getAlbums(
                    username = username,
                    token = token,
                    salt = salt,
                    version = "1.16.1",
                    client = "Castafiore",
                    type = "frequent",
                    size = 40
                )

                if (response.isSuccessful) {
                    response.body()?.let { albumsResponse ->
                        val albums = albumsResponse.subsonicResponse.albumList2?.album ?: emptyList()
                        Result.success(albums)
                    } ?: Result.failure(Exception("Empty response"))
                } else {
                    Result.failure(Exception("HTTP Error: ${response.code()} - ${response.message()}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    fun invalidateUsageLists() {
        cacheManager.invalidateCache("recent_albums")
        cacheManager.invalidateCache("frequent_albums")
    }

    fun invalidateHomeLists() {
        cacheManager.invalidateCache("newest_albums")
        cacheManager.invalidateCache("recent_albums")
        cacheManager.invalidateCache("frequent_albums")
    }

    suspend fun getArtists(): Result<List<Artist>> {
        return cacheManager.getArtists(
            key = CacheKeys.ALL_ARTISTS,
            type = CacheTypes.ARTIST_LIST_TYPE
        ) {
            try {
                val (username, token, salt) = getAuthParams()
                val response = NavidromeClient.getApiService().getArtists(
                    username = username,
                    token = token,
                    salt = salt,
                    version = "1.16.1",
                    client = "Castafiore"
                )

                if (response.isSuccessful) {
                    response.body()?.let { artistsResponse ->
                        val artists = artistsResponse.subsonicResponse.artists?.index?.flatMap { it.artist } ?: emptyList()
                        Result.success(artists)
                    } ?: Result.failure(Exception("Empty response"))
                } else {
                    Result.failure(Exception("HTTP Error: ${response.code()} - ${response.message()}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    // Métodos para manejar favoritos de álbumes con invalidación de cache
    suspend fun starAlbum(albumId: String): Result<Boolean> {
        return try {
            val (username, token, salt) = getAuthParams()
            val response = NavidromeClient.getApiService().starItem(
                username = username,
                token = token,
                salt = salt,
                version = "1.16.1",
                client = "Castafiore",
                id = albumId
            )

            if (response.isSuccessful) {
                response.body()?.let { starResponse ->
                    if (starResponse.subsonicResponse.status == "ok") {
                        // Invalidar cache relacionado con favoritos
                        CacheInvalidation.invalidateAlbumCache(cacheManager, albumId)
                        CacheInvalidation.invalidateFavoritesCache(cacheManager)
                        Result.success(true)
                    } else {
                        Result.failure(Exception("Failed to star album: ${starResponse.subsonicResponse.error?.message}"))
                    }
                } ?: Result.failure(Exception("Empty response"))
            } else {
                Result.failure(Exception("HTTP Error: ${response.code()} - ${response.message()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun unstarAlbum(albumId: String): Result<Boolean> {
        return try {
            val (username, token, salt) = getAuthParams()
            val response = NavidromeClient.getApiService().unstarItem(
                username = username,
                token = token,
                salt = salt,
                version = "1.16.1",
                client = "Castafiore",
                id = albumId
            )

            if (response.isSuccessful) {
                response.body()?.let { starResponse ->
                    if (starResponse.subsonicResponse.status == "ok") {
                        // Invalidar cache relacionado con favoritos
                        CacheInvalidation.invalidateAlbumCache(cacheManager, albumId)
                        CacheInvalidation.invalidateFavoritesCache(cacheManager)
                        Result.success(true)
                    } else {
                        Result.failure(Exception("Failed to unstar album: ${starResponse.subsonicResponse.error?.message}"))
                    }
                } ?: Result.failure(Exception("Empty response"))
            } else {
                Result.failure(Exception("HTTP Error: ${response.code()} - ${response.message()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getStarredAlbums(): Result<List<Album>> {
        return cacheManager.getAlbums(
            key = CacheKeys.FAVORITE_ALBUMS,
            type = CacheTypes.ALBUM_LIST_TYPE
        ) {
            try {
                val (username, token, salt) = getAuthParams()
                val response = NavidromeClient.getApiService().getStarredItems(
                    username = username,
                    token = token,
                    salt = salt,
                    version = "1.16.1",
                    client = "Castafiore"
                )

                if (response.isSuccessful) {
                    response.body()?.let { starredResponse ->
                        val albums = starredResponse.subsonicResponse.starred?.album ?: emptyList()
                        Result.success(albums)
                    } ?: Result.failure(Exception("Empty response"))
                } else {
                    Result.failure(Exception("HTTP Error: ${response.code()} - ${response.message()}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    // Método para verificar si un álbum está marcado como favorito con cache
    suspend fun isAlbumStarred(albumId: String): Result<Boolean> {
        return cacheManager.getOrFetch(
            key = CacheKeys.albumFavorite(albumId),
            ttl = 10 * 60 * 1000L, // 10 minutos
            type = CacheTypes.BOOLEAN_TYPE
        ) {
            try {
                val starredAlbums = getStarredAlbums()
                if (starredAlbums.isSuccess) {
                    val isStarred = starredAlbums.getOrNull()?.any { it.id == albumId } ?: false
                    Result.success(isStarred)
                } else {
                    Result.failure(starredAlbums.exceptionOrNull() ?: Exception("Failed to get starred albums"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    // Métodos para manejar artistas con cache
    suspend fun getArtistAlbums(artistId: String): Result<List<Album>> {
        return cacheManager.getAlbums(
            key = CacheKeys.artistAlbums(artistId),
            type = CacheTypes.ALBUM_LIST_TYPE
        ) {
            try {
                val (username, token, salt) = getAuthParams()
                val response = NavidromeClient.getApiService().getArtist(
                    username = username,
                    token = token,
                    salt = salt,
                    version = "1.16.1",
                    client = "Castafiore",
                    id = artistId
                )

                if (response.isSuccessful) {
                    val artistResponse = response.body()
                    val status = artistResponse?.subsonicResponse?.status

                    if (status == "ok") {
                        val albums = artistResponse.subsonicResponse.artist?.album ?: emptyList()
                        Result.success(albums)
                    } else {
                        val error = artistResponse?.subsonicResponse?.error
                        Result.failure(Exception("API Error: ${error?.message ?: "Unknown error"}"))
                    }
                } else {
                    Result.failure(Exception("HTTP Error: ${response.code()} ${response.message()}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun starArtist(artistId: String): Result<Boolean> {
        return try {
            val (username, token, salt) = getAuthParams()
            val response = NavidromeClient.getApiService().starItem(
                username = username,
                token = token,
                salt = salt,
                version = "1.16.1",
                client = "Castafiore",
                id = artistId
            )

            if (response.isSuccessful) {
                response.body()?.let { starResponse ->
                    if (starResponse.subsonicResponse.status == "ok") {
                        Result.success(true)
                    } else {
                        Result.failure(Exception("Failed to star artist: ${starResponse.subsonicResponse.error?.message}"))
                    }
                } ?: Result.failure(Exception("Empty response"))
            } else {
                Result.failure(Exception("HTTP Error: ${response.code()} - ${response.message()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun unstarArtist(artistId: String): Result<Boolean> {
        return try {
            val (username, token, salt) = getAuthParams()
            val response = NavidromeClient.getApiService().unstarItem(
                username = username,
                token = token,
                salt = salt,
                version = "1.16.1",
                client = "Castafiore",
                id = artistId
            )

            if (response.isSuccessful) {
                response.body()?.let { starResponse ->
                    if (starResponse.subsonicResponse.status == "ok") {
                        Result.success(true)
                    } else {
                        Result.failure(Exception("Failed to unstar artist: ${starResponse.subsonicResponse.error?.message}"))
                    }
                } ?: Result.failure(Exception("Empty response"))
            } else {
                Result.failure(Exception("HTTP Error: ${response.code()} - ${response.message()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getStarredArtists(): Result<List<Artist>> {
        return try {
            val (username, token, salt) = getAuthParams()
            val response = NavidromeClient.getApiService().getStarredItems(
                username = username,
                token = token,
                salt = salt,
                version = "1.16.1",
                client = "Castafiore"
            )

            if (response.isSuccessful) {
                response.body()?.let { starredResponse ->
                    val artists = starredResponse.subsonicResponse.starred?.artist ?: emptyList()
                    Result.success(artists)
                } ?: Result.failure(Exception("Empty response"))
            } else {
                Result.failure(Exception("HTTP Error: ${response.code()} - ${response.message()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun isArtistStarred(artistId: String): Result<Boolean> {
        return try {
            val starredArtists = getStarredArtists()
            if (starredArtists.isSuccess) {
                val isStarred = starredArtists.getOrNull()?.any { it.id == artistId }
                Result.success(isStarred ?: false)
            } else {
                Result.failure(starredArtists.exceptionOrNull() ?: Exception("Failed to get starred artists"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Método para obtener canciones de un álbum
    suspend fun getAlbumSongs(albumId: String): Result<List<Song>> {
        return try {
            val albumResult = getAlbumDetail(albumId)
            if (albumResult.isSuccess) {
                val album = albumResult.getOrNull()
                val songs = album?.songs ?: emptyList()
                Result.success(songs)
            } else {
                Result.failure(albumResult.exceptionOrNull() ?: Exception("Failed to get album"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Método para obtener las canciones más populares de un artista usando la API específica
    suspend fun getArtistTopSongs(artistName: String, count: Int = 25): Result<List<Song>> {
        return cacheManager.getSongs(
            key = CacheKeys.artistTopSongs(artistName, count),
            type = CacheTypes.SONG_LIST_TYPE
        ) {
            try {
                val (username, token, salt) = getAuthParams()
                val response = NavidromeClient.getApiService().getTopSongs(
                    username = username,
                    token = token,
                    salt = salt,
                    version = "1.16.1",
                    client = "Castafiore",
                    artist = artistName,
                    count = count
                )

                if (response.isSuccessful) {
                    val topSongsResponse = response.body()
                    val status = topSongsResponse?.subsonicResponse?.status

                    if (status == "ok") {
                        val songs = topSongsResponse.subsonicResponse.topSongs?.song ?: emptyList()
                        Result.success(songs)
                    } else {
                        val error = topSongsResponse?.subsonicResponse?.error
                        Result.failure(Exception("API Error: ${error?.message ?: "Unknown error"}"))
                    }
                } else {
                    Result.failure(Exception("HTTP Error: ${response.code()} ${response.message()}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    // Métodos para manejar playlists con cache
    suspend fun getPlaylists(): Result<List<Playlist>> {
        return cacheManager.getPlaylists(
            key = CacheKeys.ALL_PLAYLISTS,
            type = CacheTypes.PLAYLIST_LIST_TYPE
        ) {
            try {
                val (username, token, salt) = getAuthParams()
                val response = NavidromeClient.getApiService().getPlaylists(
                    username = username,
                    token = token,
                    salt = salt,
                    version = "1.16.1",
                    client = "Castafiore"
                )

                if (response.isSuccessful) {
                    response.body()?.let { playlistsResponse ->
                        val playlistsData = playlistsResponse.subsonicResponse.playlists?.playlist ?: emptyList()
                        val playlists = playlistsData.map { playlistData ->
                            Playlist(
                                id = playlistData.id,
                                name = playlistData.name,
                                comment = playlistData.comment,
                                owner = playlistData.owner,
                                public = playlistData.public ?: false,
                                songCount = playlistData.songCount ?: 0,
                                duration = playlistData.duration ?: 0,
                                created = playlistData.created,
                                changed = playlistData.changed,
                                coverArt = playlistData.coverArt
                            )
                        }
                        Result.success(playlists)
                    } ?: Result.failure(Exception("Empty response"))
                } else {
                    Result.failure(Exception("HTTP Error: ${response.code()} - ${response.message()}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun createPlaylist(name: String, comment: String? = null): Result<Playlist> {
        return try {
            val (username, token, salt) = getAuthParams()
            val response = NavidromeClient.getApiService().createPlaylist(
                username = username,
                token = token,
                salt = salt,
                version = "1.16.1",
                client = "Castafiore",
                name = name,
                comment = comment
            )

            if (response.isSuccessful) {
                response.body()?.let { playlistResponse ->
                    val playlistData = playlistResponse.subsonicResponse.playlist
                    if (playlistData != null) {
                        val playlist = Playlist(
                            id = playlistData.id,
                            name = playlistData.name,
                            comment = playlistData.comment,
                            owner = playlistData.owner,
                            public = playlistData.public ?: false,
                            songCount = playlistData.songCount ?: 0,
                            duration = playlistData.duration ?: 0,
                            created = playlistData.created,
                            changed = playlistData.changed,
                            coverArt = playlistData.coverArt
                        )
                        // Invalidar cache de playlists
                        cacheManager.invalidateCache(CacheKeys.ALL_PLAYLISTS)
                        Result.success(playlist)
                    } else {
                        Result.failure(Exception("Failed to create playlist"))
                    }
                } ?: Result.failure(Exception("Empty response"))
            } else {
                Result.failure(Exception("HTTP Error: ${response.code()} - ${response.message()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Método utilitario para obtener estadísticas de cache
    fun getCacheStats(): CacheManager.CacheStats {
        return cacheManager.getCacheStats()
    }

    // Método para limpiar cache manualmente
    fun clearCache() {
        cacheManager.clearAllCache()
    }

    // Método para limpiar solo cache expirado
    fun cleanupCache() {
        cacheManager.cleanupExpiredCache()
    }

    // Métodos para manejar favoritos de canciones con invalidación de cache
    suspend fun starSong(songId: String): Result<Boolean> {
        return try {
            val (username, token, salt) = getAuthParams()
            val response = NavidromeClient.getApiService().starItem(
                username = username,
                token = token,
                salt = salt,
                version = "1.16.1",
                client = "Castafiore",
                id = songId
            )

            if (response.isSuccessful) {
                response.body()?.let { starResponse ->
                    if (starResponse.subsonicResponse.status == "ok") {
                        // Invalidar cache relacionado con favoritos de canciones
                        CacheInvalidation.invalidateSongCache(cacheManager, songId)
                        CacheInvalidation.invalidateFavoritesCache(cacheManager)
                        Result.success(true)
                    } else {
                        Result.failure(Exception("Failed to star song: ${starResponse.subsonicResponse.error?.message}"))
                    }
                } ?: Result.failure(Exception("Empty response"))
            } else {
                Result.failure(Exception("HTTP Error: ${response.code()} - ${response.message()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun unstarSong(songId: String): Result<Boolean> {
        return try {
            val (username, token, salt) = getAuthParams()
            val response = NavidromeClient.getApiService().unstarItem(
                username = username,
                token = token,
                salt = salt,
                version = "1.16.1",
                client = "Castafiore",
                id = songId
            )

            if (response.isSuccessful) {
                response.body()?.let { starResponse ->
                    if (starResponse.subsonicResponse.status == "ok") {
                        // Invalidar cache relacionado con favoritos de canciones
                        CacheInvalidation.invalidateSongCache(cacheManager, songId)
                        CacheInvalidation.invalidateFavoritesCache(cacheManager)
                        Result.success(true)
                    } else {
                        Result.failure(Exception("Failed to unstar song: ${starResponse.subsonicResponse.error?.message}"))
                    }
                } ?: Result.failure(Exception("Empty response"))
            } else {
                Result.failure(Exception("HTTP Error: ${response.code()} - ${response.message()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getStarredSongs(): Result<List<Song>> {
        return cacheManager.getSongs(
            key = CacheKeys.FAVORITE_SONGS,
            type = CacheTypes.SONG_LIST_TYPE
        ) {
            try {
                val (username, token, salt) = getAuthParams()
                val response = NavidromeClient.getApiService().getStarredItems(
                    username = username,
                    token = token,
                    salt = salt,
                    version = "1.16.1",
                    client = "Castafiore"
                )

                if (response.isSuccessful) {
                    response.body()?.let { starredResponse ->
                        val songs = starredResponse.subsonicResponse.starred?.song ?: emptyList()
                        Result.success(songs)
                    } ?: Result.failure(Exception("Empty response"))
                } else {
                    Result.failure(Exception("HTTP Error: ${response.code()} - ${response.message()}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun isSongStarred(songId: String): Result<Boolean> {
        return cacheManager.getOrFetch(
            key = CacheKeys.songFavorite(songId),
            ttl = 10 * 60 * 1000L, // 10 minutos
            type = CacheTypes.BOOLEAN_TYPE
        ) {
            try {
                val starredSongs = getStarredSongs()
                if (starredSongs.isSuccess) {
                    val isStarred = starredSongs.getOrNull()?.any { it.id == songId } ?: false
                    Result.success(isStarred)
                } else {
                    Result.failure(starredSongs.exceptionOrNull() ?: Exception("Failed to get starred songs"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun addSongToPlaylist(playlistId: String, songId: String): Result<Boolean> {
        return try {
            val (username, token, salt) = getAuthParams()
            val response = NavidromeClient.getApiService().updatePlaylist(
                username = username,
                token = token,
                salt = salt,
                version = "1.16.1",
                client = "Castafiore",
                playlistId = playlistId,
                songIdToAdd = songId
            )

            if (response.isSuccessful) {
                response.body()?.let { playlistResponse ->
                    if (playlistResponse.subsonicResponse.status == "ok") {
                        // Invalidar cache de playlists
                        CacheInvalidation.invalidatePlaylistCache(cacheManager, playlistId)
                        Result.success(true)
                    } else {
                        Result.failure(Exception("Failed to add song to playlist: ${playlistResponse.subsonicResponse.error?.message}"))
                    }
                } ?: Result.failure(Exception("Empty response"))
            } else {
                Result.failure(Exception("HTTP Error: ${response.code()} - ${response.message()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deletePlaylist(playlistId: String): Result<Boolean> {
        return try {
            val (username, token, salt) = getAuthParams()
            val response = NavidromeClient.getApiService().deletePlaylist(
                username = username,
                token = token,
                salt = salt,
                version = "1.16.1",
                client = "Castafiore",
                id = playlistId
            )

            if (response.isSuccessful) {
                response.body()?.let { deleteResponse ->
                    if (deleteResponse.subsonicResponse.status == "ok") {
                        // Invalidar cache de playlists
                        CacheInvalidation.invalidatePlaylistCache(cacheManager, playlistId)
                        Result.success(true)
                    } else {
                        Result.failure(Exception("Failed to delete playlist: ${deleteResponse.subsonicResponse.error?.message}"))
                    }
                } ?: Result.failure(Exception("Empty response"))
            } else {
                Result.failure(Exception("HTTP Error: ${response.code()} - ${response.message()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getRandomAlbums(size: Int = 50): Result<List<Album>> {
        return cacheManager.getRandomAlbums(
            key = "random_albums_$size",
            type = CacheTypes.ALBUM_LIST_TYPE
        ) {
            try {
                val (username, token, salt) = getAuthParams()
                // Obtener canciones aleatorias para extraer álbumes únicos
                val response = NavidromeClient.getApiService().getRandomSongs(
                    username = username,
                    token = token,
                    salt = salt,
                    version = "1.16.1",
                    client = "Castafiore",
                    size = size
                )

                if (response.isSuccessful) {
                    response.body()?.let { randomSongsResponse ->
                        val songs = randomSongsResponse.subsonicResponse.randomSongs?.song ?: emptyList()

                        // Extraer álbumes únicos de las canciones aleatorias
                        val uniqueAlbums = songs
                            .mapNotNull { song ->
                                song.albumId?.let { id ->
                                    Album(
                                        id = id,
                                        name = song.album,
                                        artist = song.artist,
                                        artistId = song.artistId ?: "",
                                        coverArt = song.coverArt,
                                        songCount = 0,
                                        duration = 0,
                                        created = "",
                                        year = song.year,
                                        genre = song.genre
                                    )
                                }
                            }
                            .distinctBy { it.id }
                            .shuffled()

                        Result.success(uniqueAlbums)
                    } ?: Result.failure(Exception("Empty response"))
                } else {
                    Result.failure(Exception("HTTP Error: ${response.code()} - ${response.message()}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun getSimilarSongs(baseSongId: String, size: Int = 15): Result<List<Song>> {
        return try {
            val (username, token, salt) = getAuthParams()
            val response = NavidromeClient.getApiService().getSimilarSongs(
                username = username,
                token = token,
                salt = salt,
                version = "1.16.1",
                client = "Castafiore",
                id = baseSongId,
                size = size
            )

            if (response.isSuccessful) {
                response.body()?.let { similarResponse ->
                    val songs = similarResponse.subsonicResponse.similarSongs?.song ?: emptyList()
                    Result.success(songs)
                } ?: Result.failure(Exception("Empty response"))
            } else {
                Result.failure(Exception("HTTP Error: ${response.code()} - ${response.message()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Obtener información de una playlist específica
    suspend fun getPlaylistInfo(playlistId: String): Result<Playlist> {
        return cacheManager.getPlaylists(
            key = CacheKeys.playlistDetail(playlistId),
            type = CacheTypes.PLAYLIST_TYPE
        ) {
            try {
                val (username, token, salt) = getAuthParams()
                val response = NavidromeClient.getApiService().getPlaylist(
                    username = username,
                    token = token,
                    salt = salt,
                    version = "1.16.1",
                    client = "Castafiore",
                    id = playlistId
                )

                if (response.isSuccessful) {
                    val body = response.body()
                    val status = body?.subsonicResponse?.status
                    if (status == "ok") {
                        val p = body.subsonicResponse.playlist
                        if (p != null) {
                            val playlist = Playlist(
                                id = p.id,
                                name = p.name,
                                comment = p.comment,
                                owner = p.owner,
                                public = p.public ?: false,
                                songCount = p.songCount ?: (p.entries?.size ?: 0),
                                duration = p.duration ?: (p.entries?.sumOf { it.duration } ?: 0),
                                created = p.created,
                                changed = p.changed,
                                coverArt = p.coverArt
                            )
                            Result.success(playlist)
                        } else {
                            Result.failure(Exception("Playlist not found"))
                        }
                    } else {
                        val error = body?.subsonicResponse?.error
                        Result.failure(Exception("API Error: ${error?.message ?: "Unknown"}"))
                    }
                } else {
                    Result.failure(Exception("HTTP Error: ${response.code()} - ${response.message()}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    // Obtener canciones de una playlist específica
    suspend fun getPlaylistSongs(playlistId: String): Result<List<Song>> {
        return cacheManager.getSongs(
            key = CacheKeys.playlistSongs(playlistId),
            type = CacheTypes.SONG_LIST_TYPE
        ) {
            try {
                val (username, token, salt) = getAuthParams()
                val response = NavidromeClient.getApiService().getPlaylist(
                    username = username,
                    token = token,
                    salt = salt,
                    version = "1.16.1",
                    client = "Castafiore",
                    id = playlistId
                )

                if (response.isSuccessful) {
                    val body = response.body()
                    val status = body?.subsonicResponse?.status
                    if (status == "ok") {
                        val songs = body.subsonicResponse.playlist?.entries ?: emptyList()
                        Result.success(songs)
                    } else {
                        val error = body?.subsonicResponse?.error
                        Result.failure(Exception("API Error: ${error?.message ?: "Unknown"}"))
                    }
                } else {
                    Result.failure(Exception("HTTP Error: ${response.code()} - ${response.message()}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun updatePlaylistMetadata(
        playlistId: String,
        name: String? = null,
        comment: String? = null,
        isPublic: Boolean? = null
    ): Result<Boolean> {
        return try {
            val (username, token, salt) = getAuthParams()
            val response = NavidromeClient.getApiService().updatePlaylist(
                username = username,
                token = token,
                salt = salt,
                version = "1.16.1",
                client = "Castafiore",
                playlistId = playlistId,
                name = name,
                comment = comment,
                isPublic = isPublic
            )

            if (response.isSuccessful) {
                val body = response.body()
                if (body?.subsonicResponse?.status == "ok") {
                    // Invalidate playlist caches
                    CacheInvalidation.invalidatePlaylistCache(cacheManager, playlistId)
                    Result.success(true)
                } else {
                    Result.failure(Exception("Failed to update playlist"))
                }
            } else {
                Result.failure(Exception("HTTP Error: ${response.code()} - ${response.message()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun removeSongFromPlaylist(playlistId: String, indexToRemove: Int): Result<Boolean> {
        return try {
            val (username, token, salt) = getAuthParams()
            val response = NavidromeClient.getApiService().updatePlaylist(
                username = username,
                token = token,
                salt = salt,
                version = "1.16.1",
                client = "Castafiore",
                playlistId = playlistId,
                songIndexToRemove = indexToRemove
            )

            if (response.isSuccessful) {
                val body = response.body()
                if (body?.subsonicResponse?.status == "ok") {
                    CacheInvalidation.invalidatePlaylistCache(cacheManager, playlistId)
                    Result.success(true)
                } else {
                    Result.failure(Exception("Failed to remove song from playlist"))
                }
            } else {
                Result.failure(Exception("HTTP Error: ${response.code()} - ${response.message()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Reportar canción en reproducción (now playing)
    suspend fun reportNowPlaying(songId: String): Result<Boolean> {
        return try {
            val (username, token, salt) = getAuthParams()
            val response = NavidromeClient.getApiService().setNowPlaying(
                username = username,
                token = token,
                salt = salt,
                version = "1.16.1",
                client = "Castafiore",
                id = songId
            )
            if (response.isSuccessful) {
                val status = response.body()?.subsonicResponse?.status
                if (status == "ok") Result.success(true) else Result.failure(Exception("setNowPlaying failed"))
            } else {
                Result.failure(Exception("HTTP Error: ${response.code()} - ${response.message()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Scrobble de canción reproducida; invalida caches de recientes y frecuentes
    suspend fun scrobbleSong(songId: String, playedAtMillis: Long? = System.currentTimeMillis(), submission: Boolean = true): Result<Boolean> {
        return try {
            val (username, token, salt) = getAuthParams()
            val response = NavidromeClient.getApiService().scrobble(
                username = username,
                token = token,
                salt = salt,
                version = "1.16.1",
                client = "Castafiore",
                id = songId,
                time = playedAtMillis,
                submission = submission
            )
            if (response.isSuccessful) {
                val status = response.body()?.subsonicResponse?.status
                if (status == "ok") {
                    // Invalidar listas dependientes
                    cacheManager.invalidateCache("recent_albums")
                    cacheManager.invalidateCache("frequent_albums")
                    Result.success(true)
                } else {
                    Result.failure(Exception("scrobble failed"))
                }
            } else {
                Result.failure(Exception("HTTP Error: ${response.code()} - ${response.message()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Ensure cache is scoped per (serverUrl|username) and reset when it changes
    fun ensureCacheScope() {
        val server = serverUrl
        val user = username
        if (server.isNullOrEmpty() || user.isNullOrEmpty()) return
        val currentScope = "$server|$user"
        val storedScope = prefs.getString("cache_scope", null)
        if (storedScope != currentScope) {
            // New scope detected: clear all caches and persist scope
            cacheManager.clearAllCache()
            prefs.edit().putString("cache_scope", currentScope).apply()
        }
    }

    suspend fun getCurrentUserInfo(): Result<JsonObject> {
        return try {
            val (user, token, salt) = getAuthParams()
            val response = NavidromeClient.getApiService().getUser(
                username = user,
                token = token,
                salt = salt,
                version = "1.16.1",
                client = "Castafiore",
                userToGet = user
            )
            if (response.isSuccessful) {
                val body = response.body()
                val status = body?.subsonicResponse?.status
                if (status == "ok" && body.subsonicResponse.user != null) {
                    Result.success(body.subsonicResponse.user!!)
                } else {
                    val msg = body?.subsonicResponse?.error?.message ?: "Unknown error"
                    Result.failure(IllegalStateException("API error: $msg"))
                }
            } else {
                Result.failure(IllegalStateException("HTTP ${response.code()} ${response.message()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getSimilarArtists(artistId: String): Result<List<Artist>> {
        return cacheManager.getArtists(
            key = CacheKeys.similarArtists(artistId),
            type = CacheTypes.ARTIST_LIST_TYPE
        ) {
            try {
                val (username, token, salt) = getAuthParams()
                val response = NavidromeClient.getApiService().getArtistInfo2(
                    username = username,
                    token = token,
                    salt = salt,
                    version = "1.16.1",
                    client = "Castafiore",
                    id = artistId
                )
                if (response.isSuccessful) {
                    val body = response.body()
                    val status = body?.subsonicResponse?.status
                    if (status == "ok") {
                        val info = body.subsonicResponse.artistInfo2
                        // Prefer nested structure, fall back to flat array if necessary
                        val artists = when {
                            info?.similarArtists?.artist?.isNotEmpty() == true -> info.similarArtists?.artist ?: emptyList()
                            info?.similarArtist?.isNotEmpty() == true -> info.similarArtist ?: emptyList()
                            else -> emptyList()
                        }
                        Result.success(artists)
                    } else {
                        val err = body?.subsonicResponse?.error
                        Result.failure(Exception("API Error: ${err?.message ?: "Unknown"}"))
                    }
                } else {
                    Result.failure(Exception("HTTP Error: ${response.code()} - ${response.message()}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    // Método helper síncrono para leer estado favorito de álbum desde cache (si existe)
    fun peekAlbumStarred(albumId: String): Boolean? {
        return cacheManager.peek(CacheKeys.albumFavorite(albumId), 10 * 60 * 1000L, CacheTypes.BOOLEAN_TYPE)
    }

    // Método helper síncrono para leer estado favorito de artista desde cache (si existe)
    fun peekArtistStarred(artistId: String): Boolean? {
        return cacheManager.peek(CacheKeys.artistFavorite(artistId), 10 * 60 * 1000L, CacheTypes.BOOLEAN_TYPE)
    }
}
