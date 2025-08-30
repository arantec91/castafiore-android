package com.arantec.castafiore.utils

import android.content.Context

object PlaylistFavoritesManager {
    private const val PREFS_NAME = "castafiore_prefs"
    private const val KEY_FAVORITE_PUBLIC_PLAYLISTS = "favorite_public_playlists"

    private fun getPrefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getFavorites(context: Context): Set<String> {
        // Always return a new copy to avoid SharedPreferences mutable set pitfalls
        val set = getPrefs(context).getStringSet(KEY_FAVORITE_PUBLIC_PLAYLISTS, emptySet())
        return set?.toSet() ?: emptySet()
    }

    fun isFavorite(context: Context, playlistId: String?): Boolean {
        if (playlistId.isNullOrEmpty()) return false
        return getFavorites(context).contains(playlistId)
    }

    fun addFavorite(context: Context, playlistId: String?) {
        if (playlistId.isNullOrEmpty()) return
        val prefs = getPrefs(context)
        val current = prefs.getStringSet(KEY_FAVORITE_PUBLIC_PLAYLISTS, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (current.add(playlistId)) {
            prefs.edit().putStringSet(KEY_FAVORITE_PUBLIC_PLAYLISTS, current).apply()
        }
    }

    fun removeFavorite(context: Context, playlistId: String?) {
        if (playlistId.isNullOrEmpty()) return
        val prefs = getPrefs(context)
        val current = prefs.getStringSet(KEY_FAVORITE_PUBLIC_PLAYLISTS, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (current.remove(playlistId)) {
            prefs.edit().putStringSet(KEY_FAVORITE_PUBLIC_PLAYLISTS, current).apply()
        }
    }

    fun toggleFavorite(context: Context, playlistId: String?): Boolean {
        if (playlistId.isNullOrEmpty()) return false
        return if (isFavorite(context, playlistId)) {
            removeFavorite(context, playlistId)
            false
        } else {
            addFavorite(context, playlistId)
            true
        }
    }
}

