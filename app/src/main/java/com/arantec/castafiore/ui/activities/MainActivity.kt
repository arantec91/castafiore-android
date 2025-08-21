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

class MainActivity : AppCompatActivity() {

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

        // Handle intent extras for navigation
        handleNavigationFromIntent()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        android.util.Log.d("MainActivity", "onNewIntent called")
        intent?.let {
            setIntent(it)
            handleNavigationFromIntent()
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
                    val coverUrl = song.albumId?.let { albumId ->
                        ImageLoader.buildCoverArtUrl(
                            musicRepository.serverUrl!!,
                            albumId,
                            username,
                            token,
                            salt,
                            150 // Tamaño pequeño para mini player
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

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }
}
