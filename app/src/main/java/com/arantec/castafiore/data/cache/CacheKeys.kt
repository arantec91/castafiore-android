package com.arantec.castafiore.data.cache

/**
 * Cache keys for different types of data
 */
object CacheKeys {
    // Artists
    const val ARTISTS_LIST = "artists_list"
    const val ARTIST_PREFIX = "artist_"
    const val ARTIST_ALBUMS_PREFIX = "artist_albums_"
    const val ARTIST_SONGS_PREFIX = "artist_songs_"

    // Albums
    const val ALBUMS_LIST = "albums_list"
    const val ALBUM_PREFIX = "album_"
    const val ALBUM_SONGS_PREFIX = "album_songs_"

    // Songs
    const val SONGS_LIST = "songs_list"
    const val SONG_PREFIX = "song_"
    const val RANDOM_SONGS = "random_songs"

    // Playlists
    const val PLAYLISTS_LIST = "playlists_list"
    const val PLAYLIST_PREFIX = "playlist_"
    const val PLAYLIST_SONGS_PREFIX = "playlist_songs_"

    // Search
    const val SEARCH_PREFIX = "search_"

    // Favorites/Starred
    const val STARRED_SONGS = "starred_songs"
    const val STARRED_ALBUMS = "starred_albums"
    const val STARRED_ARTISTS = "starred_artists"
    const val SONG_STARRED_PREFIX = "song_starred_"
    const val ALBUM_STARRED_PREFIX = "album_starred_"
    const val ARTIST_STARRED_PREFIX = "artist_starred_"

    // Home lists
    const val NEWEST_ALBUMS = "newest_albums"
    const val RECENTLY_PLAYED = "recently_played"
    const val MOST_PLAYED = "most_played"

    // User info
    const val USER_INFO = "user_info"

    // Additional keys referenced in errors
    const val ALL_SONGS = "all_songs"
    const val ALL_ALBUMS = "all_albums"
    const val ALL_ARTISTS = "all_artists"
    const val ALL_PLAYLISTS = "all_playlists"
    const val FAVORITE_SONGS = "favorite_songs"
    const val FAVORITE_ALBUMS = "favorite_albums"
    const val FAVORITE_ARTISTS = "favorite_artists"

    // Functions for dynamic keys
    fun artistDetail(artistId: String) = "${ARTIST_PREFIX}detail_$artistId"
    fun artistAlbums(artistId: String) = "${ARTIST_ALBUMS_PREFIX}$artistId"
    fun artistFavorite(artistId: String) = "${ARTIST_STARRED_PREFIX}$artistId"
    fun similarArtists(artistId: String) = "similar_artists_$artistId"

    fun albumDetail(albumId: String) = "${ALBUM_PREFIX}detail_$albumId"
    fun albumSongs(albumId: String) = "${ALBUM_SONGS_PREFIX}$albumId"
    fun albumFavorite(albumId: String) = "${ALBUM_STARRED_PREFIX}$albumId"

    fun songFavorite(songId: String) = "${SONG_STARRED_PREFIX}$songId"

    fun playlistDetail(playlistId: String) = "${PLAYLIST_PREFIX}detail_$playlistId"
    fun playlistSongs(playlistId: String) = "${PLAYLIST_SONGS_PREFIX}$playlistId"
}
