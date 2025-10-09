package com.arantec.castafiore.data.models

import android.os.Parcelable
import com.google.gson.annotations.SerializedName
import kotlinx.parcelize.Parcelize

@Parcelize
data class Song(
    @SerializedName("id") val id: String,
    @SerializedName("title") val title: String,
    @SerializedName("artist") val artist: String,
    @SerializedName("album") val album: String,
    @SerializedName("duration") val duration: Int,
    @SerializedName("track") val track: Int? = null,
    @SerializedName("year") val year: Int? = null,
    @SerializedName("genre") val genre: String? = null,
    @SerializedName("artistId") val artistId: String? = null,
    @SerializedName("albumId") val albumId: String? = null,
    @SerializedName("bitRate") val bitRate: Int? = null,
    @SerializedName("size") val size: Long? = null,
    @SerializedName("suffix") val suffix: String? = null,
    @SerializedName("coverArt") val coverArt: String? = null,
    @SerializedName("playCount") val playCount: Int? = null,
    val path: String? = null
) : Parcelable {

    // Get stream URL for Castafiore API
    fun getStreamUrl(serverUrl: String, username: String, token: String, salt: String, quality: String? = null, format: String? = null): String {
        // Clean the ID by removing any suffix (e.g., "56012_1" -> "56012")
        val cleanId = id.substringBefore('_')
        
        val queryParams = mutableListOf(
            "id=$cleanId",
            "u=$username",
            "t=$token",
            "s=$salt",
            "v=1.16.1",
            "c=Castafiore"
        )

        quality?.let { queryParams.add("maxBitRate=$it") }
        format?.let { queryParams.add("format=$it") }

        val queryString = queryParams.joinToString("&")
        return "$serverUrl/rest/stream.view?$queryString"
    }

    // Get cover art URL
    fun getCoverImageUrl(serverUrl: String, username: String, token: String, salt: String, size: Int? = null): String? {
        return coverArt?.let { coverArtId ->
            val queryParams = mutableListOf(
                "id=$coverArtId",
                "u=$username",
                "t=$token",
                "s=$salt",
                "v=1.16.1",
                "c=Castafiore"
            )

            size?.let { queryParams.add("size=$it") }

            val queryString = queryParams.joinToString("&")
            "$serverUrl/rest/getCoverArt.view?$queryString"
        }
    }

    // Compatibility properties
    val trackNumber: Int? get() = track

    fun getFormattedDuration(): String {
        val minutes = duration / 60
        val seconds = duration % 60
        return String.format("%d:%02d", minutes, seconds)
    }
}

fun Song.getCoverArtUrl(serverUrl: String, username: String, token: String, salt: String): String? {
    return coverArt?.let {
        "$serverUrl/rest/getCoverArt.view?id=$it&u=$username&t=$token&s=$salt&v=1.16.1&c=Castafiore"
    }
}
