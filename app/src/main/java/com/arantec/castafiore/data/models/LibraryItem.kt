package com.arantec.castafiore.data.models

data class LibraryItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val imageUrl: String?,
    val type: LibraryItemType
)

enum class LibraryItemType {
    PLAYLIST, ARTIST, ALBUM, LIKED_SONGS, DOWNLOADS
}
