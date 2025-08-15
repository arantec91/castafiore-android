package com.arantec.castafiore.data.models

import com.google.gson.annotations.SerializedName

// Response models for Navidrome API
data class PingResponse(
    @SerializedName("subsonic-response")
    val subsonicResponse: SubsonicResponse
)

data class SearchResponse(
    @SerializedName("subsonic-response")
    val subsonicResponse: SearchResult
)

data class AlbumsResponse(
    @SerializedName("subsonic-response")
    val subsonicResponse: AlbumsResult
)

data class ArtistsResponse(
    @SerializedName("subsonic-response")
    val subsonicResponse: ArtistsResult
)

data class ArtistResponse(
    @SerializedName("subsonic-response")
    val subsonicResponse: ArtistResult
)

data class AlbumResponse(
    @SerializedName("subsonic-response")
    val subsonicResponse: AlbumResult
)

// Base response structure
data class SubsonicResponse(
    val status: String,
    val version: String,
    val type: String?,
    val serverVersion: String?,
    val error: ErrorResponse?
)

data class ErrorResponse(
    val code: Int,
    val message: String
)

// Search results
data class SearchResult(
    val status: String,
    val version: String,
    val searchResult3: SearchResult3?
)

data class SearchResult3(
    val artist: List<Artist>?,
    val album: List<Album>?,
    val song: List<Song>?
)

// Albums results
data class AlbumsResult(
    val status: String,
    val version: String,
    val albumList2: AlbumList2?
)

data class AlbumList2(
    val album: List<Album>
)

// Artists results
data class ArtistsResult(
    val status: String,
    val version: String,
    val artists: Artists?
)

data class Artists(
    val index: List<ArtistIndex>
)

data class ArtistIndex(
    val name: String,
    val artist: List<Artist>
)

// Album detail result
data class AlbumResult(
    val status: String,
    val version: String,
    val album: Album?
)

// Basic Artist model
data class Artist(
    val id: String,
    val name: String,
    val albumCount: Int? = null,
    val starred: String? = null
)

// Artist detail result
data class ArtistResult(
    val status: String,
    val version: String,
    val artist: ArtistDetail?,
    val error: ErrorResponse?
)

data class ArtistDetail(
    val id: String,
    val name: String,
    val albumCount: Int?,
    val starred: String?,
    val album: List<Album>?
)

// Random songs response
data class RandomSongsResponse(
    @SerializedName("subsonic-response")
    val subsonicResponse: RandomSongsResult
)

data class RandomSongsResult(
    val status: String,
    val version: String,
    val randomSongs: RandomSongs?
)

data class RandomSongs(
    val song: List<Song>
)

// Top songs response
data class TopSongsResponse(
    @SerializedName("subsonic-response")
    val subsonicResponse: TopSongsResult
)

data class TopSongsResult(
    val status: String,
    val version: String,
    val topSongs: TopSongs?,
    val error: ErrorResponse?
)

data class TopSongs(
    val song: List<Song>
)

// Star/Unstar response
data class StarResponse(
    @SerializedName("subsonic-response")
    val subsonicResponse: SubsonicResponse
)

// Starred items response
data class StarredResponse(
    @SerializedName("subsonic-response")
    val subsonicResponse: StarredResult
)

data class StarredResult(
    val status: String,
    val version: String,
    val starred: StarredItems?
)

data class StarredItems(
    val artist: List<Artist>?,
    val album: List<Album>?,
    val song: List<Song>?
)

// Similar songs response
data class SimilarSongsResponse(
    @SerializedName("subsonic-response")
    val subsonicResponse: SimilarSongsResult
)

data class SimilarSongsResult(
    val status: String,
    val version: String,
    @SerializedName(value = "similarSongs", alternate = ["similarSongs2"])
    val similarSongs: SimilarSongs?,
    val error: ErrorResponse?
)

data class SimilarSongs(
    val song: List<Song>
)

// Simple base response (status only)
data class SimpleResponse(
    @SerializedName("subsonic-response")
    val subsonicResponse: SubsonicResponse
)
