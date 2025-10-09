package com.arantec.castafiore.data.cache

import com.google.gson.reflect.TypeToken
import java.lang.reflect.Type

/**
 * Type tokens for cache operations
 */
object CacheTypes {
    val SONG_LIST: Type = object : TypeToken<List<com.arantec.castafiore.data.models.Song>>() {}.type
    val ALBUM_LIST: Type = object : TypeToken<List<com.arantec.castafiore.data.models.Album>>() {}.type
    val ARTIST_LIST: Type = object : TypeToken<List<com.arantec.castafiore.data.models.Artist>>() {}.type
    val PLAYLIST_LIST: Type = object : TypeToken<List<com.arantec.castafiore.data.models.Playlist>>() {}.type

    val SONG: Type = object : TypeToken<com.arantec.castafiore.data.models.Song>() {}.type
    val ALBUM: Type = object : TypeToken<com.arantec.castafiore.data.models.Album>() {}.type
    val ARTIST: Type = object : TypeToken<com.arantec.castafiore.data.models.Artist>() {}.type
    val PLAYLIST: Type = object : TypeToken<com.arantec.castafiore.data.models.Playlist>() {}.type
    val ALBUM_DETAIL: Type = object : TypeToken<com.arantec.castafiore.data.models.AlbumDetail>() {}.type
    val USER: Type = object : TypeToken<com.arantec.castafiore.data.models.User>() {}.type

    val BOOLEAN: Type = object : TypeToken<Boolean>() {}.type
    val STRING: Type = object : TypeToken<String>() {}.type
    val INT: Type = object : TypeToken<Int>() {}.type

    val SEARCH_RESULT: Type = object : TypeToken<Triple<List<com.arantec.castafiore.data.models.Song>, List<com.arantec.castafiore.data.models.Album>, List<com.arantec.castafiore.data.models.Artist>>>() {}.type
}
