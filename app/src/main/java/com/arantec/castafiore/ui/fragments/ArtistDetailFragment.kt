package com.arantec.castafiore.ui.fragments

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsetsController
import androidx.core.view.WindowCompat
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

    private lateinit var albumAdapter: ArtistAlbumHorizontalAdapter
    private lateinit var songAdapter: SongAdapter

    // Agregado: Variables para el servicio de música
    private var musicService: MusicService? = null
    private var isBound = false
    private var isPlaying = false

    // Referencias a los listeners para poder removerlos después
    private var playbackStateListener: ((Boolean) -> Unit)? = null
    private var songChangeListener: ((Song?) -> Unit)? = null


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

        // Intentar obtener argumentos usando SafeArgs primero
        try {
            val args: ArtistDetailFragmentArgs by navArgs()
            artistId = args.artistId
            artistName = args.artistName
        } catch (e: Exception) {
            // Si SafeArgs falla, intentar con argumentos tradicionales
            arguments?.let { bundle ->
                artistId = bundle.getString("artistId")
                artistName = bundle.getString("artistName")
            }
        }

        // Si aún no tenemos los argumentos, intentar con argumentos tradicionales directamente
        if (artistId == null || artistName == null) {
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
        loadArtistData()
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
    }

    private fun setupClickListeners() {
        binding.btnFollow.setOnClickListener {
            toggleFollowArtist()
        }

        binding.fabPlay.setOnClickListener {
            playArtistTopSongs()
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

                // Verificar si se sigue al artista
                checkFollowStatus()

            } catch (e: Exception) {
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
                    if (topSongs.isNotEmpty()) {
                        songAdapter.updateSongs(topSongs)
                    } else {
                        showError("No se encontraron canciones populares del artista")
                    }
                }
            },
            onFailure = { error ->
                withContext(Dispatchers.Main) {
                    showError("Error al cargar canciones populares: ${error.message}")
                }
            }
        )
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
        val staticColor = android.graphics.Color.parseColor("#121212")

        binding.gradientBackground.setBackgroundColor(staticColor)
        binding.collapsingToolbar.setContentScrimColor(staticColor)
        binding.collapsingToolbar.setStatusBarScrimColor(staticColor)

        // Usar iconos blancos para el toolbar (apropiado para fondo oscuro)
        binding.toolbar.navigationIcon?.setTint(android.graphics.Color.WHITE)

        // Use centralized status bar color utility
        StatusBarUtils.setStatusBarColor(this)
    }

    private fun darkenColor(color: Int, factor: Float): Int {
        val r = (Color.red(color) * factor).toInt()
        val g = (Color.green(color) * factor).toInt()
        val b = (Color.blue(color) * factor).toInt()
        return Color.rgb(r, g, b)
    }

    private fun updateArtistInfo() {
        val albumCount = albums.size
        binding.tvArtistInfo.text = "$albumCount álbum${if (albumCount != 1) "es" else ""}"
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
                        updateFollowButton()
                    },
                    onFailure = {
                        isFollowing = false
                        updateFollowButton()
                    }
                )
            } catch (e: Exception) {
                isFollowing = false
                updateFollowButton()
            }
        }
    }

    private fun updateFollowButton() {
        if (isFollowing) {
            binding.btnFollow.setImageResource(R.drawable.ic_favorite)
            binding.btnFollow.setColorFilter(android.graphics.Color.parseColor("#FF2D55")) // Color principal
        } else {
            binding.btnFollow.setImageResource(R.drawable.ic_favorite_border)
            binding.btnFollow.setColorFilter(android.graphics.Color.parseColor("#B3FFFFFF")) // Color texto secundario
        }
    }

    private fun toggleFollowArtist() {
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
                        showMessage(if (isFollowing) "Siguiendo a ${artist?.name}" else "Dejaste de seguir a ${artist?.name}")
                    },
                    onFailure = {
                        showError("Error al actualizar el estado de seguimiento")
                    }
                )
            } catch (e: Exception) {
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
                musicService?.playQueue(topSongs, 0)
            }

            // Actualizar el estado inmediatamente después de la acción
            updatePlaybackState()
        } catch (e: Exception) {
            showError("Error al reproducir: ${e.message}")
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
                musicService?.playQueue(topSongs, position)
            } else {
                // Si no está en la lista actual, crear una nueva cola con esa canción
                musicService?.playQueue(listOf(song), 0)
            }

            // Actualizar el estado inmediatamente después de la acción
            updatePlaybackState()
        } catch (e: Exception) {
            showError("Error al reproducir canción: ${e.message}")
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
                        val album = com.arantec.castafiore.data.models.Album(
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
                    } catch (e: Exception) {
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
        } catch (e: Exception) {
            showError("Error al navegar al álbum")
        }
    }

    // Métodos del servicio de música
    private fun setupMusicServiceListeners() {
        // Configurar listener para cambios de canción
        songChangeListener = { currentSong ->
            updateCurrentPlayingSong(currentSong)
        }

        // Configurar listener para cambios de estado de reproducción
        playbackStateListener = { isPlaying ->
            this.isPlaying = isPlaying
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
        // Actualizar el adaptador de canciones con la canción actual
        songAdapter.setPlayingSong(currentSong?.id)
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
        val currentSong = musicService?.getCurrentSong()
        return currentSong?.artistId == artistId ||
                topSongs.any { it.id == currentSong?.id }
    }

    private fun updatePlayButton() {
        // Verificar que el fragment esté adjunto y el binding no sea null
        if (!isAdded || _binding == null) {
            return
        }

        if (isPlaying) {
            binding.fabPlay.setImageResource(R.drawable.ic_pause)
        } else {
            binding.fabPlay.setImageResource(R.drawable.ic_play)
        }

        // Usar nuestro color principal #FF2D55 en lugar del verde de Spotify
        binding.fabPlay.backgroundTintList = android.content.res.ColorStateList.valueOf(
            android.graphics.Color.parseColor("#FF2D55")
        )
        binding.fabPlay.imageTintList = android.content.res.ColorStateList.valueOf(
            android.graphics.Color.parseColor("#FFFFFF")
        )
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
        val shareIntent = android.content.Intent().apply {
            action = android.content.Intent.ACTION_SEND
            putExtra(android.content.Intent.EXTRA_TEXT, shareText)
            type = "text/plain"
        }

        val chooser = android.content.Intent.createChooser(shareIntent, "Compartir canción")
        try {
            startActivity(chooser)
        } catch (e: Exception) {
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

                com.bumptech.glide.Glide.with(this)
                    .load(coverUrl)
                    .placeholder(R.drawable.ic_album_placeholder)
                    .error(R.drawable.ic_album_placeholder)
                    .into(ivInfoCover)
            } else {
                ivInfoCover.setImageResource(R.drawable.ic_album_placeholder)
            }
        } catch (e: Exception) {
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
        return String.format("%d:%02d", minutes, remainingSeconds)
    }

    /**
     * Formatea el tamaño del archivo en una unidad legible
     */
    private fun formatFileSize(sizeInBytes: Long): String {
        val kb = 1024.0
        val mb = kb * 1024
        val gb = mb * 1024

        return when {
            sizeInBytes >= gb -> String.format("%.1f GB", sizeInBytes / gb)
            sizeInBytes >= mb -> String.format("%.1f MB", sizeInBytes / mb)
            sizeInBytes >= kb -> String.format("%.1f KB", sizeInBytes / kb)
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
}
