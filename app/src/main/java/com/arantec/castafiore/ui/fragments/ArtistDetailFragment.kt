package com.arantec.castafiore.ui.fragments

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.os.Bundle
import android.os.IBinder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.arantec.castafiore.R
import com.arantec.castafiore.data.models.Artist
import com.arantec.castafiore.data.models.Album
import com.arantec.castafiore.data.models.Song
import com.arantec.castafiore.data.repository.MusicRepository
import com.arantec.castafiore.databinding.FragmentArtistDetailBinding
import com.arantec.castafiore.service.MusicService
import com.arantec.castafiore.ui.adapters.ArtistAlbumHorizontalAdapter
import com.arantec.castafiore.ui.adapters.SongAdapter
import com.arantec.castafiore.ui.dialogs.SongOptionsBottomSheet
import com.arantec.castafiore.utils.StatusBarUtils
import com.arantec.castafiore.utils.ImageLoader
import androidx.core.graphics.toColorInt
import com.bumptech.glide.Glide
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

class ArtistDetailFragment : Fragment() {

    private var _binding: FragmentArtistDetailBinding? = null
    private val binding get() = _binding!!

    // Usar Navigation Component args en lugar de parámetros tradicionales
    // Pero también manejar argumentos tradicionales como fallback
    private var artistId: String? = null
    private var artistName: String? = null

    private lateinit var musicRepository: MusicRepository

    private var artist: Artist? = null
    private var isFollowing = false
    private var albums: List<Album> = emptyList()
    private var topSongs: List<Song> = emptyList()
    private var similarArtists: List<Artist> = emptyList()

    private lateinit var albumAdapter: ArtistAlbumHorizontalAdapter
    private lateinit var songAdapter: SongAdapter
    private lateinit var similarAdapter: com.arantec.castafiore.ui.adapters.ArtistHorizontalAdapter

    // Agregado: Variables para el servicio de música
    private var musicService: MusicService? = null
    private var isBound = false
    private var isPlaying = false

    // Referencias a los listeners para poder removerlos después
    private var playbackStateListener: ((Boolean) -> Unit)? = null
    private var songChangeListener: ((Song?) -> Unit)? = null

    // Estado colapsable de canciones populares
    private var isSongsExpanded: Boolean = false
    private val INITIAL_SONGS_LIMIT = 6


    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MusicService.MusicBinder
            musicService = binder.getService()
            isBound = true

            // Configurar listeners después de que el servicio esté enlazado
            setupMusicServiceListeners()

            // Actualizar el estado inicial del botón basándose en el estado real del servicio
            updatePlaybackState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            musicService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Restaurar estado expandido si existe
        isSongsExpanded = savedInstanceState?.getBoolean("songsExpanded") ?: false

        // Intentar obtener argumentos usando SafeArgs primero
        try {
            val args: ArtistDetailFragmentArgs by navArgs()
            artistId = args.artistId
            artistName = args.artistName
        } catch (_: Exception) {
            // Si SafeArgs falla, intentar con argumentos tradicionales
            arguments?.let { bundle ->
                artistId = bundle.getString("artistId")
                artistName = bundle.getString("artistName")
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // Ensure consistent status bar color using utility
        StatusBarUtils.setStatusBarColor(this)

        _binding = FragmentArtistDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        musicRepository = MusicRepository.getInstance(requireContext())

        setupToolbar()
        setupRecyclerViews()
        setupClickListeners()

        // Inicializa el estado del botón de seguir/favoritos sin parpadeos
        initializeFollowUI()

        loadArtistData()
    }

    private fun initializeFollowUI() {
        // Si no tenemos un ID aún, ocultar temporalmente
        val id = artistId
        if (id.isNullOrEmpty()) {
            binding.btnFollow.visibility = View.INVISIBLE
            binding.btnFollow.isEnabled = false
            return
        }
        // Intentar leer el estado desde cache (memoria/disco)
        val cached = musicRepository.peekArtistStarred(id)
        if (cached != null) {
            isFollowing = cached
            binding.btnFollow.visibility = View.VISIBLE
            binding.btnFollow.isEnabled = true
            updateFollowButton()
        } else {
            // Estado desconocido: ocultar hasta confirmar por red
            binding.btnFollow.visibility = View.INVISIBLE
            binding.btnFollow.isEnabled = false
        }
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }
    }

