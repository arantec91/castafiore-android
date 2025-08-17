package com.arantec.castafiore.ui.fragments

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.IBinder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.arantec.castafiore.R
import com.arantec.castafiore.data.models.Album
import com.arantec.castafiore.data.models.Song
import com.arantec.castafiore.data.repository.MusicRepository
import com.arantec.castafiore.data.download.SongDownloadManager
import com.arantec.castafiore.databinding.FragmentAlbumDetailBinding
import com.arantec.castafiore.service.MusicService
import com.arantec.castafiore.ui.adapters.SongAdapter
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import androidx.palette.graphics.Palette
import kotlinx.coroutines.launch
import com.arantec.castafiore.utils.StatusBarUtils
import com.arantec.castafiore.utils.ImageLoader
import android.view.animation.AlphaAnimation
import kotlin.random.Random

class AlbumDetailFragment : Fragment() {

    private var _binding: FragmentAlbumDetailBinding? = null
    private val binding get() = _binding!!

    private lateinit var musicRepository: MusicRepository
    private lateinit var songAdapter: SongAdapter
    private var musicService: MusicService? = null
    private var isBound = false
    private var currentAlbum: Album? = null
    private var albumSongs = mutableListOf<Song>()

    private var dominantNavIconColor: Int? = null
    private var isPlaying = false
    private var isFavorited = false // Nueva variable para el estado de favoritos

    // Referencias a los listeners para poder removerlos después
    private var playbackStateListener: ((Boolean) -> Unit)? = null
    private var songChangeListener: ((Song?) -> Unit)? = null

    // Acción de reproducción pendiente mientras se enlaza el servicio
    private var pendingAction: (() -> Unit)? = null

    // Color original de la status bar ahora manejado por StatusBarUtils

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MusicService.MusicBinder
            musicService = binder.getService()
            isBound = true

            // Configurar listeners después de que el servicio esté enlazado
            setupMusicServiceListeners()

            // Actualizar el estado inicial del botón de forma inteligente
            isPlaying = musicService?.isPlaying() == true && isCurrentAlbumPlaying()
            updatePlayButton()

            // Actualizar el estado de la canción en reproducción al conectarse
            updateCurrentPlayingSong()

            // Ejecutar acción pendiente si existe
            pendingAction?.invoke()
            pendingAction = null
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            musicService = null
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // Ensure consistent status bar color using utility
        StatusBarUtils.setStatusBarColor(this)

        _binding = FragmentAlbumDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        musicRepository = MusicRepository.getInstance(requireContext())

        // Establecer inmediatamente el color de fondo y status bar para evitar parpadeos
        setStaticBackground(null)

        // Obtener álbum de los argumentos
        currentAlbum = requireArguments().getParcelable<Album>("album")

        if (currentAlbum == null) {
            findNavController().popBackStack()
            return
        }

        // Solo mostrar información básica inmediatamente
        showBasicAlbumInfo()

        // Configurar UI básica
        setupBasicUI()
        setupRecyclerView()

