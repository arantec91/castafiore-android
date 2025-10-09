package com.arantec.castafiore.data.network

import com.arantec.castafiore.data.models.*
import com.arantec.castafiore.data.model.SubsonicResponseWrapper
import retrofit2.Response
import retrofit2.http.*

interface CastafioreApiService {

    // Authentication endpoints
    @POST("auth/login")
    suspend fun login(@Body request: LoginRequest): Response<ApiResponse<LoginResponse>>

    @POST("auth/refresh")
    suspend fun refreshToken(@Header("Authorization") refreshToken: String): Response<ApiResponse<LoginResponse>>

    @POST("auth/logout")
    suspend fun logout(@Header("Authorization") token: String): Response<ApiResponse<Unit>>

    // Artist endpoints
    @GET("artists")
    suspend fun getArtists(
        @Header("Authorization") token: String,
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 20,
        @Query("search") search: String? = null,
        @Query("sort") sort: String = "name"
    ): Response<PaginatedResponse<ArtistResponse>>

    @GET("artists/{id}")
    suspend fun getArtist(
        @Header("Authorization") token: String,
        @Path("id") id: String
    ): Response<ApiResponse<ArtistResponse>>

    @GET("artists/{id}/albums")
    suspend fun getArtistAlbums(
        @Header("Authorization") token: String,
        @Path("id") id: String,
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 20
    ): Response<PaginatedResponse<AlbumResponse>>

    @GET("artists/{id}/songs")
    suspend fun getArtistSongs(
        @Header("Authorization") token: String,
        @Path("id") id: String,
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 50
    ): Response<PaginatedResponse<SongResponse>>

    // Album endpoints
    @GET("albums")
    suspend fun getAlbums(
        @Header("Authorization") token: String,
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 20,
        @Query("search") search: String? = null,
        @Query("sort") sort: String = "title",
        @Query("artist_id") artistId: String? = null
    ): Response<PaginatedResponse<AlbumResponse>>

    @GET("albums/{id}")
    suspend fun getAlbum(
        @Header("Authorization") token: String,
        @Path("id") id: String
    ): Response<ApiResponse<AlbumResponse>>

    @GET("albums/{id}/songs")
    suspend fun getAlbumSongs(
        @Header("Authorization") token: String,
        @Path("id") id: String
    ): Response<ApiResponse<List<SongResponse>>>

    @GET("albums/recent")
    suspend fun getRecentAlbums(
        @Header("Authorization") token: String,
        @Query("limit") limit: Int = 20
    ): Response<ApiResponse<List<AlbumResponse>>>

    // Song endpoints
    @GET("songs")
    suspend fun getSongs(
        @Header("Authorization") token: String,
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 50,
        @Query("search") search: String? = null,
        @Query("artist_id") artistId: String? = null,
        @Query("album_id") albumId: String? = null,
        @Query("genre") genre: String? = null
    ): Response<PaginatedResponse<SongResponse>>

    @GET("songs/{id}")
    suspend fun getSong(
        @Header("Authorization") token: String,
        @Path("id") id: String
    ): Response<ApiResponse<SongResponse>>

    @GET("songs/random")
    suspend fun getRandomSongs(
        @Header("Authorization") token: String,
        @Query("limit") limit: Int = 20,
        @Query("genre") genre: String? = null
    ): Response<ApiResponse<List<SongResponse>>>

    @GET("songs/top")
    suspend fun getTopSongs(
        @Header("Authorization") token: String,
        @Query("limit") limit: Int = 25,
        @Query("period") period: String = "month" // "week", "month", "year", "all"
    ): Response<ApiResponse<List<SongResponse>>>

    @GET("songs/recent")
    suspend fun getRecentSongs(
        @Header("Authorization") token: String,
        @Query("limit") limit: Int = 20
    ): Response<ApiResponse<List<SongResponse>>>

    // Search endpoint
    @GET("rest/search3.view")
    suspend fun search(
        @Query("query") query: String,
        @Query("artistCount") artistCount: Int = 20,
        @Query("albumCount") albumCount: Int = 20,
        @Query("songCount") songCount: Int = 20,
        @Query("u") username: String,
        @Query("t") token: String,
        @Query("s") salt: String,
        @Query("v") version: String,
        @Query("c") client: String,
        @Query("f") format: String = "json"
    ): Response<SubsonicResponseWrapper>

    // Playlist endpoints
    @GET("playlists")
    suspend fun getPlaylists(
        @Header("Authorization") token: String,
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 20,
        @Query("own") ownOnly: Boolean = false
    ): Response<PaginatedResponse<CastafiorePlaylistResponse>>

    @GET("playlists/{id}")
    suspend fun getPlaylist(
        @Header("Authorization") token: String,
        @Path("id") id: String
    ): Response<ApiResponse<CastafiorePlaylistResponse>>