    private fun setupRecyclerViews() {
        // Setup albums RecyclerView
        albumAdapter = ArtistAlbumHorizontalAdapter { album ->
            navigateToAlbumDetail(album)
        }
        binding.rvPopularAlbums.apply {
            adapter = albumAdapter
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        }

        // Setup songs RecyclerView con la firma correcta del SongAdapter
        songAdapter = SongAdapter(
            onSongClick = { song, position -> playSong(song) },
            onSongMoreClick = { song -> showSongOptions(song) }
        )
        binding.rvPopularSongs.apply {
            adapter = songAdapter
            layoutManager = LinearLayoutManager(context)
            isNestedScrollingEnabled = false
        }

        // Setup similar artists RecyclerView
        similarAdapter = com.arantec.castafiore.ui.adapters.ArtistHorizontalAdapter { artist ->
            navigateToArtistDetail(artist)
        }
        binding.rvSimilarArtists.apply {
            adapter = similarAdapter
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        }
    }

    private fun setupClickListeners() {
        binding.btnFollow.setOnClickListener {
            toggleFollowArtist()
        }

        binding.btnPlay.setOnClickListener {
            playArtistTopSongs()
        }

        // Toggle Ver más / Mostrar menos
        binding.btnToggleSongs.setOnClickListener {
            isSongsExpanded = !isSongsExpanded
            updateSongsListUI()
        }
    }

    private fun loadArtistData() {
        showLoading(true)

        lifecycleScope.launch {
            try {
                // Cargar información del artista
                loadArtistInfo()

                // Cargar álbumes del artista
                loadArtistAlbums()

                // Cargar canciones populares del artista
                loadArtistTopSongs()

                // Cargar artistas similares (después de populares)
                loadSimilarArtists()

                // Verificar si se sigue al artista
                checkFollowStatus()

            } catch (_: Exception) {
                showError("Error al cargar la información del artista")
            } finally {
                showLoading(false)
            }
        }
    }

    private suspend fun loadArtistInfo() {
        // Por ahora, usar la información básica del artista desde los argumentos
        artist = Artist(
            id = artistId ?: "",
            name = artistName ?: "",
            albumCount = null,
            starred = null
        )

        withContext(Dispatchers.Main) {
            binding.tvArtistName.text = artist?.name
            // No establecer título en toolbar/collapsingToolbar ya que titleEnabled="false"

            // Cargar imagen del artista
            loadArtistImage()
        }
    }

    private suspend fun loadArtistAlbums() {
        val result = withContext(Dispatchers.IO) {
            musicRepository.getArtistAlbums(artistId ?: "")
        }

        result.fold(
            onSuccess = { albumList ->
                // Ordenar álbumes del más reciente al más antiguo por año
                albums = albumList.sortedByDescending { album ->
                    album.year ?: 0 // Si no hay año, poner al final
                }

                withContext(Dispatchers.Main) {
                    // Mostrar TODOS los álbumes, no solo los primeros 10
                    albumAdapter.updateAlbums(albums)
                    updateArtistInfo()
                }
            },
            onFailure = {
                withContext(Dispatchers.Main) {
                    showError("Error al cargar álbumes")
                }
            }
        )
    }

    private suspend fun loadArtistTopSongs() {
        // Usar la API específica getTopSongs.view de Navidrome
        val result = withContext(Dispatchers.IO) {
            musicRepository.getArtistTopSongs(artistName ?: "", 25)
        }

        result.fold(
            onSuccess = { songs ->
                topSongs = songs
                withContext(Dispatchers.Main) {
                    updateSongsListUI()
                }
            },
            onFailure = { error ->
                withContext(Dispatchers.Main) {
                    showError("Error al cargar canciones populares: ${error.message}")
                }
            }
        )
    }

    private suspend fun loadSimilarArtists() {
        val id = artistId ?: return
        val result = withContext(Dispatchers.IO) {
            musicRepository.getSimilarArtists(id)
        }
        result.fold(
            onSuccess = { list ->
                similarArtists = list
                withContext(Dispatchers.Main) {
                    if (list.isNotEmpty()) {
                        binding.similarArtistsSection.visibility = View.VISIBLE
                        similarAdapter.submit(list)
                    } else {
                        binding.similarArtistsSection.visibility = View.GONE
                    }
                }
            },
            onFailure = {
                withContext(Dispatchers.Main) {
                    binding.similarArtistsSection.visibility = View.GONE
                }
            }
        )
    }

