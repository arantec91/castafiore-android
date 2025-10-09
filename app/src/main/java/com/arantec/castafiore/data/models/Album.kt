package com.arantec.castafiore.data.models

import android.os.Parcelable
import com.google.gson.annotations.SerializedName
import kotlinx.parcelize.Parcelize

@Parcelize
data class Album(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("artist") val artist: String,
    @SerializedName("artistId") val artistId: String? = null,
    @SerializedName("songCount") val songCount: Int,
    @SerializedName("duration") val duration: Int,
    @SerializedName("year") val year: Int? = null,
    @SerializedName("genre") val genre: String? = null,
    @SerializedName("coverArt") val coverArt: String? = null,
    @SerializedName("playCount") val playCount: Int? = null // Added for most played sorting
) : Parcelable {
    // Get formatted duration
    fun getFormattedDuration(): String {
        val hours = duration / 3600
        val minutes = (duration % 3600) / 60
        return if (hours > 0) {
            String.format("%d:%02d:00", hours, minutes)
        } else {
            String.format("%d:00", minutes)
        }
    }
}
