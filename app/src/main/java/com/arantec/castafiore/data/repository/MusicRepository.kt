package com.arantec.castafiore.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.arantec.castafiore.data.models.*
import com.arantec.castafiore.data.model.*
import com.arantec.castafiore.data.network.CastafioreClient
import com.arantec.castafiore.data.cache.CacheManager
import com.arantec.castafiore.data.cache.CacheKeys
import com.arantec.castafiore.data.cache.CacheTypes
import com.arantec.castafiore.data.cache.CacheInvalidation
import java.security.MessageDigest
import java.util.*

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
    private val cacheManager = CacheManager.getInstance(context)
    private val recentPlaysStore = com.arantec.castafiore.data.cache.RecentPlaysStore.getInstance(context)

    // Cliente para la API de Castafiore
    private var castafioreClient: CastafioreClient? = null

    // TTL usado para estados booleanos de favoritos
    private val FAVORITE_TTL = 10 * 60 * 1000L

    // Helper interno para actualización optimista del estado favorito
    private fun updateFavoriteCacheOptimistic(key: String, value: Boolean) {
        try {
            cacheManager.putCache(key, value, CacheTypes.BOOLEAN, FAVORITE_TTL)
        } catch (e: Exception) {
            Log.w("MusicRepository", "Failed optimistic favorite cache update for $key", e)
        }
    }

    // Cache interno de auth token
    @Volatile
    private var authToken: String? = null

    var serverUrl: String?
        get() = prefs.getString("server_url", null)
        set(value) {
            val oldValue = prefs.getString("server_url", null)
            prefs.edit { putString("server_url", value) }

            // Si cambió el servidor, limpiar todo el cache y auth
            if (oldValue != value) {
                CacheInvalidation.invalidateAllCache(cacheManager)
                authToken = null
                castafioreClient = null

                // Limpiar token persistido
                prefs.edit {
                    remove("auth_token")
                }

                // Crear nuevo cliente si hay URL
                value?.let {
                    castafioreClient = CastafioreClient(context, it)
                }
            }
        }

    var username: String?
        get() = prefs.getString("username", null)
        set(value) {
            val oldValue = prefs.getString("username", null)
            prefs.edit { putString("username", value) }

            // Si cambió el usuario, limpiar todo el cache y auth
            if (oldValue != value) {
                CacheInvalidation.invalidateAllCache(cacheManager)
                authToken = null

                // Limpiar token persistido
                prefs.edit {
                    remove("auth_token")
                }
            }
        }

    var password: String?
        get() = prefs.getString("password", null)
        set(value) {
            val old = prefs.getString("password", null)
            prefs.edit { putString("password", value) }
            if (old != value) {
                authToken = null

                // Limpiar token persistido
                prefs.edit {
                    remove("auth_token")
                }
            }
        }

    var continueWithSimilarEnabled: Boolean
        get() = prefs.getBoolean("continue_with_similar", true)
        set(value) {
            prefs.edit { putBoolean("continue_with_similar", value) }
        }

    // Preferencia de calidad de audio: true = Alta calidad (original), false = Básica (128 kbps)
    var highQualityEnabled: Boolean
        get() = prefs.getBoolean("high_quality_enabled", true)
        set(value) {
            prefs.edit { putBoolean("high_quality_enabled", value) }
        }

    // Preferencia de reproducción sin pausas: elimina las pausas entre canciones
    var seamlessPlaybackEnabled: Boolean
        get() = prefs.getBoolean("seamless_playback_enabled", false)
        set(value) {
            prefs.edit { putBoolean("seamless_playback_enabled", value) }
        }

    fun isConfigured(): Boolean {
        return !serverUrl.isNullOrEmpty() && !username.isNullOrEmpty() && !password.isNullOrEmpty()
    }

    private fun getClient(): CastafioreClient {
        if (castafioreClient == null) {
            // You may need to provide the correct baseUrl here
            castafioreClient = CastafioreClient.getInstance(context) ?: CastafioreClient(context, "https://your-api-base-url/")
        }
        return castafioreClient!!
    }

    private suspend fun ensureAuthenticated(): Boolean {
        // Para API Subsonic, no necesitamos tokens JWT
        // La autenticación se hace con username/password/salt en cada request
        val user = username ?: return false
        val pass = password ?: return false
        
        // Verificar que las credenciales estén configuradas
        if (user.isNotEmpty() && pass.isNotEmpty()) {
            getClient().setCredentials(user, pass)
            return true
        }
        
        return false
    }

    // Auth params for subsonic API
    fun getAuthParams(): Triple<String, String, String> {
        val user = username ?: ""
        val pass = password ?: ""
        val salt = UUID.randomUUID().toString().substring(0, 8)
        val token = md5("$pass$salt")
        return Triple(user, token, salt)
    }

    fun generateAuthParams(username: String, password: String): Triple<String, String, String> {
        val salt = UUID.randomUUID().toString().substring(0, 8)
        val token = md5("$password$salt")
        return Triple(username, token, salt)
    }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun initialize(serverUrl: String, username: String, password: String) {
        this.serverUrl = serverUrl
        this.username = username
        this.password = password
    }

    fun getApiService() = getClient()

    suspend fun searchMusic(query: String): Result<Triple<List<Song>, List<Album>, List<Artist>>> {
        return try {
            if (!ensureAuthenticated()) {
                return Result.failure(Exception("Authentication failed"))
            }

            val response = getClient().search(query, null, 100, 0)

            if (response.isSuccessful && response.body()?.success == true) {
                response.body()?.data?.let { searchResponse ->
                    val songs = searchResponse.songs?.map { songResponse ->
                        // Sanitize artist name: filter out invalid values like "."
                        val artistName = songResponse.artist?.name?.takeIf {
                            it.isNotBlank() && it != "."
                        } ?: ""

                        Song(
                            id = songResponse.id,
                            title = songResponse.title,
                            artist = artistName,
                            album = songResponse.album?.title ?: "",
                            duration = songResponse.duration,
                            artistId = songResponse.artistId,
                            albumId = songResponse.albumId,
                            coverArt = songResponse.coverArt
                        )
                    } ?: emptyList()

                    val albums = searchResponse.albums?.map { albumResponse ->
                        Album(
                            id = albumResponse.id,
                            name = albumResponse.title ?: "",
                            artist = albumResponse.artist ?: "",
                            artistId = albumResponse.artistId,
                            songCount = albumResponse.songCount ?: 0,
                            duration = albumResponse.duration ?: 0,
                            year = albumResponse.year,
                            genre = albumResponse.genre,
                            coverArt = albumResponse.coverArt
                        )
                    } ?: emptyList()

                    val artists = searchResponse.artists?.map { artistResponse ->
                        Artist(
                            id = artistResponse.id,
                            name = artistResponse.name,
                            albumCount = artistResponse.albumCount ?: 0
                        )
                    } ?: emptyList()

                    Result.success(Triple(songs, albums, artists))
                } ?: Result.failure(Exception("Empty response"))
            } else {
                val errorMessage = response.body()?.error ?: "Search failed"
                Result.failure(Exception(errorMessage))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getRandomSongs(size: Int = 20): Result<List<Song>> {
        return try {
            if (!ensureAuthenticated()) {
                return Result.failure(Exception("Authentication failed"))
            }

            val response = getClient().getRandomSongs(size)

            if (response.isSuccessful && response.body()?.success == true) {
                response.body()?.data?.let { songs ->
                    val songList = songs.map { songResponse ->
                        Song(
                            id = songResponse.id,
                            title = songResponse.title,
                            artist = songResponse.artist?.name ?: "",
                            album = songResponse.album?.title ?: "",
                            duration = songResponse.duration,
                            track = songResponse.track,
                            year = songResponse.year,
                            genre = songResponse.genre,
                            artistId = songResponse.artistId,
                            albumId = songResponse.albumId,
                            bitRate = songResponse.bitRate,
                            size = songResponse.size,
                            coverArt = songResponse.coverArt,
                            suffix = songResponse.suffix
                        )
                    }
                    Result.success(songList)
                } ?: Result.failure(Exception("Empty response"))
            } else {
                val errorMessage = response.body()?.error ?: "Failed to get random songs"
                Result.failure(Exception(errorMessage))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getAlbums(): Result<List<Album>> {
        return try {
            if (!ensureAuthenticated()) {
                return Result.failure(Exception("Authentication failed"))
            }

            // Use Subsonic API instead of REST API
            val response = getClient().getAlbumsSubsonic(type = "alphabeticalByName", size = 500)

            if (response.isSuccessful) {
                val subsonicResponse = response.body()?.subsonicResponse
                
                if (subsonicResponse?.status == "ok") {
                    subsonicResponse.albumList2?.albums?.let { albums ->
                        val albumList = albums.map { albumResponse ->
                            Album(
                                id = albumResponse.id,
                                name = albumResponse.name,
                                artist = albumResponse.artist ?: "",
                                artistId = albumResponse.artistId,
                                songCount = albumResponse.songCount ?: 0,
                                duration = albumResponse.duration ?: 0,
                                year = albumResponse.year,
                                genre = albumResponse.genre,
                                coverArt = albumResponse.coverArt
                            )
                        }
                        Result.success(albumList)
                    } ?: Result.failure(Exception("Empty album list"))
                } else {
                    val errorMessage = subsonicResponse?.error?.message ?: "Failed to get albums"
                    Result.failure(Exception(errorMessage))
                }
            } else {
                Result.failure(Exception("HTTP ${response.code()}: ${response.message()}"))
            }
        } catch (e: Exception) {
            Log.e("MusicRepository", "Error getting albums", e)
            Result.failure(e)
        }
    }

    suspend fun getArtists(): Result<List<Artist>> {
        return try {
            if (!ensureAuthenticated()) {
                return Result.failure(Exception("Authentication failed"))
            }

            val response = getClient().getArtists()

            if (response.isSuccessful && response.body()?.success == true) {
                response.body()?.data?.let { artists ->
                    val artistList = artists.map { artistResponse ->
                        Artist(
                            id = artistResponse.id,
                            name = artistResponse.name,
                            albumCount = artistResponse.albumCount ?: 0
                        )
                    }
                    Result.success(artistList)
                } ?: Result.failure(Exception("Empty response"))
            } else {
                val errorMessage = response.body()?.error ?: "Failed to get artists"
                Result.failure(Exception(errorMessage))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getAlbumDetail(albumId: String): Result<AlbumDetail> {
        return try {
            if (!ensureAuthenticated()) {
                return Result.failure(Exception("Authentication failed"))
            }

            // Use Subsonic API instead of REST API
            val response = getClient().getAlbumSubsonic(albumId)

            if (response.isSuccessful) {
                val subsonicResponse = response.body()?.subsonicResponse

                if (subsonicResponse?.status == "ok") {
                    subsonicResponse.album?.let { albumResponse ->
                        val songs = albumResponse.songs?.map { songResponse ->
                            Song(
                                id = songResponse.id,
                                title = songResponse.title,
                                artist = songResponse.artist ?: albumResponse.artist ?: "",
                                album = albumResponse.name,
                                duration = songResponse.duration ?: 0,
                                track = songResponse.track,
                                year = songResponse.year,
                                genre = songResponse.genre,
                                artistId = songResponse.artistId,
                                albumId = albumResponse.id,
                                bitRate = songResponse.bitRate,
                                size = songResponse.size,
                                coverArt = songResponse.coverArt ?: albumResponse.coverArt,
                                suffix = songResponse.suffix,
                                path = songResponse.path
                            )
                        } ?: emptyList()

                        val albumDetail = AlbumDetail(
                            id = albumResponse.id,
                            title = albumResponse.name,
                            artist = albumResponse.artist ?: "",
                            artistId = albumResponse.artistId,
                            songCount = albumResponse.songCount ?: songs.size,
                            duration = albumResponse.duration ?: 0,
                            year = albumResponse.year,
                            genre = albumResponse.genre,
                            coverArt = albumResponse.coverArt,
                            songs = songs
                        )
                        Result.success(albumDetail)
                    } ?: Result.failure(Exception("Empty album response"))
                } else {
                    val errorMessage = subsonicResponse?.error?.message ?: "Failed to get album detail"
                    Result.failure(Exception(errorMessage))
                }
            } else {
                Result.failure(Exception("HTTP ${response.code()}: ${response.message()}"))
            }
        } catch (e: Exception) {
            Log.e("MusicRepository", "Error getting album detail", e)
            Result.failure(e)
        }
    }

    suspend fun getAlbumSongs(albumId: String): Result<List<Song>> {
        return try {
            val albumDetail = getAlbumDetail(albumId)
            albumDetail.map { it.songs }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getArtistAlbums(artistId: String): Result<List<Album>> {
        return try {
            if (!ensureAuthenticated()) {
                return Result.failure(Exception("Authentication failed"))
            }

            // Use Subsonic API instead of REST API
            val response = getClient().getArtistSubsonic(artistId)

            if (response.isSuccessful) {
                val subsonicResponse = response.body()?.subsonicResponse
                
                if (subsonicResponse?.status == "ok") {
                    subsonicResponse.artist?.let { artistResponse ->
                        val albums = artistResponse.albums?.map { albumResponse ->
                            Album(
                                id = albumResponse.id,
                                name = albumResponse.name,
                                artist = albumResponse.artist ?: artistResponse.name,
                                artistId = artistId,
                                songCount = albumResponse.songCount ?: 0,
                                duration = albumResponse.duration ?: 0,
                                year = albumResponse.year,
                                genre = albumResponse.genre,
                                coverArt = albumResponse.coverArt
                            )
                        } ?: emptyList()
                        Result.success(albums)
                    } ?: Result.failure(Exception("Empty artist data"))
                } else {
                    val errorMessage = subsonicResponse?.error?.message ?: "Failed to get artist albums"
                    Result.failure(Exception(errorMessage))
                }
            } else {
                Result.failure(Exception("HTTP ${response.code()}: ${response.message()}"))
            }
        } catch (e: Exception) {
            Log.e("MusicRepository", "Error getting artist albums", e)
            Result.failure(e)
        }
    }

    suspend fun getArtistTopSongs(artistName: String, count: Int = 20): Result<List<Song>> {
        return try {
            if (!ensureAuthenticated()) {
                return Result.failure(Exception("Authentication failed"))
            }

            // Use Subsonic API getTopSongs with artist filter
            val response = getClient().getTopSongsSubsonic(
                artist = artistName,
                count = count
            )

            if (response.isSuccessful) {
                val subsonicResponse = response.body()?.subsonicResponse
                
                if (subsonicResponse?.status == "ok") {
                    subsonicResponse.topSongs?.song?.let { songs ->
                        val songList = songs.map { songResponse ->
                            Song(
                                id = songResponse.id,
                                title = songResponse.title,
                                artist = songResponse.artist ?: artistName,
                                album = songResponse.album ?: "",
                                duration = songResponse.duration ?: 0,
                                track = songResponse.track,
                                year = songResponse.year,
                                genre = songResponse.genre,
                                artistId = songResponse.artistId,
                                albumId = songResponse.albumId,
                                bitRate = songResponse.bitRate,
                                size = songResponse.size,
                                coverArt = songResponse.coverArt,
                                suffix = songResponse.suffix
                            )
                        }
                        Result.success(songList)
                    } ?: Result.failure(Exception("Empty top songs response"))
                } else {
                    val errorMessage = subsonicResponse?.error?.message ?: "Failed to get top songs"
                    Result.failure(Exception(errorMessage))
                }
            } else {
                Result.failure(Exception("HTTP ${response.code()}: ${response.message()}"))
            }
        } catch (e: Exception) {
            Log.e("MusicRepository", "Error getting artist top songs", e)
            Result.failure(e)
        }
    }

    suspend fun getSimilarArtists(artistId: String, count: Int = 20): Result<List<Artist>> {
        return try {
            if (!ensureAuthenticated()) {
                return Result.failure(Exception("Authentication failed"))
            }

            // Subsonic API doesn't have a direct "similar artists" endpoint
            // Return empty list for now - this feature is not available in Subsonic API
            Log.w("MusicRepository", "Similar artists feature not available in Subsonic API")
            Result.success(emptyList())
        } catch (e: Exception) {
            Log.e("MusicRepository", "Error getting similar artists", e)
            Result.failure(e)
        }
    }

    suspend fun getSimilarSongs(songId: String, count: Int = 20): Result<List<Song>> {
        return try {
            if (!ensureAuthenticated()) {
                return Result.failure(Exception("Authentication failed"))
            }

            // Clean the ID by removing any suffix (e.g., "56012_1" -> "56012")
            val cleanId = songId.substringBefore('_')
            val response = getClient().getSimilarSongs(cleanId, count)

            if (response.isSuccessful && response.body()?.success == true) {
                response.body()?.data?.let { songs ->
                    val songList = songs.map { songResponse ->
                        Song(
                            id = songResponse.id,
                            title = songResponse.title,
                            artist = songResponse.artist?.name ?: "",
                            album = songResponse.album?.title ?: "",
                            duration = songResponse.duration,
                            track = songResponse.track,
                            year = songResponse.year,
                            genre = songResponse.genre,
                            artistId = songResponse.artistId,
                            albumId = songResponse.albumId,
                            bitRate = songResponse.bitRate,
                            size = songResponse.size,
                            coverArt = songResponse.coverArt,
                            suffix = songResponse.suffix
                        )
                    }
                    Result.success(songList)
                } ?: Result.failure(Exception("Empty response"))
            } else {
                val errorMessage = response.body()?.error ?: "Failed to get similar songs"
                Result.failure(Exception(errorMessage))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getSong(songId: String): Result<Song> {
        return try {
            if (!ensureAuthenticated()) {
                return Result.failure(Exception("Authentication failed"))
            }

            // Clean the ID by removing any suffix (e.g., "56012_1" -> "56012")
            val cleanId = songId.substringBefore('_')
            val response = getClient().getSong(cleanId)

            if (response.isSuccessful && response.body()?.success == true) {
                response.body()?.data?.let { songResponse ->
                    val song = Song(
                        id = songResponse.id,
                        title = songResponse.title,
                        artist = songResponse.artist?.name ?: "",
                        album = songResponse.album?.title ?: "",
                        duration = songResponse.duration,
                        track = songResponse.track,
                        year = songResponse.year,
                        genre = songResponse.genre,
                        artistId = songResponse.artistId,
                        albumId = songResponse.albumId,
                        bitRate = songResponse.bitRate,
                        size = songResponse.size,
                        coverArt = songResponse.coverArt,
                        suffix = songResponse.suffix
                    )
                    Result.success(song)
                } ?: Result.failure(Exception("Empty response"))
            } else {
                val errorMessage = response.body()?.error ?: "Failed to get song"
                Result.failure(Exception(errorMessage))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getSongById(id: String): Result<Song?> {
        return try {
            if (!ensureAuthenticated()) {
                return Result.failure(Exception("Authentication failed"))
            }
            val response = getClient().getSongById(id)
            if (response.isSuccessful && response.body()?.success == true) {
                response.body()?.data?.let { songResponse ->
                    val song = Song(
                        id = songResponse.id,
                        title = songResponse.title,
                        artist = songResponse.artist?.name ?: "",
                        album = songResponse.album?.title ?: "",
                        duration = songResponse.duration,
                        track = songResponse.track,
                        year = songResponse.year,
                        genre = songResponse.genre,
                        artistId = songResponse.artistId,
                        albumId = songResponse.albumId,
                        bitRate = songResponse.bitRate,
                        size = songResponse.size,
                        coverArt = songResponse.coverArt,
                        suffix = songResponse.suffix
                    )
                    Result.success(song)
                } ?: Result.failure(Exception("Song not found"))
            } else {
                val errorMessage = response.body()?.error ?: "Failed to get song"
                Result.failure(Exception(errorMessage))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Playlist methods
    suspend fun getPlaylists(): Result<List<Playlist>> {
        return try {
            if (!ensureAuthenticated()) {
                return Result.failure(Exception("Authentication failed"))
            }

            val response = getClient().getPlaylists()

            if (response.isSuccessful && response.body()?.success == true) {
                response.body()?.data?.let { playlistsData ->
                    val playlists = playlistsData.map { playlistData ->
                        Playlist(
                            id = playlistData.id,
                            name = playlistData.name,
                            songCount = playlistData.songCount ?: 0,
                            duration = playlistData.duration ?: 0,
                            public = playlistData.public ?: false,
                            owner = playlistData.owner,
                            comment = playlistData.description,
                            created = playlistData.created,
                            changed = playlistData.updated,
                            coverArt = null // CastafiorePlaylistResponse doesn't have coverArt
                        )
                    }
                    Result.success(playlists)
                } ?: Result.success(emptyList())
            } else {
                val errorMessage = response.body()?.error ?: "Failed to get playlists"
                Result.failure(Exception(errorMessage))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun createPlaylist(name: String): Result<Playlist> {
        return try {
            if (!ensureAuthenticated()) {
                return Result.failure(Exception("Authentication failed"))
            }

            val response = getClient().createPlaylist(name)

            if (response.isSuccessful && response.body()?.success == true) {
                response.body()?.data?.let { playlistData ->
                    val playlist = Playlist(
                        id = playlistData.id,
                        name = playlistData.name,
                        songCount = playlistData.songCount ?: 0,
                        duration = playlistData.duration ?: 0,
                        public = playlistData.public ?: false,
                        owner = playlistData.owner,
                        comment = playlistData.description,
                        created = playlistData.created,
                        changed = playlistData.updated,
                        coverArt = null
                    )
                    Result.success(playlist)
                } ?: Result.failure(Exception("Empty response"))
            } else {
                val errorMessage = response.body()?.error ?: "Failed to create playlist"
                Result.failure(Exception(errorMessage))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun addSongToPlaylist(playlistId: String, songId: String): Result<Boolean> {
        return try {
            if (!ensureAuthenticated()) {
                return Result.failure(Exception("Authentication failed"))
            }

            // Clean the ID by removing any suffix (e.g., "56012_1" -> "56012")
            val cleanId = songId.substringBefore('_')
            val response = getClient().addSongsToPlaylist(playlistId, listOf(cleanId))

            if (response.isSuccessful && response.body()?.success == true) {
                Result.success(true)
            } else {
                val errorMessage = response.body()?.error ?: "Failed to add song to playlist"
                Result.failure(Exception(errorMessage))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getPlaylistSongs(playlistId: String): Result<List<Song>> {
        return try {
            if (!ensureAuthenticated()) {
                return Result.failure(Exception("Authentication failed"))
            }

            val response = getClient().getPlaylist(playlistId)

            if (response.isSuccessful && response.body()?.success == true) {
                response.body()?.data?.songs?.let { songResponses ->
                    val songs = songResponses.map { songResponse ->
                        Song(
                            id = songResponse.id,
                            title = songResponse.title,
                            artist = songResponse.artist?.name ?: "",
                            album = songResponse.album?.title ?: "",
                            duration = songResponse.duration,
                            track = songResponse.track,
                            year = songResponse.year,
                            genre = songResponse.genre,
                            artistId = songResponse.artistId,
                            albumId = songResponse.albumId,
                            bitRate = songResponse.bitRate,
                            size = songResponse.size,
                            coverArt = songResponse.coverArt,
                            suffix = songResponse.suffix
                        )
                    }
                    Result.success(songs)
                } ?: Result.success(emptyList())
            } else {
                val errorMessage = response.body()?.error ?: "Failed to get playlist songs"
                Result.failure(Exception(errorMessage))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getPlaylistInfo(playlistId: String): Result<Playlist> {
        // TODO: Implement actual logic
        return Result.failure(Exception("Not implemented"))
    }

    suspend fun removeSongFromPlaylist(playlistId: String, index: Int): Result<Boolean> {
        // TODO: Implement actual logic
        return Result.failure(Exception("Not implemented"))
    }

    suspend fun updatePlaylistMetadata(playlistId: String, name: String? = null): Result<Boolean> {
        // TODO: Implement actual logic
        return Result.failure(Exception("Not implemented"))
    }

    suspend fun deletePlaylist(playlistId: String): Result<Boolean> {
        // TODO: Implement actual logic
        return Result.failure(Exception("Not implemented"))
    }

    // Cache management
    fun invalidateHomeLists() {
        CacheInvalidation.invalidateHomeLists(cacheManager)
    }

    fun clearCache() {
        CacheInvalidation.invalidateAllCache(cacheManager)
    }

    fun cleanupCache() {
        cacheManager.cleanupCache()
    }

    fun ensureCacheScope() {
        // Initialize cache if needed - no action needed as CacheManager handles this
    }

    // Cover art URL helper
    fun getCoverArtUrl(coverArtId: String?, serverUrl: String, username: String, token: String, salt: String): String? {
        return coverArtId?.let {
            "$serverUrl/rest/getCoverArt.view?id=$it&u=$username&t=$token&s=$salt&v=1.16.1&c=Castafiore"
        }
    }

    // Scrobbling and now playing methods
    suspend fun scrobbleSong(songId: String): Result<Boolean> {
        return try {
            if (!ensureAuthenticated()) {
                return Result.failure(Exception("Authentication failed"))
            }

            // Clean the ID by removing any suffix (e.g., "56012_1" -> "56012")
            val cleanId = songId.substringBefore('_')
            val response = getClient().scrobble(cleanId, System.currentTimeMillis(), true)

            if (response.isSuccessful && response.body()?.success == true) {
                Result.success(true)
            } else {
                val errorMessage = response.body()?.error ?: "Failed to scrobble song"
                Result.failure(Exception(errorMessage))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun reportNowPlaying(songId: String): Result<Boolean> {
        return try {
            if (!ensureAuthenticated()) {
                return Result.failure(Exception("Authentication failed"))
            }

            // Clean the ID by removing any suffix (e.g., "56012_1" -> "56012")
            val cleanId = songId.substringBefore('_')
            val response = getClient().setNowPlaying(cleanId)

            if (response.isSuccessful && response.body()?.success == true) {
                Result.success(true)
            } else {
                val errorMessage = response.body()?.error ?: "Failed to report now playing"
                Result.failure(Exception(errorMessage))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Star/favorite methods
    suspend fun isSongStarred(songId: String): Result<Boolean> {
        return try {
            if (!ensureAuthenticated()) {
                return Result.failure(Exception("Authentication failed"))
            }

            // Check cache first
            val cacheKey = "favorite_song_$songId"
            val cachedValue = cacheManager.getCache<Boolean>(cacheKey, CacheTypes.BOOLEAN)
            if (cachedValue != null) {
                return Result.success(cachedValue)
            }

            // Get starred items from API
            val response = getClient().getStarred()

            if (response.isSuccessful && response.body()?.success == true) {
                response.body()?.data?.let { starredResponse ->
                    val isStarred = starredResponse.songs?.any { it.id == songId } ?: false
                    // Cache the result
                    updateFavoriteCacheOptimistic(cacheKey, isStarred)
                    Result.success(isStarred)
                } ?: Result.success(false)
            } else {
                val errorMessage = response.body()?.error ?: "Failed to check starred status"
                Result.failure(Exception(errorMessage))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun starSong(songId: String): Result<Boolean> {
        return try {
            if (!ensureAuthenticated()) {
                return Result.failure(Exception("Authentication failed"))
            }

            // Update cache optimistically
            val cacheKey = "favorite_song_$songId"
            updateFavoriteCacheOptimistic(cacheKey, true)

            // Clean the ID by removing any suffix (e.g., "56012_1" -> "56012")
            val cleanId = songId.substringBefore('_')
            val response = getClient().starSong(cleanId)

            if (response.isSuccessful && response.body()?.success == true) {
                Result.success(true)
            } else {
                // Revert cache on failure
                updateFavoriteCacheOptimistic(cacheKey, false)
                val errorMessage = response.body()?.error ?: "Failed to star song"
                Result.failure(Exception(errorMessage))
            }
        } catch (e: Exception) {
            // Revert cache on failure
            val cacheKey = "favorite_song_$songId"
            updateFavoriteCacheOptimistic(cacheKey, false)
            Result.failure(e)
        }
    }

    suspend fun unstarSong(songId: String): Result<Boolean> {
        return try {
            if (!ensureAuthenticated()) {
                return Result.failure(Exception("Authentication failed"))
            }

            // Update cache optimistically
            val cacheKey = "favorite_song_$songId"
            updateFavoriteCacheOptimistic(cacheKey, false)

            // Clean the ID by removing any suffix (e.g., "56012_1" -> "56012")
            val cleanId = songId.substringBefore('_')
            val response = getClient().unstarSong(cleanId)

            if (response.isSuccessful && response.body()?.success == true) {
                Result.success(true)
            } else {
                // Revert cache on failure
                updateFavoriteCacheOptimistic(cacheKey, true)
                val errorMessage = response.body()?.error ?: "Failed to unstar song"
                Result.failure(Exception(errorMessage))
            }
        } catch (e: Exception) {
            // Revert cache on failure
            val cacheKey = "favorite_song_$songId"
            updateFavoriteCacheOptimistic(cacheKey, true)
            Result.failure(e)
        }
    }

    // Album starring methods
    suspend fun isAlbumStarred(albumId: String): Result<Boolean> {
        return try {
            if (!ensureAuthenticated()) {
                return Result.failure(Exception("Authentication failed"))
            }

            // Check cache first
            val cacheKey = "favorite_album_$albumId"
            val cachedValue = cacheManager.getCache<Boolean>(cacheKey, CacheTypes.BOOLEAN)
            if (cachedValue != null) {
                return Result.success(cachedValue)
            }

            // Get starred items from API
            val response = getClient().getStarred()

            if (response.isSuccessful && response.body()?.success == true) {
                response.body()?.data?.let { starredResponse ->
                    val isStarred = starredResponse.albums?.any { it.id == albumId } ?: false
                    // Cache the result
                    updateFavoriteCacheOptimistic(cacheKey, isStarred)
                    Result.success(isStarred)
                } ?: Result.success(false)
            } else {
                val errorMessage = response.body()?.error ?: "Failed to check album starred status"
                Result.failure(Exception(errorMessage))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun starAlbum(albumId: String): Result<Boolean> {
        return try {
            if (!ensureAuthenticated()) {
                return Result.failure(Exception("Authentication failed"))
            }

            // Update cache optimistically
            val cacheKey = "favorite_album_$albumId"
            updateFavoriteCacheOptimistic(cacheKey, true)

            val response = getClient().starAlbum(albumId)

            if (response.isSuccessful && response.body()?.success == true) {
                Result.success(true)
            } else {
                // Revert cache on failure
                updateFavoriteCacheOptimistic(cacheKey, false)
                val errorMessage = response.body()?.error ?: "Failed to star album"
                Result.failure(Exception(errorMessage))
            }
        } catch (e: Exception) {
            // Revert cache on failure
            val cacheKey = "favorite_album_$albumId"
            updateFavoriteCacheOptimistic(cacheKey, false)
            Result.failure(e)
        }
    }

    suspend fun unstarAlbum(albumId: String): Result<Boolean> {
        return try {
            if (!ensureAuthenticated()) {
                return Result.failure(Exception("Authentication failed"))
            }

            // Update cache optimistically
            val cacheKey = "favorite_album_$albumId"
            updateFavoriteCacheOptimistic(cacheKey, false)

            val response = getClient().unstarAlbum(albumId)

            if (response.isSuccessful && response.body()?.success == true) {
                Result.success(true)
            } else {
                // Revert cache on failure
                updateFavoriteCacheOptimistic(cacheKey, true)
                val errorMessage = response.body()?.error ?: "Failed to unstar album"
                Result.failure(Exception(errorMessage))
            }
        } catch (e: Exception) {
            // Revert cache on failure
            val cacheKey = "favorite_album_$albumId"
            updateFavoriteCacheOptimistic(cacheKey, true)
            Result.failure(e)
        }
    }

    /**
     * Returns whether the album is starred (favorited) in cache, or null if unknown.
     */
    fun peekAlbumStarred(albumId: String): Boolean? {
        // TODO: Implement actual cache lookup logic
        // For ahora, devuelve null para indicar desconocido
        return null
    }

    // Synchronous check for artist starred state (stub)
    fun getArtistStarredSync(artistId: String): Boolean? {
        // TODO: Implement actual cache or synchronous check
        return null
    }

    // Get artist details (stub)
    suspend fun getArtist(artistId: String): Result<Artist> {
        // TODO: Implement actual API call
        return Result.failure(Exception("Not implemented"))
    }

    // Get artist starred state (stub)
    suspend fun getArtistStarred(artistId: String): Result<Boolean> {
        // TODO: Implement actual API call
        return Result.success(false)
    }

    // Set artist starred state (stub)
    suspend fun setArtistStarred(artistId: String, starred: Boolean): Result<Boolean> {
        // TODO: Implement actual API call
        return Result.success(starred)
    }

    // Fetches the user's starred (favorite) songs
    suspend fun getStarredSongs(): Result<List<Song>> {
        return try {
            if (!ensureAuthenticated()) {
                return Result.failure(Exception("Not authenticated"))
            }
            val response = getClient().getStarred()
            if (response.isSuccessful) {
                val songs: List<Song> = response.body()?.data?.songs?.map { it.toSong() } ?: emptyList()
                Result.success(songs)
            } else {
                Result.failure(Exception("Failed to fetch starred songs: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Fetch the newest albums for the Home screen using Subsonic API.
     * Uses GET /rest/getAlbumList2.view?type=newest&size=10
     */
    suspend fun getNewestAlbums(): Result<List<Album>> {
        return try {
            if (!ensureAuthenticated()) {
                return Result.failure(Exception("Authentication failed"))
            }

            val response = getClient().getAlbumsSubsonic(type = "newest", size = 10)

            if (response.isSuccessful) {
                val subsonicResponse = response.body()?.subsonicResponse

                if (subsonicResponse?.status == "ok") {
                    subsonicResponse.albumList2?.albums?.let { albums ->
                        val albumList = albums.map { albumResponse ->
                            Album(
                                id = albumResponse.id,
                                name = albumResponse.name,
                                artist = albumResponse.artist ?: "",
                                artistId = albumResponse.artistId,
                                songCount = albumResponse.songCount ?: 0,
                                duration = albumResponse.duration ?: 0,
                                year = albumResponse.year,
                                genre = albumResponse.genre,
                                coverArt = albumResponse.coverArt
                            )
                        }
                        Result.success(albumList)
                    } ?: Result.failure(Exception("Empty newest albums list"))
                } else {
                    val errorMessage = subsonicResponse?.error?.message ?: "Failed to get newest albums"
                    Result.failure(Exception(errorMessage))
                }
            } else {
                Result.failure(Exception("HTTP ${response.code()}: ${response.message()}"))
            }
        } catch (e: Exception) {
            Log.e("MusicRepository", "Error getting newest albums", e)
            Result.failure(e)
        }
    }

    /**
     * Fetch recently played albums for the Home screen using Subsonic API.
     * Uses GET /rest/getAlbumList2.view?type=recent&size=10
     */
    suspend fun getRecentlyPlayedAlbums(): Result<List<Album>> {
        return try {
            if (!ensureAuthenticated()) {
                return Result.failure(Exception("Authentication failed"))
            }

            val response = getClient().getAlbumsSubsonic(type = "recent", size = 10)

            if (response.isSuccessful) {
                val subsonicResponse = response.body()?.subsonicResponse

                if (subsonicResponse?.status == "ok") {
                    subsonicResponse.albumList2?.albums?.let { albums ->
                        val albumList = albums.map { albumResponse ->
                            Album(
                                id = albumResponse.id,
                                name = albumResponse.name,
                                artist = albumResponse.artist ?: "",
                                artistId = albumResponse.artistId,
                                songCount = albumResponse.songCount ?: 0,
                                duration = albumResponse.duration ?: 0,
                                year = albumResponse.year,
                                genre = albumResponse.genre,
                                coverArt = albumResponse.coverArt
                            )
                        }
                        Result.success(albumList)
                    } ?: Result.failure(Exception("Empty recently played albums list"))
                } else {
                    val errorMessage = subsonicResponse?.error?.message ?: "Failed to get recently played albums"
                    Result.failure(Exception(errorMessage))
                }
            } else {
                Result.failure(Exception("HTTP ${response.code()}: ${response.message()}"))
            }
        } catch (e: Exception) {
            Log.e("MusicRepository", "Error getting recently played albums", e)
            Result.failure(e)
        }
    }

    /**
     * Fetch most played albums for the Home screen using Subsonic API.
     * Uses GET /rest/getAlbumList2.view?type=frequent&size=10
     */
    suspend fun getMostPlayedAlbums(): Result<List<Album>> {
        return try {
            if (!ensureAuthenticated()) {
                return Result.failure(Exception("Authentication failed"))
            }

            val response = getClient().getAlbumsSubsonic(type = "frequent", size = 10)

            if (response.isSuccessful) {
                val subsonicResponse = response.body()?.subsonicResponse

                if (subsonicResponse?.status == "ok") {
                    subsonicResponse.albumList2?.albums?.let { albums ->
                        val albumList = albums.map { albumResponse ->
                            Album(
                                id = albumResponse.id,
                                name = albumResponse.name,
                                artist = albumResponse.artist ?: "",
                                artistId = albumResponse.artistId,
                                songCount = albumResponse.songCount ?: 0,
                                duration = albumResponse.duration ?: 0,
                                year = albumResponse.year,
                                genre = albumResponse.genre,
                                coverArt = albumResponse.coverArt
                            )
                        }
                        Result.success(albumList)
                    } ?: Result.failure(Exception("Empty most played albums list"))
                } else {
                    val errorMessage = subsonicResponse?.error?.message ?: "Failed to get most played albums"
                    Result.failure(Exception(errorMessage))
                }
            } else {
                Result.failure(Exception("HTTP ${response.code()}: ${response.message()}"))
            }
        } catch (e: Exception) {
            Log.e("MusicRepository", "Error getting most played albums", e)
            Result.failure(e)
        }
    }

    /**
     * Get artist info including similar artists using Subsonic API.
     * Uses GET /rest/getArtistInfo2.view
     */
    suspend fun getArtistInfo2(id: String): Result<ArtistInfo> {
        return try {
            if (!ensureAuthenticated()) {
                return Result.failure(Exception("Authentication failed"))
            }

            val response = getClient().getArtistInfo2Subsonic(id)

            if (response.isSuccessful) {
                val subsonicResponse = response.body()?.subsonicResponse

                if (subsonicResponse?.status == "ok") {
                    subsonicResponse.artistInfo2?.let { artistInfo ->
                        val similarArtists = artistInfo.similarArtist?.mapNotNull { similarArtist ->
                            similarArtist.id?.let {
                                Artist(
                                    id = it,
                                    name = similarArtist.name,
                                    albumCount = similarArtist.albumCount ?: 0
                                )
                            }
                        } ?: emptyList()
                        val artistInfoObj = ArtistInfo(view = similarArtists)
                        Result.success(artistInfoObj)
                    } ?: Result.failure(Exception("Empty artist info response"))
                } else {
                    val errorMessage = subsonicResponse?.error?.message ?: "Failed to get artist info"
                    Result.failure(Exception(errorMessage))
                }
            } else {
                Result.failure(Exception("HTTP ${response.code()}: ${response.message()}"))
            }
        } catch (e: Exception) {
            Log.e("MusicRepository", "Error getting artist info", e)
            // Return empty list instead of failure to avoid hiding the section
            Result.success(ArtistInfo(emptyList()))
        }
    }

    suspend fun starArtist(artistId: String): Result<Unit> {
        return try {
            if (!ensureAuthenticated()) {
                return Result.failure(Exception("Authentication failed"))
            }
            val response = getClient().starArtist(artistId)
            if (response.isSuccessful && response.body()?.success == true) {
                Result.success(Unit)
            } else {
                val errorMessage = response.body()?.error ?: "Failed to star artist"
                Result.failure(Exception(errorMessage))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun unstarArtist(artistId: String): Result<Unit> {
        return try {
            if (!ensureAuthenticated()) {
                return Result.failure(Exception("Authentication failed"))
            }
            val response = getClient().unstarArtist(artistId)
            if (response.isSuccessful && response.body()?.success == true) {
                Result.success(Unit)
            } else {
                val errorMessage = response.body()?.error ?: "Failed to unstar artist"
                Result.failure(Exception(errorMessage))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getStarredAlbums(): Result<List<Album>> {
        return try {
            val client = getClient()
            val albums = client.getStarredAlbums() // This should return List<Album>
            Result.success(albums)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Fetch starred (favorite) artists for the Home screen.
     * If your backend has a dedicated endpoint, use it here. Otherwise, return emptyList().
     */
    suspend fun getStarredArtists(): Result<List<Artist>> {
        // Replace with actual API call if available
        return Result.success(emptyList())
    }

    /**
     * Get the CastafioreClient instance for direct API access
     */
    fun getCastafioreClient(): CastafioreClient? {
        return castafioreClient
    }

    /**
     * Get the SubsonicApiService for scrobble operations
     */
    fun getSubsonicApiService(): com.arantec.castafiore.data.network.SubsonicApiService? {
        return castafioreClient?.subsonicApiService
    }
}