    private fun updateSongsListUI() {
        if (!isAdded || _binding == null) return

        val hasMoreThanLimit = topSongs.size > INITIAL_SONGS_LIMIT

        // Configurar visibilidad y texto del botón
        binding.btnToggleSongs.visibility = if (hasMoreThanLimit) View.VISIBLE else View.GONE
        binding.btnToggleSongs.text = if (isSongsExpanded) getString(R.string.show_less) else getString(R.string.show_more)

        // Actualizar lista a mostrar
        val songsToShow = if (hasMoreThanLimit && !isSongsExpanded) {
            topSongs.take(INITIAL_SONGS_LIMIT)
        } else {
            topSongs
        }
        songAdapter.updateSongs(songsToShow)

        // Actualizar overlay de degradado
        updateGradientOverlay(show = hasMoreThanLimit && !isSongsExpanded)
    }

    private fun updateGradientOverlay(show: Boolean) {
        if (!isAdded || _binding == null) return

        val overlay = binding.gradientMoreOverlay
        if (!show) {
            overlay.visibility = View.GONE
            return
        }

        val rv = binding.rvPopularSongs
        // Ejecutar tras el layout para medir el ítem 6 (índice 5)
        rv.post {
            val lm = rv.layoutManager as? LinearLayoutManager
            val index = 5 // sexto elemento (0-based)
            val child = lm?.findViewByPosition(index)

            if (child != null && child.height > 0) {
                val desiredHeight = max(child.height / 2, dpToPx(56))
                val lp = overlay.layoutParams
                if (lp.height != desiredHeight) {
                    lp.height = desiredHeight
                    overlay.layoutParams = lp
                }
                overlay.visibility = View.VISIBLE
            } else {
                // Fallback: usar altura por defecto si aún no está disponible la vista
                val lp = overlay.layoutParams
                val fallback = dpToPx(80)
                if (lp.height != fallback) {
                    lp.height = fallback
                    overlay.layoutParams = lp
                }
                overlay.visibility = View.VISIBLE
            }
        }
    }

    private fun dpToPx(dp: Int): Int {
        val metrics = resources.displayMetrics
        return (dp * metrics.density).toInt()
    }

    private fun loadArtistImage() {
        // Usar ImageLoader optimizado para cargar imagen del artista
        val musicRepo = MusicRepository.getInstance(requireContext())
        val (username, token, salt) = musicRepo.getAuthParams()
        val artistCoverUrl = ImageLoader.buildArtistImageUrl(
            musicRepo.serverUrl ?: "",
            artistId ?: "",
            username,
            token,
            salt,
            400 // Tamaño optimizado para imagen principal del artista
        )

        ImageLoader.loadArtistImageForFragment(this, binding.ivArtistImage, artistCoverUrl)

        // Aplicar color estático al fondo y status bar
        setStaticBackground()
    }


    private fun setStaticBackground() {
        // Aplicar el color estático predeterminado al fondo y status bar
        val staticColor = "#121212".toColorInt()

        binding.gradientBackground.setBackgroundColor(staticColor)
        binding.collapsingToolbar.setContentScrimColor(staticColor)
        binding.collapsingToolbar.setStatusBarScrimColor(staticColor)

        // Usar iconos blancos para el toolbar (apropiado para fondo oscuro)
        binding.toolbar.navigationIcon?.setTint(Color.WHITE)

        // Use centralized status bar color utility
        StatusBarUtils.setStatusBarColor(this)
    }

    private fun updateArtistInfo() {
        val albumCount = albums.size
        val resId = if (albumCount == 1) R.string.albums_count_singular else R.string.albums_count_plural
        binding.tvArtistInfo.text = getString(resId, albumCount)
    }

    private fun checkFollowStatus() {
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    musicRepository.isArtistStarred(artistId ?: "")
                }

