package com.arantec.castafiore.data.models

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class Playlist(
    val id: String,
    val name: String,
    val comment: String? = null,
    val owner: String? = null,
    val public: Boolean = false,
    val songCount: Int = 0,
    val duration: Int = 0, // en segundos
    val created: String? = null,
    val changed: String? = null,
    val coverArt: String? = null
) : Parcelable {

    fun getCoverArtUrl(serverUrl: String, username: String, token: String, salt: String, size: Int = 300): String? {
        return if (coverArt != null) {
            "$serverUrl/rest/getCoverArt.view?id=$coverArt&u=$username&t=$token&s=$salt&v=1.16.1&c=Castafiore&size=$size"
        } else {
            null
        }
    }
}
