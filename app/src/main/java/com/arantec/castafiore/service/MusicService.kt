package com.arantec.castafiore.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.media.session.MediaButtonReceiver
import androidx.core.content.ContextCompat
import androidx.media.app.NotificationCompat.MediaStyle
import android.support.v4.media.MediaMetadataCompat
import android.app.Service
import android.os.Binder
import android.os.IBinder
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import com.arantec.castafiore.R
import com.arantec.castafiore.data.models.Song
import com.arantec.castafiore.data.repository.MusicRepository
import com.arantec.castafiore.utils.ImageLoader
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.content.pm.ServiceInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import com.arantec.castafiore.ui.activities.PlayerActivity

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
    private var originalQueue: MutableList<Song>? = null
    private var progressJob: Job? = null
    private var prefetchedSimilar: List<Song>? = null

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
        private const val NOTIFICATION_CHANNEL_ID = "playback_channel"
        private const val NOTIFICATION_ID = 2001
    }

    inner class MusicBinder : Binder() {
        fun getService(): MusicService = this@MusicService
    }

    override fun onCreate() {
        super.onCreate()

        musicRepository = MusicRepository.getInstance(this)

        createNotificationChannel()

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
                        RepeatMode.ONE -> { startNewSong() }
                        RepeatMode.ALL -> {
                            if (currentIndex < playlist.size - 1) { next() } else if (playlist.isNotEmpty()) {
                                currentIndex = 0
                                currentSong = playlist[currentIndex]
                                notifySongChanged(currentSong)
                                startNewSong()
                            }
                        }
                        RepeatMode.OFF -> {
                            if (currentIndex < playlist.size - 1) {
                                next()
                            } else {
                                if (musicRepository.continueWithSimilarEnabled) {
                                    if (!prefetchedSimilar.isNullOrEmpty()) {
                                        val toAppend = prefetchedSimilar!!.filter { s -> playlist.none { it.id == s.id } }
                                        if (toAppend.isNotEmpty()) {
                                            playlist.addAll(toAppend)
                                            notifyQueueChanged(playlist.toList())
                                            prefetchedSimilar = null
                                            next()
                                            prefetchSimilarForCurrentSong()
                                            return
                                        }
                                    }
                                    continueWithSimilarSongs()
                                } else {
                                    stopAtEnd()
                                }
                            }
                        }
                    }
                }
                // Actualizar notificación cuando ExoPlayer cambia a READY o BUFFERING
                showOrUpdateNotification()
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // Actualizar el mini player al cambiar de canción
                currentSong = playlist.getOrNull(currentIndex)
                updateMediaMetadata()
                notifySongChanged(currentSong)
                showOrUpdateNotification()
                // Lanzar prefetch de similares de la canción actual (solo si repeat OFF)
                if (repeatMode == RepeatMode.OFF && musicRepository.continueWithSimilarEnabled) {
                    prefetchSimilarForCurrentSong()
                }
            }
        })

        updatePlaybackState()
        updateMediaMetadata()
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
        stopProgressUpdates()
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
        showOrUpdateNotification()
        startProgressUpdates()
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
        updateMediaMetadata()
        showOrUpdateNotification()
        startProgressUpdates()
    }

    fun resume() {
        // Método específico para reanudar sin reiniciar
        if (exoPlayer?.playbackState == com.google.android.exoplayer2.Player.STATE_READY) {
            exoPlayer?.play()
            isPlaying = true
            updatePlaybackState()
            notifyPlaybackStateChanged(true)
            showOrUpdateNotification()
            startProgressUpdates()
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
        showOrUpdateNotification()
        stopProgressUpdates()
    }

    fun stop() {
        isPlaying = false
        currentPosition = 0L
        updatePlaybackState()
        notifyPlaybackStateChanged(false)
        exoPlayer?.stop()
        stopForeground(true)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID)
        stopSelf()
        stopProgressUpdates()
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
                showOrUpdateNotification()
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
                showOrUpdateNotification()
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
        showOrUpdateNotification()
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
        showOrUpdateNotification()
    }

    // --- Notificación y metadata ---
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.app_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Reproducción de música"
                setSound(null, null)
                enableVibration(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun getContentPendingIntent(): PendingIntent? {
        // Abrir directamente PlayerActivity al tocar la notificación
        val intent = Intent(this, PlayerActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildBaseNotification(largeIcon: Bitmap? = null): NotificationCompat.Builder {
        val song = currentSong
        val title = song?.title ?: getString(R.string.app_name)
        val artist = song?.artist ?: ""

        val playPauseAction = NotificationCompat.Action(
            if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
            if (isPlaying) getString(R.string.pause) else getString(R.string.play),
            MediaButtonReceiver.buildMediaButtonPendingIntent(
                this,
                PlaybackStateCompat.ACTION_PLAY_PAUSE
            )
        )
        val prevAction = NotificationCompat.Action(
            R.drawable.ic_arrow_back,
            getString(R.string.previous),
            MediaButtonReceiver.buildMediaButtonPendingIntent(
                this,
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
            )
        )
        val nextAction = NotificationCompat.Action(
            R.drawable.ic_skip_next,
            getString(R.string.next),
            MediaButtonReceiver.buildMediaButtonPendingIntent(
                this,
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT
            )
        )

        val style = MediaStyle()
            .setMediaSession(mediaSession.sessionToken)
            .setShowActionsInCompactView(0, 1, 2)

        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentTitle(title)
            .setContentText(artist)
            .setContentIntent(getContentPendingIntent())
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setOnlyAlertOnce(true)
            .setColor(ContextCompat.getColor(this, R.color.primary))
            .setStyle(style)
            .addAction(prevAction)
            .addAction(playPauseAction)
            .addAction(nextAction)

        if (largeIcon != null) builder.setLargeIcon(largeIcon)

        return builder
    }

    private fun showOrUpdateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val isForeground = isPlaying

        // Cargar carátula en background y actualizar notificación
        val song = currentSong
        if (song != null && musicRepository.serverUrl != null) {
            val (u, t, s) = musicRepository.getAuthParams()
            val coverUrl = song.getCoverArtUrl(musicRepository.serverUrl!!, u, t, s)
            CoroutineScope(Dispatchers.IO).launch {
                val bitmap = try {
                    if (coverUrl != null) {
                        val future = com.bumptech.glide.Glide.with(this@MusicService)
                            .asBitmap()
                            .load(coverUrl)
                            .submit(256, 256)
                        future.get()
                    } else null
                } catch (_: Exception) { null }

                withContext(Dispatchers.Main) {
                    val notification = buildBaseNotification(bitmap).build()
                    if (isForeground) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                        } else {
                            startForeground(NOTIFICATION_ID, notification)
                        }
                    } else {
                        nm.notify(NOTIFICATION_ID, notification)
                        // Mantener notificación visible pero quitar foreground si está pausado
                        stopForeground(false)
                    }
                }
            }
        } else {
            val notification = buildBaseNotification(null).build()
            if (isForeground) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            } else {
                nm.notify(NOTIFICATION_ID, notification)
                stopForeground(false)
            }
        }
    }

    private fun updateMediaMetadata() {
        val song = currentSong ?: return
        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, song.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, song.artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, song.album)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, (song.duration * 1000).toLong())
            .build()
        mediaSession.setMetadata(metadata)
    }

    // Getters para el estado actual
    fun getCurrentSong(): Song? = currentSong
    fun isPlaying(): Boolean = isPlaying
    fun getCurrentPosition(): Long = exoPlayer?.currentPosition ?: 0L
    fun getDuration(): Long = exoPlayer?.duration ?: 0L
    fun getQueue(): List<Song> = playlist.toList()
    fun getCurrentIndex(): Int = currentIndex

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
        val position = exoPlayer?.currentPosition ?: currentPosition
        currentPosition = position
        val buffered = exoPlayer?.bufferedPosition ?: 0L
        val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        val speed = if (isPlaying) 1.0f else 0.0f

        val playbackState = PlaybackStateCompat.Builder()
            .setState(state, position, speed)
            .setBufferedPosition(buffered)
            .setActions(
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_SEEK_TO
            )
            .build()

        mediaSession.setPlaybackState(playbackState)
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
            addToQueue(song)
        }
    }

    fun shuffleQueue() {
        if (playlist.isEmpty()) return
        // Guardar orden original una sola vez
        if (originalQueue == null) {
            originalQueue = playlist.toMutableList()
        }
        val played = playlist.take(currentIndex + 1)
        val remaining = playlist.drop(currentIndex + 1).shuffled()
        playlist.clear()
        playlist.addAll(played + remaining)
        notifyQueueChanged(playlist.toList())
    }

    fun unshuffleQueue() {
        val original = originalQueue ?: return
        // Mantener la canción actual si existe
        val current = currentSong
        playlist.clear()
        playlist.addAll(original)
        originalQueue = null
        currentIndex = current?.let { song -> playlist.indexOfFirst { it.id == song.id } }.takeIf { it != null && it >= 0 } ?: 0
        currentSong = playlist.getOrNull(currentIndex)
        notifyQueueChanged(playlist.toList())
    }

    fun setRepeatMode(mode: RepeatMode) {
        repeatMode = mode
        // Mapear con ExoPlayer: solo repetir UNA pista a nivel ExoPlayer.
        // Para repetir TODA la cola, mantenemos REPEAT_MODE_OFF y dejamos que la lógica
        // de onPlaybackStateChanged avance por la playlist y regrese al inicio.
        val exoMode = when (mode) {
            RepeatMode.OFF -> com.google.android.exoplayer2.Player.REPEAT_MODE_OFF
            RepeatMode.ALL -> com.google.android.exoplayer2.Player.REPEAT_MODE_OFF
            RepeatMode.ONE -> com.google.android.exoplayer2.Player.REPEAT_MODE_ONE
        }
        exoPlayer?.repeatMode = exoMode
    }

    fun getRepeatMode(): RepeatMode = repeatMode

    fun playFromQueue(index: Int) {
        if (index in 0 until playlist.size) {
            currentIndex = index
            currentSong = playlist[currentIndex]
            notifySongChanged(currentSong)
            startNewSong()
            isPlaying = true
            updatePlaybackState()
            notifyPlaybackStateChanged(true)
            showOrUpdateNotification()
        }
    }

    fun removeFromQueue(index: Int) {
        if (index in 0 until playlist.size) {
            playlist.removeAt(index)
            when {
                index < currentIndex -> currentIndex--
                index == currentIndex -> {
                    if (playlist.isNotEmpty()) {
                        if (currentIndex >= playlist.size) currentIndex = playlist.size - 1
                        currentSong = playlist[currentIndex]
                        notifySongChanged(currentSong)
                        startNewSong()
                    } else {
                        currentSong = null
                        notifySongChanged(null)
                        stop()
                    }
                }
            }
            notifyQueueChanged(playlist.toList())
        }
    }

    fun moveInQueue(fromIndex: Int, toIndex: Int) {
        if (fromIndex in 0 until playlist.size && toIndex in 0 until playlist.size) {
            val song = playlist.removeAt(fromIndex)
            playlist.add(toIndex, song)

            // Actualizar currentIndex si es necesario
            currentIndex = when {
                fromIndex == currentIndex -> toIndex
                fromIndex < currentIndex && toIndex >= currentIndex -> currentIndex - 1
                fromIndex > currentIndex && toIndex <= currentIndex -> currentIndex + 1
                else -> currentIndex
            }

            notifyQueueChanged(playlist.toList())
        }
    }

    // Inicia y detiene actualizaciones periódicas del progreso para la notificación/lockscreen
    private fun startProgressUpdates() {
        progressJob?.cancel()
        progressJob = CoroutineScope(Dispatchers.Main).launch {
            while (isPlaying) {
                updatePlaybackState()
                delay(1000L)
            }
        }
    }

    private fun stopProgressUpdates() {
        progressJob?.cancel()
        progressJob = null
    }

    private fun prefetchSimilarForCurrentSong() {
        val base = currentSong ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = musicRepository.getSimilarSongs(base.id, size = 15)
                val list = result.getOrNull().orEmpty()
                    .filter { it.id != base.id }
                    .filter { s -> playlist.none { it.id == s.id } }
                if (list.isNotEmpty()) {
                    prefetchedSimilar = list
                }
            } catch (_: Exception) {
                // Ignorar errores de prefetch
            }
        }
    }

    private fun continueWithSimilarSongs() {
        val baseSong = currentSong
        if (baseSong == null) {
            stopAtEnd()
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val similarResult = musicRepository.getSimilarSongs(baseSong.id, size = 15)
                val similar = similarResult.getOrNull()
                    ?.filter { it.id != baseSong.id }
                    ?.filter { s -> playlist.none { it.id == s.id } }
                    ?: emptyList()

                if (similar.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        playlist.addAll(similar)
                        notifyQueueChanged(playlist.toList())
                        next()
                        // Prefetch para la nueva canción
                        prefetchSimilarForCurrentSong()
                    }
                } else {
                    val randomResult = musicRepository.getRandomSongs(size = 15)
                    val randomSongs = randomResult.getOrNull()?.filter { s -> playlist.none { it.id == s.id } } ?: emptyList()
                    if (randomSongs.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            playlist.addAll(randomSongs)
                            notifyQueueChanged(playlist.toList())
                            next()
                            prefetchSimilarForCurrentSong()
                        }
                    } else {
                        withContext(Dispatchers.Main) { stopAtEnd() }
                    }
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) { stopAtEnd() }
            }
        }
    }

    private fun stopAtEnd() {
        isPlaying = false
        updatePlaybackState()
        notifyPlaybackStateChanged(false)
        showOrUpdateNotification()
    }
}
