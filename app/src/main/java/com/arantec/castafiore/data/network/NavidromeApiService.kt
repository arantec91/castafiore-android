package com.arantec.castafiore.data.network

import com.arantec.castafiore.data.models.*
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

interface NavidromeApiService {

    @GET("rest/ping.view")
    suspend fun ping(
        @Query("u") username: String,
        @Query("t") token: String,
        @Query("s") salt: String,
        @Query("v") version: String,
        @Query("c") client: String,
        @Query("f") format: String = "json"
    ): Response<PingResponse>

    @GET("rest/search3.view")
    suspend fun search(
        @Query("u") username: String,
        @Query("t") token: String,
        @Query("s") salt: String,
        @Query("v") version: String,
        @Query("c") client: String,
        @Query("query") query: String,
        @Query("artistCount") artistCount: Int = 10,
        @Query("albumCount") albumCount: Int = 10,
        @Query("songCount") songCount: Int = 20,
        @Query("f") format: String = "json"
    ): Response<SearchResponse>

    @GET("rest/getAlbumList2.view")
    suspend fun getAlbums(
        @Query("u") username: String,
        @Query("t") token: String,
        @Query("s") salt: String,
        @Query("v") version: String,
        @Query("c") client: String,
        @Query("type") type: String = "newest",
        @Query("size") size: Int = 20,
        @Query("offset") offset: Int = 0,
        @Query("f") format: String = "json"
    ): Response<AlbumsResponse>

    @GET("rest/getArtists.view")
    suspend fun getArtists(
        @Query("u") username: String,
        @Query("t") token: String,
        @Query("s") salt: String,
        @Query("v") version: String,
        @Query("c") client: String,
        @Query("f") format: String = "json"
    ): Response<ArtistsResponse>

    @GET("rest/getArtist.view")
    suspend fun getArtist(
        @Query("u") username: String,
        @Query("t") token: String,
        @Query("s") salt: String,
        @Query("v") version: String,
        @Query("c") client: String,
        @Query("id") id: String,
        @Query("f") format: String = "json"
    ): Response<ArtistResponse>

    @GET("rest/getAlbum.view")
    suspend fun getAlbum(
        @Query("u") username: String,
        @Query("t") token: String,
        @Query("s") salt: String,
        @Query("v") version: String,
        @Query("c") client: String,
        @Query("id") id: String,
        @Query("f") format: String = "json"
    ): Response<AlbumResponse>

    @GET("rest/getRandomSongs.view")
    suspend fun getRandomSongs(
        @Query("u") username: String,
        @Query("t") token: String,
        @Query("s") salt: String,
        @Query("v") version: String,
        @Query("c") client: String,
        @Query("size") size: Int = 20,
        @Query("f") format: String = "json"
    ): Response<RandomSongsResponse>

    @GET("rest/getTopSongs.view")
    suspend fun getTopSongs(
        @Query("u") username: String,
        @Query("t") token: String,
        @Query("s") salt: String,
        @Query("v") version: String,
        @Query("c") client: String,
        @Query("artist") artist: String,
        @Query("count") count: Int = 25,
        @Query("f") format: String = "json"
    ): Response<TopSongsResponse>

    @GET("rest/star.view")
    suspend fun starItem(
        @Query("u") username: String,
        @Query("t") token: String,
        @Query("s") salt: String,
        @Query("v") version: String,
        @Query("c") client: String,
        @Query("id") id: String,
        @Query("f") format: String = "json"
    ): Response<StarResponse>

    @GET("rest/unstar.view")
    suspend fun unstarItem(
        @Query("u") username: String,
        @Query("t") token: String,
        @Query("s") salt: String,
        @Query("v") version: String,
        @Query("c") client: String,
        @Query("id") id: String,
        @Query("f") format: String = "json"
    ): Response<StarResponse>

    @GET("rest/getStarred.view")
    suspend fun getStarredItems(
        @Query("u") username: String,
        @Query("t") token: String,
        @Query("s") salt: String,
        @Query("v") version: String,
        @Query("c") client: String,
        @Query("f") format: String = "json"
    ): Response<StarredResponse>

    // Endpoints para playlists
    @GET("rest/getPlaylists.view")
    suspend fun getPlaylists(
        @Query("u") username: String,
        @Query("t") token: String,
        @Query("s") salt: String,
        @Query("v") version: String,
        @Query("c") client: String,
        @Query("f") format: String = "json"
    ): Response<PlaylistsResponse>

    @GET("rest/createPlaylist.view")
    suspend fun createPlaylist(
        @Query("u") username: String,
        @Query("t") token: String,
        @Query("s") salt: String,
        @Query("v") version: String,
        @Query("c") client: String,
        @Query("name") name: String,
        @Query("comment") comment: String? = null,
        @Query("f") format: String = "json"
    ): Response<PlaylistResponse>

    @GET("rest/updatePlaylist.view")
    suspend fun updatePlaylist(
        @Query("u") username: String,
        @Query("t") token: String,
        @Query("s") salt: String,
        @Query("v") version: String,
        @Query("c") client: String,
        @Query("playlistId") playlistId: String,
        @Query("name") name: String? = null,
        @Query("comment") comment: String? = null,
        @Query("public") isPublic: Boolean? = null,
        @Query("songIdToAdd") songIdToAdd: String? = null,
        @Query("songIndexToRemove") songIndexToRemove: Int? = null,
        @Query("f") format: String = "json"
    ): Response<PlaylistResponse>

    @GET("rest/deletePlaylist.view")
    suspend fun deletePlaylist(
        @Query("u") username: String,
        @Query("t") token: String,
        @Query("s") salt: String,
        @Query("v") version: String,
        @Query("c") client: String,
        @Query("id") id: String,
        @Query("f") format: String = "json"
    ): Response<PlaylistResponse>

    @GET("rest/getSimilarSongs2.view")
    suspend fun getSimilarSongs(
        @Query("u") username: String,
        @Query("t") token: String,
        @Query("s") salt: String,
        @Query("v") version: String,
        @Query("c") client: String,
        @Query("id") id: String,
        @Query("size") size: Int = 20,
        @Query("f") format: String = "json"
    ): Response<SimilarSongsResponse>

    @GET("rest/getPlaylist.view")
    suspend fun getPlaylist(
        @Query("u") username: String,
        @Query("t") token: String,
        @Query("s") salt: String,
        @Query("v") version: String,
        @Query("c") client: String,
        @Query("id") id: String,
        @Query("f") format: String = "json"
    ): Response<PlaylistDetailResponse>

    @GET("rest/setNowPlaying.view")
    suspend fun setNowPlaying(
        @Query("u") username: String,
        @Query("t") token: String,
        @Query("s") salt: String,
        @Query("v") version: String,
        @Query("c") client: String,
        @Query("id") id: String,
        @Query("f") format: String = "json"
    ): Response<SimpleResponse>

    @GET("rest/scrobble.view")
    suspend fun scrobble(
        @Query("u") username: String,
        @Query("t") token: String,
        @Query("s") salt: String,
        @Query("v") version: String,
        @Query("c") client: String,
        @Query("id") id: String,
        @Query("time") time: Long? = null,
        @Query("submission") submission: Boolean? = null,
        @Query("f") format: String = "json"
    ): Response<SimpleResponse>
}
