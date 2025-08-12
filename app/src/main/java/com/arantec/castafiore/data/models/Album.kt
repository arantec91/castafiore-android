package com.arantec.castafiore.data.models

import android.os.Parcelable
import com.google.gson.annotations.SerializedName
import kotlinx.parcelize.Parcelize

@Parcelize
data class Album(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("artist") val artist: String,
    @SerializedName("artistId") val artistId: String,
    @SerializedName("coverArt") val coverArt: String? = null,
    @SerializedName("songCount") val songCount: Int,
    @SerializedName("duration") val duration: Int,
    @SerializedName("playCount") val playCount: Int? = null,
    @SerializedName("created") val created: String = "",
    @SerializedName("year") val year: Int? = null,
    @SerializedName("genre") val genre: String? = null,
    @SerializedName("song") val songs: List<Song>? = null
) : Parcelable {
    fun getCoverArtUrl(serverUrl: String, username: String, token: String, salt: String): String? {
        return coverArt?.let {
            "$serverUrl/rest/getCoverArt.view?id=$it&u=$username&t=$token&s=$salt&v=1.16.1&c=Castafiore&size=300"
        }
    }
}
