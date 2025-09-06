package com.arantec.castafiore.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.media.AudioManager
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
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory
import com.google.android.exoplayer2.audio.AudioAttributes
import com.arantec.castafiore.data.lyrics.LyricsProvider
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.net.Uri
import androidx.core.app.TaskStackBuilder
import com.arantec.castafiore.ui.activities.MainActivity

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
    // Track shuffle state centrally in the service
    private var isShuffleEnabled: Boolean = false
    // Track base time offset (ms) when restarting transcoded stream at a specific point
    private var baseOffsetMs: Long = 0L

    // Scrobble tracking
    private var scrobbleSentForCurrent = false
    private var trackStartTimeMillis: Long = 0L

    // Receiver to pause when audio becomes noisy (e.g., headphones unplugged)
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                pause()
            }
        }
    }

    // Enum para los modos de repetición
    enum class RepeatMode {
        OFF, ALL, ONE
    }

    // Listeners para cambios de estado
    private val playbackStateListeners = mutableListOf<(Boolean) -> Unit>()
    private val songChangeListeners = mutableListOf<(Song?) -> Unit>()

    // Track favorite state for the current song to reflect it in the notification
    private var isCurrentSongFavorite: Boolean = false

    companion object {
        private const val TAG = "MusicService"
        private const val MEDIA_SESSION_TAG = "CastafioreMediaSession"
        private const val NOTIFICATION_CHANNEL_ID = "playback_channel"
        private const val NOTIFICATION_ID = 2001
        // Explicit actions for notification controls (immutable PendingIntents)
        private const val ACTION_TOGGLE = "com.arantec.castafiore.action.TOGGLE_PLAY_PAUSE"
        private const val ACTION_NEXT = "com.arantec.castafiore.action.NEXT"
        private const val ACTION_PREV = "com.arantec.castafiore.action.PREV"
        private const val ACTION_STOP = "com.arantec.castafiore.action.STOP"
        private const val ACTION_TOGGLE_FAVORITE = "com.arantec.castafiore.action.TOGGLE_FAVORITE"
    }

    inner class MusicBinder : Binder() {
        fun getService(): MusicService = this@MusicService
    }

    override fun onCreate() {
        super.onCreate()

        musicRepository = MusicRepository.getInstance(this)

        // Initialize shared player cache and wire ExoPlayer to use it
        PlayerCache.init(this)

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

        // Register receiver for becoming noisy (headphones unplug)
        registerReceiver(noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))

        // Disable lazy preparation so upcoming items can be prepared
        val mediaSourceFactory = DefaultMediaSourceFactory(PlayerCache.cacheDataSourceFactory)
        exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
        // Set audio attributes to ensure a stable music session for equalizer
        exoPlayer?.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(com.google.android.exoplayer2.C.USAGE_MEDIA)
                .setContentType(com.google.android.exoplayer2.C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            /* handleAudioFocus= */ true
        )
        exoPlayer?.addListener(object : com.google.android.exoplayer2.Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == com.google.android.exoplayer2.Player.STATE_ENDED) {
                    // Enviar scrobble si aún no se envió (canción completada)
                    sendScrobbleIfNeeded(force = true)
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
                // Avanzar índice solo cuando la transición fue automática dentro del reproductor
                if (reason == com.google.android.exoplayer2.Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && repeatMode != RepeatMode.ONE) {
                    if (currentIndex < playlist.size - 1) {
                        currentIndex++
                    }
                }
                // Actualizar canción actual y metadatos
                currentSong = playlist.getOrNull(currentIndex)
                // Reiniciar offset base en transiciones
                baseOffsetMs = 0L
                // Registrar en recientes (offline history) solo en transición automática
                if (reason == com.google.android.exoplayer2.Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                    currentSong?.let {
                        try {
                            com.arantec.castafiore.data.cache.RecentPlaysStore.getInstance(this@MusicService).add(it)
                        } catch (_: Exception) { }
                    }
                }
                // Reiniciar tracking de scrobble para la nueva canción
                scrobbleSentForCurrent = false
                trackStartTimeMillis = System.currentTimeMillis()
                currentSong?.let { reportNowPlayingSafe(it) }
                updateMediaMetadata()
                notifySongChanged(currentSong)
                showOrUpdateNotification()
                // Lanzar prefetch de similares de la canción actual (solo si repeat OFF)
                if (repeatMode == RepeatMode.OFF && musicRepository.continueWithSimilarEnabled) {
                    prefetchSimilarForCurrentSong()
                }
                // Prefetch de letras para la canción actual y la siguiente
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val now = currentSong
                        LyricsProvider.getInstance().prefetch(this@MusicService, now)
                        val next = playlist.getOrNull(currentIndex + 1)
                        LyricsProvider.getInstance().prefetch(this@MusicService, next)
                    } catch (_: Exception) { }
                }
                // Enqueue próximo track para preparación anticipada
                enqueueNextMediaItem()
                // Refresh favorite state for the new current song
                refreshFavoriteStateForCurrentSong()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // Mantener sincronizado el estado interno ante cambios por audio focus (llamadas, notificaciones) u otros
                this@MusicService.isPlaying = isPlaying
                updatePlaybackState()
                notifyPlaybackStateChanged(isPlaying)
                showOrUpdateNotification()
                if (isPlaying) startProgressUpdates() else stopProgressUpdates()
            }
        })

        updatePlaybackState()
        updateMediaMetadata()

        // Restore shuffle preference
        val prefs = getSharedPreferences("player_prefs", Context.MODE_PRIVATE)
        isShuffleEnabled = prefs.getBoolean("shuffle_mode", false)
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onDestroy() {
        try { unregisterReceiver(noisyReceiver) } catch (_: Exception) {}
        exoPlayer?.release()
        exoPlayer = null
        mediaSession.release()
        playbackStateListeners.clear()
        songChangeListeners.clear()
        stopProgressUpdates()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Handle our explicit notification actions first (ensures stability on Android 12+)
        when (intent?.action) {
            ACTION_TOGGLE -> {
                togglePlayPause()
                return START_NOT_STICKY
            }
            ACTION_NEXT -> {
                next()
                return START_NOT_STICKY
            }
            ACTION_PREV -> {
                previous()
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                stop()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_FAVORITE -> {
                toggleFavoriteFromNotification()
                return START_NOT_STICKY
            }
        }
        // Fallback: handle media button intents (hardware/headset controls)
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
        val dm = com.arantec.castafiore.data.download.SongDownloadManager.getInstance(this)
        // Prefer MediaStore content URI if downloaded
        val downloadedUri: Uri? = try { dm.getDownloadedContentUri(song.id) } catch (_: Exception) { null }
        val localPath = try { dm.createDownloadPath(song) } catch (_: Exception) { null }
        val localFile = if (!localPath.isNullOrEmpty()) java.io.File(localPath) else null

        val mediaItemBuilder = MediaItem.Builder()
        if (downloadedUri != null) {
            mediaItemBuilder.setUri(downloadedUri)
        } else if (song.path?.startsWith("content:") == true) {
            mediaItemBuilder.setUri(Uri.parse(song.path))
        } else if (localFile != null && localFile.exists()) {
            val uri = android.net.Uri.fromFile(localFile)
            mediaItemBuilder.setUri(uri)
        } else {
            val serverUrl = musicRepository.serverUrl ?: return
            val (username, token, salt) = musicRepository.getAuthParams()
            // Apply quality preference: high quality = original; basic = 128 kbps mp3
            val highQuality = musicRepository.highQualityEnabled
            val maxBitRate = if (highQuality) null else 128
            val format = if (highQuality) null else "mp3"
            val streamUrl = song.getStreamUrl(serverUrl, username, token, salt, maxBitRate, format)
            val qualityTag = if (highQuality) "orig" else "128"
            val cacheKey = "song_${song.id}_$qualityTag"
            mediaItemBuilder
                .setUri(streamUrl)
                .setCustomCacheKey(cacheKey)
        }
        val mediaItem = mediaItemBuilder.build()
        // Reset base offset on new song
        baseOffsetMs = 0L
        exoPlayer?.setMediaItem(mediaItem)
        // Also enqueue the next item so the player can prepare it in advance
        enqueueNextMediaItem()
        exoPlayer?.prepare()
        exoPlayer?.play()
        // Reiniciar tracking de scrobble
        scrobbleSentForCurrent = false
        trackStartTimeMillis = System.currentTimeMillis()
        reportNowPlayingSafe(song)
        // Prefetch letras para la actual y la siguiente
        CoroutineScope(Dispatchers.IO).launch {
            try {
                LyricsProvider.getInstance().prefetch(this@MusicService, song)
                val next = playlist.getOrNull(currentIndex + 1)
                LyricsProvider.getInstance().prefetch(this@MusicService, next)
            } catch (_: Exception) { }
        }
        // Registrar también cuando la reproducción inicia manualmente
        try {
            com.arantec.castafiore.data.cache.RecentPlaysStore.getInstance(this).add(song)
        } catch (_: Exception) { }
        // Refresh favorite state for current song
        refreshFavoriteStateForCurrentSong()
        updateMediaMetadata()
        showOrUpdateNotification()
        startProgressUpdates()
        // Prefetch el siguiente tema para cambio rápido
        // prefetchNextTrack()
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
        // Leaving FGS is handled inside showOrUpdateNotification; ensure flag resets
    }

    fun stop() {
        isPlaying = false
        currentPosition = 0L
        baseOffsetMs = 0L
        updatePlaybackState()
        notifyPlaybackStateChanged(false)
        exoPlayer?.stop()
        try { stopForeground(true) } catch (_: Exception) {}
        isInForegroundNotification = false
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID)
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
            
            // Al saltar a la siguiente, marcamos reproducción activa antes de preparar
            isPlaying = true
            // Reset offset for new song
            baseOffsetMs = 0L
            // Preparar y reproducir la nueva canción
            startNewSong()
            updatePlaybackState()
            notifyPlaybackStateChanged(true)
            showOrUpdateNotification()
        }
    }

    fun previous() {
        if (currentIndex > 0) {
            currentIndex--
            currentSong = playlist[currentIndex]
            notifySongChanged(currentSong)
            
            // Al retroceder, marcamos reproducción activa antes de preparar
            isPlaying = true
            // Reset offset for new song
            baseOffsetMs = 0L
            // Preparar y reproducir la nueva canción
            startNewSong()
            updatePlaybackState()
            notifyPlaybackStateChanged(true)
            showOrUpdateNotification()
        }
    }

    fun seekTo(position: Long) {
        val song = currentSong
        val player = exoPlayer
        if (song == null || player == null) return
        currentPosition = position
        val dm = com.arantec.castafiore.data.download.SongDownloadManager.getInstance(this)
        val downloadedUri: Uri? = try { dm.getDownloadedContentUri(song.id) } catch (_: Exception) { null }
        val localPath = try { dm.createDownloadPath(song) } catch (_: Exception) { null }
        val localFile = if (!localPath.isNullOrEmpty()) java.io.File(localPath) else null
        val highQuality = musicRepository.highQualityEnabled

        if (downloadedUri != null || song.path?.startsWith("content:") == true) {
            // Local (MediaStore) file is fully seekable
            player.seekTo(position)
            updatePlaybackState()
            return
        }
        if (localFile != null && localFile.exists()) {
            player.seekTo(position)
            updatePlaybackState()
            return
        }
        if (highQuality) {
            // Original stream: server should support range; ExoPlayer can seek
            player.seekTo(position)
            updatePlaybackState()
            return
        }
        // Basic quality (transcoded): restart stream at given offset using Subsonic/Navidrome timeOffset
        val serverUrl = musicRepository.serverUrl ?: return
        val (username, token, salt) = musicRepository.getAuthParams()
        val offsetSec = (position / 1000L).toInt().coerceAtLeast(0)
        val streamUrl = song.getStreamUrl(serverUrl, username, token, salt, maxBitRate = 128, format = "mp3", timeOffsetSeconds = offsetSec)
        val cacheKey = "song_${song.id}_128_offset_${offsetSec}"
        val newItem = MediaItem.Builder()
            .setUri(streamUrl)
            .setCustomCacheKey(cacheKey)
            .build()
        val wasPlaying = isPlaying && player.playWhenReady
        // Track base offset so UI/state reflect absolute position within the song
        baseOffsetMs = (offsetSec * 1000).toLong()
        player.setMediaItem(newItem, /*startPositionMs*/ 0)
        enqueueNextMediaItem()
        player.prepare()
        player.playWhenReady = wasPlaying
        updatePlaybackState()
    }

    fun playQueue(songs: List<Song>, startIndex: Int = 0, source: PlaybackSource? = null) {
        playlist.clear()
        playlist.addAll(songs)
        currentIndex = startIndex
        currentSong = if (songs.isNotEmpty()) songs[startIndex] else null
        // Set playback source (default to SONGS if not provided)
        playbackSource = source ?: PlaybackSource(SourceType.SONGS, null, "Canciones")

        // If shuffle is enabled, reorder queue so current is first and the rest are shuffled
        if (isShuffleEnabled && songs.size > 1 && startIndex in songs.indices) {
            originalQueue = songs.toMutableList()
            val current = songs[startIndex]
            val others = songs.filterIndexed { index, _ -> index != startIndex }.shuffled()
            playlist.clear()
            playlist.addAll(listOf(current) + others)
            currentIndex = 0
            currentSong = current
            notifyQueueChanged(playlist.toList())
        } else {
            // No shuffle: keep original ordering and index
            originalQueue = null
            notifyQueueChanged(playlist.toList())
        }
        notifySongChanged(currentSong)

        // Ensure ExoPlayer starts from the selected/current item and queues the next
        baseOffsetMs = 0L
        startNewSong()
        isPlaying = true
        updatePlaybackState()
        notifyPlaybackStateChanged(true)
        showOrUpdateNotification()
    }

    fun playSong(song: Song, source: PlaybackSource? = null) {
        playlist.clear()
        playlist.add(song)
        currentIndex = 0
        currentSong = song
        // Set playback source (default to SONGS if not provided)
        playbackSource = source ?: PlaybackSource(SourceType.SONGS, null, "Canciones")
        originalQueue = null
        notifySongChanged(currentSong)
        notifyQueueChanged(playlist.toList())

        // Usar startNewSong para asegurar que se prepare correctamente
        baseOffsetMs = 0L
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
        // Build a proper back stack: MainActivity -> PlayerActivity
        val stackBuilder = TaskStackBuilder.create(this)
            .addNextIntent(Intent(this, MainActivity::class.java))
            .addNextIntent(Intent(this, PlayerActivity::class.java))
        return stackBuilder.getPendingIntent(
            0,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun pendingService(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, MusicService::class.java).apply { this.action = action }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Ensure a foreground-start when launched from notification actions (Android 12+ compliant)
            PendingIntent.getForegroundService(
                this,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            PendingIntent.getService(
                this,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }

    private fun buildBaseNotification(largeIcon: Bitmap? = null): NotificationCompat.Builder {
        val song = currentSong
        val title = song?.title ?: getString(R.string.app_name)
        val artist = song?.artist ?: ""

        // Decide UI state based on playWhenReady so buffering still shows Pause
        val showingPause = exoPlayer?.playWhenReady == true

        val playPauseAction = NotificationCompat.Action(
            if (showingPause) R.drawable.ic_pause else R.drawable.ic_play,
            if (showingPause) getString(R.string.pause) else getString(R.string.play),
            pendingService(ACTION_TOGGLE, /*requestCode*/ 100)
        )
        val prevAction = NotificationCompat.Action(
            R.drawable.ic_prev,
            getString(R.string.previous),
            pendingService(ACTION_PREV, /*requestCode*/ 101)
        )
        val nextAction = NotificationCompat.Action(
            R.drawable.ic_next,
            getString(R.string.next),
            pendingService(ACTION_NEXT, /*requestCode*/ 102)
        )
        val favoriteIcon = if (isCurrentSongFavorite) R.drawable.ic_favorite_36 else R.drawable.ic_favorite_border_36
        val favoriteTitle = if (isCurrentSongFavorite) getString(R.string.remove_favorite) else getString(R.string.add_to_favorites)
        val favoriteAction = NotificationCompat.Action(
            favoriteIcon,
            favoriteTitle,
            pendingService(ACTION_TOGGLE_FAVORITE, /*requestCode*/ 103)
        )

        val style = MediaStyle()
            .setMediaSession(mediaSession.sessionToken)
            // Keep compact view with prev, play/pause, next; favorite appears in expanded
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
            .addAction(favoriteAction)

        if (largeIcon != null) builder.setLargeIcon(largeIcon)

        return builder
    }

    private var isInForegroundNotification: Boolean = false

    // Cache de carátula para evitar parpadeos al actualizar la notificación
    private var lastArtworkSongId: String? = null
    private var lastArtworkBitmap: Bitmap? = null

    private fun showOrUpdateNotification() {
        // Throttle excessive updates (except when we must enter FGS immediately)
        val now = SystemClock.uptimeMillis()
        val wantsForeground = exoPlayer?.playWhenReady == true
        val enteringFg = wantsForeground && !isInForegroundNotification
        if (!enteringFg && now - lastNotifPostedAt < 200L) {
            if (!pendingNotifUpdate) {
                pendingNotifUpdate = true
                mainHandler.postDelayed({
                    pendingNotifUpdate = false
                    showOrUpdateNotification()
                }, 200L)
            }
            return
        }
        lastNotifPostedAt = now

        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val song = currentSong

        // Usar carátula en caché si existe para evitar desaparecer el icono (parpadeo)
        val initialArt: Bitmap? = lastArtworkBitmap

        // Construir notificación inmediata (con carátula cacheada si la hay) para cumplir límite de FGS
        val immediateNotification = buildBaseNotification(initialArt).build()

        if (wantsForeground) {
            if (!isInForegroundNotification) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        startForeground(
                            NOTIFICATION_ID,
                            immediateNotification,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                        )
                    } else {
                        startForeground(NOTIFICATION_ID, immediateNotification)
                    }
                    isInForegroundNotification = true
                } catch (_: Throwable) {
                    // Fallback: just post the notification; avoid crashing
                    nm.notify(NOTIFICATION_ID, immediateNotification)
                }
            } else {
                // Already in foreground: update inmediata primero
                nm.notify(NOTIFICATION_ID, immediateNotification)
            }
        } else {
            // Not playing or playWhenReady=false: show as non-foreground and stop FGS if needed
            nm.notify(NOTIFICATION_ID, immediateNotification)
            try { stopForeground(false) } catch (_: Exception) {}
            isInForegroundNotification = false
        }

        // Si hay canción, cargar carátula async y actualizar sólo si hay nueva imagen y sigue siendo la misma canción
        if (song != null) {
            CoroutineScope(Dispatchers.IO).launch {
                // Primero intentar carátula local
                val dm = com.arantec.castafiore.data.download.SongDownloadManager.getInstance(this@MusicService)
                val coverPath = try { dm.createCoverPath(song) } catch (_: Exception) { null }
                var bitmap: android.graphics.Bitmap? = null
                if (!coverPath.isNullOrEmpty()) {
                    val file = java.io.File(coverPath)
                    if (file.exists()) {
                        bitmap = android.graphics.BitmapFactory.decodeFile(coverPath)
                    }
                }
                if (bitmap == null && musicRepository.serverUrl != null) {
                    val (u, t, s) = musicRepository.getAuthParams()
                    val coverUrl = song.getCoverArtUrl(musicRepository.serverUrl!!, u, t, s)
                    try {
                        if (coverUrl != null) {
                            val future = com.bumptech.glide.Glide.with(this@MusicService)
                                .asBitmap()
                                .load(coverUrl)
                                .submit(256, 256)
                            bitmap = future.get()
                        }
                    } catch (_: Exception) { bitmap = null }
                }

                withContext(Dispatchers.Main) {
                    // Evitar sobrescribir si la canción actual cambió
                    val current = currentSong
                    if (current == null || current.id != song.id) return@withContext

                    // Coalesce artwork updates too
                    val now2 = SystemClock.uptimeMillis()
                    if (now2 - lastNotifPostedAt < 150L) {
                        lastNotifPostedAt = now2
                    }

                    // Si obtuvimos nueva imagen, actualizar caché y notificación; si no, mantener la previa
                    if (bitmap != null) {
                        lastArtworkBitmap = bitmap
                        lastArtworkSongId = song.id
                    }
                    val artToUse = lastArtworkBitmap
                    val updated = buildBaseNotification(artToUse).build()
                    nm.notify(NOTIFICATION_ID, updated)
                }
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
    fun getCurrentPosition(): Long = baseOffsetMs + (exoPlayer?.currentPosition ?: 0L)
    fun getDuration(): Long = exoPlayer?.duration ?: 0L
    fun getQueue(): List<Song> = playlist.toList()
    fun getCurrentIndex(): Int = currentIndex
    fun getPlaybackSource(): PlaybackSource? = playbackSource
    fun getShuffleEnabled(): Boolean = isShuffleEnabled

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
        val position = getCurrentPosition()
        currentPosition = position
        val buffered = (exoPlayer?.bufferedPosition ?: 0L) + baseOffsetMs
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
        // If shuffle is on, keep the new addition at the end of remaining order; no immediate reshuffle here.
        if (isShuffleEnabled) {
            // Recalcular siguiente ítem para mantener coherencia
            enqueueNextMediaItem()
        }
    }

    fun playNext(song: Song) {
        val insertPosition = currentIndex + 1
        if (insertPosition <= playlist.size) {
            playlist.add(insertPosition, song)
            notifyQueueChanged(playlist.toList())
            enqueueNextMediaItem()
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
        // Asegurar que el próximo ítem en ExoPlayer siga la nueva cola
        enqueueNextMediaItem()
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
        // Re-sincronizar próximo ítem en ExoPlayer
        enqueueNextMediaItem()
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

    fun setShuffleEnabled(enabled: Boolean) {
        if (isShuffleEnabled == enabled) return
        isShuffleEnabled = enabled
        val prefs = getSharedPreferences("player_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("shuffle_mode", isShuffleEnabled).apply()
        if (enabled) {
            shuffleQueue()
        } else {
            unshuffleQueue()
        }
        // Asegurar que el próximo MediaItem concuerde con la nueva configuración
        enqueueNextMediaItem()
    }

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
            enqueueNextMediaItem()
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
            enqueueNextMediaItem()
        }
    }

    // Inicia y detiene actualizaciones periódicas del progreso para la notificación/lockscreen
    private fun startProgressUpdates() {
        progressJob?.cancel()
        progressJob = CoroutineScope(Dispatchers.Main).launch {
            while (isPlaying) {
                updatePlaybackState()
                // Verificar umbral de scrobble (50% o 240s, lo que ocurra primero)
                maybeScrobbleByProgress()
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

    private fun maybeScrobbleByProgress() {
        if (scrobbleSentForCurrent) return
        val durationMs = exoPlayer?.duration ?: 0L
        val positionMs = getCurrentPosition()
        if (durationMs <= 0L) return
        val halfMs = durationMs / 2
        val fourMinMs = 240_000L
        val threshold = minOf(halfMs, fourMinMs)
        if (positionMs >= threshold) {
            sendScrobbleIfNeeded(force = false)
        }
    }

    private fun sendScrobbleIfNeeded(force: Boolean) {
        val song = currentSong ?: return
        if (!force && scrobbleSentForCurrent) return
        scrobbleSentForCurrent = true
        CoroutineScope(Dispatchers.IO).launch {
            try {
                musicRepository.scrobbleSong(song.id, playedAtMillis = trackStartTimeMillis, submission = true)
            } catch (_: Exception) { /* Ignorar errores de red */ }
        }
    }

    private fun reportNowPlayingSafe(song: Song) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                musicRepository.reportNowPlaying(song.id)
            } catch (_: Exception) { /* Ignorar errores */ }
        }
    }

    // Playback source tracking
    data class PlaybackSource(val type: SourceType, val id: String? = null, val name: String? = null)
    enum class SourceType { ALBUM, ARTIST, PLAYLIST, FAVORITES, SONGS, DOWNLOADS, UNKNOWN }

    private var playbackSource: PlaybackSource? = null

    // Notification update throttling state
    private val mainHandler = android.os.Handler(Looper.getMainLooper())
    private var lastNotifPostedAt: Long = 0L
    private var pendingNotifUpdate: Boolean = false

    // Helper: add only the immediate next song as a queued MediaItem
    private fun enqueueNextMediaItem() {
        if (repeatMode == RepeatMode.ONE) return
        val nextSong = playlist.getOrNull(currentIndex + 1) ?: return
        val dm = com.arantec.castafiore.data.download.SongDownloadManager.getInstance(this)
        val downloadedUri: Uri? = try { dm.getDownloadedContentUri(nextSong.id) } catch (_: Exception) { null }
        val localPath = try { dm.createDownloadPath(nextSong) } catch (_: Exception) { null }
        val localFile = if (!localPath.isNullOrEmpty()) java.io.File(localPath) else null

        val builder = MediaItem.Builder()
        if (downloadedUri != null) {
            builder.setUri(downloadedUri)
        } else if (nextSong.path?.startsWith("content:") == true) {
            builder.setUri(Uri.parse(nextSong.path))
        } else if (localFile != null && localFile.exists()) {
            val uri = android.net.Uri.fromFile(localFile)
            builder.setUri(uri)
        } else {
            val serverUrl = musicRepository.serverUrl ?: return
            val (username, token, salt) = musicRepository.getAuthParams()
            val highQuality = musicRepository.highQualityEnabled
            val maxBitRate = if (highQuality) null else 128
            val format = if (highQuality) null else "mp3"
            val streamUrl = nextSong.getStreamUrl(serverUrl, username, token, salt, maxBitRate, format)
            val qualityTag = if (highQuality) "orig" else "128"
            val cacheKey = "song_${nextSong.id}_$qualityTag"
            builder.setUri(streamUrl).setCustomCacheKey(cacheKey)
        }
        val nextItem = builder.build()
        // Clear any items after current to avoid buildup, then add one next
        val player = exoPlayer ?: return
        val currentIdxInPlayer = player.currentMediaItemIndex
        val total = player.mediaItemCount
        if (total - 1 > currentIdxInPlayer) {
            player.removeMediaItems(currentIdxInPlayer + 1, total)
        }
        player.addMediaItem(nextItem)
    }

    private fun refreshFavoriteStateForCurrentSong() {
        val song = currentSong ?: run {
            isCurrentSongFavorite = false
            showOrUpdateNotification()
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = musicRepository.isSongStarred(song.id)
                val favorite = result.getOrElse { false }
                withContext(Dispatchers.Main) {
                    isCurrentSongFavorite = favorite
                    showOrUpdateNotification()
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    isCurrentSongFavorite = false
                    showOrUpdateNotification()
                }
            }
        }
    }

    private fun toggleFavoriteFromNotification() {
        val song = currentSong ?: return
        // Optimistic UI
        isCurrentSongFavorite = !isCurrentSongFavorite
        showOrUpdateNotification()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (isCurrentSongFavorite) {
                    musicRepository.starSong(song.id)
                } else {
                    musicRepository.unstarSong(song.id)
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    // Revert on failure
                    isCurrentSongFavorite = !isCurrentSongFavorite
                    showOrUpdateNotification()
                }
            }
        }
    }
}
