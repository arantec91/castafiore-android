package com.arantec.castafiore.service

import android.content.Context
import android.content.SharedPreferences
import com.arantec.castafiore.data.models.Song
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Gestiona la persistencia del estado de reproducción para mantener la información
 * cuando la aplicación se cierra o el servicio se destruye
 */
class PlaybackStateManager(private val context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences("playback_state", Context.MODE_PRIVATE)
    private val gson = Gson()
    
    companion object {
        private const val KEY_CURRENT_SONG = "current_song"
        private const val KEY_CURRENT_POSITION = "current_position"
        private const val KEY_CURRENT_INDEX = "current_index"
        private const val KEY_PLAYLIST = "playlist"
        private const val KEY_ORIGINAL_QUEUE = "original_queue"
        private const val KEY_IS_PLAYING = "is_playing"
        private const val KEY_REPEAT_MODE = "repeat_mode"
        private const val KEY_SHUFFLE_ENABLED = "shuffle_enabled"
        private const val KEY_PLAYBACK_SOURCE = "playback_source"
        private const val KEY_STATE_SAVED_TIME = "state_saved_time"
        
        // Tiempo máximo para considerar válido un estado guardado (2 horas para Android 13+)
        private const val MAX_STATE_AGE_MS = 2 * 60 * 60 * 1000L
    }
    
    data class PlaybackState(
        val currentSong: Song?,
        val currentPosition: Long,
        val currentIndex: Int,
        val playlist: List<Song>,
        val originalQueue: List<Song>?,
        val isPlaying: Boolean,
        val repeatMode: MusicService.RepeatMode,
        val shuffleEnabled: Boolean,
        val playbackSource: com.arantec.castafiore.data.model.PlaybackSource?,
        val savedTime: Long
    )
    
    /**
     * Guarda el estado actual de reproducción
     */
    fun savePlaybackState(
        currentSong: Song?,
        currentPosition: Long,
        currentIndex: Int,
        playlist: List<Song>,
        originalQueue: List<Song>?,
        isPlaying: Boolean,
        repeatMode: MusicService.RepeatMode,
        shuffleEnabled: Boolean,
        playbackSource: com.arantec.castafiore.data.model.PlaybackSource?
    ) {
        try {
            val editor = prefs.edit()
            
            // Guardar canción actual
            if (currentSong != null) {
                editor.putString(KEY_CURRENT_SONG, gson.toJson(currentSong))
            } else {
                editor.remove(KEY_CURRENT_SONG)
            }
            
            // Guardar información básica
            editor.putLong(KEY_CURRENT_POSITION, currentPosition)
            editor.putInt(KEY_CURRENT_INDEX, currentIndex)
            editor.putBoolean(KEY_IS_PLAYING, isPlaying)
            editor.putInt(KEY_REPEAT_MODE, repeatMode.ordinal)
            editor.putBoolean(KEY_SHUFFLE_ENABLED, shuffleEnabled)
            editor.putLong(KEY_STATE_SAVED_TIME, System.currentTimeMillis())
            
            // Guardar playlist
            if (playlist.isNotEmpty()) {
                editor.putString(KEY_PLAYLIST, gson.toJson(playlist))
            } else {
                editor.remove(KEY_PLAYLIST)
            }
            
            // Guardar cola original (para shuffle)
            if (originalQueue != null && originalQueue.isNotEmpty()) {
                editor.putString(KEY_ORIGINAL_QUEUE, gson.toJson(originalQueue))
            } else {
                editor.remove(KEY_ORIGINAL_QUEUE)
            }
            
            // Guardar fuente de reproducción
            if (playbackSource != null) {
                editor.putString(KEY_PLAYBACK_SOURCE, gson.toJson(playbackSource))
            } else {
                editor.remove(KEY_PLAYBACK_SOURCE)
            }
            
            editor.apply()
            
            android.util.Log.d("PlaybackStateManager", "Estado de reproducción guardado: ${currentSong?.title}")
        } catch (e: Exception) {
            android.util.Log.e("PlaybackStateManager", "Error al guardar estado de reproducción", e)
        }
    }
    
    /**
     * Restaura el estado de reproducción guardado
     * @return PlaybackState si hay un estado válido guardado, null en caso contrario
     */
    fun restorePlaybackState(): PlaybackState? {
        try {
            val savedTime = prefs.getLong(KEY_STATE_SAVED_TIME, 0L)
            val currentTime = System.currentTimeMillis()
            
            // Verificar si el estado no es demasiado antiguo
            if (savedTime == 0L || (currentTime - savedTime) > MAX_STATE_AGE_MS) {
                android.util.Log.d("PlaybackStateManager", "Estado de reproducción demasiado antiguo o inexistente")
                clearPlaybackState()
                return null
            }
            
            // Verificar que hay información básica guardada
            if (!prefs.contains(KEY_CURRENT_SONG) || !prefs.contains(KEY_PLAYLIST)) {
                android.util.Log.d("PlaybackStateManager", "No hay información suficiente para restaurar el estado")
                return null
            }
            
            // Restaurar canción actual
            val currentSongJson = prefs.getString(KEY_CURRENT_SONG, null)
            val currentSong = if (currentSongJson != null) {
                gson.fromJson(currentSongJson, Song::class.java)
            } else null
            
            // Restaurar playlist
            val playlistJson = prefs.getString(KEY_PLAYLIST, null)
            val playlist = if (playlistJson != null) {
                val type = object : TypeToken<List<Song>>() {}.type
                gson.fromJson<List<Song>>(playlistJson, type) ?: emptyList()
            } else emptyList()
            
            // Restaurar cola original
            val originalQueueJson = prefs.getString(KEY_ORIGINAL_QUEUE, null)
            val originalQueue = if (originalQueueJson != null) {
                val type = object : TypeToken<List<Song>>() {}.type
                gson.fromJson<List<Song>>(originalQueueJson, type)
            } else null
            
            // Restaurar fuente de reproducción
            val playbackSourceJson = prefs.getString(KEY_PLAYBACK_SOURCE, null)
            val playbackSource = if (playbackSourceJson != null) {
                gson.fromJson(playbackSourceJson, com.arantec.castafiore.data.model.PlaybackSource::class.java)
            } else null
            
            // Restaurar información básica
            val currentPosition = prefs.getLong(KEY_CURRENT_POSITION, 0L)
            val currentIndex = prefs.getInt(KEY_CURRENT_INDEX, 0)
            val isPlaying = prefs.getBoolean(KEY_IS_PLAYING, false)
            val repeatModeOrdinal = prefs.getInt(KEY_REPEAT_MODE, MusicService.RepeatMode.OFF.ordinal)
            val repeatMode = MusicService.RepeatMode.entries.getOrElse(repeatModeOrdinal) { MusicService.RepeatMode.OFF }
            val shuffleEnabled = prefs.getBoolean(KEY_SHUFFLE_ENABLED, false)
            
            val state = PlaybackState(
                currentSong = currentSong,
                currentPosition = currentPosition,
                currentIndex = currentIndex,
                playlist = playlist,
                originalQueue = originalQueue,
                isPlaying = isPlaying,
                repeatMode = repeatMode,
                shuffleEnabled = shuffleEnabled,
                playbackSource = playbackSource,
                savedTime = savedTime
            )
            
            android.util.Log.d("PlaybackStateManager", "Estado de reproducción restaurado: ${currentSong?.title}")
            return state
            
        } catch (e: Exception) {
            android.util.Log.e("PlaybackStateManager", "Error al restaurar estado de reproducción", e)
            clearPlaybackState()
            return null
        }
    }
    
    /**
     * Limpia el estado de reproducción guardado
     */
    fun clearPlaybackState() {
        try {
            prefs.edit().clear().apply()
            android.util.Log.d("PlaybackStateManager", "Estado de reproducción limpiado")
        } catch (e: Exception) {
            android.util.Log.e("PlaybackStateManager", "Error al limpiar estado de reproducción", e)
        }
    }
    
    /**
     * Verifica si hay un estado de reproducción válido guardado
     */
    fun hasValidPlaybackState(): Boolean {
        val savedTime = prefs.getLong(KEY_STATE_SAVED_TIME, 0L)
        val currentTime = System.currentTimeMillis()
        
        return savedTime > 0L && 
               (currentTime - savedTime) <= MAX_STATE_AGE_MS &&
               prefs.contains(KEY_CURRENT_SONG) &&
               prefs.contains(KEY_PLAYLIST)
    }
}