        // Diferir operaciones pesadas para evitar bloqueo de la animación
        view.post {
            if (isAdded && _binding != null) {
                setupUI()  // Cargar imágenes de forma diferida
                setupClickListeners()
                loadAlbumDetails()
                checkFavoriteStatus()
            }
        }
    }

    private fun showBasicAlbumInfo() {
        currentAlbum?.let { album ->
            binding.tvAlbumTitle.text = album.name
            binding.tvArtistName.text = album.artist

            // Formatear información básica del álbum
            val year = album.year?.toString() ?: getString(R.string.unknown_year)
            val songCount = album.songCount
            binding.tvAlbumInfo.text = getString(R.string.album_info, year, songCount, formatAlbumDuration(album.duration))
        }
    }

    private fun setupBasicUI() {
        // Solo configurar el toolbar inmediatamente
        binding.toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }

        // Configurar el AppBarLayout con optimización
        binding.appBarLayout.addOnOffsetChangedListener(
            object : com.google.android.material.appbar.AppBarLayout.OnOffsetChangedListener {
                override fun onOffsetChanged(appBarLayout: com.google.android.material.appbar.AppBarLayout, verticalOffset: Int) {
                    dominantNavIconColor?.let { color ->
                        binding.toolbar.navigationIcon?.setTint(color)
                    }
                }
            }
        )
    }

    private fun setupUI() {
        // Mover la carga de imágenes aquí (diferida)
        currentAlbum?.let { album ->
            // Cargar imagen del artista de forma asíncrona
            val musicRepo = MusicRepository.getInstance(requireContext())
            val (username, token, salt) = musicRepo.getAuthParams()
            val artistCoverUrl = ImageLoader.buildArtistImageUrl(
                musicRepo.serverUrl ?: "",
                album.artistId,
                username,
                token,
                salt,
                300
            )

            ImageLoader.loadArtistImageForFragment(this, binding.ivArtistAvatar, artistCoverUrl)

            // Cargar imagen del álbum de forma asíncrona
            loadAlbumCover(album)
        }
    }

    private fun setupRecyclerView() {
        songAdapter = SongAdapter(
            onSongClick = { song, position ->
                // Reproducir el álbum completo desde la canción seleccionada
                if (isBound && musicService != null) {
                    musicService?.playQueue(
                        albumSongs,
                        position,
                        com.arantec.castafiore.service.MusicService.PlaybackSource(
                            com.arantec.castafiore.service.MusicService.SourceType.ALBUM,
                            currentAlbum?.id,
                            currentAlbum?.name
                        )
                    )
                } else {
                    pendingAction = {
                        musicService?.playQueue(
                            albumSongs,
                            position,
                            com.arantec.castafiore.service.MusicService.PlaybackSource(
                                com.arantec.castafiore.service.MusicService.SourceType.ALBUM,
                                currentAlbum?.id,
                                currentAlbum?.name
                            )
                        )
                    }
                    bindMusicService()
                }
            },
            onSongMoreClick = { song ->
                showSongOptions(song)
            }
        )

        binding.rvSongs.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = songAdapter
            isNestedScrollingEnabled = false
        }
    }

    private fun setupMusicServiceListeners() {
        musicService?.let { service ->
            // Limpiar listeners existentes antes de agregar nuevos
            cleanupListeners()

            // Crear y guardar referencias a los listeners
            playbackStateListener = { playing ->
                // Verificar que el fragment esté adjunto antes de actualizar UI
                if (isAdded && _binding != null) {
                    requireActivity().runOnUiThread {
                        isPlaying = playing && isCurrentAlbumPlaying()
                        updatePlayButton()
                    }
                }
            }

            songChangeListener = { song ->
                if (isAdded && _binding != null) {
                    requireActivity().runOnUiThread {
                        // Actualizar el estado del botón cuando cambie la canción
                        isPlaying = service.isPlaying() && isCurrentAlbumPlaying()
                        updatePlayButton()
                        songAdapter.setPlayingSong(song?.id)
                    }
                }
            }

            // Agregar los listeners al servicio
            playbackStateListener?.let { service.addPlaybackStateListener(it) }
            songChangeListener?.let { service.addSongChangeListener(it) }
        }
    }

    /**
     * Verifica si el álbum actual es el que se está reproduciendo actualmente
     */
    private fun isCurrentAlbumPlaying(): Boolean {
        val currentAlbumId = currentAlbum?.id ?: return false
        val src = musicService?.getPlaybackSource() ?: return false
        return src.type == com.arantec.castafiore.service.MusicService.SourceType.ALBUM && src.id == currentAlbumId
    }

    private fun cleanupListeners() {
        musicService?.let { service ->
            playbackStateListener?.let {
                service.removePlaybackStateListener(it)
            }
            songChangeListener?.let {
                service.removeSongChangeListener(it)
            }
        }
        playbackStateListener = null
        songChangeListener = null
    }

    private fun updatePlayButton() {
        // Verificar que el fragment esté adjunto y la vista exista
        if (!isAdded || _binding == null) return

        // Asegurar que se ejecute en el hilo principal
        if (Thread.currentThread() == requireActivity().mainLooper.thread) {
            // Ya estamos en el hilo principal
            if (isPlaying) {
                binding.fabPlay.setImageResource(R.drawable.ic_pause)
            } else {
                binding.fabPlay.setImageResource(R.drawable.ic_play)
            }
        } else {
            // Ejecutar en el hilo principal
            requireActivity().runOnUiThread {
                if (isAdded && _binding != null) {
                    if (isPlaying) {
                        binding.fabPlay.setImageResource(R.drawable.ic_pause)
                    } else {
                        binding.fabPlay.setImageResource(R.drawable.ic_play)
                    }
                }
            }
        }
    }

    private fun setupClickListeners() {
        // Click listener para navegar al artista
        binding.tvArtistName.setOnClickListener {
            currentAlbum?.let { album ->
                navigateToArtist(album.artistId, album.artist)
            }
        }

        binding.fabPlay.setOnClickListener {
            if (isBound && musicService != null) {
                musicService?.let { service ->
                    if (isCurrentAlbumPlaying()) {
                        // Si este álbum es el que se está reproduciendo actualmente
                        if (service.isPlaying()) {
                            // Si está reproduciéndose, pausar
                            service.pause()
                        } else {
                            // Si está pausado, reanudar (no reiniciar la cola)
                            service.play()
                        }
                    } else {
                        // Si este álbum NO se está reproduciendo, iniciar desde el principio
                        if (albumSongs.isNotEmpty()) {
                            playAlbum()
                        }
                    }
                }
            } else {
                // Posponer acción hasta que el servicio esté enlazado
                pendingAction = {
                    musicService?.let { service ->
                        if (isCurrentAlbumPlaying()) {
                            if (service.isPlaying()) service.pause() else service.play()
                        } else if (albumSongs.isNotEmpty()) {
                            playAlbum()
                        }
                    }
                }
                bindMusicService()
            }
        }

        binding.btnMore.setOnClickListener {
            showAlbumOptions()
        }

        // Agregar listener para el botón de favoritos
        binding.btnFavorite.setOnClickListener {
            toggleFavorite()
        }
    }

    private fun loadAlbumDetails() {
        currentAlbum?.let { album ->
            lifecycleScope.launch {
                try {
                    musicRepository.getAlbumDetail(album.id).fold(
                        onSuccess = { detailedAlbum ->
                            // Actualizar el álbum actual con la información completa
                            currentAlbum = detailedAlbum

                            // Actualizar la UI con la información completa del álbum
                            updateAlbumInfo(detailedAlbum)

                            // Usar las canciones reales del álbum si están disponibles
                            val songsToUse = detailedAlbum.songs
                            if (!songsToUse.isNullOrEmpty()) {
                                albumSongs.clear()
                                albumSongs.addAll(songsToUse)
                                songAdapter.updateSongs(albumSongs)
                            } else {
                                // Si no hay canciones en la respuesta, generar de muestra
                                generateSampleSongs(detailedAlbum)
                            }
                        },
                        onFailure = {
                            // Si falla, generar canciones de muestra
                            generateSampleSongs(album)
                        }
                    )
                } catch (_: Exception) {
                    generateSampleSongs(album)
                }
            }
        }
    }

    private fun updateAlbumInfo(album: Album) {
        // Actualizar la información del álbum en la UI
        binding.tvAlbumTitle.text = album.name
        binding.tvArtistName.text = album.artist

        // Actualizar la información detallada del álbum
        val year = album.year?.toString() ?: getString(R.string.unknown_year)
        val songCount = album.songCount
        val duration = album.duration

        binding.tvAlbumInfo.text = getString(R.string.album_info, year, songCount, formatAlbumDuration(duration))
    }

    private fun generateSampleSongs(album: Album) {
        // Temporal: generar canciones de muestra solo si no tenemos canciones reales
        val sampleSongs = mutableListOf<Song>()

        // Generar títulos más realistas basados en el álbum
        val sampleTitles = listOf(
            "Intro", "First Light", "Dreams", "Midnight", "Echoes", "Journey",
            "Memories", "Dawn", "Whispers", "Finale", "Outro", "Reflection",
            "Hope", "Freedom", "Paradise", "Thunder", "Ocean", "Mountain"
        )

        for (i in 1..album.songCount) {
            val titleIndex = (i - 1) % sampleTitles.size
            val title = if (i <= sampleTitles.size) {
                sampleTitles[titleIndex]
            } else {
                "${sampleTitles[titleIndex]} (Part ${(i - 1) / sampleTitles.size + 1})"
            }

            sampleSongs.add(
                Song(
                    id = "${album.id}_$i",
                    title = title,
                    artist = album.artist,
                    album = album.name,
                    duration = (180 + (Math.random() * 120)).toInt(), // 3-5 min
                    track = i,
                    albumId = album.id,
                    artistId = album.artistId
                )
            )
        }

        albumSongs.clear()
        albumSongs.addAll(sampleSongs)
        songAdapter.updateSongs(albumSongs)
    }

    private fun loadAlbumCover(album: Album) {
        if (album.coverArt != null && musicRepository.serverUrl != null) {
            try {
                val (username, token, salt) = musicRepository.getAuthParams()
                val coverUrl = ImageLoader.buildCoverArtUrl(
                    musicRepository.serverUrl!!,
                    album.coverArt!!,
                    username,
                    token,
                    salt,
                    500 // Tamaño más grande para la imagen principal
                )

                // Usar ImageLoader optimizado para la imagen del álbum
                ImageLoader.loadAlbumCoverForFragment(
                    this,
                    coverUrl,
                    onSuccess = { bitmap ->
                        if (isAdded && _binding != null) {
                            // Aplicar un fade-in suave al establecer la imagen
                            binding.ivAlbumCoverLarge.alpha = 0f
                            binding.ivAlbumCoverLarge.setImageBitmap(bitmap)
                            binding.ivAlbumCoverLarge.animate().alpha(1f).setDuration(250).start()
                            setStaticBackground(bitmap)
                        }
                    },
                    onError = {
                        if (isAdded && _binding != null) {
                            binding.ivAlbumCoverLarge.alpha = 1f
                            binding.ivAlbumCoverLarge.setImageResource(R.drawable.ic_album_placeholder)
                            setStaticBackground(null)
                        }
                    }
                )
            } catch (e: Exception) {
                if (isAdded && _binding != null) {
                    binding.ivAlbumCoverLarge.alpha = 1f
                    binding.ivAlbumCoverLarge.setImageResource(R.drawable.ic_album_placeholder)
                    setStaticBackground(null)
                }
            }
        } else {
            if (isAdded && _binding != null) {
                binding.ivAlbumCoverLarge.alpha = 1f
                binding.ivAlbumCoverLarge.setImageResource(R.drawable.ic_album_placeholder)
                setStaticBackground(null)
            }
        }
    }

    private fun setStaticBackground(bitmap: android.graphics.Bitmap? = null) {
        if (!isAdded || _binding == null) return

        // Aplicar color estático independientemente del bitmap
        val staticColor = 0xFF121212.toInt()
        binding.gradientBackground.setBackgroundColor(staticColor)
        binding.collapsingToolbar.setContentScrimColor(staticColor)
        binding.collapsingToolbar.setStatusBarScrimColor(staticColor)

        // Usar iconos blancos para el toolbar (apropiado para fondo oscuro)
        binding.toolbar.navigationIcon?.setTint(android.graphics.Color.WHITE)

        // Use centralized status bar color utility
        StatusBarUtils.setStatusBarColor(this)
    }

    private fun playAlbum() {
        if (albumSongs.isNotEmpty()) {
            val service = musicService
            val startIndex = if (service?.getShuffleEnabled() == true && albumSongs.size > 1) {
                Random.nextInt(albumSongs.size)
            } else 0
            service?.playQueue(
                albumSongs,
                startIndex,
                com.arantec.castafiore.service.MusicService.PlaybackSource(
                    com.arantec.castafiore.service.MusicService.SourceType.ALBUM,
                    currentAlbum?.id,
                    currentAlbum?.name
                )
            )
        }
    }

    private fun playSongFromAlbum(position: Int) {
        musicService?.playQueue(
            albumSongs,
            position,
            com.arantec.castafiore.service.MusicService.PlaybackSource(
                com.arantec.castafiore.service.MusicService.SourceType.ALBUM,
                currentAlbum?.id,
                currentAlbum?.name
            )
        )
    }

    private fun showSongOptions(song: Song) {
        val bottomSheet = com.arantec.castafiore.ui.dialogs.SongOptionsBottomSheet.newInstance(song, false)
            .setOnDownloadClickListener { selectedSong ->
                // Implementar descarga de canción con el nuevo sistema
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
                playlistSelector.show(childFragmentManager, "PlaylistSelectorBottomSheet")
            }
            .setOnViewArtistClickListener { selectedSong ->
                // Navegar a la vista del artista
                navigateToArtist(selectedSong.artistId ?: currentAlbum?.artistId ?: "", selectedSong.artist)
            }
            .setOnShareClickListener { selectedSong ->
                shareSong(selectedSong)
            }
            .setOnSongInfoClickListener { selectedSong ->
                showSongInfo(selectedSong)
            }
            .hideViewAlbumOption() // Ocultar "Ver álbum" ya que estamos viendo este álbum

        bottomSheet.show(childFragmentManager, "SongOptionsBottomSheet")
    }

    private fun showAlbumOptions() {
        currentAlbum?.let { album ->
            val bottomSheet = com.arantec.castafiore.ui.dialogs.AlbumOptionsBottomSheet.newInstance(album)
                .setOnDownloadClickListener { selectedAlbum ->
                    downloadAlbum(selectedAlbum)
                }
                .setOnAddToFavoritesClickListener { selectedAlbum ->
                    toggleFavorite()
                }
                .setOnAddToQueueClickListener { selectedAlbum ->
                    addAlbumToQueue(selectedAlbum)
                }
                .setOnShareClickListener { selectedAlbum ->
                    shareAlbum(selectedAlbum)
                }
                .setOnAlbumInfoClickListener { selectedAlbum ->
                    showAlbumInfo(selectedAlbum)
                }

            bottomSheet.show(childFragmentManager, "AlbumOptionsBottomSheet")
        }
    }

    /**
     * Descarga todas las canciones del álbum
     */
    private fun downloadAlbum(album: Album) {
        if (albumSongs.isEmpty()) {
            android.widget.Toast.makeText(requireContext(), "No hay canciones para descargar", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        val downloadManager = com.arantec.castafiore.data.download.SongDownloadManager.getInstance(requireContext())

        // Verificar cuántas canciones ya están descargadas o descargándose
        val alreadyDownloaded = albumSongs.count { downloadManager.isSongDownloaded(it.id) }
        val currentlyDownloading = albumSongs.count { downloadManager.isSongDownloading(it.id) }
        val toDownload = albumSongs.filter {
            !downloadManager.isSongDownloaded(it.id) && !downloadManager.isSongDownloading(it.id)
        }

        when {
            alreadyDownloaded == albumSongs.size -> {
                android.widget.Toast.makeText(requireContext(), "El álbum ya está completamente descargado", android.widget.Toast.LENGTH_SHORT).show()
            }
            toDownload.isEmpty() && currentlyDownloading > 0 -> {
                android.widget.Toast.makeText(requireContext(), "El álbum se está descargando ($currentlyDownloading canciones pendientes)", android.widget.Toast.LENGTH_SHORT).show()
            }
            else -> {
                // Iniciar descarga de las canciones pendientes
                toDownload.forEach { song ->
                    downloadManager.downloadSong(song)
                }

                val message = when {
                    alreadyDownloaded > 0 -> "Descargando ${toDownload.size} canciones restantes del álbum"
                    else -> "Descargando álbum completo (${toDownload.size} canciones)"
                }
                android.widget.Toast.makeText(requireContext(), message, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Agrega todas las canciones del álbum a la cola de reproducción
     */
    private fun addAlbumToQueue(album: Album) {
        if (albumSongs.isEmpty()) {
            android.widget.Toast.makeText(requireContext(), "No hay canciones para agregar a la cola", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        musicService?.let { service ->
            albumSongs.forEach { song ->
                service.addToQueue(song)
            }
            android.widget.Toast.makeText(requireContext(), "Álbum agregado a la cola (${albumSongs.size} canciones)", android.widget.Toast.LENGTH_SHORT).show()
        } ?: run {
            android.widget.Toast.makeText(requireContext(), "Servicio de música no disponible", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Comparte información del álbum
     */
    private fun shareAlbum(album: Album) {
        val shareText = "Escucha ${album.name} de ${album.artist}"
        val shareIntent = android.content.Intent().apply {
            action = android.content.Intent.ACTION_SEND
            putExtra(android.content.Intent.EXTRA_TEXT, shareText)
            type = "text/plain"
        }

        try {
            startActivity(android.content.Intent.createChooser(shareIntent, "Compartir álbum"))
        } catch (e: Exception) {
            android.widget.Toast.makeText(requireContext(), "No se pudo compartir el álbum", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Muestra información detallada del álbum
     */
    private fun showAlbumInfo(album: Album) {
        val infoMessage = buildString {
            append("Álbum: ${album.name}\n")
            append("Artista: ${album.artist}\n")
            if (album.year != null) append("Año: ${album.year}\n")
            append("Canciones: ${album.songCount}\n")
            append("Duración: ${formatAlbumDuration(album.duration)}\n")
            if (album.genre != null) append("Género: ${album.genre}\n")
        }

        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Información del Álbum")
            .setMessage(infoMessage)
            .setPositiveButton("Cerrar") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun formatAlbumDuration(seconds: Int): String {
        val minutes = seconds / 60
        val remainingSeconds = seconds % 60

        return if (minutes > 0) {
            "${minutes}m ${remainingSeconds}s"
        } else {
            "${remainingSeconds}s"
        }
    }

    private fun formatSongDuration(seconds: Int): String {
        val minutes = seconds / 60
        val remainingSeconds = seconds % 60
        return String.format("%d:%02d", minutes, remainingSeconds)
    }

    /**
     * Navega a la página del artista
     */
    private fun navigateToArtist(artistId: String, artistName: String) {
        try {
            // Usar Navigation Component en lugar de transacciones manuales
            val action = AlbumDetailFragmentDirections.actionAlbumDetailToArtistDetail(artistId, artistName)
            findNavController().navigate(action)
        } catch (e: Exception) {
            android.widget.Toast.makeText(requireContext(), "Error al navegar al artista", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // Métodos para manejar favoritos
    private fun toggleFavorite() {
        currentAlbum?.let { album ->
            lifecycleScope.launch {
                try {
                    val result = if (isFavorited) {
                        musicRepository.unstarAlbum(album.id)
                    } else {
                        musicRepository.starAlbum(album.id)
                    }

                    result.fold(
                        onSuccess = {
                            isFavorited = !isFavorited
                            updateFavoriteButton()

                            // Mostrar mensaje de confirmación
                            val message = if (isFavorited) {
                                "Álbum agregado a favoritos"
                            } else {
                                "Álbum removido de favoritos"
                            }
                            android.widget.Toast.makeText(requireContext(), message, android.widget.Toast.LENGTH_SHORT).show()
                        },
                        onFailure = { error ->
                            android.widget.Toast.makeText(
                                requireContext(),
                                "Error al actualizar favoritos: ${error.message}",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    )
                } catch (e: Exception) {
                    android.widget.Toast.makeText(
                        requireContext(),
                        "Error: ${e.message}",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun updateFavoriteButton() {
        if (!isAdded || _binding == null) return

        if (isFavorited) {
            binding.btnFavorite.setImageResource(R.drawable.ic_favorite)
            binding.btnFavorite.setColorFilter(android.graphics.Color.parseColor("#FF2D55")) // Color principal
        } else {
            binding.btnFavorite.setImageResource(R.drawable.ic_favorite_border)
            binding.btnFavorite.setColorFilter(android.graphics.Color.parseColor("#B3FFFFFF")) // Color texto secundario
        }
    }

    private fun checkFavoriteStatus() {
        currentAlbum?.let { album ->
            lifecycleScope.launch {
                try {
                    musicRepository.isAlbumStarred(album.id).fold(
                        onSuccess = { isStarred ->
                            isFavorited = isStarred
                            updateFavoriteButton()
                        },
                        onFailure = {
                            // Si falla la verificación, asumir que no está marcado como favorito
                            isFavorited = false
                            updateFavoriteButton()
                        }
                    )
                } catch (e: Exception) {
                    isFavorited = false
                    updateFavoriteButton()
                }
            }
        }
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
        if (chooser.resolveActivity(requireContext().packageManager) != null) {
            startActivity(chooser)
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
                    .transition(com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade(200))
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



    /**
     * Actualiza el estado de la canción en reproducción al conectarse al servicio
     */
    private fun updateCurrentPlayingSong() {
        // Verificar que el songAdapter esté inicializado antes de usarlo
        if (!::songAdapter.isInitialized) {
            return
        }

        musicService?.getCurrentSong()?.let { currentSong ->
            // Solo actualizar si la canción pertenece a este álbum
            if (isCurrentAlbumPlaying()) {
                songAdapter.setPlayingSong(currentSong.id)
            } else {
                // Si no es de este álbum, limpiar el estado
                songAdapter.setPlayingSong(null)
            }
        } ?: run {
            // Si no hay canción reproduciéndose, limpiar el estado
            songAdapter.setPlayingSong(null)
        }
    }

    // Lifecycle methods
    override fun onResume() {
        super.onResume()
        // Ensure consistent status bar color on resume
        StatusBarUtils.setStatusBarColor(this)
    }

    override fun onStart() {
        super.onStart()
        // Conectar al servicio cuando el fragment sea visible
        bindMusicService()
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

    companion object {
        fun newInstance(album: Album): AlbumDetailFragment {
            val fragment = AlbumDetailFragment()
            val args = Bundle()
            args.putParcelable("album", album)
            fragment.arguments = args
            return fragment
        }
    }

    private fun bindMusicService() {
        if (!isBound) {
            val intent = Intent(requireContext(), MusicService::class.java)
            requireContext().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    private fun unbindMusicService() {
        if (isBound) {
            // Remover listeners específicos de este fragment
            cleanupListeners()
            requireContext().unbindService(serviceConnection)
            isBound = false
            musicService = null
        }
    }
}
