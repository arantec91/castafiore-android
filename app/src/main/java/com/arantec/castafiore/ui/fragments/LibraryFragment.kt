package com.arantec.castafiore.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.arantec.castafiore.R
import com.arantec.castafiore.databinding.FragmentLibraryBinding
import com.arantec.castafiore.ui.adapters.LibraryAdapter
import com.arantec.castafiore.data.models.LibraryItem
import com.arantec.castafiore.data.models.LibraryItemType
import com.arantec.castafiore.data.models.Album
import com.arantec.castafiore.data.models.Playlist
import com.arantec.castafiore.data.repository.MusicRepository
import com.arantec.castafiore.utils.ImageLoader
import com.arantec.castafiore.utils.StatusBarUtils
import com.google.android.material.chip.Chip
import com.arantec.castafiore.data.download.SongDownloadManager
import java.io.File

class LibraryFragment : Fragment() {

    private var _binding: FragmentLibraryBinding? = null
    private val binding get() = _binding!!

    private lateinit var libraryAdapter: LibraryAdapter
    private lateinit var musicRepository: MusicRepository
    private var currentFilter = "all"

    // Listas de datos separadas por tipo
    private var allItems = mutableListOf<LibraryItem>()
    private var playlists = mutableListOf<LibraryItem>()
    private var albums = mutableListOf<LibraryItem>()
    private var artists = mutableListOf<LibraryItem>()

    // Mapas para mantener referencias a los objetos completos
    private var albumsMap = mutableMapOf<String, Album>()
    private var playlistsMap = mutableMapOf<String, Playlist>()

    // Descargas
    private var downloads = mutableListOf<LibraryItem>()
    private var downloadsComputed = false
    private var downloadedAlbumsCache = mutableMapOf<String, Boolean>()
    private var downloadedPlaylistsCache = mutableMapOf<String, Boolean>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLibraryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        musicRepository = MusicRepository.getInstance(requireContext())

        // Asegurar que el contenido no se dibuje bajo la status bar, sin añadir insets inferiores
        StatusBarUtils.applyStatusBarTopPadding(binding.root)

        // Restaurar filtro si viene de estado guardado
        savedInstanceState?.getString("currentFilter")?.let { restored ->
            currentFilter = restored
        }

        setupRecyclerView()
        setupFilters()
        setupControls()

        // Asegurar que el chip resaltado corresponda al filtro actual
        applyCheckedChipFromFilter()

