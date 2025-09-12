package com.arantec.castafiore.data.local

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

object SearchHistoryManager {
    private const val PREF_NAME = "search_history_prefs"
    private const val KEY_HISTORY = "search_history_items"
    private const val MAX_ITEMS = 20

    private val gson = Gson()

    data class Entry(
        val type: Type,
        val id: String,
        val title: String,
        val subtitle: String? = null,
        val timestamp: Long = System.currentTimeMillis()
    ) {
        enum class Type { SONG, ARTIST, ALBUM, PLAYLIST }
    }

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun getHistory(ctx: Context): List<Entry> {
        val json = prefs(ctx).getString(KEY_HISTORY, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<Entry>>() {}.type
            gson.fromJson<List<Entry>>(json, type).sortedByDescending { it.timestamp }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit().remove(KEY_HISTORY).apply()
    }

    private fun save(ctx: Context, list: List<Entry>) {
        val json = gson.toJson(list.take(MAX_ITEMS))
        prefs(ctx).edit().putString(KEY_HISTORY, json).apply()
    }

    private fun add(ctx: Context, entry: Entry) {
        val current = getHistory(ctx).toMutableList()
        // Remove existing same id/type
        current.removeAll { it.type == entry.type && it.id == entry.id }
        current.add(0, entry.copy(timestamp = System.currentTimeMillis()))
        save(ctx, current)
    }

    fun addSong(ctx: Context, id: String, title: String, artist: String?) {
        add(ctx, Entry(Entry.Type.SONG, id, title, artist))
    }

    fun addArtist(ctx: Context, id: String, name: String) {
        add(ctx, Entry(Entry.Type.ARTIST, id, name, null))
    }

    fun addAlbum(ctx: Context, id: String, name: String, artist: String?) {
        add(ctx, Entry(Entry.Type.ALBUM, id, name, artist))
    }

    fun addPlaylist(ctx: Context, id: String, name: String) {
        add(ctx, Entry(Entry.Type.PLAYLIST, id, name))
    }
}

