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
    @SerializedName("coverArt") val coverArt: String? = null,
    @SerializedName("artistId") val artistId: String? = null,
    @SerializedName("albumId") val albumId: String? = null,
    @SerializedName("path") val path: String? = null,
    @SerializedName("suffix") val suffix: String? = null,
    @SerializedName("bitRate") val bitRate: Int? = null,
    @SerializedName("size") val size: Long? = null
) : Parcelable {
    fun getStreamUrl(
        serverUrl: String,
        username: String,
        token: String,
        salt: String,
        maxBitRate: Int? = null,
        format: String? = null
    ): String {
        val base = StringBuilder()
            .append(serverUrl)
            .append("/rest/stream?id=")
            .append(id)
            .append("&u=")
            .append(username)
            .append("&t=")
            .append(token)
            .append("&s=")
            .append(salt)
            .append("&v=1.16.1&c=Castafiore")
        if (maxBitRate != null) base.append("&maxBitRate=").append(maxBitRate)
        if (format != null) base.append("&format=").append(format)
        return base.toString()
    }

    fun getCoverArtUrl(serverUrl: String, username: String, token: String, salt: String): String? {
        return coverArt?.let {
            "$serverUrl/rest/getCoverArt?id=$it&u=$username&t=$token&s=$salt&v=1.16.1&c=Castafiore&size=300"
        }
    }

    fun getFormattedDuration(): String {
        val minutes = duration / 60
        val seconds = duration % 60
        return String.format("%d:%02d", minutes, seconds)
    }
}
