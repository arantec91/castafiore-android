package com.arantec.castafiore.data.cache

import android.content.Context
import android.content.SharedPreferences
import com.arantec.castafiore.data.models.Song
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Simple ring buffer store for recently played songs, persisted in SharedPreferences.
 * Keeps at most MAX_SIZE items, most recent first, unique by song id.
 */
class RecentPlaysStore private constructor(context: Context) {

    companion object {
        @Volatile private var INSTANCE: RecentPlaysStore? = null
        fun getInstance(context: Context): RecentPlaysStore {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: RecentPlaysStore(context.applicationContext).also { INSTANCE = it }
            }
        }
        private const val PREF_NAME = "recent_plays_prefs"
        private const val KEY_LIST = "recent_plays_list"
        private const val MAX_SIZE = 100
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val listType = object : TypeToken<MutableList<Song>>() {}.type

    @Synchronized
    fun add(song: Song) {
        try {
            val current = loadInternal()
            // Remove existing occurrence by id
            val iterator = current.iterator()
            while (iterator.hasNext()) {
                if (iterator.next().id == song.id) {
                    iterator.remove()
                    break
                }
            }
            // Add to front
            current.add(0, song)
            // Trim
            if (current.size > MAX_SIZE) {
                while (current.size > MAX_SIZE) {
                    if (current.isNotEmpty()) current.removeAt(current.size - 1) else break
                }
            }
            saveInternal(current)
        } catch (_: Exception) { }
    }

    fun getAll(limit: Int = MAX_SIZE): List<Song> {
        return try {
            val list = loadInternal()
            if (limit < list.size) list.subList(0, limit) else list
        } catch (_: Exception) { emptyList() }
    }

    private fun loadInternal(): MutableList<Song> {
        val json = prefs.getString(KEY_LIST, null) ?: return mutableListOf()
        return try { gson.fromJson<MutableList<Song>>(json, listType) ?: mutableListOf() } catch (_: Exception) { mutableListOf() }
    }

    private fun saveInternal(list: MutableList<Song>) {
        val json = gson.toJson(list)
        prefs.edit().putString(KEY_LIST, json).apply()
    }
}
