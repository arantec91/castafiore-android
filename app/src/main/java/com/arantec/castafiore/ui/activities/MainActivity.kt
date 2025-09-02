package com.arantec.castafiore.ui.activities

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.navigation.NavOptions
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import androidx.navigation.findNavController
import com.arantec.castafiore.R
import com.arantec.castafiore.data.cache.CacheConfig
import com.arantec.castafiore.data.models.Song
import com.arantec.castafiore.data.repository.MusicRepository
import com.arantec.castafiore.databinding.ActivityMainBinding
import com.arantec.castafiore.service.MusicService
import com.arantec.castafiore.utils.ImageLoader
import com.arantec.castafiore.utils.StatusBarUtils
import com.arantec.castafiore.data.network.NavidromeClient
import com.arantec.castafiore.data.network.InFlightTracker
import com.arantec.castafiore.ui.helpers.HasContentState
import com.arantec.castafiore.ui.helpers.LoadingHost
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity(), LoadingHost {

    private lateinit var binding: ActivityMainBinding
    private lateinit var musicRepository: MusicRepository
    private var musicService: MusicService? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MusicService.MusicBinder
            musicService = binder.getService()
            isBound = true

            // Setup listeners
            musicService?.addPlaybackStateListener { isPlaying ->
                updatePlayPauseButton(isPlaying)
            }

            musicService?.addSongChangeListener { song ->
                updateMiniPlayer(song)
            }

            // Ensure mini player UI is populated even if song didn't change
            updateMiniPlayer(musicService?.getCurrentSong())
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            musicService = null
            isBound = false
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        // No es crítico si el usuario no concede el permiso
        // La música seguirá funcionando sin notificaciones
    }

    // Global loading overlay control
    private var overlayVisible = false
    private var pendingShowJob: Job? = null
    private var lastShowStartAt: Long = 0L
    // Reference-counted manual override so fragments can force the overlay without flicker
    private var overlayManualOverrideCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        musicRepository = MusicRepository.getInstance(this)

        // Check if configured
        if (!musicRepository.isConfigured()) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }

        // Initialize API client with saved server URL
        musicRepository.serverUrl?.let { NavidromeClient.initialize(it) }

        // Inicializar el sistema de cache
        CacheConfig.initialize(this)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Set consistent status bar color
        StatusBarUtils.setStatusBarColor(this)

        setupNavigationComponent()
        setupMiniPlayer()
        bindMusicService()
        requestNotificationPermission()

        // Observe global network state and toggle the overlay
        setupGlobalLoadingObserver()

        // Handle intent extras for navigation
        handleNavigationFromIntent()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Update stored intent so handleNavigationFromIntent() reads the latest extras
        setIntent(intent)
        // React to navigation requests when MainActivity is reused (SINGLE_TOP/CLEAR_TOP)
        handleNavigationFromIntent()
    }

    // LoadingHost implementation to allow child fragments to control the overlay explicitly
    override fun showGlobalLoading(show: Boolean) {
        // Cancel any pending debounced show to avoid race conditions
        pendingShowJob?.cancel()
        pendingShowJob = null
        if (show) {
            // Bump manual override and force show immediately
            overlayManualOverrideCount++
            if (!overlayVisible) {
                binding.globalLoadingOverlay.visibility = View.VISIBLE
                overlayVisible = true
                lastShowStartAt = System.currentTimeMillis()
            }
        } else {
            // Release manual override if present
            if (overlayManualOverrideCount > 0) overlayManualOverrideCount--
        }
        // Recompute final state after manual change
        updateOverlayStateFromSources()
    }

    override fun isGlobalLoadingVisible(): Boolean = overlayVisible

    private fun setupGlobalLoadingObserver() {
        // Recompute when in-flight state changes
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                InFlightTracker.isLoading.collect { isLoading ->
                    recomputeGlobalOverlay(isLoading)
                }
            }
        }

        // Recompute when destination changes
        val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navHostFragment.navController.addOnDestinationChangedListener { _, _, _ ->
            recomputeGlobalOverlay(InFlightTracker.isLoading.value)
        }
    }

    private fun recomputeGlobalOverlay(isLoading: Boolean) {
        // If a fragment requested manual override, keep overlay as-is (visible) until released
        if (overlayManualOverrideCount > 0) {
            android.util.Log.d("GlobalLoader", "Manual override active ($overlayManualOverrideCount), keeping overlay visible")
            if (!overlayVisible) {
                binding.globalLoadingOverlay.visibility = View.VISIBLE
                overlayVisible = true
                lastShowStartAt = System.currentTimeMillis()
            }
            return
        }
        val hasContent = currentFragmentHasContent()
        val shouldShow = isLoading && !hasContent
        android.util.Log.d("GlobalLoader", "isLoading=$isLoading, hasContent=$hasContent, shouldShow=$shouldShow")
        if (shouldShow) {
            maybeShowOverlayWithDebounce()
        } else {
            hideOverlayRespectingMinShow()
        }
    }

    private fun updateOverlayStateFromSources() {
        // Respect manual override first
        if (overlayManualOverrideCount > 0) {
            if (!overlayVisible) {
                binding.globalLoadingOverlay.visibility = View.VISIBLE
                overlayVisible = true
                lastShowStartAt = System.currentTimeMillis()
            }
            return
        }
        // Otherwise derive from in-flight and content state
        val shouldShow = InFlightTracker.isLoading.value && !currentFragmentHasContent()
        if (shouldShow) {
            // Debounce show to avoid quick blinks
            maybeShowOverlayWithDebounce()
        } else {
            hideOverlayRespectingMinShow()
        }
    }

    private fun currentFragmentHasContent(): Boolean {
        val navHost = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as? NavHostFragment
        val current = navHost?.childFragmentManager?.fragments?.lastOrNull { it.isVisible }
        val provider = current as? HasContentState
        // Default to false so the overlay appears unless the screen explicitly signals content
        return provider?.hasContent() ?: false
    }

    private fun maybeShowOverlayWithDebounce() {
        if (overlayVisible) return
        if (pendingShowJob != null) return
        pendingShowJob = lifecycleScope.launch {
            // Debounce to avoid flicker on super fast calls
            delay(150)
            // Ensure condition still holds and no manual override is active
            if (overlayManualOverrideCount == 0 && InFlightTracker.isLoading.value && !currentFragmentHasContent()) {
                android.util.Log.d("GlobalLoader", "Showing overlay")
                binding.globalLoadingOverlay.visibility = View.VISIBLE
                overlayVisible = true
                lastShowStartAt = System.currentTimeMillis()
            }
            pendingShowJob = null
        }
    }

    private fun hideOverlayRespectingMinShow() {
        // Do not hide if a manual override is active
        if (overlayManualOverrideCount > 0) return
        pendingShowJob?.cancel()
        pendingShowJob = null
        if (!overlayVisible) return
        val shownFor = System.currentTimeMillis() - lastShowStartAt
        val hideAction = {
            android.util.Log.d("GlobalLoader", "Hiding overlay")
            binding.globalLoadingOverlay.visibility = View.GONE
            overlayVisible = false
        }
        if (shownFor >= 200) {
            hideAction()
        } else {
            lifecycleScope.launch {
                delay(200 - shownFor)
                // Ensure no manual override appeared meanwhile
                if (overlayManualOverrideCount == 0) hideAction()
            }
        }
    }

    private fun setupNavigationComponent() {
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController

        // Mantener sincronización visual (badges/selección) con NavigationUI
        binding.bottomNavigation.setupWithNavController(navController)

        // Forzar navegación al fragmento correspondiente siempre, sin importar el actual
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            val options = NavOptions.Builder()
                // Volver al inicio del grafo para salir de detalles antes de cambiar de pestaña
                .setPopUpTo(navController.graph.startDestinationId, false)
                .setLaunchSingleTop(true)
                .setRestoreState(true)
                .build()

            try {
                if (navController.currentDestination?.id == item.itemId) {
                    // Ya estamos en el destino: limpiar cualquier pila interna si aplica
                    navController.popBackStack(item.itemId, false)
                } else {
                    navController.navigate(item.itemId, null, options)
                }
                true
            } catch (e: IllegalArgumentException) {
                // Si el destino no existe en el grafo
                android.util.Log.e("MainActivity", "Destino no encontrado para itemId=${item.itemId}", e)
                false
            }
        }

        // Al re-seleccionar la misma pestaña, volver al root de esa sección
        binding.bottomNavigation.setOnItemReselectedListener { item ->
            try {
                navController.popBackStack(item.itemId, false)
            } catch (_: Exception) { }
        }
    }

    private fun handleNavigationFromIntent() {
        val selectedTab = intent.getStringExtra("selected_tab")
        selectedTab?.let { tab ->
            val itemId = when (tab) {
                "home" -> R.id.homeFragment
                "search" -> R.id.searchFragment
                "library" -> R.id.libraryFragment
                "downloads" -> R.id.downloadsFragment
                else -> R.id.homeFragment
            }
            binding.bottomNavigation.selectedItemId = itemId
        }

        // Handle navigation from PlayerActivity to AlbumDetailFragment or ArtistDetailFragment
        val navigateTo = intent.getStringExtra("navigate_to")
        android.util.Log.d("MainActivity", "Received navigation intent - navigate_to: $navigateTo")

        when (navigateTo) {
            "album_detail" -> {
                val albumId = intent.getStringExtra("album_id")
                val albumName = intent.getStringExtra("album_name")
                android.util.Log.d("MainActivity", "Album navigation - albumId: $albumId, albumName: $albumName")
                if (albumId != null && albumName != null) {
                    navigateToAlbumDetail(albumId, albumName)
                }
            }
            "artist_detail" -> {
                val artistId = intent.getStringExtra("artist_id")
                val artistName = intent.getStringExtra("artist_name")
                android.util.Log.d("MainActivity", "Artist navigation - artistId: $artistId, artistName: $artistName")
                if (artistId != null && artistName != null) {
                    navigateToArtistDetail(artistId, artistName)
                }
            }
        }
    }

    private fun navigateToAlbumDetail(albumId: String, albumName: String) {
        // Usar Navigation Component para navegar al AlbumDetailFragment
        android.util.Log.d("MainActivity", "navigateToAlbumDetail called with albumId: $albumId, albumName: $albumName")
        try {
            val navController = findNavController(R.id.nav_host_fragment)

            // Obtener información adicional del artista si está disponible
            val artistName = intent.getStringExtra("artist_name") ?: ""
            val artistId = intent.getStringExtra("artist_id") ?: ""

            // Crear un objeto Album con toda la información disponible
            // El AlbumDetailFragment espera un objeto Album completo
            val album = com.arantec.castafiore.data.models.Album(
                id = albumId,
                name = albumName,
                artist = artistName, // Ahora tenemos el nombre del artista
                artistId = artistId, // Ahora tenemos el ID del artista
                songCount = 0, // Se cargará desde el servicio
                duration = 0, // Se cargará desde el servicio
                coverArt = albumId, // Usar el albumId como coverArt para que pueda cargar la imagen
                year = null,
                genre = null,
                songs = null
            )

            val bundle = Bundle().apply {
                putParcelable("album", album)
            }

            android.util.Log.d("MainActivity", "Attempting to navigate to albumDetailFragment with Album object (artist: $artistName)")
            navController.navigate(R.id.albumDetailFragment, bundle)
            android.util.Log.d("MainActivity", "Navigation to albumDetailFragment completed successfully")
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error navigating to album detail: ${e.message}", e)
        }
    }

    private fun navigateToArtistDetail(artistId: String, artistName: String) {
        // Usar Navigation Component para navegar al ArtistDetailFragment
        android.util.Log.d("MainActivity", "navigateToArtistDetail called with artistId: $artistId, artistName: $artistName")
        try {
            val navController = findNavController(R.id.nav_host_fragment)
            // Necesitamos usar SafeArgs para pasar los parámetros al ArtistDetailFragment
            // Por ahora, usando Bundle para compatibilidad
            val bundle = Bundle().apply {
                putString("artistId", artistId)
                putString("artistName", artistName)
            }
            android.util.Log.d("MainActivity", "Attempting to navigate to artistDetailFragment with bundle")
            navController.navigate(R.id.artistDetailFragment, bundle)
            android.util.Log.d("MainActivity", "Navigation to artistDetailFragment completed successfully")
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error navigating to artist detail: ${e.message}", e)
        }
    }

    private fun setupMiniPlayer() {
        binding.btnPlayPause.setOnClickListener {
            musicService?.togglePlayPause()
        }

        // New: Next button handler
        binding.btnNext.setOnClickListener {
            musicService?.next()
        }

        binding.playerContainer.setOnClickListener {
            // Launch PlayerActivity for full-screen player experience
            try {
                val intent = Intent(this, PlayerActivity::class.java)
                startActivity(intent)
            } catch (_: Exception) {
                // Handle launch error silently
            }
        }
    }

    private fun bindMusicService() {
        val intent = Intent(this, MusicService::class.java)
        bindService(intent, serviceConnection, android.content.Context.BIND_AUTO_CREATE)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun updatePlayPauseButton(isPlaying: Boolean) {
        val iconRes = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        binding.btnPlayPause.setImageResource(iconRes)
    }

    private fun updateMiniPlayer(song: Song?) {
        if (song != null) {
            binding.playerContainer.visibility = View.VISIBLE
            binding.tvSongTitle.text = song.title
            binding.tvArtistName.text = song.artist

            // Preferir portada local si disponible; fallback a URL con ImageLoader
            try {
                val dm = com.arantec.castafiore.data.download.SongDownloadManager.getInstance(this)
                val localCoverPath = try { dm.createCoverPath(song) } catch (_: Exception) { null }
                var usedLocal = false
                if (!localCoverPath.isNullOrEmpty()) {
                    val file = java.io.File(localCoverPath)
                    if (file.exists()) {
                        val bmp = android.graphics.BitmapFactory.decodeFile(localCoverPath)
                        if (bmp != null) {
                            binding.ivAlbumArt.setImageBitmap(bmp)
                            usedLocal = true
                        }
                    }
                }
                if (!usedLocal) {
                    val (username, token, salt) = musicRepository.getAuthParams()
                    val coverId = song.coverArt ?: song.albumId
                    val coverUrl = coverId?.let { id ->
                        ImageLoader.buildCoverArtUrl(
                            musicRepository.serverUrl!!,
                            id,
                            username,
                            token,
                            salt,
                            500 // match PlayerActivity size to share cache offline
                        )
                    }
                    ImageLoader.loadThumbnail(this, binding.ivAlbumArt, coverUrl)
                }
            } catch (_: Exception) {
                // Si falla, usar placeholder
                binding.ivAlbumArt.setImageResource(R.drawable.ic_album_placeholder)
            }
        } else {
            binding.playerContainer.visibility = View.GONE
        }
    }

    override fun onResume() {
        super.onResume()
        // Ensure overlay state is consistent after returning from another Activity (e.g., PlayerActivity)
        recomputeGlobalOverlay(com.arantec.castafiore.data.network.InFlightTracker.isLoading.value)
        // When returning from PlayerActivity, refresh mini player artwork/state
        if (isBound) {
            updateMiniPlayer(musicService?.getCurrentSong())
            musicService?.let { updatePlayPauseButton(it.isPlaying()) }
        }
    }

    override fun onPause() {
        // Hide overlay when going to background to avoid a stuck scrim if any in-flight state glitches
        hideOverlayRespectingMinShow()
        super.onPause()
    }

    override fun onStop() {
        // Extra safety: ensure overlay is hidden when activity stops
        hideOverlayRespectingMinShow()
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }
}
