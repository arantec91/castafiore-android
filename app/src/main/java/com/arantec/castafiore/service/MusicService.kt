package com.arantec.castafiore.service

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.session.MediaButtonReceiver
import com.arantec.castafiore.data.models.Song
import com.arantec.castafiore.data.repository.MusicRepository
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem

class MusicService : Service() {

    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var musicRepository: MusicRepository
    private val binder = MusicBinder()
    private var exoPlayer: ExoPlayer? = null

    private var currentSong: Song? = null
    private var isPlaying = false
    private var currentPosition = 0L
    private var playlist = mutableListOf<Song>()
    private var currentIndex = 0
    private var repeatMode = RepeatMode.OFF

    // Enum para los modos de repetición
    enum class RepeatMode {
        OFF, ALL, ONE
    }

    // Listeners para cambios de estado
    private val playbackStateListeners = mutableListOf<(Boolean) -> Unit>()
    private val songChangeListeners = mutableListOf<(Song?) -> Unit>()

    companion object {
        private const val TAG = "MusicService"
        private const val MEDIA_SESSION_TAG = "CastafioreMediaSession"
    }

    inner class MusicBinder : Binder() {
        fun getService(): MusicService = this@MusicService
    }

    override fun onCreate() {
        super.onCreate()

        musicRepository = MusicRepository.getInstance(this)

        // Inicializar MediaSession
        mediaSession = MediaSessionCompat(this, MEDIA_SESSION_TAG).apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setCallback(mediaSessionCallback)
            isActive = true
        }