        loadInitialData()
    }

    private fun setupRecyclerView() {
        libraryAdapter = LibraryAdapter { item ->
            handleItemClick(item)
        }

        binding.rvLibraryItems.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = libraryAdapter
        }
    }

    private fun setupFilters() {
        // Configurar chips de filtros con manejo mejorado
        binding.chipAll.setOnClickListener {
            selectChip(binding.chipAll, "all")
        }

        binding.chipPlaylists.setOnClickListener {
            selectChip(binding.chipPlaylists, "playlists")
        }

        binding.chipAlbums.setOnClickListener {
            selectChip(binding.chipAlbums, "albums")
        }

        binding.chipArtists.setOnClickListener {
            selectChip(binding.chipArtists, "artists")
        }

        binding.chipDownloads.setOnClickListener {
            selectChip(binding.chipDownloads, "downloads")
        }
    }

    private fun selectChip(selectedChip: Chip, filter: String) {
        // Desmarcar todos los chips sin disparar listeners
        val chips = listOf(binding.chipAll, binding.chipPlaylists, binding.chipAlbums, binding.chipArtists, binding.chipDownloads)
        chips.forEach { chip ->
            chip.isChecked = false
        }

        // Marcar el chip seleccionado
        selectedChip.isChecked = true

        // Aplicar filtro
        currentFilter = filter
        filterContent()

        // Actualizar colores visuales
        updateChipColors()
    }

    private fun updateChipColors() {
        val primaryColor = androidx.core.content.ContextCompat.getColor(requireContext(), R.color.primary)
        val onPrimaryColor = androidx.core.content.ContextCompat.getColor(requireContext(), R.color.on_primary)
        val onSurfaceColor = androidx.core.content.ContextCompat.getColor(requireContext(), R.color.on_surface)
        val outlineColor = androidx.core.content.ContextCompat.getColor(requireContext(), R.color.outline)

        val chips = listOf(
            binding.chipAll,
            binding.chipPlaylists,
            binding.chipAlbums,
            binding.chipArtists,
            binding.chipDownloads
        )

        chips.forEach { chip ->
            if (chip.isChecked) {
                // Selected: filled primary background, white text, no stroke
                chip.chipBackgroundColor = android.content.res.ColorStateList.valueOf(primaryColor)
                chip.setTextColor(onPrimaryColor)
                chip.chipStrokeColor = android.content.res.ColorStateList.valueOf(primaryColor)
                chip.chipStrokeWidth = 0f
                chip.isChipIconVisible = false
            } else {
                // Unselected: transparent background, on-surface text, outline stroke
                chip.chipBackgroundColor = android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
                chip.setTextColor(onSurfaceColor)
                chip.chipStrokeColor = android.content.res.ColorStateList.valueOf(outlineColor)
                chip.chipStrokeWidth = 1f
                chip.isChipIconVisible = false
            }
        }
    }

    private fun applyCheckedChipFromFilter() {
        // Establecer el checked real según el filtro actual y luego pintar
        val isAll = currentFilter == "all"
        val isPlaylists = currentFilter == "playlists"
        val isAlbums = currentFilter == "albums"
        val isArtists = currentFilter == "artists"
        val isDownloads = currentFilter == "downloads"

        binding.chipAll.isChecked = isAll
        binding.chipPlaylists.isChecked = isPlaylists
        binding.chipAlbums.isChecked = isAlbums
        binding.chipArtists.isChecked = isArtists
        binding.chipDownloads.isChecked = isDownloads

        updateChipColors()
    }

    private fun setupControls() {
        binding.tvSortBy.setOnClickListener {
            // TODO: Mostrar opciones de ordenamiento
        }

        binding.btnViewMode.setOnClickListener {
            // TODO: Cambiar entre vista lista y cuadrícula
        }

        binding.btnCreatePlaylist.setOnClickListener {
            // TODO: Crear nueva playlist
        }
    }

    private fun loadInitialData() {
        showLoading(true)

        lifecycleScope.launch {
            try {
                // Cargar datos por separado
                loadPlaylists()
                loadAlbums()
                loadArtists()

                // Una vez cargado todo, construir lista completa y mostrar contenido
                buildAllItemsList()
                filterContent()

            } catch (e: Exception) {
                showError("Error al cargar la biblioteca: ${e.message}")
            } finally {
                showLoading(false)
            }
        }
    }

    private suspend fun loadPlaylists() {
        try {
            playlists.clear()

            // Siempre agregar "Canciones que te gustan" primero
            playlists.add(
                LibraryItem(
                    id = "liked_songs",
                    title = "Canciones que te gustan",
                    subtitle = "Playlist • Tus favoritas",
                    imageUrl = null,
                    type = LibraryItemType.LIKED_SONGS
                )
            )

            // Luego cargar las playlists de la API
            val result = musicRepository.getPlaylists()
            result.onSuccess { playlistsFromApi ->
                playlistsFromApi.forEach { playlist ->
                    val subtitle = "${playlist.songCount ?: 0} canciones"

                    // Construir URL de imagen si la playlist tiene coverArt
                    var imageUrl: String? = null
                    if (playlist.coverArt != null) {
                        val (username, token, salt) = musicRepository.getAuthParams()
                        imageUrl = playlist.getCoverArtUrl(
                            musicRepository.serverUrl!!,
                            username,
                            token,
                            salt,
                            200
                        )
                    }

                    playlists.add(
                        LibraryItem(
                            id = playlist.id,
                            title = playlist.name,
                            subtitle = "Playlist • $subtitle",
                            imageUrl = imageUrl,
                            type = LibraryItemType.PLAYLIST
                        )
                    )

                    // Agregar al mapa de playlists
                    playlistsMap[playlist.id] = playlist
                }
            }.onFailure {
                // Si falla la API, al menos tenemos "Canciones que te gustan"
            }
        } catch (e: Exception) {
            // En caso de error, solo mantener "Canciones que te gustan"
            playlists.clear()
            playlists.add(
                LibraryItem(
                    id = "liked_songs",
                    title = "Canciones que te gustan",
                    subtitle = "Playlist • Tus favoritas",
                    imageUrl = null,
                    type = LibraryItemType.LIKED_SONGS
                )
            )
        }
    }

    private suspend fun loadAlbums() {
        try {
            val result = musicRepository.getStarredAlbums()
            result.onSuccess { albumsFromApi ->
                albums.clear()

                albumsFromApi.forEach { album ->
                    val (username, token, salt) = musicRepository.getAuthParams()
                    val imageUrl = ImageLoader.buildCoverArtUrl(
                        musicRepository.serverUrl!!,
                        album.id,
                        username,
                        token,
                        salt,
                        200
                    )

                    albums.add(
                        LibraryItem(
                            id = album.id,
                            title = album.name,
                            subtitle = "Álbum • ${album.artist}",
                            imageUrl = imageUrl,
                            type = LibraryItemType.ALBUM
                        )
                    )

                    // Agregar al mapa de álbumes
                    albumsMap[album.id] = album
                }
            }.onFailure {
                albums.clear()
            }
        } catch (e: Exception) {
            albums.clear()
        }
    }

    private suspend fun loadArtists() {
        try {
            val result = musicRepository.getStarredArtists()
            result.onSuccess { artistsFromApi ->
                artists.clear()

                artistsFromApi.forEach { artist ->
                    val (username, token, salt) = musicRepository.getAuthParams()
                    val imageUrl = ImageLoader.buildArtistImageUrl(
                        musicRepository.serverUrl!!,
                        artist.id,
                        username,
                        token,
                        salt,
                        200
                    )

                    // Construir subtítulo basado en si albumCount está disponible
                    val subtitle = if (artist.albumCount != null && artist.albumCount > 0) {
                        "Artista • ${artist.albumCount} álbumes"
                    } else {
                        "Artista"
                    }

                    artists.add(
                        LibraryItem(
                            id = artist.id,
                            title = artist.name,
                            subtitle = subtitle,
                            imageUrl = imageUrl,
                            type = LibraryItemType.ARTIST
                        )
                    )
                }
            }.onFailure {
                artists.clear()
            }
        } catch (e: Exception) {
            artists.clear()
        }
    }

    private fun buildAllItemsList() {
        allItems.clear()

        // Agregar en orden: playlists, álbumes, artistas
        allItems.addAll(playlists)
        allItems.addAll(albums)
        allItems.addAll(artists)
    }

    private fun filterContent() {
        if (currentFilter == "downloads") {
            filterDownloads()
            return
        }

        val filteredItems = when (currentFilter) {
            "all" -> allItems.toList()
            "playlists" -> playlists.toList()
            "albums" -> albums.toList()
            "artists" -> artists.toList()
            else -> allItems.toList()
        }

        if (filteredItems.isEmpty()) {
            showEmptyState()
        } else {
            hideEmptyState()
            libraryAdapter.updateItems(filteredItems)
        }
    }

    private fun filterDownloads() {
        lifecycleScope.launch {
            showLoading(true)
            try {
                computeDownloadsIfNeeded(force = true)
                val filteredItems = downloads.toList()
                if (filteredItems.isEmpty()) {
                    showEmptyState()
                } else {
                    hideEmptyState()
                    libraryAdapter.updateItems(filteredItems)
                }
            } catch (e: Exception) {
                showError("Error al cargar descargados: ${e.message}")
            } finally {
                showLoading(false)
            }
        }
    }

    private suspend fun computeDownloadsIfNeeded(force: Boolean = false) {
        if (downloadsComputed && !force) return
        downloads.clear()
        if (force) {
            downloadedAlbumsCache.clear()
            downloadedPlaylistsCache.clear()
            downloadsComputed = false
        }

        val dm = SongDownloadManager.getInstance(requireContext())

        // Agregar Playlists descargadas (excluyendo 'liked_songs')
        for (pl in playlistsMap.values) {
            if (pl.id == "liked_songs") continue
            val cached = downloadedPlaylistsCache[pl.id]
            val isDownloaded = if (cached != null) {
                cached
            } else {
                var fullyDownloaded = false
                musicRepository.getPlaylistSongs(pl.id).onSuccess { songs ->
                    fullyDownloaded = songs.isNotEmpty() && songs.all { song ->
                        val path = dm.createDownloadPath(song)
                        File(path).exists()
                    }
                }
                downloadedPlaylistsCache[pl.id] = fullyDownloaded
                fullyDownloaded
            }

            if (isDownloaded) {
                // Buscar el LibraryItem ya preparado para la playlist
                playlists.find { it.id == pl.id }?.let { item ->
                    downloads.add(item)
                } ?: run {
                    // Fallback simple
                    downloads.add(
                        LibraryItem(
                            id = pl.id,
                            title = pl.name,
                            subtitle = "Playlist",
                            imageUrl = null,
                            type = LibraryItemType.PLAYLIST
                        )
                    )
                }
            }
        }

        // Agregar Álbumes descargados
        for (al in albumsMap.values) {
            val cached = downloadedAlbumsCache[al.id]
            val isDownloaded = if (cached != null) {
                cached
            } else {
                var fullyDownloaded = false
                musicRepository.getAlbumSongs(al.id).onSuccess { songs ->
                    fullyDownloaded = songs.isNotEmpty() && songs.all { song ->
                        val path = dm.createDownloadPath(song)
                        File(path).exists()
                    }
                }
                downloadedAlbumsCache[al.id] = fullyDownloaded
                fullyDownloaded
            }

            if (isDownloaded) {
                albums.find { it.id == al.id }?.let { item ->
                    downloads.add(item)
                } ?: run {
                    val (username, token, salt) = musicRepository.getAuthParams()
                    val imageUrl = ImageLoader.buildCoverArtUrl(
                        musicRepository.serverUrl!!,
                        al.id,
                        username,
                        token,
                        salt,
                        200
                    )
                    downloads.add(
                        LibraryItem(
                            id = al.id,
                            title = al.name,
                            subtitle = "Álbum • ${al.artist}",
                            imageUrl = imageUrl,
                            type = LibraryItemType.ALBUM
                        )
                    )
                }
            }
        }

        // Orden opcional: mantener orden por tipo como en allItems
        // Primero playlists, luego álbumes
        downloads.sortWith(compareBy({ it.type != LibraryItemType.PLAYLIST }, { it.title.lowercase() }))

        downloadsComputed = true
    }

    private fun handleItemClick(item: LibraryItem) {
        // Congelar el RecyclerView y scroll antes de navegar para evitar efectos visuales
        binding.rvLibraryItems.isNestedScrollingEnabled = false
        binding.scrollFilters.isNestedScrollingEnabled = false

        // Forzar que los views se "asienten" antes de la transición
        binding.root.post {
            when (item.type) {
                LibraryItemType.PLAYLIST -> {
                    // Navegar a playlist específica
                    val playlist = playlistsMap[item.id]
                    if (playlist != null) {
                        val bundle = Bundle().apply {
                            putString("playlistId", playlist.id)
                            putString("playlistName", playlist.name)
                        }
                        findNavController().navigate(R.id.action_library_to_playlistDetail, bundle)
                    }
                }
                LibraryItemType.ARTIST -> {
                    val bundle = Bundle().apply {
                        putString("artistId", item.id)
                        putString("artistName", item.title)
                    }
                    // Usar la acción definida en nav_graph para asegurar que las animaciones se ejecuten
                    findNavController().navigate(R.id.action_library_to_artistDetail, bundle)
                }
                LibraryItemType.ALBUM -> {
                    // Navegar a detalle de álbum usando la acción del nav_graph
                    val album = albumsMap[item.id]
                    if (album != null) {
                        val bundle = Bundle().apply {
                            putParcelable("album", album)
                        }
                        // Usar la acción definida en nav_graph para asegurar que las animaciones se ejecuten
                        findNavController().navigate(R.id.action_library_to_albumDetail, bundle)
                    }
                }
                LibraryItemType.LIKED_SONGS -> {
                    // Navegar a canciones favoritas con animaciones definidas
                    findNavController().navigate(R.id.action_library_to_favorites)
                }
            }
        }
    }

    private fun showLoading(show: Boolean) {
        binding.progressBar.visibility = if (show) View.VISIBLE else View.GONE
        binding.rvLibraryItems.visibility = if (show) View.GONE else View.VISIBLE
    }

    private fun showError(message: String) {
        binding.layoutEmpty.visibility = View.VISIBLE
        binding.rvLibraryItems.visibility = View.GONE
        // TODO: Mostrar mensaje de error específico
    }

    private fun showEmptyState() {
        binding.rvLibraryItems.visibility = View.GONE
        binding.layoutEmpty.visibility = View.VISIBLE
    }

    private fun hideEmptyState() {
        binding.layoutEmpty.visibility = View.GONE
        binding.rvLibraryItems.visibility = View.VISIBLE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onResume() {
        super.onResume()
        // Aplicar el color estático consistente de la app
        StatusBarUtils.setStatusBarColor(this)

        // Restaurar el comportamiento de scroll normal cuando regresemos al fragmento
        if (_binding != null) {
            binding.rvLibraryItems.isNestedScrollingEnabled = true
            binding.scrollFilters.isNestedScrollingEnabled = true
            // Reaplicar colores de chips en caso de que el estado visual haya sido alterado
            applyCheckedChipFromFilter()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("currentFilter", currentFilter)
    }
}