    @POST("playlists")
    suspend fun createPlaylist(
        @Header("Authorization") token: String,
        @Body request: CreatePlaylistRequest
    ): Response<ApiResponse<CastafiorePlaylistResponse>>

    @PUT("playlists/{id}")
    suspend fun updatePlaylist(
        @Header("Authorization") token: String,
        @Path("id") id: String,
        @Body request: CreatePlaylistRequest
    ): Response<ApiResponse<CastafiorePlaylistResponse>>

    @DELETE("playlists/{id}")
    suspend fun deletePlaylist(
        @Header("Authorization") token: String,
        @Path("id") id: String
    ): Response<ApiResponse<Unit>>

    @POST("playlists/{id}/songs")
    suspend fun addSongsToPlaylist(
        @Header("Authorization") token: String,
        @Path("id") id: String,
        @Body request: AddToPlaylistRequest
    ): Response<ApiResponse<CastafiorePlaylistResponse>>

    @DELETE("playlists/{id}/songs/{songId}")
    suspend fun removeSongFromPlaylist(
        @Header("Authorization") token: String,
        @Path("id") playlistId: String,
        @Path("songId") songId: String
    ): Response<ApiResponse<Unit>>

    // Favorites endpoints
    @GET("favorites")
    suspend fun getFavorites(
        @Header("Authorization") token: String,
        @Query("type") type: String? = null // "song", "album", "artist"
    ): Response<ApiResponse<FavoritesResponse>>

    @POST("favorites")
    suspend fun addToFavorites(
        @Header("Authorization") token: String,
        @Body request: FavoriteRequest
    ): Response<ApiResponse<Unit>>

    @DELETE("favorites")
    suspend fun removeFromFavorites(
        @Header("Authorization") token: String,
        @Query("item_id") itemId: String,
        @Query("item_type") itemType: String
    ): Response<ApiResponse<Unit>>

    // Streaming endpoints
    @GET("stream/{id}")
    suspend fun streamSong(
        @Header("Authorization") token: String,
        @Path("id") songId: String,
        @Query("quality") quality: String? = null,
        @Query("format") format: String? = null
    ): Response<okhttp3.ResponseBody>

    // Statistics and discovery
    @GET("stats")
    suspend fun getStats(
        @Header("Authorization") token: String
    ): Response<ApiResponse<StatsResponse>>

    @GET("discover/similar/songs/{id}")
    suspend fun getSimilarSongs(
        @Header("Authorization") token: String,
        @Path("id") songId: String,
        @Query("limit") limit: Int = 20
    ): Response<ApiResponse<List<SongResponse>>>

    @GET("discover/similar/artists/{id}")
    suspend fun getSimilarArtists(
        @Header("Authorization") token: String,
        @Path("id") artistId: String,
        @Query("limit") limit: Int = 10
    ): Response<ApiResponse<List<ArtistResponse>>>

    // Playback tracking
    @POST("playback/start")
    suspend fun startPlayback(
        @Header("Authorization") token: String,
        @Body request: Map<String, String> // {"song_id": "123"}
    ): Response<ApiResponse<Unit>>

    @POST("playback/scrobble")
    suspend fun scrobble(
        @Header("Authorization") token: String,
        @Body request: ScrobbleRequest
    ): Response<ApiResponse<Unit>>

    // Star/Favorite endpoints
    @POST("starred/songs/{id}")
    suspend fun starSong(
        @Header("Authorization") token: String,
        @Path("id") songId: String
    ): Response<ApiResponse<Unit>>

    @DELETE("starred/songs/{id}")
    suspend fun unstarSong(
        @Header("Authorization") token: String,
        @Path("id") songId: String
    ): Response<ApiResponse<Unit>>

    @POST("starred/albums/{id}")
    suspend fun starAlbum(
        @Header("Authorization") token: String,
        @Path("id") albumId: String
    ): Response<ApiResponse<Unit>>

    @DELETE("starred/albums/{id}")
    suspend fun unstarAlbum(
        @Header("Authorization") token: String,
        @Path("id") albumId: String
    ): Response<ApiResponse<Unit>>

    @POST("starred/artists/{id}")
    suspend fun starArtist(
        @Header("Authorization") token: String,
        @Path("id") artistId: String
    ): Response<ApiResponse<Unit>>

    @DELETE("starred/artists/{id}")
    suspend fun unstarArtist(
        @Header("Authorization") token: String,
        @Path("id") artistId: String
    ): Response<ApiResponse<Unit>>

    @GET("starred")
    suspend fun getStarred(
        @Header("Authorization") token: String
    ): Response<ApiResponse<StarredResponse>>

    @GET("rest/getStarredAlbums.view")
    suspend fun getStarredAlbums(): List<Album>
}
