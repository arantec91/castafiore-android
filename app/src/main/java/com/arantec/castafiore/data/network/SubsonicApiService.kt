package com.arantec.castafiore.data.network

import com.arantec.castafiore.data.model.SubsonicResponseWrapper
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query
import retrofit2.http.Streaming

/**
 * Subsonic API Service for compatibility with Subsonic protocol
 * Based on Subsonic API version 1.16.1
 */
interface SubsonicApiService {
    
    /**
     * Ping endpoint for testing authentication
     * Used to verify server connectivity and credentials
     */
    @GET("rest/ping.view")
    suspend fun ping(
        @Query("u") username: String,
        @Query("t") token: String,
        @Query("s") salt: String,
        @Query("v") version: String,
        @Query("c") client: String,
        @Query("f") format: String = "json"
    ): Response<SubsonicResponseWrapper>
    
    /**
     * Get license information
     */
    @GET("rest/getLicense.view")
    suspend fun getLicense(
        @Query("u") username: String,
        @Query("t") token: String,
        @Query("s") salt: String,
        @Query("v") version: String,
        @Query("c") client: String,
        @Query("f") format: String = "json"
    ): Response<SubsonicResponseWrapper>
    
    /**
     * Get all artists
     */
    @GET("rest/getArtists.view")
    suspend fun getArtists(
        @Query("u") username: String,
        @Query("t") token: String,
        @Query("s") salt: String,
        @Query("v") version: String,
        @Query("c") client: String,
        @Query("f") format: String = "json"
    ): Response<SubsonicResponseWrapper>
    
    /**
     * Get artist details
     */
    @GET("rest/getArtist.view")
    suspend fun getArtist(
        @Query("id") id: String,
        @Query("u") username: String,
        @Query("t") token: String,
        @Query("s") salt: String,
        @Query("v") version: String,
        @Query("c") client: String,
        @Query("f") format: String = "json"
    ): Response<SubsonicResponseWrapper>
    
    /**
     * Get album details
     */
    @GET("rest/getAlbum.view")
    suspend fun getAlbum(
        @Query("id") id: String,
        @Query("u") username: String,
        @Query("t") token: String,
        @Query("s") salt: String,
        @Query("v") version: String,
        @Query("c") client: String,
        @Query("f") format: String = "json"
    ): Response<SubsonicResponseWrapper>
    
    /**
     * Search for artists, albums, and songs
     */
    @GET("rest/search3.view")
    suspend fun search3(
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
    
    /**
     * Get random songs
     */
    @GET("rest/getRandomSongs.view")
    suspend fun getRandomSongs(
        @Query("size") size: Int = 20,
        @Query("genre") genre: String? = null,
        @Query("u") username: String,
        @Query("t") token: String,
        @Query("s") salt: String,
        @Query("v") version: String,
        @Query("c") client: String,
        @Query("f") format: String = "json"
    ): Response<SubsonicResponseWrapper>
    
    /**
     * Get album list
     */
    @GET("rest/getAlbumList2.view")
    suspend fun getAlbumList2(
        @Query("type") type: String, // newest, recent, frequent, random, etc.
        @Query("size") size: Int = 20,
        @Query("offset") offset: Int = 0,
        @Query("u") username: String,
        @Query("t") token: String,
        @Query("s") salt: String,
        @Query("v") version: String,
        @Query("c") client: String,
        @Query("f") format: String = "json"
    ): Response<SubsonicResponseWrapper>
    
    /**
     * Star an item (song, album, or artist)
     */
    @GET("rest/star.view")
    suspend fun star(
        @Query("id") id: String? = null,
        @Query("albumId") albumId: String? = null,
        @Query("artistId") artistId: String? = null,
        @Query("u") username: String,
        @Query("t") token: String,
        @Query("s") salt: String,
        @Query("v") version: String,
        @Query("c") client: String,
        @Query("f") format: String = "json"
    ): Response<SubsonicResponseWrapper>
    
    /**
     * Unstar an item
     */
    @GET("rest/unstar.view")
    suspend fun unstar(
        @Query("id") id: String? = null,
        @Query("albumId") albumId: String? = null,
        @Query("artistId") artistId: String? = null,
        @Query("u") username: String,
        @Query("t") token: String,
        @Query("s") salt: String,
        @Query("v") version: String,
        @Query("c") client: String,
        @Query("f") format: String = "json"
    ): Response<SubsonicResponseWrapper>
    
    /**
     * Get starred items
     */
    @GET("rest/getStarred2.view")
    suspend fun getStarred2(
        @Query("u") username: String,
        @Query("t") token: String,
        @Query("s") salt: String,
        @Query("v") version: String,
        @Query("c") client: String,
        @Query("f") format: String = "json"
    ): Response<SubsonicResponseWrapper>
    
    /**
     * Scrobble a song (mark as played)
     */
    @GET("rest/scrobble.view")
    suspend fun scrobble(
        @Query("id") id: String,
        @Query("time") time: Long? = null,
        @Query("submission") submission: Boolean = true,
        @Query("u") username: String,
        @Query("t") token: String,
        @Query("s") salt: String,
        @Query("v") version: String,
        @Query("c") client: String,
        @Query("f") format: String = "json"
    ): Response<SubsonicResponseWrapper>
    
    /**
     * Set now playing status for a song
     */
    @GET("rest/setNowPlaying.view")
    suspend fun setNowPlaying(
        @Query("id") id: String,
        @Query("u") username: String,
        @Query("t") token: String,
        @Query("s") salt: String,
        @Query("v") version: String,
        @Query("c") client: String,
        @Query("f") format: String = "json"
    ): Response<SubsonicResponseWrapper>

    /**
     * Get top songs, optionally filtered by artist
     */
    @GET("rest/getTopSongs.view")
    suspend fun getTopSongs(
        @Query("artist") artist: String? = null,
        @Query("count") count: Int = 50,
        @Query("u") username: String,
        @Query("t") token: String,
        @Query("s") salt: String,
        @Query("v") version: String,
        @Query("c") client: String,
        @Query("f") format: String = "json"
    ): Response<SubsonicResponseWrapper>

    /**
     * Get artist info including similar artists
     */
    @GET("rest/getArtistInfo2.view")
    suspend fun getArtistInfo2(
        @Query("id") id: String,
        @Query("count") count: Int = 20,
        @Query("includeNotPresent") includeNotPresent: Boolean = true,
        @Query("u") username: String,
        @Query("t") token: String,
        @Query("s") salt: String,
        @Query("v") version: String,
        @Query("c") client: String,
        @Query("f") format: String = "json"
    ): Response<SubsonicResponseWrapper>

    /**
     * Download a song with streaming support
     * Returns ResponseBody for streaming download
     */
    @GET("rest/download.view")
    @Streaming
    suspend fun downloadSong(
        @Query("id") id: String,
        @Query("u") username: String,
        @Query("t") token: String,
        @Query("s") salt: String,
        @Query("v") version: String,
        @Query("c") client: String
    ): Response<okhttp3.ResponseBody>

    /**
     * Get download info for multiple songs
     * Returns size and format information
     */
    @GET("rest/getDownloadInfo.view")
    suspend fun getDownloadInfo(
        @Query("id") ids: String, // Comma-separated list of song IDs
        @Query("u") username: String,
        @Query("t") token: String,
        @Query("s") salt: String,
        @Query("v") version: String,
        @Query("c") client: String,
        @Query("f") format: String = "json"
    ): Response<SubsonicResponseWrapper>
}