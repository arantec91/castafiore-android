package com.arantec.castafiore.data.models

fun SongResponse.toSong(): Song = Song(
    id = this.id,
    title = this.title,
    artist = this.artist?.name?.takeIf { it.isNotBlank() && it != "." } ?: "",
    album = this.album?.title ?: "",
    duration = this.duration,
    track = this.track,
    year = this.year,
    genre = this.genre,
    artistId = this.artistId,
    albumId = this.albumId,
    bitRate = this.bitRate,
    size = this.size,
    suffix = this.suffix,
    coverArt = this.coverArt,
    path = this.path
)
