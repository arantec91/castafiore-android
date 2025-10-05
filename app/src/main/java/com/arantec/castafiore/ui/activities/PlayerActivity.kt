package com.arantec.castafiore.ui.activities

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.View
import android.view.WindowInsets
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.graphics.toColorInt
import androidx.lifecycle.lifecycleScope
import androidx.palette.graphics.Palette
import com.arantec.castafiore.R
import com.arantec.castafiore.data.models.Song
import com.arantec.castafiore.data.repository.MusicRepository
import com.arantec.castafiore.data.download.SongDownloadManager
import com.arantec.castafiore.databinding.ActivityPlayerBinding
import com.arantec.castafiore.service.MusicService
import com.arantec.castafiore.ui.dialogs.SongOptionsBottomSheet
import com.arantec.castafiore.ui.dialogs.PlaylistSelectorBottomSheet
import com.arantec.castafiore.utils.StatusBarUtils
import com.arantec.castafiore.utils.snack
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.ColorDrawable
import android.graphics.Color
import java.util.Locale
import kotlinx.coroutines.launch
import com.google.android.material.slider.Slider

class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding

    // Remember last dynamic system bars color to reapply on resume
    private var lastSystemBarsColor: Int? = null
    // Track the current player background color to decide foreground contrast
    private var lastAppliedBackgroundColor: Int? = null

    private lateinit var musicRepository: MusicRepository
    private var musicService: MusicService? = null
    private var isBound = false

    private var currentSong: Song? = null
    private var isPlaying = false
    private var isShuffleEnabled = false
    private var repeatMode = RepeatMode.OFF
    private var isFavorite = false

    // Handler para actualizar la progress bar
    private val handler = Handler(Looper.getMainLooper())
    private var updateProgressRunnable: Runnable? = null

    private enum class RepeatMode {
        OFF, ALL, ONE
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MusicService.MusicBinder
            musicService = binder.getService()
            isBound = true
            android.util.Log.d("PlayerActivity", "[DEBUG_LOG] onServiceConnected: shuffle state before sync = $isShuffleEnabled")
            android.util.Log.d("PlayerActivity", "[DEBUG_LOG] onServiceConnected: repeat mode before updateUIFromService = $repeatMode")

            // NO re-aplicar shuffle automáticamente al reconectar - la cola ya está en el estado correcto
            // Solo sincronizar el repeat mode con el servicio
            val serviceRepeatMode = when (repeatMode) {
                RepeatMode.OFF -> MusicService.RepeatMode.OFF
                RepeatMode.ALL -> MusicService.RepeatMode.ALL
                RepeatMode.ONE -> MusicService.RepeatMode.ONE
            }
            musicService?.setRepeatMode(serviceRepeatMode)
            android.util.Log.d("PlayerActivity", "[DEBUG_LOG] onServiceConnected: Syncing repeat mode with service = $repeatMode")

            // Sync shuffle UI state from service
            isShuffleEnabled = musicService?.getShuffleEnabled() ?: false
            android.util.Log.d("PlayerActivity", "[DEBUG_LOG] onServiceConnected: Synced shuffle from service = $isShuffleEnabled")
            updateShuffleButton()

            setupMusicServiceListeners()
            updateUIFromService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            musicService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Para Android 16+, habilitar edge-to-edge con barras transparentes ANTES de setContentView
        // El color de fondo del layout se verá a través de las barras transparentes
        // detectDarkMode=true porque el fondo por defecto (#121212) es oscuro -> iconos claros
        if (Build.VERSION.SDK_INT >= 36) {
            StatusBarUtils.applyEdgeToEdgeWithTransparentBars(this, detectDarkMode = true)
        }
        
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Configurar pantalla completa inmersiva
        setupFullScreenMode()
        
        // Para Android 16+, manejar insets manualmente ya que decorFitsSystemWindows=false
        if (Build.VERSION.SDK_INT >= 36) {
            setupWindowInsetsForAndroid16()
        }

        musicRepository = MusicRepository.getInstance(this)

        // Restaurar el estado de shuffle y repeat desde SharedPreferences
        val prefs = getSharedPreferences("player_prefs", MODE_PRIVATE)
        isShuffleEnabled = prefs.getBoolean("shuffle_mode", false)
        val savedRepeatMode = prefs.getInt("repeat_mode", RepeatMode.OFF.ordinal)
        repeatMode = RepeatMode.entries[savedRepeatMode]
        android.util.Log.d("PlayerActivity", "[DEBUG_LOG] onCreate: Restored shuffle state from SharedPreferences = $isShuffleEnabled")
        android.util.Log.d("PlayerActivity", "[DEBUG_LOG] onCreate: Restored repeat mode from SharedPreferences = $repeatMode")
        updateShuffleButton()
        updateRepeatButton()

        setupClickListeners()
        bindMusicService()
    }

    private fun setupFullScreenMode() {
        // Hacer visibles la status bar y la navigation bar
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.show(
                WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars()
            )
        } else {
            @Suppress("DEPRECATION")
            run {
                // Remover flags inmersivos y dejar solo layout estable
                window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            }
        }

        // Set consistent bars color: use last dynamic if available, else default
        lastSystemBarsColor?.let { StatusBarUtils.setSystemBarsColor(this, it) }
            ?: StatusBarUtils.setSystemBarsColor(this, "#121212".toColorInt())
    }

    /**
     * Configura el manejo manual de window insets para Android 16+.
     * 
     * Estrategia:
     * - El root (CoordinatorLayout) NO tiene padding -> el fondo se extiende detrás de las barras
     * - El ScrollView SÍ tiene padding -> el contenido no se oculta detrás de las barras
     * - El gradientBackground se extiende detrás de las barras transparentes mostrando el color dinámico
     */
    private fun setupWindowInsetsForAndroid16() {
        // NO aplicar padding al root para que el fondo se extienda detrás de las barras
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, windowInsets ->
            // Solo pasar los insets sin consumirlos
            windowInsets
        }
        
        // Aplicar padding al ScrollView para que el contenido no se oculte
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.scrollViewContent) { view, windowInsets ->
            val insets = windowInsets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars()
            )
            
            // Aplicar padding solo en los lados donde hay barras del sistema
            // Esto evita que el contenido se dibuje debajo de las barras
            view.setPadding(
                insets.left,
                insets.top,
                insets.right,
                insets.bottom
            )
            
            androidx.core.view.WindowInsetsCompat.CONSUMED
        }
    }

    private fun setupClickListeners() {
        // Configuración del Slider (Material) programáticamente
        binding.seekBarProgress.apply {
            valueFrom = 0f
            valueTo = 100f
            stepSize = 0f
            value = 0f
            // Removed setLabelBehavior to avoid unresolved constant issues; keep default behavior
            val primary = getColor(R.color.primary)
            val secondary = getColor(R.color.text_secondary)
            thumbTintList = android.content.res.ColorStateList.valueOf(primary)
            trackActiveTintList = android.content.res.ColorStateList.valueOf(primary)
            trackInactiveTintList = android.content.res.ColorStateList.valueOf(secondary)
            haloTintList = android.content.res.ColorStateList.valueOf(primary)
        }

        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnPlayPause.setOnClickListener {
            togglePlayPause()
        }

        binding.btnNext.setOnClickListener {
            playNext()
        }

        binding.btnPrevious.setOnClickListener {
            playPrevious()
        }

        binding.btnShuffle.setOnClickListener {
            toggleShuffle()
        }

        binding.btnRepeat.setOnClickListener {
            toggleRepeat()
        }

        binding.btnFavorite.setOnClickListener {
            toggleFavorite()
        }


        binding.btnQueue.setOnClickListener {
            // Abrir la cola de reproducción
            val intent = Intent(this, QueueActivity::class.java)
            startActivity(intent)
        }

        binding.btnMore.setOnClickListener {
            // Mostrar opciones de la canción actual
            currentSong?.let { song ->
                val bottomSheet = SongOptionsBottomSheet.newInstance(song, isFavorite)
                    .setOnDownloadClickListener { downloadSong ->
                        // Implementar descarga de la canción
                        val downloadManager = SongDownloadManager.getInstance(this@PlayerActivity)
                        if (!downloadManager.isSongDownloaded(downloadSong.id)) {
                            downloadManager.downloadSong(downloadSong)
                            showMessage("Descarga iniciada: ${downloadSong.title}")
                        } else {
                            showMessage("La canción ya está descargada")
                        }
                    }
                    .setOnDeleteDownloadClickListener { downloadSong ->
                        // Implementar eliminación de descarga
                        val downloadManager = SongDownloadManager.getInstance(this@PlayerActivity)
                        if (downloadManager.isSongDownloaded(downloadSong.id)) {
                            val success = downloadManager.deleteSong(downloadSong.id)
                            if (success) {
                                showMessage("Descarga eliminada: ${downloadSong.title}")
                            } else {
                                showMessage("Error al eliminar descarga")
                            }
                        } else {
                            showMessage("La canción no está descargada")
                        }
                    }
                    // Ocultar las opciones que no tienen sentido para la canción que se está reproduciendo
                    .hideAddToQueueOption()
                    .hidePlayNextOption()
                    .setOnAddToPlaylistClickListener { playlistSong ->
                        // Mostrar diálogo selector de playlists
                        val playlistSelector = PlaylistSelectorBottomSheet.newInstance(playlistSong)
                        playlistSelector.show(supportFragmentManager, "PlaylistSelectorBottomSheet")
                    }
                    .setOnViewAlbumClickListener { albumSong ->
                        // Navegar al álbum de la canción
                        navigateToAlbum(albumSong)
                    }
                    .setOnViewArtistClickListener { artistSong ->
                        // Navegar al artista de la canción
                        navigateToArtist(artistSong)
                    }
                    .setOnShareClickListener { shareSong ->
                        shareCurrentSong()
                    }
                    .setOnSongInfoClickListener { infoSong ->
                        showSongInfoDialog(infoSong)
                    }

                bottomSheet.show(supportFragmentManager, "SongOptionsBottomSheet")
            } ?: showMessage("No hay canción reproduciéndose")
        }

        // Nueva navegación desde el texto de origen de reproducción
        binding.tvPlayingFrom.setOnClickListener {
            navigateToPlaybackSource()
        }

        // Slider listeners para el progreso (reemplaza SeekBar)
        binding.seekBarProgress.addOnChangeListener { _: Slider, value: Float, fromUser: Boolean ->
            if (fromUser) {
                val duration = currentSong?.duration ?: 0
                val positionSeconds = ((value / 100f) * duration).toLong()
                binding.tvCurrentTime.text = formatTime(positionSeconds)
            }
        }
        binding.seekBarProgress.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                stopProgressUpdates()
            }
            override fun onStopTrackingTouch(slider: Slider) {
                val duration = currentSong?.duration ?: 0
                val positionSeconds = ((slider.value / 100f) * duration).toLong()
                musicService?.seekTo(positionSeconds * 1000) // ExoPlayer usa milisegundos
                startProgressUpdates()
            }
        })
    }

    private fun bindMusicService() {
        val intent = Intent(this, MusicService::class.java)
        // Ensure the service is started so playback keeps running even after unbinding
        try {
            startService(intent)
        } catch (_: Exception) { }
        bindService(intent, serviceConnection, BIND_AUTO_CREATE)
    }

    private fun unbindMusicService() {
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
            musicService = null
        }
    }

    private fun setupMusicServiceListeners() {
        musicService?.let { service ->
            // Agregar listener para cambios de estado de reproducción
            service.addPlaybackStateListener { playing ->
                isPlaying = playing
                updatePlayPauseButton()
                if (playing) {
                    startProgressUpdates()
                } else {
                    stopProgressUpdates()
                }
            }

            // Agregar listener para cambios de canción
            service.addSongChangeListener { song ->
                currentSong = song
                song?.let { 
                    updateSongInfo(it)
                    loadAlbumArt(it)
                    checkFavoriteStatus(it)
                }
                // Actualizar fuente de reproducción
                updatePlayingFrom()
            }

            // Agregar listener para cambios en la cola (para detectar cambio de origen)
            service.addQueueChangeListener {
                updatePlayingFrom()
            }
        }
    }

    private fun updateUIFromService() {
        android.util.Log.d("PlayerActivity", "[DEBUG_LOG] updateUIFromService: shuffle state (before) = $isShuffleEnabled")
        musicService?.let { service ->
            currentSong = service.getCurrentSong()
            isPlaying = service.isPlaying()
            // Sync shuffle from service
            isShuffleEnabled = service.getShuffleEnabled()

            currentSong?.let { song ->
                updateSongInfo(song)
                loadAlbumArt(song)
                checkFavoriteStatus(song)
                
                // Si hay una canción cargada, significa que se restauró el estado
                if (!isPlaying) {
                    //showMessage("Reproducción restaurada. Toca play para continuar.")
                    //Eliminado para no mostrar mensaje, pero sin romper la logica
                }
            }

            // Mostrar de dónde se está reproduciendo
            updatePlayingFrom()

            updatePlayPauseButton()
            updateShuffleButton()
            updateRepeatButton()
            updateProgressBar()

            // Solo iniciar actualizaciones si está reproduciéndose
            if (isPlaying) {
                startProgressUpdates()
            }
        }
    }

    private fun updateSongInfo(song: Song) {
        binding.tvSongTitle.text = song.title
        binding.tvArtistName.text = song.artist
        // No establecer tvPlayingFrom aquí; se gestiona por updatePlayingFrom()
        binding.tvTotalTime.text = formatTime(song.duration.toLong())

        // Inicializar Slider de progreso a 0%
        binding.seekBarProgress.value = 0f
    }

    private fun loadAlbumArt(song: Song) {
        try {
            // Preferir portada local si está disponible
            val dm = SongDownloadManager.getInstance(this)
            val localCoverPath = try { dm.createCoverPath(song) } catch (_: Exception) { null }
            if (!localCoverPath.isNullOrEmpty()) {
                val file = java.io.File(localCoverPath)
                if (file.exists()) {
                    val bmp = android.graphics.BitmapFactory.decodeFile(localCoverPath)
                    if (bmp != null) {
                        binding.ivAlbumCover.setImageBitmap(bmp)
                        extractColorsAndApplyTheme(bmp)
                        return
                    }
                }
            }

            val (username, token, salt) = musicRepository.getAuthParams()
            val coverUrl = if (song.albumId != null) {
                "${musicRepository.serverUrl}/rest/getCoverArt.view?id=${song.albumId}&u=$username&t=$token&s=$salt&v=1.16.1&c=Castafiore&size=500"
            } else {
                null
            }
            if (coverUrl != null) {
                android.util.Log.d("PlayerActivity", "[DEBUG_LOG] About to load cover art with Glide: $coverUrl")
                val previous = binding.ivAlbumCover.drawable
                var request = Glide.with(this)
                    .load(coverUrl)
                    .error(R.drawable.ic_album_placeholder)
                    .transition(DrawableTransitionOptions.withCrossFade(300))
                    .listener(object : RequestListener<Drawable> {
                        override fun onLoadFailed(
                            e: GlideException?,
                            model: Any?,
                            target: Target<Drawable>,
                            isFirstResource: Boolean
                        ): Boolean {
                            applyDefaultTheme()
                            return false
                        }

                        override fun onResourceReady(
                            resource: Drawable,
                            model: Any,
                            target: Target<Drawable>,
                            dataSource: com.bumptech.glide.load.DataSource,
                            isFirstResource: Boolean
                        ): Boolean {
                            val bitmap = (resource as? BitmapDrawable)?.bitmap
                            if (bitmap != null) {
                                extractColorsAndApplyTheme(bitmap)
                            } else {
                                applyDefaultTheme()
                            }
                            return false
                        }
                    })

                // Use previous image as placeholder to avoid flashing the static placeholder
                request = if (previous != null) {
                    request.placeholder(previous)
                } else {
                    request.placeholder(ColorDrawable(Color.TRANSPARENT))
                }

                request.into(binding.ivAlbumCover)
            } else {
                binding.ivAlbumCover.setImageResource(R.drawable.ic_album_placeholder)
                applyDefaultTheme()
            }
        } catch (_: Exception) {
            binding.ivAlbumCover.setImageResource(R.drawable.ic_album_placeholder)
            applyDefaultTheme()
        }
    }

    private fun extractColorsAndApplyTheme(bitmap: Bitmap) {
        val color = com.arantec.castafiore.utils.ImageUtils.extractBackgroundColor(bitmap)
        if (color != null) {
            currentSong?.let { com.arantec.castafiore.utils.ThemeColorCache.put(it, color) }
            applyDynamicTheme(color)
        } else {
            applyDefaultTheme()
        }
    }

    private fun applyDefaultTheme() {
        val staticColor = "#121212".toColorInt()
        binding.gradientBackground.setBackgroundColor(staticColor)
        // Reset memory and apply fixed bars color
        lastSystemBarsColor = null
        
        // Para Android 16+, actualizar iconos de las barras del sistema
        // detectDarkMode=true porque #121212 es oscuro -> iconos claros
        if (Build.VERSION.SDK_INT >= 36) {
            StatusBarUtils.applyEdgeToEdgeWithTransparentBars(this, detectDarkMode = true)
        }
        
        StatusBarUtils.setSystemBarsColor(this, staticColor)
        // Apply foreground contrast for default background
        applyForegroundContrastForBackground(staticColor)
    }

    private fun applyDynamicTheme(color: Int) {
        // Apply color to the player background
        binding.gradientBackground.setBackgroundColor(color)
        // Remember and apply to both status and navigation bars
        lastSystemBarsColor = color
        
        // Para Android 16+, actualizar iconos de las barras del sistema según la luminancia del color
        if (Build.VERSION.SDK_INT >= 36) {
            // Calcular si el fondo es oscuro o claro
            val isLightBackground = try {
                androidx.core.graphics.ColorUtils.calculateLuminance(color) > 0.5
            } catch (_: Throwable) {
                false
            }
            // detectDarkMode=true para fondos oscuros (iconos claros)
            // detectDarkMode=false para fondos claros (iconos oscuros)
            StatusBarUtils.applyEdgeToEdgeWithTransparentBars(this, detectDarkMode = !isLightBackground)
        }
        
        StatusBarUtils.setSystemBarsColor(this, color)
        // Apply foreground contrast for dynamic background
        applyForegroundContrastForBackground(color)
    }

    // Calculates best on-colors (primary/secondary/inactive) based on WCAG contrast
    private data class OnColors(val primary: Int, val secondary: Int, val inactive: Int)

    private fun pickOnColors(backgroundColor: Int): OnColors {
        // Compare contrast of black/white over the background and pick the best
        val contrastBlack = androidx.core.graphics.ColorUtils.calculateContrast(Color.BLACK, backgroundColor)
        val contrastWhite = androidx.core.graphics.ColorUtils.calculateContrast(Color.WHITE, backgroundColor)
        val primary = if (contrastBlack >= contrastWhite) Color.BLACK else Color.WHITE
        // Secondary ~60% alpha of the primary; inactive ~30%
        val secondary = androidx.core.graphics.ColorUtils.setAlphaComponent(primary, 0x99)
        val inactive = androidx.core.graphics.ColorUtils.setAlphaComponent(primary, 0x4D)
        return OnColors(primary, secondary, inactive)
    }

    // Adjust text and icon colors based on best-contrast color (black/white) instead of luminance threshold
    private fun applyForegroundContrastForBackground(backgroundColor: Int) {
        lastAppliedBackgroundColor = backgroundColor
        val on = pickOnColors(backgroundColor)

        // Texts
        binding.tvSongTitle.setTextColor(on.primary)
        binding.tvArtistName.setTextColor(on.secondary)
        binding.tvPlayingFrom.setTextColor(on.primary)
        binding.tvPlayingFromLabel.setTextColor(on.secondary)
        binding.tvCurrentTime.setTextColor(on.secondary)
        binding.tvTotalTime.setTextColor(on.secondary)

        // Top bar icons
        binding.btnBack.imageTintList = android.content.res.ColorStateList.valueOf(on.primary)
        binding.btnMore.imageTintList = android.content.res.ColorStateList.valueOf(on.primary)

        // Transport controls
        binding.btnPlayPause.imageTintList = android.content.res.ColorStateList.valueOf(on.primary)
        binding.btnPrevious.imageTintList = android.content.res.ColorStateList.valueOf(on.primary)
        binding.btnNext.imageTintList = android.content.res.ColorStateList.valueOf(on.primary)

        // Queue / Lyrics
        runCatching { binding.btnQueue.imageTintList = android.content.res.ColorStateList.valueOf(on.primary) }
        runCatching { binding.btnLyrics.imageTintList = android.content.res.ColorStateList.valueOf(on.primary) }

        // Buttons that depend on state will be refreshed with on.secondary/on.primary
        updateFavoriteButton()
        updateShuffleButton()
        updateRepeatButton()

        // Slider
        binding.seekBarProgress.apply {
            thumbTintList = android.content.res.ColorStateList.valueOf(on.primary)
            trackActiveTintList = android.content.res.ColorStateList.valueOf(on.primary)
            trackInactiveTintList = android.content.res.ColorStateList.valueOf(on.inactive)
            haloTintList = android.content.res.ColorStateList.valueOf(on.primary)
        }
    }

    private fun currentOnColors(): OnColors {
        val bg = lastAppliedBackgroundColor ?: "#121212".toColorInt()
        return pickOnColors(bg)
    }

    private fun updatePlayingFrom() {
        val source = musicService?.getPlaybackSource()
        val text = when (source?.type) {
            MusicService.SourceType.ALBUM -> source.name ?: "Álbum"
            MusicService.SourceType.ARTIST -> "Artista: ${source.name ?: "Desconocido"}"
            MusicService.SourceType.PLAYLIST -> "Playlist: ${source.name ?: "Desconocida"}"
            MusicService.SourceType.FAVORITES -> getString(R.string.favorite_songs_title)
            MusicService.SourceType.SONGS -> getString(R.string.songs)
            MusicService.SourceType.DOWNLOADS -> source.name ?: getString(R.string.bottom_downloads)
            MusicService.SourceType.UNKNOWN, null -> currentSong?.album ?: "Álbum desconocido"
        }
        binding.tvPlayingFrom.text = text
    }

    private fun togglePlayPause() {
        musicService?.let { service ->
            if (service.isPlaying()) {
                service.pause()
            } else {
                service.resume()
            }
            // El estado se actualizará automáticamente a través del listener
        }
    }

    private fun playNext() {
        musicService?.next()
    }

    private fun playPrevious() {
        musicService?.previous()
    }

    private fun toggleShuffle() {
        val service = musicService ?: run {
            // Fallback a estado local si no hay servicio aún
            isShuffleEnabled = !isShuffleEnabled
            updateShuffleButton()
            // Guardar provisionalmente; el servicio sincronizará al conectarse
            val prefs = getSharedPreferences("player_prefs", MODE_PRIVATE)
            prefs.edit { putBoolean("shuffle_mode", isShuffleEnabled) }
            android.util.Log.d("PlayerActivity", "[DEBUG_LOG] toggleShuffle (no service): Saved to SharedPreferences = $isShuffleEnabled")
            return
        }
        val newState = !(service.getShuffleEnabled())
        android.util.Log.d("PlayerActivity", "[DEBUG_LOG] toggleShuffle: Request setShuffleEnabled = $newState")
        service.setShuffleEnabled(newState)
        // Sync local + UI to service state
        isShuffleEnabled = service.getShuffleEnabled()
        updateShuffleButton()
    }

    private fun toggleRepeat() {
        android.util.Log.d("PlayerActivity", "[DEBUG_LOG] toggleRepeat: Before toggle = $repeatMode")
        repeatMode = when (repeatMode) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        android.util.Log.d("PlayerActivity", "[DEBUG_LOG] toggleRepeat: After toggle = $repeatMode")
        updateRepeatButton()

        // Guardar el estado en SharedPreferences
        val prefs = getSharedPreferences("player_prefs", MODE_PRIVATE)
        prefs.edit { putInt("repeat_mode", repeatMode.ordinal) }
        android.util.Log.d("PlayerActivity", "[DEBUG_LOG] toggleRepeat: Saved to SharedPreferences = $repeatMode")

        // Sincronizar el estado con el servicio
        val serviceRepeatMode = when (repeatMode) {
            RepeatMode.OFF -> MusicService.RepeatMode.OFF
            RepeatMode.ALL -> MusicService.RepeatMode.ALL
            RepeatMode.ONE -> MusicService.RepeatMode.ONE
        }
        musicService?.setRepeatMode(serviceRepeatMode)
        android.util.Log.d("PlayerActivity", "[DEBUG_LOG] toggleRepeat: Synced with MusicService = $repeatMode")
    }

    private fun toggleFavorite() {
        currentSong?.let { song ->
            lifecycleScope.launch {
                try {
                    val result = if (isFavorite) {
                        // Si ya es favorito, quitar de favoritos
                        musicRepository.unstarSong(song.id)
                    } else {
                        // Si no es favorito, agregar a favoritos
                        musicRepository.starSong(song.id)
                    }

                    result.fold(
                        onSuccess = {
                            // Actualizar el estado local
                            isFavorite = !isFavorite
                            updateFavoriteButton()
                        },
                        onFailure = { exception ->
                            showMessage("Error al actualizar favoritos: ${exception.message}")
                        }
                    )
                } catch (e: Exception) {
                    showMessage("Error al actualizar favoritos: ${e.message}")
                }
            }
        }
    }

    private fun checkFavoriteStatus(song: Song) {
        lifecycleScope.launch {
            try {
                val result = musicRepository.isSongStarred(song.id)
                result.fold(
                    onSuccess = { isStarred ->
                        isFavorite = isStarred
                        updateFavoriteButton()
                    },
                    onFailure = {
                        // En caso de error, asumir que no es favorito
                        isFavorite = false
                        updateFavoriteButton()
                    }
                )
            } catch (_: Exception) {
                // En caso de excepción, asumir que no es favorito
                isFavorite = false
                updateFavoriteButton()
            }
        }
    }

    private fun shareCurrentSong() {
        currentSong?.let { song ->
            val shareText = "Escuchando: ${song.title} - ${song.artist}"
            val shareIntent = Intent().apply {
                action = Intent.ACTION_SEND
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, shareText)
            }

            val chooser = Intent.createChooser(shareIntent, "Compartir canción")
            startActivity(chooser)
        }
    }

    private fun updatePlayPauseButton() {
        val iconRes = if (isPlaying) R.drawable.ic_pause_main else R.drawable.ic_play_main
        binding.btnPlayPause.setImageResource(iconRes)
    }

    private fun updateShuffleButton() {
        android.util.Log.d("PlayerActivity", "[DEBUG_LOG] updateShuffleButton: Using shuffle state = $isShuffleEnabled")
        val on = currentOnColors()
        val tint = if (isShuffleEnabled) on.primary else on.secondary
        binding.btnShuffle.imageTintList = android.content.res.ColorStateList.valueOf(tint)
    }

    private fun updateRepeatButton() {
        val on = currentOnColors()
        val (iconRes, tint) = when (repeatMode) {
            RepeatMode.OFF -> R.drawable.ic_repeat to on.secondary
            RepeatMode.ALL -> R.drawable.ic_repeat to on.primary
            RepeatMode.ONE -> R.drawable.ic_repeat_one to on.primary
        }

        binding.btnRepeat.setImageResource(iconRes)
        binding.btnRepeat.imageTintList = android.content.res.ColorStateList.valueOf(tint)
        // Nuevo botón de letras
        binding.btnLyrics.setOnClickListener {
            val intent = Intent(this, LyricsActivity::class.java)
            startActivity(intent)
        }

    }

    private fun updateFavoriteButton() {
        val on = currentOnColors()
        val iconRes = if (isFavorite) R.drawable.ic_favorite_36 else R.drawable.ic_favorite_border_36
        val tint = if (isFavorite) on.primary else on.secondary

        binding.btnFavorite.setImageResource(iconRes)
        binding.btnFavorite.imageTintList = android.content.res.ColorStateList.valueOf(tint)
    }

    private fun updateProgressBar() {
        musicService?.let { service ->
            val currentPosition = service.getCurrentPosition()
            val duration = currentSong?.duration?.toLong() ?: 1

            if (duration > 0) {
                val progress = ((currentPosition / 1000) * 100 / duration).toInt()
                binding.seekBarProgress.value = progress.toFloat()
                binding.tvCurrentTime.text = formatTime(currentPosition / 1000)
            }
        }
    }

    private fun startProgressUpdates() {
        stopProgressUpdates()
        updateProgressRunnable = object : Runnable {
            override fun run() {
                if (isPlaying && isBound) {
                    updateProgressBar()
                    handler.postDelayed(this, 1000)
                }
            }
        }
        updateProgressRunnable?.let { handler.post(it) }
    }

    private fun stopProgressUpdates() {
        updateProgressRunnable?.let {
            handler.removeCallbacks(it)
            updateProgressRunnable = null
        }
    }

    private fun formatTime(seconds: Long): String {
        val minutes = seconds / 60
        val secs = seconds % 60
        return String.format(Locale.getDefault(), "%d:%02d", minutes, secs)
    }

    private fun showMessage(message: String) {
        snack(message)
    }

    private fun navigateToAlbum(song: Song) {
        // Verificar que la canción tenga información del álbum
        val albumId = song.albumId
        val albumName = song.album

        if (albumId != null) {
            // Como AlbumDetailFragment usa Navigation Component, necesitamos volver a MainActivity
            // y navegar usando Navigation Component
            val intent = Intent(this, MainActivity::class.java).apply {
                // Agregar flags para limpiar el stack y crear una nueva tarea
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                // Pasar información del álbum para que MainActivity pueda navegar al fragmento correcto
                putExtra("navigate_to", "album_detail")
                putExtra("album_id", albumId)
                putExtra("album_name", albumName)
                // Agregar información adicional del artista
                putExtra("artist_name", song.artist)
                putExtra("artist_id", song.artistId)
            }
            startActivity(intent)
            finish() // Cerrar PlayerActivity
        } else {
            showMessage("Información del álbum no disponible")
        }
    }

    private fun navigateToArtist(song: Song) {
        // Verificar que la canción tenga información del artista
        val artistId = song.artistId
        val artistName = song.artist

        android.util.Log.d("PlayerActivity", "navigateToArtist called - artistId: $artistId, artistName: $artistName")

        if (artistId != null) {
            // Como ArtistDetailFragment usa Navigation Component, necesitamos volver a MainActivity
            val intent = Intent(this, MainActivity::class.java).apply {
                // Agregar flags para limpiar el stack y crear una nueva tarea
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                // Pasar información del artista para que MainActivity pueda navegar al fragmento correcto
                putExtra("navigate_to", "artist_detail")
                putExtra("artist_id", artistId)
                putExtra("artist_name", artistName)
            }
            android.util.Log.d("PlayerActivity", "Starting MainActivity with artist_detail navigation")
            startActivity(intent)
            finish() // Cerrar PlayerActivity
        } else {
            showMessage("Información del artista no disponible")
        }
    }

    // Navegar según la fuente actual (álbum, artista, playlist, favoritos, descargas)
    private fun navigateToPlaybackSource() {
        val source = musicService?.getPlaybackSource()
        when (source?.type) {
            MusicService.SourceType.ALBUM -> currentSong?.let { navigateToAlbum(it) }
            MusicService.SourceType.ARTIST -> currentSong?.let { navigateToArtist(it) }
            MusicService.SourceType.PLAYLIST -> navigateToPlaylist(source)
            MusicService.SourceType.FAVORITES -> navigateToFavorites()
            MusicService.SourceType.DOWNLOADS -> navigateToDownloads()
            else -> { /* No acción para SONGS o UNKNOWN */ }
        }
    }

    private fun navigateToPlaylist(source: MusicService.PlaybackSource) {
        val playlistId = source.id
        val playlistName = source.name
        if (playlistId.isNullOrEmpty() || playlistName.isNullOrEmpty()) {
            showMessage("Información de playlist no disponible")
            return
        }
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("navigate_to", "playlist_detail")
            putExtra("playlist_id", playlistId)
            putExtra("playlist_name", playlistName)
        }
        startActivity(intent)
        finish()
    }

    private fun navigateToFavorites() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("navigate_to", "favorites")
        }
        startActivity(intent)
        finish()
    }

    private fun navigateToDownloads() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("navigate_to", "downloads")
        }
        startActivity(intent)
        finish()
    }

    private fun showSongInfoDialog(song: Song) {
        val dialogBuilder = androidx.appcompat.app.AlertDialog.Builder(this)

        // Crear el layout del diálogo
        val dialogView = layoutInflater.inflate(R.layout.dialog_song_info, null)

        // Referencias a las vistas del diálogo
        val tvTitle = dialogView.findViewById<android.widget.TextView>(R.id.tv_info_title)
        val tvArtist = dialogView.findViewById<android.widget.TextView>(R.id.tv_info_artist)
        val tvAlbum = dialogView.findViewById<android.widget.TextView>(R.id.tv_info_album)
        val tvDuration = dialogView.findViewById<android.widget.TextView>(R.id.tv_info_duration)
        val tvGenre = dialogView.findViewById<android.widget.TextView>(R.id.tv_info_genre)
        val tvYear = dialogView.findViewById<android.widget.TextView>(R.id.tv_info_year)
        val tvBitrate = dialogView.findViewById<android.widget.TextView>(R.id.tv_info_bitrate)
        val tvFormat = dialogView.findViewById<android.widget.TextView>(R.id.tv_info_format)
        val tvFileSize = dialogView.findViewById<android.widget.TextView>(R.id.tv_info_file_size)
        val ivCover = dialogView.findViewById<android.widget.ImageView>(R.id.iv_info_cover)

        // Llenar la información
        tvTitle.text = song.title
        tvArtist.text = song.artist
        tvAlbum.text = song.album
        tvDuration.text = formatTime(song.duration.toLong())
        tvGenre.text = song.genre ?: "Género desconocido"
        tvYear.text = song.year?.toString() ?: "Año desconocido"
        tvBitrate.text = if (song.bitRate != null) "${song.bitRate} kbps" else "Bitrate desconocido"
        tvFormat.text = song.suffix?.uppercase() ?: "Formato desconocido"
        tvFileSize.text = if (song.size != null) {
            val sizeInMB = song.size / (1024.0 * 1024.0)
            String.format(Locale.getDefault(), "%.1f MB", sizeInMB)
        } else {
            "Tamaño desconocido"
        }

        // Cargar la portada del álbum
        try {
            val (username, token, salt) = musicRepository.getAuthParams()
            val coverUrl = if (song.albumId != null) {
                "${musicRepository.serverUrl}/rest/getCoverArt.view?id=${song.albumId}&u=$username&t=$token&s=$salt&v=1.16.1&c=Castafiore&size=300"
            } else {
                null
            }
            if (coverUrl != null) {
                android.util.Log.d("PlayerActivity", "Requesting cover art (info dialog): $coverUrl")
                Glide.with(this)
                    .load(coverUrl)
                    .placeholder(R.drawable.ic_album_placeholder)
                    .error(R.drawable.ic_album_placeholder)
                    .transition(DrawableTransitionOptions.withCrossFade(200))
                    .into(ivCover)
            } else {
                ivCover.setImageResource(R.drawable.ic_album_placeholder)
            }
        } catch (_: Exception) {
            ivCover.setImageResource(R.drawable.ic_album_placeholder)
        }

        dialogBuilder.setView(dialogView)
            .setTitle("Información de la canción")
            .setPositiveButton("Cerrar") { dialog, _ ->
                dialog.dismiss()
            }
            .create()
            .show()
    }

    override fun onStart() {
        super.onStart()
        if (!isBound) {
            bindMusicService()
        }
    }

    override fun onResume() {
        super.onResume()

        // Ensure system bars color is consistent with last dynamic
        lastSystemBarsColor?.let { StatusBarUtils.setSystemBarsColor(this, it) }
            ?: StatusBarUtils.setSystemBarsColor(this, "#121212".toColorInt())

        // Reestablecer modo de pantalla completa
        setupFullScreenMode()

        // Actualizar estado desde el servicio
        if (isBound && musicService != null) {
            updateUIFromService()
        }

        // Reiniciar actualizaciones de progreso
        if (isPlaying) {
            startProgressUpdates()
        }
    }

    override fun onPause() {
        super.onPause()
        stopProgressUpdates()
    }

    override fun onStop() {
        super.onStop()
        stopProgressUpdates()
        unbindMusicService()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopProgressUpdates()
    }
}
