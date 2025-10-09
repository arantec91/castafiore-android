package com.arantec.castafiore.data.network

import android.content.Context
import android.text.format.Formatter
import com.arantec.castafiore.data.models.*
import com.arantec.castafiore.data.model.*
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import java.io.File
import java.security.MessageDigest
import kotlin.random.Random

class CastafioreClient(
    private val context: Context,
    private val baseUrl: String
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        })
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(httpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    private val apiService = retrofit.create(CastafioreApiService::class.java)
    val subsonicApiService = retrofit.create(SubsonicApiService::class.java)

    private var authToken: String? = null
    private var username: String? = null
    private var password: String? = null

    companion object {
        @Volatile
        private var INSTANCE: CastafioreClient? = null

        fun getInstance(context: Context): CastafioreClient? {
            return INSTANCE
        }

        fun initialize(context: Context, baseUrl: String): CastafioreClient {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: CastafioreClient(context, baseUrl).also { INSTANCE = it }
            }
        }
    }

    fun setAuthToken(token: String) {
        authToken = token
    }

    fun setCredentials(user: String, pass: String) {
        username = user
        password = pass
    }

    private fun getAuthHeader(): String {
        return "Bearer ${authToken ?: ""}"
    }

    fun getApiService(): CastafioreApiService {
        return apiService
    }

    /**
     * Generate authentication parameters for Subsonic-compatible API
     * Returns Triple(username, md5Token, salt)
     */
    fun generateAuthParams(): Triple<String, String, String> {
        val salt = Random.nextInt(1000000).toString()
        val user = username ?: ""
        val passwordHash = (password ?: "") + salt
        val md5Token = MessageDigest.getInstance("MD5").digest(passwordHash.toByteArray()).joinToString("") { "%02x".format(it) }
        return Triple(user, md5Token, salt)
    }

    /**
     * Ping the server to test connectivity and authentication
     */
    suspend fun ping(): Response<SubsonicResponseWrapper> {
        val (user, token, salt) = generateAuthParams()
        return subsonicApiService.ping(
            username = user,
            token = token,
            salt = salt,
            version = "1.16.1",
            client = "Castafiore"
        )
    }

    /**
     * Get cover art URL for an item
     */
    fun getCoverArtUrl(
        coverArtId: String,
        size: Int? = null
    ): String {
        val sizeParam = size?.let { "&size=$it" } ?: ""
        return "$baseUrl/rest/getCoverArt?id=$coverArtId$sizeParam"
    }

    /**
     * Get streaming URL for a song
     */
    fun getStreamUrl(
        serverUrl: String,
        username: String,
        token: String,
        salt: String,
        songId: String,
        quality: String? = null,
        format: String? = null
    ): String {
        val qualityParam = quality?.let { "&maxBitRate=$it" } ?: ""
        val formatParam = format?.let { "&format=$it" } ?: ""
        return "$serverUrl/rest/stream?id=$songId&u=$username&t=$token&s=$salt$qualityParam$formatParam&v=1.16.1&c=Castafiore&f=json"
    }

    /**
     * Create cover art path for caching
     */
    fun createCoverPath(coverArtId: String): String? {
        if (coverArtId.isBlank()) return null
        val coversDir = File(context.cacheDir, "covers")
        if (!coversDir.exists()) {
            coversDir.mkdirs()
        }
        return File(coversDir, "$coverArtId.jpg").absolutePath
    }

    /**
     * Create album cover path for caching
     */
    fun createAlbumCoverPath(albumId: String): String? {
        if (albumId.isBlank()) return null
        val coversDir = File(context.cacheDir, "album_covers")
        if (!coversDir.exists()) {
            coversDir.mkdirs()
        }
        return File(coversDir, "$albumId.jpg").absolutePath
    }

    /**
     * Format file size in human readable format
     */
    fun formatFileSize(context: Context, sizeBytes: Long): String {
        return Formatter.formatFileSize(context, sizeBytes)
    }

    // Authentication methods
    suspend fun login(username: String, password: String): Response<ApiResponse<LoginResponse>> {
        val request = LoginRequest(username, password)
        return apiService.login(request)
    }

    suspend fun refreshToken(refreshToken: String): Response<ApiResponse<LoginResponse>> {
        return apiService.refreshToken("Bearer $refreshToken")
    }

    suspend fun logout(): Response<ApiResponse<Unit>> {
        return apiService.logout(getAuthHeader())
    }

    // Artist methods
    suspend fun getArtists(
        page: Int = 1,
        limit: Int = 20,
        search: String? = null,
        sort: String = "name"
    ): Response<PaginatedResponse<ArtistResponse>> {
        return apiService.getArtists(getAuthHeader(), page, limit, search, sort)
    }

    suspend fun getArtist(id: String): Response<ApiResponse<ArtistResponse>> {
        return apiService.getArtist(getAuthHeader(), id)
    }

    suspend fun getArtistAlbums(
        id: String,
        page: Int = 1,
        limit: Int = 20
    ): Response<PaginatedResponse<AlbumResponse>> {
        return apiService.getArtistAlbums(getAuthHeader(), id, page, limit)
    }

    suspend fun getArtistSongs(
        id: String,
        page: Int = 1,
        limit: Int = 50
    ): Response<PaginatedResponse<SongResponse>> {
        return apiService.getArtistSongs(getAuthHeader(), id, page, limit)
    }

    // Album methods
    suspend fun getAlbums(
        page: Int = 1,
        limit: Int = 20,
        search: String? = null,
        sort: String = "title",
        artistId: String? = null
    ): Response<PaginatedResponse<AlbumResponse>> {
        return apiService.getAlbums(getAuthHeader(), page, limit, search, sort, artistId)
    }

    suspend fun getAlbum(id: String): Response<ApiResponse<AlbumResponse>> {
        return apiService.getAlbum(getAuthHeader(), id)
    }

    suspend fun getAlbumSongs(id: String): Response<ApiResponse<List<SongResponse>>> {
        return apiService.getAlbumSongs(getAuthHeader(), id)
    }

    suspend fun getRecentAlbums(limit: Int = 20): Response<ApiResponse<List<AlbumResponse>>> {
        return apiService.getRecentAlbums(getAuthHeader(), limit)
    }

    // Song methods
    suspend fun getSongs(
        page: Int = 1,
        limit: Int = 50,
        search: String? = null,
        artistId: String? = null,
        albumId: String? = null,
        genre: String? = null
    ): Response<PaginatedResponse<SongResponse>> {
        return apiService.getSongs(getAuthHeader(), page, limit, search, artistId, albumId, genre)
    }

    suspend fun getSong(id: String): Response<ApiResponse<SongResponse>> {
        return apiService.getSong(getAuthHeader(), id)
    }

    suspend fun getSongById(id: String): Response<ApiResponse<SongResponse>> {
        return apiService.getSong(getAuthHeader(), id)
    }

    suspend fun getRandomSongs(
        limit: Int = 20,
        genre: String? = null
    ): Response<ApiResponse<List<SongResponse>>> {
        return apiService.getRandomSongs(getAuthHeader(), limit, genre)
    }

    suspend fun getTopSongs(
        limit: Int = 25,
        period: String = "month"
    ): Response<ApiResponse<List<SongResponse>>> {
        return apiService.getTopSongs(getAuthHeader(), limit, period)
    }

    suspend fun getRecentSongs(limit: Int = 20): Response<ApiResponse<List<SongResponse>>> {
        return apiService.getRecentSongs(getAuthHeader(), limit)
    }

    // Search methods
    suspend fun search(
        query: String,
        type: String? = null,
        limit: Int = 20,
        offset: Int = 0
    ): Response<ApiResponse<SearchResponse>> {
        // Use Subsonic API for search
        val (user, token, salt) = generateAuthParams()
        val subsonicResponse = apiService.search(
            query = query,
            artistCount = limit,
            albumCount = limit,
            songCount = limit,
            username = user,
            token = token,
            salt = salt,
            version = "1.16.1",
            client = "Castafiore"
        )

        // Convert SubsonicResponseWrapper to ApiResponse<SearchResponse>
        return if (subsonicResponse.isSuccessful) {
            val subsonicBody = subsonicResponse.body()
            if (subsonicBody?.subsonicResponse?.status == "ok") {
                val searchResults = subsonicBody.subsonicResponse.searchResult3
                val searchResponse = SearchResponse(
                    artists = searchResults?.artists?.map { artist ->
                        ArtistResponse(
                            id = artist.id,
                            name = artist.name,
                            albumCount = artist.albumCount
                        )
                    } ?: emptyList(),
                    albums = searchResults?.albums?.map { album ->
                        AlbumResponse(
                            id = album.id,
                            title = album.name,
                            artist = album.artist,
                            artistId = album.artistId,
                            songCount = album.songCount,
                            duration = album.duration,
                            year = album.year,
                            genre = album.genre,
                            coverArt = album.coverArt
                        )
                    } ?: emptyList(),
                    songs = searchResults?.songs?.map { song ->
                        SongResponse(
                            id = song.id,
                            title = song.title,
                            duration = song.duration ?: 0,
                            track = song.track,
                            year = song.year,
                            genre = song.genre,
                            artistId = song.artistId,
                            albumId = song.albumId,
                            bitRate = song.bitRate,
                            size = song.size,
                            coverArt = song.coverArt,
                            suffix = song.suffix,
                            path = song.path
                        )
                    } ?: emptyList()
                )
                Response.success(ApiResponse(success = true, data = searchResponse, message = null, error = null))
            } else {
                val error = subsonicBody?.subsonicResponse?.error?.message ?: "Search failed"
                Response.success(ApiResponse(success = false, data = null, message = error, error = error))
            }
        } else {
            Response.success(ApiResponse(success = false, data = null, message = "Network error", error = "Network error"))
        }
    }

    // Playlist methods
    suspend fun getPlaylists(
        page: Int = 1,
        limit: Int = 20,
        ownOnly: Boolean = false
    ): Response<ApiResponse<List<CastafiorePlaylistResponse>>> {
        return apiService.getPlaylists(getAuthHeader(), page, limit, ownOnly).let { response ->
            if (response.isSuccessful && response.body()?.success == true) {
                val playlists = response.body()?.data ?: emptyList()
                // Transform PaginatedResponse to ApiResponse
                val transformedResponse = ApiResponse(
                    success = true,
                    data = playlists,
                    message = null,
                    error = null
                )
                Response.success(transformedResponse)
            } else {
                // Create error response
                val errorResponse = ApiResponse<List<CastafiorePlaylistResponse>>(
                    success = false,
                    data = null,
                    message = response.body()?.message,
                    error = response.body()?.error
                )
                Response.success(errorResponse)
            }
        }
    }

    suspend fun getPlaylist(id: String): Response<ApiResponse<CastafiorePlaylistResponse>> {
        return apiService.getPlaylist(getAuthHeader(), id)
    }

    suspend fun createPlaylist(
        name: String,
        description: String? = null,
        isPublic: Boolean = false
    ): Response<ApiResponse<CastafiorePlaylistResponse>> {
        val request = CreatePlaylistRequest(name, description, isPublic)
        return apiService.createPlaylist(getAuthHeader(), request)
    }

    suspend fun updatePlaylist(
        id: String,
        name: String,
        description: String? = null,
        isPublic: Boolean = false
    ): Response<ApiResponse<CastafiorePlaylistResponse>> {
        val request = CreatePlaylistRequest(name, description, isPublic)
        return apiService.updatePlaylist(getAuthHeader(), id, request)
    }

    suspend fun deletePlaylist(id: String): Response<ApiResponse<Unit>> {
        return apiService.deletePlaylist(getAuthHeader(), id)
    }

    suspend fun addSongsToPlaylist(
        playlistId: String,
        songIds: List<String>
    ): Response<ApiResponse<CastafiorePlaylistResponse>> {
        val request = AddToPlaylistRequest(songIds)
        return apiService.addSongsToPlaylist(getAuthHeader(), playlistId, request)
    }

    suspend fun removeSongFromPlaylist(
        playlistId: String,
        songId: String
    ): Response<ApiResponse<Unit>> {
        return apiService.removeSongFromPlaylist(getAuthHeader(), playlistId, songId)
    }

    // Scrobbling methods
    suspend fun scrobble(
        songId: String,
        playedAtMillis: Long,
        submission: Boolean = true
    ): Response<ApiResponse<Unit>> {
        // Use Subsonic API for scrobbling
        val (user, token, salt) = generateAuthParams()
        val response = subsonicApiService.scrobble(
            id = songId,
            time = playedAtMillis,
            submission = submission,
            username = user,
            token = token,
            salt = salt,
            version = "1.16.1",
            client = "Castafiore"
        )
        
        // Convert Subsonic response to ApiResponse format
        return if (response.isSuccessful) {
            val subsonicBody = response.body()
            if (subsonicBody?.subsonicResponse?.status == "ok") {
                Response.success(ApiResponse(success = true, data = Unit, message = null, error = null))
            } else {
                val error = subsonicBody?.subsonicResponse?.error?.message ?: "Scrobble failed"
                Response.success(ApiResponse(success = false, data = null, message = error, error = error))
            }
        } else {
            Response.success(ApiResponse(success = false, data = null, message = "Network error", error = "Network error"))
        }
    }
    
    /**
     * Set now playing status using Subsonic API
     */
    suspend fun setNowPlaying(songId: String): Response<ApiResponse<Unit>> {
        val (user, token, salt) = generateAuthParams()
        val response = subsonicApiService.setNowPlaying(
            id = songId,
            username = user,
            token = token,
            salt = salt,
            version = "1.16.1",
            client = "Castafiore"
        )
        
        // Convert Subsonic response to ApiResponse format
        return if (response.isSuccessful) {
            val subsonicBody = response.body()
            if (subsonicBody?.subsonicResponse?.status == "ok") {
                Response.success(ApiResponse(success = true, data = Unit, message = null, error = null))
            } else {
                val error = subsonicBody?.subsonicResponse?.error?.message ?: "Set now playing failed"
                Response.success(ApiResponse(success = false, data = null, message = error, error = error))
            }
        } else {
            Response.success(ApiResponse(success = false, data = null, message = "Network error", error = "Network error"))
        }
    }

    // Star/Favorite methods using Subsonic API
    suspend fun starSong(songId: String): Response<ApiResponse<Unit>> {
        val (user, token, salt) = generateAuthParams()
        val response = subsonicApiService.star(
            id = songId,
            username = user,
            token = token,
            salt = salt,
            version = "1.16.1",
            client = "Castafiore"
        )
        
        return if (response.isSuccessful) {
            val subsonicBody = response.body()
            if (subsonicBody?.subsonicResponse?.status == "ok") {
                Response.success(ApiResponse(success = true, data = Unit, message = null, error = null))
            } else {
                val error = subsonicBody?.subsonicResponse?.error?.message ?: "Failed to star song"
                Response.success(ApiResponse(success = false, data = null, message = error, error = error))
            }
        } else {
            Response.success(ApiResponse(success = false, data = null, message = "Network error", error = "Network error"))
        }
    }

    suspend fun unstarSong(songId: String): Response<ApiResponse<Unit>> {
        val (user, token, salt) = generateAuthParams()
        val response = subsonicApiService.unstar(
            id = songId,
            username = user,
            token = token,
            salt = salt,
            version = "1.16.1",
            client = "Castafiore"
        )
        
        return if (response.isSuccessful) {
            val subsonicBody = response.body()
            if (subsonicBody?.subsonicResponse?.status == "ok") {
                Response.success(ApiResponse(success = true, data = Unit, message = null, error = null))
            } else {
                val error = subsonicBody?.subsonicResponse?.error?.message ?: "Failed to unstar song"
                Response.success(ApiResponse(success = false, data = null, message = error, error = error))
            }
        } else {
            Response.success(ApiResponse(success = false, data = null, message = "Network error", error = "Network error"))
        }
    }

    suspend fun starAlbum(albumId: String): Response<ApiResponse<Unit>> {
        val (user, token, salt) = generateAuthParams()
        val response = subsonicApiService.star(
            albumId = albumId,
            username = user,
            token = token,
            salt = salt,
            version = "1.16.1",
            client = "Castafiore"
        )
        
        return if (response.isSuccessful) {
            val subsonicBody = response.body()
            if (subsonicBody?.subsonicResponse?.status == "ok") {
                Response.success(ApiResponse(success = true, data = Unit, message = null, error = null))
            } else {
                val error = subsonicBody?.subsonicResponse?.error?.message ?: "Failed to star album"
                Response.success(ApiResponse(success = false, data = null, message = error, error = error))
            }
        } else {
            Response.success(ApiResponse(success = false, data = null, message = "Network error", error = "Network error"))
        }
    }

    suspend fun unstarAlbum(albumId: String): Response<ApiResponse<Unit>> {
        val (user, token, salt) = generateAuthParams()
        val response = subsonicApiService.unstar(
            albumId = albumId,
            username = user,
            token = token,
            salt = salt,
            version = "1.16.1",
            client = "Castafiore"
        )
        
        return if (response.isSuccessful) {
            val subsonicBody = response.body()
            if (subsonicBody?.subsonicResponse?.status == "ok") {
                Response.success(ApiResponse(success = true, data = Unit, message = null, error = null))
            } else {
                val error = subsonicBody?.subsonicResponse?.error?.message ?: "Failed to unstar album"
                Response.success(ApiResponse(success = false, data = null, message = error, error = error))
            }
        } else {
            Response.success(ApiResponse(success = false, data = null, message = "Network error", error = "Network error"))
        }
    }

    suspend fun starArtist(artistId: String): Response<ApiResponse<Unit>> {
        val (user, token, salt) = generateAuthParams()
        val response = subsonicApiService.star(
            artistId = artistId,
            username = user,
            token = token,
            salt = salt,
            version = "1.16.1",
            client = "Castafiore"
        )
        
        return if (response.isSuccessful) {
            val subsonicBody = response.body()
            if (subsonicBody?.subsonicResponse?.status == "ok") {
                Response.success(ApiResponse(success = true, data = Unit, message = null, error = null))
            } else {
                val error = subsonicBody?.subsonicResponse?.error?.message ?: "Failed to star artist"
                Response.success(ApiResponse(success = false, data = null, message = error, error = error))
            }
        } else {
            Response.success(ApiResponse(success = false, data = null, message = "Network error", error = "Network error"))
        }
    }

    suspend fun unstarArtist(artistId: String): Response<ApiResponse<Unit>> {
        val (user, token, salt) = generateAuthParams()
        val response = subsonicApiService.unstar(
            artistId = artistId,
            username = user,
            token = token,
            salt = salt,
            version = "1.16.1",
            client = "Castafiore"
        )
        
        return if (response.isSuccessful) {
            val subsonicBody = response.body()
            if (subsonicBody?.subsonicResponse?.status == "ok") {
                Response.success(ApiResponse(success = true, data = Unit, message = null, error = null))
            } else {
                val error = subsonicBody?.subsonicResponse?.error?.message ?: "Failed to unstar artist"
                Response.success(ApiResponse(success = false, data = null, message = error, error = error))
            }
        } else {
            Response.success(ApiResponse(success = false, data = null, message = "Network error", error = "Network error"))
        }
    }

    suspend fun getStarred(): Response<ApiResponse<StarredResponse>> {
        return apiService.getStarred(getAuthHeader())
    }

    suspend fun getStarredAlbums(): List<Album> {
        // Example implementation, adjust as needed for your backend
        // If you need authentication, add token/headers as required
        return apiService.getStarredAlbums()
    }

    // Additional methods needed by MusicRepository
    suspend fun getSimilarArtists(artistId: String, count: Int = 20): Response<ApiResponse<List<ArtistResponse>>> {
        return apiService.getSimilarArtists(getAuthHeader(), artistId, count)
    }

    suspend fun getSimilarSongs(songId: String, count: Int = 20): Response<ApiResponse<List<SongResponse>>> {
        return apiService.getSimilarSongs(getAuthHeader(), songId, count)
    }

    // ========== SUBSONIC API METHODS ==========
    
    /**
     * Get albums using Subsonic API
     */
    suspend fun getAlbumsSubsonic(
        type: String = "alphabeticalByName",
        size: Int = 500,
        offset: Int = 0
    ): Response<SubsonicResponseWrapper> {
        val (user, token, salt) = generateAuthParams()
        return subsonicApiService.getAlbumList2(
            type = type,
            size = size,
            offset = offset,
            username = user,
            token = token,
            salt = salt,
            version = "1.16.1",
            client = "Castafiore"
        )
    }

    /**
     * Get artists using Subsonic API
     */
    suspend fun getArtistsSubsonic(): Response<SubsonicResponseWrapper> {
        val (user, token, salt) = generateAuthParams()
        return subsonicApiService.getArtists(
            username = user,
            token = token,
            salt = salt,
            version = "1.16.1",
            client = "Castafiore"
        )
    }

    /**
     * Get artist details using Subsonic API
     */
    suspend fun getArtistSubsonic(id: String): Response<SubsonicResponseWrapper> {
        val (user, token, salt) = generateAuthParams()
        return subsonicApiService.getArtist(
            id = id,
            username = user,
            token = token,
            salt = salt,
            version = "1.16.1",
            client = "Castafiore"
        )
    }

    /**
     * Get album details using Subsonic API
     */
    suspend fun getAlbumSubsonic(id: String): Response<SubsonicResponseWrapper> {
        val (user, token, salt) = generateAuthParams()
        return subsonicApiService.getAlbum(
            id = id,
            username = user,
            token = token,
            salt = salt,
            version = "1.16.1",
            client = "Castafiore"
        )
    }

    /**
     * Get top songs using Subsonic API, optionally filtered by artist
     */
    suspend fun getTopSongsSubsonic(
        artist: String? = null,
        count: Int = 50
    ): Response<SubsonicResponseWrapper> {
        val (user, token, salt) = generateAuthParams()
        return subsonicApiService.getTopSongs(
            artist = artist,
            count = count,
            username = user,
            token = token,
            salt = salt,
            version = "1.16.1",
            client = "Castafiore"
        )
    }

    /**
     * Search using Subsonic API
     */
    suspend fun searchSubsonic(
        query: String,
        artistCount: Int = 20,
        albumCount: Int = 20,
        songCount: Int = 20
    ): Response<SubsonicResponseWrapper> {
        val (user, token, salt) = generateAuthParams()
        return subsonicApiService.search3(
            query = query,
            artistCount = artistCount,
            albumCount = albumCount,
            songCount = songCount,
            username = user,
            token = token,
            salt = salt,
            version = "1.16.1",
            client = "Castafiore"
        )
    }

    /**
     * Get random songs using Subsonic API
     */
    suspend fun getRandomSongsSubsonic(
        size: Int = 20,
        genre: String? = null
    ): Response<SubsonicResponseWrapper> {
        val (user, token, salt) = generateAuthParams()
        return subsonicApiService.getRandomSongs(
            size = size,
            genre = genre,
            username = user,
            token = token,
            salt = salt,
            version = "1.16.1",
            client = "Castafiore"
        )
    }

    suspend fun addToFavorites(itemId: String, itemType: String): Response<ApiResponse<Unit>> {
        val request = FavoriteRequest(itemId, itemType)
        return apiService.addToFavorites(getAuthHeader(), request)
    }

    suspend fun removeFromFavorites(itemId: String, itemType: String): Response<ApiResponse<Unit>> {
        return apiService.removeFromFavorites(getAuthHeader(), itemId, itemType)
    }

    suspend fun getFavorites(type: String? = null): Response<ApiResponse<FavoritesResponse>> {
        return apiService.getFavorites(getAuthHeader(), type)
    }

    suspend fun getStats(): Response<ApiResponse<StatsResponse>> {
        return apiService.getStats(getAuthHeader())
    }

    /**
     * Get artist info including similar artists using Subsonic API
     */
    suspend fun getArtistInfo2Subsonic(id: String, count: Int = 20): Response<SubsonicResponseWrapper> {
        val (user, token, salt) = generateAuthParams()
        return subsonicApiService.getArtistInfo2(
            id = id,
            count = count,
            includeNotPresent = true,
            username = user,
            token = token,
            salt = salt,
            version = "1.16.1",
            client = "Castafiore"
        )
    }
}
