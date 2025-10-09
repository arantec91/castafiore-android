package com.arantec.castafiore.data.model

import com.google.gson.annotations.SerializedName

// Clase para mapear el posible error en la respuesta
data class SubsonicError(
    val code: Int?,
    val message: String?
)

// Clase principal para la respuesta subsonic
data class SubsonicResponse(
    val status: String?,
    val version: String?,
    val type: String?,
    val error: SubsonicError? = null,
    @SerializedName("albumList2") val albumList2: SubsonicAlbumList2? = null,
    @SerializedName("artists") val artists: SubsonicArtists? = null,
    @SerializedName("artist") val artist: SubsonicArtistWithAlbums? = null,
    @SerializedName("album") val album: SubsonicAlbumID3? = null,
    @SerializedName("searchResult3") val searchResult3: SubsonicSearchResult3? = null,
    @SerializedName("randomSongs") val randomSongs: SubsonicRandomSongs? = null,
    @SerializedName("starred2") val starred2: SubsonicStarred2? = null,
    @SerializedName("topSongs") val topSongs: SubsonicTopSongs? = null,
    @SerializedName("artistInfo2") val artistInfo2: SubsonicArtistInfo2? = null
)

// Clase para topSongs
data class SubsonicTopSongs(
    val song: List<SubsonicChild>? = null
)
