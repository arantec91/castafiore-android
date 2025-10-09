package com.arantec.castafiore.data.model

import com.google.gson.annotations.SerializedName

/**
 * Subsonic API Models
 * Based on Subsonic API version 1.16.1
 */

// Album model for Subsonic API
data class SubsonicAlbumID3(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("artist") val artist: String?,
    @SerializedName("artistId") val artistId: String?,
    @SerializedName("coverArt") val coverArt: String?,
    @SerializedName("songCount") val songCount: Int?,
    @SerializedName("duration") val duration: Int?,
    @SerializedName("created") val created: String?,
    @SerializedName("year") val year: Int?,
    @SerializedName("genre") val genre: String?,
    @SerializedName("song") val songs: List<SubsonicChild>?
)

// Child model for songs
data class SubsonicChild(
    @SerializedName("id") val id: String,
    @SerializedName("parent") val parent: String?,
    @SerializedName("isDir") val isDir: Boolean?,
    @SerializedName("title") val title: String,
    @SerializedName("album") val album: String?,
    @SerializedName("artist") val artist: String?,
    @SerializedName("track") val track: Int?,
    @SerializedName("year") val year: Int?,
    @SerializedName("genre") val genre: String?,
    @SerializedName("coverArt") val coverArt: String?,
    @SerializedName("size") val size: Long?,
    @SerializedName("contentType") val contentType: String?,
    @SerializedName("suffix") val suffix: String?,
    @SerializedName("duration") val duration: Int?,
    @SerializedName("bitRate") val bitRate: Int?,
    @SerializedName("path") val path: String?,
    @SerializedName("albumId") val albumId: String?,
    @SerializedName("artistId") val artistId: String?,
    @SerializedName("type") val type: String?,
    @SerializedName("starred") val starred: String?
)

// Artist model for Subsonic API
data class SubsonicArtistID3(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("coverArt") val coverArt: String?,
    @SerializedName("albumCount") val albumCount: Int?,
    @SerializedName("starred") val starred: String?
)

// Album list response
data class SubsonicAlbumList2(
    @SerializedName("album") val albums: List<SubsonicAlbumID3>?
)

// Artists response
data class SubsonicArtists(
    @SerializedName("index") val indexes: List<SubsonicIndex>?
)

data class SubsonicIndex(
    @SerializedName("name") val name: String,
    @SerializedName("artist") val artists: List<SubsonicArtistID3>?
)

// Artist with albums
data class SubsonicArtistWithAlbums(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("albumCount") val albumCount: Int?,
    @SerializedName("album") val albums: List<SubsonicAlbumID3>?
)

// Search result
data class SubsonicSearchResult3(
    @SerializedName("artist") val artists: List<SubsonicArtistID3>?,
    @SerializedName("album") val albums: List<SubsonicAlbumID3>?,
    @SerializedName("song") val songs: List<SubsonicChild>?
)

// Random songs
data class SubsonicRandomSongs(
    @SerializedName("song") val songs: List<SubsonicChild>?
)

// Starred items
data class SubsonicStarred2(
    @SerializedName("artist") val artists: List<SubsonicArtistID3>?,
    @SerializedName("album") val albums: List<SubsonicAlbumID3>?,
    @SerializedName("song") val songs: List<SubsonicChild>?
)

// Artist info response
data class SubsonicArtistInfo2(
    @SerializedName("similarArtist") val similarArtist: List<SubsonicArtistID3>?
)
