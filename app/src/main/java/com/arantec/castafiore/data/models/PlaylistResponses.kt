package com.arantec.castafiore.data.models

import com.google.gson.annotations.SerializedName

// Respuesta para getPlaylists
data class PlaylistsResponse(
    @SerializedName("subsonic-response")
    val subsonicResponse: PlaylistsResult
)

data class PlaylistsResult(
    val status: String,
    val version: String,
    val playlists: PlaylistsContainer?,
    val error: ErrorResponse?
)

data class PlaylistsContainer(
    val playlist: List<PlaylistData>?
)

// Respuesta para createPlaylist y updatePlaylist
data class PlaylistResponse(
    @SerializedName("subsonic-response")
    val subsonicResponse: PlaylistResult
)

data class PlaylistResult(
    val status: String,
    val version: String,
    val playlist: PlaylistData?,
    val error: ErrorResponse?
)

// Datos de playlist que vienen del servidor
data class PlaylistData(
    val id: String,
    val name: String,
    val comment: String? = null,
    val owner: String? = null,
    val public: Boolean? = false,
    val songCount: Int? = 0,
    val duration: Int? = 0,
    val created: String? = null,
    val changed: String? = null,
    val coverArt: String? = null
)

// Nuevos modelos para getPlaylist (detalle con entradas)
data class PlaylistDetailResponse(
    @SerializedName("subsonic-response")
    val subsonicResponse: PlaylistDetailResult
)

data class PlaylistDetailResult(
    val status: String,
    val version: String,
    val playlist: PlaylistDetail?,
    val error: ErrorResponse?
)

// PlaylistDetail incluye las mismas propiedades que PlaylistData y la lista de canciones como `entry`
data class PlaylistDetail(
    val id: String,
    val name: String,
    val comment: String? = null,
    val owner: String? = null,
    val public: Boolean? = false,
    val songCount: Int? = 0,
    val duration: Int? = 0,
    val created: String? = null,
    val changed: String? = null,
    val coverArt: String? = null,
    @SerializedName("entry") val entries: List<Song>? = null
)
