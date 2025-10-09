package com.arantec.castafiore.data.models

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class Playlist(
    val id: String,
    val name: String,
    val songCount: Int = 0,
    val duration: Int = 0,
    val public: Boolean = false,
    val owner: String? = null,
    val comment: String? = null,
    val created: String? = null,
    val changed: String? = null,
    val coverArt: String? = null,
    val songs: List<Song> = emptyList()
) : Parcelable
