package com.arantec.castafiore.data.cache

import android.content.Context
import android.content.SharedPreferences
import com.arantec.castafiore.data.lyrics.LyricsLine
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Simple disk cache for lyrics lines, keyed by song id.
 * Backed by SharedPreferences with JSON serialization.
 */
class LyricsCacheStore private constructor(context: Context) {

    companion object {
        @Volatile private var INSTANCE: LyricsCacheStore? = null
        private const val PREF_NAME = "lyrics_cache_prefs"

        fun getInstance(context: Context): LyricsCacheStore {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: LyricsCacheStore(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val listType = object : TypeToken<List<LyricsLine>>() {}.type

    fun get(songId: String): List<LyricsLine>? {
        return try {
            val json = prefs.getString(key(songId), null) ?: return null
            gson.fromJson<List<LyricsLine>>(json, listType)
        } catch (_: Exception) { null }
    }

    fun put(songId: String, lines: List<LyricsLine>) {
        try {
            val json = gson.toJson(lines)
            prefs.edit().putString(key(songId), json).apply()
        } catch (_: Exception) { /* ignore */ }
    }

    fun clear(songId: String) {
        prefs.edit().remove(key(songId)).apply()
    }

    private fun key(songId: String) = "lyrics_$songId"
}