        exoPlayer = ExoPlayer.Builder(this).build()
        exoPlayer?.addListener(object : com.google.android.exoplayer2.Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == com.google.android.exoplayer2.Player.STATE_ENDED) {
                    when (repeatMode) {
                        RepeatMode.ONE -> {
                            // Repetir la canción actual
                            startNewSong()
                        }
                        RepeatMode.ALL -> {
                            // Si hay más canciones, avanzar; si no, volver al inicio
                            if (currentIndex < playlist.size - 1) {
                                next()
                            } else if (playlist.isNotEmpty()) {
                                // Volver al inicio de la lista
                                currentIndex = 0
                                currentSong = playlist[currentIndex]
                                notifySongChanged(currentSong)
                                startNewSong()
                            }
                        }
                        RepeatMode.OFF -> {
                            // Comportamiento original: avanzar o parar
                            if (currentIndex < playlist.size - 1) {
                                next()
                            } else {
                                // Fin de la cola, detener reproducción
                                isPlaying = false
                                updatePlaybackState()
                                notifyPlaybackStateChanged(false)
                            }
                        }
                    }
                }
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // Actualizar el mini player al cambiar de canción
                currentSong = playlist.getOrNull(currentIndex)
                notifySongChanged(currentSong)
            }
        })

        updatePlaybackState()
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onDestroy() {
        exoPlayer?.release()
        exoPlayer = null
        mediaSession.release()
        playbackStateListeners.clear()
        songChangeListeners.clear()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        MediaButtonReceiver.handleIntent(mediaSession, intent)
        return START_NOT_STICKY
    }

    private val mediaSessionCallback = object : MediaSessionCompat.Callback() {
        override fun onPlay() {
            play()
        }

        override fun onPause() {
            pause()
        }

        override fun onStop() {
            stop()
        }

        override fun onSkipToNext() {
            next()
        }

        override fun onSkipToPrevious() {
            previous()
        }

        override fun onSeekTo(pos: Long) {
            seekTo(pos)
        }
    }

    // Métodos públicos para controlar la reproducción
    fun play() {
        // Solo reanudar si ya hay una canción preparada
        if (exoPlayer?.playbackState == com.google.android.exoplayer2.Player.STATE_READY) {
            // El reproductor ya está preparado, solo reanudar
            exoPlayer?.play()
        } else {
            // Preparar y reproducir nueva canción
            startNewSong()
        }

        isPlaying = true
        updatePlaybackState()
        notifyPlaybackStateChanged(true)
    }

    private fun startNewSong() {
        val song = currentSong ?: return
        val serverUrl = musicRepository.serverUrl ?: return
        val (username, token, salt) = musicRepository.getAuthParams()
        val streamUrl = song.getStreamUrl(serverUrl, username, token, salt)
        val mediaItem = MediaItem.fromUri(streamUrl)
        exoPlayer?.setMediaItem(mediaItem)
        exoPlayer?.prepare()
        exoPlayer?.play()
    }

    fun resume() {
        // Método específico para reanudar sin reiniciar
        if (exoPlayer?.playbackState == com.google.android.exoplayer2.Player.STATE_READY) {
            exoPlayer?.play()
            isPlaying = true
            updatePlaybackState()
            notifyPlaybackStateChanged(true)
        } else {
            // Si no está listo, llamar a play normal
            play()
        }
    }

    fun pause() {
        isPlaying = false
        updatePlaybackState()
        notifyPlaybackStateChanged(false)
        exoPlayer?.pause()
    }

    fun stop() {
        isPlaying = false
        currentPosition = 0L
        updatePlaybackState()
        notifyPlaybackStateChanged(false)
        exoPlayer?.stop()
    }

    fun togglePlayPause() {
        if (isPlaying) {
            pause()
        } else {
            play()
        }
    }

    fun next() {
        if (currentIndex < playlist.size - 1) {
            currentIndex++
            currentSong = playlist[currentIndex]
            notifySongChanged(currentSong)
            
            // Usar startNewSong para preparar correctamente la nueva canción
            startNewSong()
            if (isPlaying) {
                updatePlaybackState()
                notifyPlaybackStateChanged(true)
            }
        }
    }

    fun previous() {
        if (currentIndex > 0) {
            currentIndex--
            currentSong = playlist[currentIndex]
            notifySongChanged(currentSong)
            
            // Usar startNewSong para preparar correctamente la nueva canción
            startNewSong()
            if (isPlaying) {
                updatePlaybackState()
                notifyPlaybackStateChanged(true)
            }
        }
    }

    fun seekTo(position: Long) {
        currentPosition = position
        updatePlaybackState()
        exoPlayer?.seekTo(position)
    }

    fun playQueue(songs: List<Song>, startIndex: Int = 0) {
        playlist.clear()
        playlist.addAll(songs)
        currentIndex = startIndex
        currentSong = if (songs.isNotEmpty()) songs[startIndex] else null
        notifySongChanged(currentSong)
        notifyQueueChanged(playlist.toList())

        // Usar startNewSong para asegurar que se prepare correctamente
        startNewSong()
        isPlaying = true
        updatePlaybackState()
        notifyPlaybackStateChanged(true)
    }

    fun playSong(song: Song) {
        playlist.clear()
        playlist.add(song)
        currentIndex = 0
        currentSong = song
        notifySongChanged(currentSong)
        notifyQueueChanged(playlist.toList())

        // Usar startNewSong para asegurar que se prepare correctamente
        startNewSong()
        isPlaying = true
        updatePlaybackState()
        notifyPlaybackStateChanged(true)
    }

    fun addToQueue(song: Song) {
        playlist.add(song)
        notifyQueueChanged(playlist.toList())
    }

    fun playNext(song: Song) {
        val insertPosition = currentIndex + 1
        if (insertPosition <= playlist.size) {
            playlist.add(insertPosition, song)
            notifyQueueChanged(playlist.toList())
        } else {
            // Si no hay posición siguiente válida, agregar al final
            addToQueue(song)
        }
    }

    fun removeFromQueue(index: Int) {
        if (index in 0 until playlist.size) {
            playlist.removeAt(index)
            if (index < currentIndex) {
                currentIndex--
            } else if (index == currentIndex && currentIndex >= playlist.size) {
                currentIndex = playlist.size - 1
                currentSong = if (playlist.isNotEmpty()) playlist[currentIndex] else null
                notifySongChanged(currentSong)
            }
            notifyQueueChanged(playlist.toList())
        }
    }

    // Nuevos métodos para la cola de reproducción
    fun getQueue(): List<Song> = playlist.toList()

    fun getCurrentIndex(): Int = currentIndex

    fun playFromQueue(index: Int) {
        if (index in 0 until playlist.size) {
            currentIndex = index
            currentSong = playlist[currentIndex]
            notifySongChanged(currentSong)
            startNewSong()
            isPlaying = true
            updatePlaybackState()
            notifyPlaybackStateChanged(true)
        }
    }

    fun clearQueue() {
        val hadSongs = playlist.isNotEmpty()
        playlist.clear()
        currentIndex = 0
        currentSong = null

        if (hadSongs) {
            stop()
            notifySongChanged(null)
            notifyQueueChanged(emptyList())
        }
    }

    fun moveInQueue(fromIndex: Int, toIndex: Int) {
        if (fromIndex in 0 until playlist.size && toIndex in 0 until playlist.size) {
            val song = playlist.removeAt(fromIndex)
            playlist.add(toIndex, song)

            // Actualizar currentIndex si es necesario
            when {
                fromIndex == currentIndex -> currentIndex = toIndex
                fromIndex < currentIndex && toIndex >= currentIndex -> currentIndex--
                fromIndex > currentIndex && toIndex <= currentIndex -> currentIndex++
            }

            notifyQueueChanged(playlist.toList())
        }
    }

    fun shuffleQueue() {
        if (playlist.isEmpty()) return

        // Obtener las canciones restantes (después de la actual)
        val remainingSongs = playlist.drop(currentIndex + 1).shuffled()

        // Reconstruir la playlist: canciones ya reproducidas + actual + restantes shuffled
        val playedSongs = playlist.take(currentIndex + 1)
        playlist.clear()
        playlist.addAll(playedSongs)
        playlist.addAll(remainingSongs)

        notifyQueueChanged(playlist.toList())
    }

    fun unshuffleQueue() {
        // Esta función requeriría mantener el orden original
        // Por simplicidad, no implementamos deshacer shuffle
        // En una app real guardarías el orden original
    }

    fun setRepeatMode(mode: RepeatMode) {
        repeatMode = mode
    }

    fun getRepeatMode(): RepeatMode = repeatMode

    // Getters para el estado actual
    fun getCurrentSong(): Song? = currentSong
    fun isPlaying(): Boolean = isPlaying
    fun getCurrentPosition(): Long = exoPlayer?.currentPosition ?: 0L
    fun getDuration(): Long = exoPlayer?.duration ?: 0L

    // Métodos públicos para registrar listeners (compatibilidad con ArtistDetailFragment)
    fun setOnSongChangeListener(listener: ((Song?) -> Unit)?) {
        songChangeListeners.clear()
        listener?.let { songChangeListeners.add(it) }
    }

    fun setOnPlaybackStateChangeListener(listener: ((Boolean) -> Unit)?) {
        playbackStateListeners.clear()
        listener?.let { playbackStateListeners.add(it) }
    }

    // Listeners para cambios de estado
    private val queueChangeListeners = mutableListOf<(List<Song>) -> Unit>()

    fun addPlaybackStateListener(listener: (Boolean) -> Unit) {
        playbackStateListeners.add(listener)
    }

    fun addSongChangeListener(listener: (Song?) -> Unit) {
        songChangeListeners.add(listener)
    }

    fun addQueueChangeListener(listener: (List<Song>) -> Unit) {
        queueChangeListeners.add(listener)
    }

    fun removePlaybackStateListener(listener: (Boolean) -> Unit) {
        playbackStateListeners.remove(listener)
    }

    fun removeSongChangeListener(listener: (Song?) -> Unit) {
        songChangeListeners.remove(listener)
    }

    fun removeQueueChangeListener(listener: (List<Song>) -> Unit) {
        queueChangeListeners.remove(listener)
    }

    private fun notifyPlaybackStateChanged(isPlaying: Boolean) {
        playbackStateListeners.forEach { it(isPlaying) }
    }

    private fun notifySongChanged(song: Song?) {
        songChangeListeners.forEach { it(song) }
    }

    private fun notifyQueueChanged(queue: List<Song>) {
        queueChangeListeners.forEach { it(queue) }
    }

    private fun updatePlaybackState() {
        val state = if (isPlaying) {
            PlaybackStateCompat.STATE_PLAYING
        } else {
            PlaybackStateCompat.STATE_PAUSED
        }

        val playbackState = PlaybackStateCompat.Builder()
            .setState(state, currentPosition, 1.0f)
            .setActions(
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_SEEK_TO
            )
            .build()

        mediaSession.setPlaybackState(playbackState)
    }
}
