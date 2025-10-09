package com.arantec.castafiore.data.models

import android.os.Parcelable
import com.google.gson.annotations.SerializedName
import kotlinx.parcelize.Parcelize

@Parcelize
data class Artist(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("albumCount") val albumCount: Int = 0,
    val starred: Boolean = false
) : Parcelable

data class ArtistInfo(
    val view: List<Artist>
)