                result.fold(
                    onSuccess = { starred ->
                        isFollowing = starred
                        // Asegurar visibilidad/uso tras la verificación
                        binding.btnFollow.visibility = View.VISIBLE
                        binding.btnFollow.isEnabled = true
                        updateFollowButton()
                    },
                    onFailure = {
                        isFollowing = false
                        binding.btnFollow.visibility = View.VISIBLE
                        binding.btnFollow.isEnabled = true
                        updateFollowButton()
                    }
                )
            } catch (_: Exception) {
                isFollowing = false
                binding.btnFollow.visibility = View.VISIBLE
                binding.btnFollow.isEnabled = true
                updateFollowButton()
            }
        }
    }

    private fun updateFollowButton() {
        if (isFollowing) {
            binding.btnFollow.setImageResource(R.drawable.ic_favorite)
            binding.btnFollow.setColorFilter("#FF2D55".toColorInt()) // Color principal
        } else {
            binding.btnFollow.setImageResource(R.drawable.ic_favorite_border)
            binding.btnFollow.setColorFilter("#B3FFFFFF".toColorInt()) // Color texto secundario
        }
    }

    private fun toggleFollowArtist() {
        // Deshabilitar mientras se procesa para evitar taps repetidos y parpadeo
        binding.btnFollow.isEnabled = false
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    if (isFollowing) {
                        musicRepository.unstarArtist(artistId ?: "")
                    } else {
                        musicRepository.starArtist(artistId ?: "")
                    }
                }

                result.fold(
                    onSuccess = {
                        isFollowing = !isFollowing
                        updateFollowButton()
                        binding.btnFollow.isEnabled = true
                    },
                    onFailure = {
                        binding.btnFollow.isEnabled = true
                        showError("Error al actualizar el estado de seguimiento")
                    }
                )
            } catch (_: Exception) {
                binding.btnFollow.isEnabled = true
                showError("Error al actualizar el estado de seguimiento")
            }
        }
    }

    private fun playArtistTopSongs() {
        if (topSongs.isEmpty()) {
            showMessage("No hay canciones disponibles")
            return
        }

        if (!isBound || musicService == null) {
            bindMusicService()
            return
        }

        try {
            // Si ya está reproduciendo canciones de este artista, pausar/reanudar
            if (isPlaying && isCurrentArtistPlaying()) {
                if (musicService?.isPlaying() == true) {
                    musicService?.pause()
                } else {
                    musicService?.resume()
                }
            } else {
                // Reproducir todas las canciones populares del artista
                val service = musicService
                val startIndex = if (service?.getShuffleEnabled() == true && topSongs.size > 1) {
                    Random.nextInt(topSongs.size)
                } else 0
                service?.playQueue(
                    topSongs,
                    startIndex,
                    MusicService.PlaybackSource(
                        MusicService.SourceType.ARTIST,
                        artistId,
                        artistName
                    )
                )
            }

            // Actualizar el estado inmediatamente después de la acción
            updatePlaybackState()
        } catch (_: Exception) {
            showError("Error al reproducir")
        }
    }

    private fun playSong(song: Song) {
        if (!isBound || musicService == null) {
            bindMusicService()
            return
        }

        try {
            // Encontrar la posición de la canción en la lista
            val position = topSongs.indexOfFirst { it.id == song.id }
            if (position != -1) {
                // Reproducir desde esa posición en la cola de canciones del artista
                musicService?.playQueue(
                    topSongs,
                    position,
                    MusicService.PlaybackSource(
                        MusicService.SourceType.ARTIST,
                        artistId,
                        artistName
                    )
                )
            } else {
                // Si no está en la lista actual, crear una nueva cola con esa canción
                musicService?.playQueue(
                    listOf(song),
                    0,
                    MusicService.PlaybackSource(
                        MusicService.SourceType.ARTIST,
                        artistId,
                        artistName
                    )
                )
            }

            // Actualizar el estado inmediatamente después de la acción
            updatePlaybackState()
        } catch (_: Exception) {
            showError("Error al reproducir canción")
        }
    }

    private fun showSongOptions(song: Song) {
        val bottomSheet = SongOptionsBottomSheet.newInstance(song)
            .setOnDownloadClickListener { selectedSong ->
                // Implementar descarga de canción con el sistema de descarga completo
                val downloadManager = com.arantec.castafiore.data.download.SongDownloadManager.getInstance(requireContext())

                when {
                    downloadManager.isSongDownloaded(selectedSong.id) -> {
                        android.widget.Toast.makeText(requireContext(), "La canción ya está descargada", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    downloadManager.isSongDownloading(selectedSong.id) -> {
                        // Cancelar descarga en progreso
                        downloadManager.cancelDownload(selectedSong.id)
                        android.widget.Toast.makeText(requireContext(), "Descarga cancelada: ${selectedSong.title}", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    else -> {
                        downloadManager.downloadSong(selectedSong)
                        android.widget.Toast.makeText(requireContext(), "Descarga iniciada: ${selectedSong.title}", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setOnDeleteDownloadClickListener { selectedSong ->
                // Implementar eliminación de descarga
                val downloadManager = com.arantec.castafiore.data.download.SongDownloadManager.getInstance(requireContext())

                if (downloadManager.isSongDownloaded(selectedSong.id)) {
                    // Mostrar diálogo de confirmación
                    android.app.AlertDialog.Builder(requireContext())
                        .setTitle("Eliminar descarga")
                        .setMessage("¿Estás seguro de que quieres eliminar la descarga de \"${selectedSong.title}\"?")
                        .setPositiveButton("Eliminar") { _, _ ->
                            val success = downloadManager.deleteSong(selectedSong.id)
                            if (success) {
                                android.widget.Toast.makeText(requireContext(), "Descarga eliminada: ${selectedSong.title}", android.widget.Toast.LENGTH_SHORT).show()
                            } else {
                                android.widget.Toast.makeText(requireContext(), "Error al eliminar la descarga", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                        .setNegativeButton("Cancelar", null)
                        .show()
                } else {
                    android.widget.Toast.makeText(requireContext(), "La canción no está descargada", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            .setOnAddToQueueClickListener { selectedSong ->
                // Agregar canción a la cola de reproducción
                musicService?.addToQueue(selectedSong)
                android.widget.Toast.makeText(requireContext(), "Agregado a la cola: ${selectedSong.title}", android.widget.Toast.LENGTH_SHORT).show()
            }
            .setOnPlayNextClickListener { selectedSong ->
                // Agregar canción para reproducir siguiente
                musicService?.playNext(selectedSong)
                android.widget.Toast.makeText(requireContext(), "Se reproducirá siguiente: ${selectedSong.title}", android.widget.Toast.LENGTH_SHORT).show()
            }
            .setOnAddToPlaylistClickListener { selectedSong ->
                // Mostrar diálogo de selección de playlist
                val playlistSelector = com.arantec.castafiore.ui.dialogs.PlaylistSelectorBottomSheet.newInstance(selectedSong)
                playlistSelector.show(parentFragmentManager, "PlaylistSelectorBottomSheet")
            }
            .setOnViewAlbumClickListener { selectedSong ->
                // Navegar al álbum de la canción
                selectedSong.albumId?.let { albumId ->
                    try {
                        // Crear un objeto Album temporal para la navegación
                        val album = Album(
                            id = albumId,
                            name = selectedSong.album,
                            artist = selectedSong.artist,
                            artistId = selectedSong.artistId ?: "",
                            coverArt = selectedSong.coverArt,
                            songCount = 0,
                            duration = 0,
                            created = "",
                            year = selectedSong.year,
                            genre = selectedSong.genre
                        )
                        val action = ArtistDetailFragmentDirections.actionArtistDetailToAlbumDetail(album)
                        findNavController().navigate(action)
                    } catch (_: Exception) {
                        android.widget.Toast.makeText(requireContext(), "Error al navegar al álbum", android.widget.Toast.LENGTH_SHORT).show()
                    }
                } ?: run {
                    android.widget.Toast.makeText(requireContext(), "Información del álbum no disponible", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            .setOnShareClickListener { selectedSong ->
                shareSong(selectedSong)
            }
            .setOnSongInfoClickListener { selectedSong ->
                showSongInfo(selectedSong)
            }

        bottomSheet.show(parentFragmentManager, "SongOptionsBottomSheet")
    }

    private fun navigateToAlbumDetail(album: Album) {
        try {
            // Usar Navigation Component para navegar al detalle del álbum
            val action = ArtistDetailFragmentDirections.actionArtistDetailToAlbumDetail(album)
            findNavController().navigate(action)
        } catch (_: Exception) {
            showError("Error al navegar al álbum")
        }
    }

    private fun navigateToArtistDetail(artist: Artist) {
        try {
            val args = Bundle().apply {
                putString("artistId", artist.id)
                putString("artistName", artist.name)
            }
            findNavController().navigate(R.id.artistDetailFragment, args)
        } catch (_: Exception) {
            showError("Error al navegar al artista")
        }
    }

    // Métodos del servicio de música
    private fun setupMusicServiceListeners() {
        // Configurar listener para cambios de canción
        songChangeListener = { currentSong ->
            updateCurrentPlayingSong(currentSong)
            // Recalcular el estado de reproducción en función del contexto actual (artista)
            updatePlaybackState()
        }

        // Configurar listener para cambios de estado de reproducción
        playbackStateListener = { isPlaying ->
            // Solo mostrar "pausa" si el artista currente es el que se está reproduciendo
            this.isPlaying = isPlaying && isCurrentArtistPlaying()
            updatePlayButton()
        }

        // Usar addListener en lugar de setOnListener para no interferir con otros listeners
        musicService?.addSongChangeListener(songChangeListener!!)
        musicService?.addPlaybackStateListener(playbackStateListener!!)

        // Actualizar el estado inicial
        updatePlaybackState()
        updateCurrentPlayingSong(musicService?.getCurrentSong())
    }

    private fun updateCurrentPlayingSong(currentSong: Song?) {
        // Solo resaltar canción si este contexto está activo
        val inContext = isCurrentArtistPlaying()
        songAdapter.setPlayingSong(if (inContext) currentSong?.id else null)
    }

    private fun updatePlaybackState() {
        // Obtener el estado real del servicio
        val serviceIsPlaying = musicService?.isPlaying() == true
        val currentlyPlayingArtist = isCurrentArtistPlaying()

        // Actualizar el estado local
        isPlaying = serviceIsPlaying && currentlyPlayingArtist

        // Actualizar la UI
        updatePlayButton()
    }

    private fun isCurrentArtistPlaying(): Boolean {
        val service = musicService ?: return false
        val src = service.getPlaybackSource() ?: return false
        return src.type == MusicService.SourceType.ARTIST && src.id == artistId
    }

    private fun updatePlayButton() {
        // Verificar que el fragment esté adjunto y el binding no sea null
        if (!isAdded || _binding == null) {
            return
        }

        // Cambiar el texto del botón según el estado de reproducción
        binding.btnPlay.text = if (isPlaying) {
            getString(R.string.pause)
        } else {
            getString(R.string.play)
        }
    }

    // Métodos de conexión al servicio
    private fun bindMusicService() {
        val intent = Intent(requireContext(), MusicService::class.java)
        requireContext().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun unbindMusicService() {
        if (isBound) {
            // Remover los listeners específicos de este fragment antes de desconectar
            songChangeListener?.let { musicService?.removeSongChangeListener(it) }
            playbackStateListener?.let { musicService?.removePlaybackStateListener(it) }

            // Limpiar referencias
            playbackStateListener = null
            songChangeListener = null

            requireContext().unbindService(serviceConnection)
            isBound = false
            musicService = null
        }
    }

    // Métodos de UI auxiliares
    private fun showLoading(show: Boolean) {
        if (!isAdded || _binding == null) return
        binding.progressBar.visibility = if (show) View.VISIBLE else View.GONE
        // Usar el contenedor principal del layout
        binding.root.findViewById<View>(android.R.id.content)?.visibility = if (show) View.GONE else View.VISIBLE
    }

    private fun showError(message: String) {
        android.widget.Toast.makeText(requireContext(), message, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun showMessage(message: String) {
        android.widget.Toast.makeText(requireContext(), message, android.widget.Toast.LENGTH_SHORT).show()
    }

    // Métodos auxiliares para el bottom sheet de opciones de canciones
    private fun shareSong(song: Song) {
        val shareText = "Escucha \"${song.title}\" de ${song.artist} en el álbum \"${song.album}\""
        val shareIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, shareText)
            type = "text/plain"
        }

        val chooser = Intent.createChooser(shareIntent, "Compartir canción")
        try {
            startActivity(chooser)
        } catch (_: Exception) {
            android.widget.Toast.makeText(requireContext(), "No se pudo compartir la canción", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun showSongInfo(song: Song) {
        // Crear un diálogo personalizado usando el layout dialog_song_info.xml
        val dialogBuilder = androidx.appcompat.app.AlertDialog.Builder(requireContext())
        val inflater = LayoutInflater.from(requireContext())
        val dialogView = inflater.inflate(R.layout.dialog_song_info, null)

        // Referencias a las vistas del diálogo
        val ivInfoCover = dialogView.findViewById<android.widget.ImageView>(R.id.iv_info_cover)
        val tvInfoTitle = dialogView.findViewById<android.widget.TextView>(R.id.tv_info_title)
        val tvInfoArtist = dialogView.findViewById<android.widget.TextView>(R.id.tv_info_artist)
        val tvInfoAlbum = dialogView.findViewById<android.widget.TextView>(R.id.tv_info_album)
        val tvInfoDuration = dialogView.findViewById<android.widget.TextView>(R.id.tv_info_duration)
        val tvInfoGenre = dialogView.findViewById<android.widget.TextView>(R.id.tv_info_genre)
        val tvInfoYear = dialogView.findViewById<android.widget.TextView>(R.id.tv_info_year)
        val tvInfoBitrate = dialogView.findViewById<android.widget.TextView>(R.id.tv_info_bitrate)
        val tvInfoFormat = dialogView.findViewById<android.widget.TextView>(R.id.tv_info_format)
        val tvInfoFileSize = dialogView.findViewById<android.widget.TextView>(R.id.tv_info_file_size)

        // Configurar la información básica
        tvInfoTitle.text = song.title
        tvInfoArtist.text = song.artist
        tvInfoAlbum.text = song.album
        tvInfoDuration.text = formatSongDuration(song.duration)

        // Configurar información opcional
        tvInfoGenre.text = song.genre ?: "Desconocido"
        tvInfoYear.text = song.year?.toString() ?: "Desconocido"
        tvInfoBitrate.text = if (song.bitRate != null) "${song.bitRate} kbps" else "Desconocido"
        tvInfoFormat.text = song.suffix?.uppercase() ?: "Desconocido"

        // Formatear el tamaño del archivo
        tvInfoFileSize.text = if (song.size != null) {
            formatFileSize(song.size)
        } else {
            "Desconocido"
        }

        // Cargar la imagen de la canción
        try {
            if (musicRepository.serverUrl != null && song.coverArt != null) {
                val (username, token, salt) = musicRepository.getAuthParams()
                val coverUrl = song.getCoverArtUrl(
                    musicRepository.serverUrl!!,
                    username,
                    token,
                    salt
                )

                Glide.with(this)
                    .load(coverUrl)
                    .placeholder(R.drawable.ic_album_placeholder)
                    .error(R.drawable.ic_album_placeholder)
                    .into(ivInfoCover)
            } else {
                ivInfoCover.setImageResource(R.drawable.ic_album_placeholder)
            }
        } catch (_: Exception) {
            ivInfoCover.setImageResource(R.drawable.ic_album_placeholder)
        }

        // Crear y mostrar el diálogo
        dialogBuilder.setView(dialogView)
            .setTitle("Información de la canción")
            .setPositiveButton("Cerrar") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    /**
     * Formatea la duración de la canción en formato mm:ss
     */
    private fun formatSongDuration(seconds: Int): String {
        val minutes = seconds / 60
        val remainingSeconds = seconds % 60
        return String.format(Locale.getDefault(), "%d:%02d", minutes, remainingSeconds)
    }

    /**
     * Formatea el tamaño del archivo en una unidad legible
     */
    private fun formatFileSize(sizeInBytes: Long): String {
        val kb = 1024.0
        val mb = kb * 1024
        val gb = mb * 1024

        return when {
            sizeInBytes >= gb -> String.format(Locale.getDefault(), "%.1f GB", sizeInBytes / gb)
            sizeInBytes >= mb -> String.format(Locale.getDefault(), "%.1f MB", sizeInBytes / mb)
            sizeInBytes >= kb -> String.format(Locale.getDefault(), "%.1f KB", sizeInBytes / kb)
            else -> "$sizeInBytes bytes"
        }
    }


    // Métodos del ciclo de vida del fragment
    override fun onStart() {
        super.onStart()
        // Conectar al servicio cuando el fragment sea visible
        bindMusicService()
    }

    override fun onResume() {
        super.onResume()
        // Actualizar el estado del botón cada vez que regresamos al fragment
        if (isBound && musicService != null) {
            updatePlaybackState()
        }

        // Ensure consistent status bar color on resume
        StatusBarUtils.setStatusBarColor(this)
    }

    override fun onStop() {
        super.onStop()
        // Desconectar del servicio cuando el fragment no sea visible
        unbindMusicService()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("songsExpanded", isSongsExpanded)
    }
}
