package com.arantec.castafiore.data.models

import android.os.Parcelable
import com.google.gson.annotations.SerializedName
import kotlinx.parcelize.Parcelize

data class ErrorResponse(
    @SerializedName("code") val code: Int,
    @SerializedName("message") val message: String
)

// Base API response wrapper
data class ApiResponse<T>(
    val success: Boolean,
    val data: T? = null,
    val message: String? = null,
    val error: String? = null
)

// Paginated response wrapper
data class PaginatedResponse<T>(
    val success: Boolean,
    val data: List<T>? = null,
    val pagination: Pagination? = null,
    val message: String? = null,
    val error: String? = null
)

data class Pagination(
    val page: Int,
    val limit: Int,
    val total: Int,
    val totalPages: Int
)

// Authentication models
data class LoginRequest(
    val username: String,
    val password: String
)

data class LoginResponse(
    val token: String,
    val refreshToken: String? = null,
    val user: UserResponse? = null,
    val expiresIn: Long? = null
)

data class UserResponse(
    val id: String,
    val username: String,
    val email: String? = null,
    val scrobblingEnabled: Boolean? = null,
    val adminRole: Boolean? = null,
    val settingsRole: Boolean? = null,
    val downloadRole: Boolean? = null,
    val uploadRole: Boolean? = null,
    val playlistRole: Boolean? = null,
    val coverArtRole: Boolean? = null,
    val commentRole: Boolean? = null,
    val podcastRole: Boolean? = null,
    val streamRole: Boolean? = null,
    val jukeboxRole: Boolean? = null,
    val shareRole: Boolean? = null
)

// Domain models - moved to separate files
// Song class removed - defined in Song.kt instead
// Playlist class removed - defined in Playlist.kt instead

data class AlbumDetail(
    val id: String,
    val title: String, // Added title property
    val artist: String,
    val artistId: String? = null,
    val songCount: Int,
    val duration: Int,
    val year: Int? = null,
    val genre: String? = null,
    val coverArt: String? = null,
    val songs: List<Song> = emptyList()
)

@Parcelize
data class UserInfo(
    val email: String? = null,
    val scrobblingEnabled: Boolean = false,
    val adminRole: Boolean = false,
    val settingsRole: Boolean = false,
    val downloadRole: Boolean = false,
    val uploadRole: Boolean = false,
    val playlistRole: Boolean = false,
    val coverArtRole: Boolean = false,
    val commentRole: Boolean = false,
    val podcastRole: Boolean = false,
    val streamRole: Boolean = false,
    val jukeboxRole: Boolean = false,
    val shareRole: Boolean = false
) : Parcelable

// API Response models
data class SongResponse(
    val id: String,
    val title: String,
    val artist: ArtistResponse? = null,
    val album: AlbumResponse? = null,
    val duration: Int,
    val track: Int? = null,
    val year: Int? = null,
    val genre: String? = null,
    val artistId: String? = null,
    val albumId: String? = null,
    val bitRate: Int? = null,
    val size: Long? = null,
    val coverArt: String? = null,
    val suffix: String? = null,
    val path: String? = null
)

data class AlbumResponse(
    val id: String,
    val title: String? = null,
    val artist: String? = null,
    val artistId: String? = null,
    val songCount: Int? = null,
    val duration: Int? = null,
    val year: Int? = null,
    val genre: String? = null,
    val coverArt: String? = null,
    val songs: List<SongResponse>? = null
)

data class ArtistResponse(
    val id: String,
    val name: String,
    val albumCount: Int? = null,
    val albums: List<AlbumResponse>? = null
)

// Search response models
data class SearchResponse(
    val songs: List<SongResponse>? = null,
    val albums: List<AlbumResponse>? = null,
    val artists: List<ArtistResponse>? = null
)

// Search request model
data class SearchRequest(
    val query: String,
    val types: List<String>? = null, // ["song", "album", "artist"]
    val limit: Int = 20,
    val offset: Int = 0
)

// Playlist request models
data class CreatePlaylistRequest(
    val name: String,
    val description: String? = null,
    val public: Boolean = false
)

data class AddToPlaylistRequest(
    val songIds: List<String>
)

// Favorites models
data class FavoritesResponse(
    val songs: List<SongResponse>? = null,
    val albums: List<AlbumResponse>? = null,
    val artists: List<ArtistResponse>? = null
)

data class FavoriteRequest(
    val itemId: String,
    val itemType: String // "song", "album", "artist"
)

// User model for NavidromeClient compatibility
data class User(
    val id: String,
    val username: String,
    val email: String? = null,
    val createdAt: String,
    val updatedAt: String
)

// Statistics response model
data class StatsResponse(
    val totalSongs: Int,
    val totalAlbums: Int,
    val totalArtists: Int,
    val totalPlaylists: Int,
    val totalPlayTime: Long, // in seconds
    val recentlyPlayed: List<SongResponse>? = null,
    val topSongs: List<SongResponse>? = null,
    val topAlbums: List<AlbumResponse>? = null,
    val topArtists: List<ArtistResponse>? = null
)

// Starred response models
data class StarredResponse(
    val songs: List<SongResponse>? = null,
    val albums: List<AlbumResponse>? = null,
    val artists: List<ArtistResponse>? = null
)

// Scrobble request model
data class ScrobbleRequest(
    val songId: String,
    val playedAtMillis: Long,
    val submission: Boolean = true
)

// Simple Playlist response model for Castafiore API
data class CastafiorePlaylistResponse(
    val id: String,
    val name: String,
    val description: String? = null,
    val public: Boolean? = false,
    val songCount: Int? = 0,
    val duration: Int? = 0,
    val created: String? = null,
    val updated: String? = null,
    val owner: String? = null,
    val songs: List<SongResponse>? = null
)
