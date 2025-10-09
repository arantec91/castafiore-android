package com.arantec.castafiore.data.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class PlaybackSource(
    val type: SourceType,
    val id: String?,
    val name: String
) : Parcelable

enum class SourceType {
    SONGS,
    ALBUM,
    ARTIST,
    PLAYLIST,
    FAVORITES,
    DOWNLOADS,
    SEARCH,
    RANDOM,
    SIMILAR_SONGS
}
