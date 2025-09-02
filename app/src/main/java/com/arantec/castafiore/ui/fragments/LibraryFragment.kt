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
import com.arantec.castafiore.utils.PlaylistFavoritesManager
import java.io.File
import com.arantec.castafiore.ui.helpers.HasContentState
import com.arantec.castafiore.ui.helpers.LoadingHost
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

class LibraryFragment : Fragment(), HasContentState {

    private var _binding: FragmentLibraryBinding? = null
    private val binding get() = _binding!!

    private lateinit var libraryAdapter: LibraryAdapter
    private lateinit var musicRepository: MusicRepository
    private var currentFilter = "all"

    // Listas de datos separadas por tipo
    private var allItems = mutableListOf<LibraryItem>()
    private var playlists: List<LibraryItem> = emptyList()
    private var albums = mutableListOf<LibraryItem>()
    private var artists = mutableListOf<LibraryItem>()

    // Mapas para mantener referencias a los objetos completos
    private var albumsMap = mutableMapOf<String, Album>()
    private var playlistsMap = mutableMapOf<String, Playlist>()

    // Mutex para evitar condiciones de carrera al actualizar playlists
    private val playlistsMutex = Mutex()

    // Descargas
    private var downloads = mutableListOf<LibraryItem>()
    private var downloadsComputed = false
    private var downloadedAlbumsCache = mutableMapOf<String, Boolean>()
    private var downloadedPlaylistsCache = mutableMapOf<String, Boolean>()
    // Estado para evitar parpadeo y actualizaciones redundantes
    private var lastDownloadsVisible: Boolean = false
    private var lastDownloadedIds: Set<String> = emptySet()

    // Optimización: evitar recomputes concurrentes y duplicar llamadas HTTP
    private val downloadsMutex = Mutex()
    private val playlistCheckMutexes = mutableMapOf<String, Mutex>()
    private fun playlistMutex(id: String) = playlistCheckMutexes.getOrPut(id) { Mutex() }

    // Fuente única para listas de playlists (evita llamar a la API dos veces en el arranque)
    private var allPlaylistsRaw: List<Playlist> = emptyList()

    private fun loadingHost(): LoadingHost? = activity as? LoadingHost

    override fun hasContent(): Boolean {
        return this::libraryAdapter.isInitialized && libraryAdapter.itemCount > 0
    }

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

        // Asegurar que el adapter nuevo conozca los IDs descargados ya calculados
        if (lastDownloadedIds.isNotEmpty()) {
            libraryAdapter.updateDownloadedIds(lastDownloadedIds)
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
        val chips = listOf(
            binding.chipAll,
            binding.chipDownloads,
            binding.chipPlaylists,
            binding.chipAlbums,
            binding.chipArtists
        )
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
            binding.chipDownloads,
            binding.chipPlaylists,
            binding.chipAlbums,
            binding.chipArtists
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

        binding.actionLibraryToSearch.setOnClickListener {
            // Navegar al buscador
            findNavController().navigate(R.id.action_library_to_search)
        }
    }

    private fun loadInitialData() {
        // Only show local loader if we already have content (refresh behavior)
        if (hasContent()) {
            showLoading(true)
        } else {
            // Cold start without content: ensure global overlay is visible as fallback
            loadingHost()?.showGlobalLoading(true)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Load sections in parallel on IO to reduce wall time on slower devices
                val playlistsDeferred = async(Dispatchers.IO) { loadPlaylists() }
                val albumsDeferred = async(Dispatchers.IO) { loadAlbums() }
                val artistsDeferred = async(Dispatchers.IO) { loadArtists() }

                // Await all
                playlistsDeferred.await()
                albumsDeferred.await()
                artistsDeferred.await()

                // Build and render on main
                buildAllItemsList()
                filterContent()

                // Kick off downloads chip computation in background (IO) without blocking UI
                updateDownloadsChipVisibility(force = true)
            } catch (e: Exception) {
                showError("Error al cargar la biblioteca: ${e.message}")
            } finally {
                // Always hide global overlay after finishing initial load (success or error)
                loadingHost()?.showGlobalLoading(false)
                showLoading(false)
            }
        }
    }

    private suspend fun loadPlaylists() {
        try {
            // Construir listas locales para asignar de forma atómica y evitar duplicados por cargas concurrentes
            val newPlaylists = mutableListOf<LibraryItem>()
            val newPlaylistsMap = mutableMapOf<String, Playlist>()

            // Conjunto para evitar duplicados por ID (robusto ante respuestas repetidas de API)
            val seenPlaylistIds = mutableSetOf<String>()
            // Siempre agregar "Canciones que te gustan" primero
            newPlaylists.add(
                LibraryItem(
                    id = "liked_songs",
                    title = "Canciones que te gustan",
                    subtitle = "Playlist • Tus favoritas",
                    imageUrl = null,
                    type = LibraryItemType.LIKED_SONGS
                )
            )
            seenPlaylistIds.add("liked_songs")

            // Agregar acceso a Canciones descargadas (lista de canciones locales)
            newPlaylists.add(
                LibraryItem(
                    id = "downloads",
                    title = "Canciones descargadas",
                    subtitle = "Playlist • Offline",
                    imageUrl = null,
                    type = LibraryItemType.DOWNLOADS
                )
            )
            seenPlaylistIds.add("downloads")

            // Luego cargar las playlists de la API UNA SOLA VEZ
            val playlistsFromApi = musicRepository.getPlaylists().getOrElse { emptyList() }
            allPlaylistsRaw = playlistsFromApi

            // Preparar helpers reutilizables
            val favorites = PlaylistFavoritesManager.getFavorites(requireContext())

            // 1) Playlists privadas (siempre)
            playlistsFromApi.filter { !it.public }.forEach { playlist ->
                if (seenPlaylistIds.add(playlist.id)) {
                    val subtitle = "${playlist.songCount} canciones"

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

                    newPlaylists.add(
                        LibraryItem(
                            id = playlist.id,
                            title = playlist.name,
                            subtitle = "Playlist • $subtitle",
                            imageUrl = imageUrl,
                            type = LibraryItemType.PLAYLIST
                        )
                    )

                    // Agregar al mapa de playlists
                    newPlaylistsMap[playlist.id] = playlist
                }
            }

            // 2) Playlists públicas marcadas como favoritas: incluir aunque no estén descargadas
            playlistsFromApi.filter { it.public && favorites.contains(it.id) }.forEach { playlist ->
                if (seenPlaylistIds.add(playlist.id)) {
                    var imageUrl: String? = null
                    if (playlist.coverArt != null && musicRepository.serverUrl != null) {
                        val (u, t, s) = musicRepository.getAuthParams()
                        imageUrl = playlist.getCoverArtUrl(musicRepository.serverUrl!!, u, t, s, 200)
                    }
                    val subtitle = if (playlist.songCount > 0) "Playlist • ${playlist.songCount} canciones" else "Playlist"
                    newPlaylists.add(
                        LibraryItem(
                            id = playlist.id,
                            title = playlist.name,
                            subtitle = subtitle,
                            imageUrl = imageUrl,
                            type = LibraryItemType.PLAYLIST
                        )
                    )
                    // Añadir al mapa para navegación completa
                    newPlaylistsMap[playlist.id] = playlist
                }
            }

            // De-duplicar por seguridad antes de asignar
            val finalPlaylists = newPlaylists.distinctBy { it.id }

            // Asignación atómica protegida por mutex para evitar interleavings
            playlistsMutex.withLock {
                playlists = finalPlaylists
                playlistsMap.clear()
                playlistsMap.putAll(newPlaylistsMap)
            }
        } catch (_: Exception) {
            // En caso de error, solo mantener "Canciones que te gustan" y Descargas
            playlistsMutex.withLock {
                playlists = listOf(
                    LibraryItem(
                        id = "liked_songs",
                        title = "Canciones que te gustan",
                        subtitle = "Playlist • Tus favoritas",
                        imageUrl = null,
                        type = LibraryItemType.LIKED_SONGS
                    ),
                    LibraryItem(
                        id = "downloads",
                        title = "Canciones descargadas",
                        subtitle = "Playlist • Offline",
                        imageUrl = null,
                        type = LibraryItemType.DOWNLOADS
                    )
                )
                playlistsMap.clear()
            }
        }
    }

    // Chequeo coalescido: determina si una playlist está completamente descargada evitando llamadas duplicadas
    private suspend fun isPlaylistFullyDownloaded(playlistId: String): Boolean {
        // Cache rápida
        downloadedPlaylistsCache[playlistId]?.let { return it }

        // Asegurar que solo un hilo por ID haga la consulta HTTP/cómputo
        return playlistMutex(playlistId).withLock {
            // Comprobar nuevamente dentro de la sección crítica (double-checked)
            downloadedPlaylistsCache[playlistId]?.let { return@withLock it }

            val dm = SongDownloadManager.getInstance(requireContext())
            var fullyDownloaded = false
            withContext(Dispatchers.IO) {
                try {
                    musicRepository.getPlaylistSongs(playlistId).onSuccess { songs ->
                        fullyDownloaded = songs.isNotEmpty() && songs.all { song ->
                            val path = dm.createDownloadPath(song)
                            File(path).exists()
                        }
                    }
                } catch (_: Exception) {
                    fullyDownloaded = false
                }
            }

            downloadedPlaylistsCache[playlistId] = fullyDownloaded
            fullyDownloaded
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
        } catch (_: Exception) {
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
        } catch (_: Exception) {
            artists.clear()
        }
    }

    private fun buildAllItemsList() {
        allItems.clear()

        // Agregar en orden: playlists, álbumes, artistas (deduplicando defensivamente por ID)
        val uniquePlaylists = playlists.distinctBy { it.id }
        allItems.addAll(uniquePlaylists)
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
            "playlists" -> playlists.distinctBy { it.id }
            "albums" -> albums.toList()
            "artists" -> artists.toList()
            else -> allItems.toList()
        }

        if (filteredItems.isEmpty()) {
            showEmptyState()
        } else {
            hideEmptyState()
            libraryAdapter.updateItems(filteredItems)
            // Scroll to top after applying the filter
            binding.rvLibraryItems.post {
                (binding.rvLibraryItems.layoutManager as? LinearLayoutManager)
                    ?.scrollToPositionWithOffset(0, 0)
            }
        }
    }

    private fun filterDownloads() {
        viewLifecycleOwner.lifecycleScope.launch {
            // Ensure global overlay is not blocking interactions in this local-only compute
            loadingHost()?.showGlobalLoading(false)
            showLoading(true)
            try {
                // Heavy IO on background to avoid blocking main
                withContext(Dispatchers.IO) {
                    computeDownloadsIfNeeded(force = true)
                }
                // Informar al adapter qué IDs están descargados solo si cambia
                val newIds = downloads.map { it.id }.toSet()
                if (newIds != lastDownloadedIds) {
                    lastDownloadedIds = newIds
                    libraryAdapter.updateDownloadedIds(newIds)
                }
                val filteredItems = downloads.toList()
                if (filteredItems.isEmpty()) {
                    showEmptyState()
                } else {
                    hideEmptyState()
                    libraryAdapter.updateItems(filteredItems)
                    // Scroll to top after applying the filter
                    binding.rvLibraryItems.post {
                        (binding.rvLibraryItems.layoutManager as? LinearLayoutManager)
                            ?.scrollToPositionWithOffset(0, 0)
                    }
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

        // Serializar el cálculo de descargas para evitar trabajos concurrentes duplicados
        downloadsMutex.withLock {
            if (downloadsComputed && !force) return

            downloads.clear()
            if (force) {
                downloadedAlbumsCache.clear()
                downloadedPlaylistsCache.clear()
                downloadsComputed = false
            }

            val dm = SongDownloadManager.getInstance(requireContext())

            // Agregar Playlists descargadas (limitar a privadas o favoritas para evitar llamadas innecesarias)
            try {
                val favorites = PlaylistFavoritesManager.getFavorites(requireContext())
                // Reutilizar la lista ya descargada si existe, evitando otra llamada a la API
                val allPlaylists = if (allPlaylistsRaw.isNotEmpty()) allPlaylistsRaw else musicRepository.getPlaylists().getOrNull().orEmpty()
                val playlistsToCheck = allPlaylists.filter { pl ->
                    pl.id != "liked_songs" && (!pl.public || favorites.contains(pl.id))
                }
                for (pl in playlistsToCheck) {
                    if (!coroutineContext.isActive) return

                    val cached = downloadedPlaylistsCache[pl.id]
                    val isDownloaded = if (cached != null) {
                        cached
                    } else {
                        // Reutilizar chequeo coalescido (también cachea)
                        try {
                            isPlaylistFullyDownloaded(pl.id)
                        } catch (_: Exception) {
                            false
                        }
                    }

                    if (isDownloaded) {
                        // Try to reuse prepared LibraryItem from 'playlists' list (private ones)
                        val existing = playlists.find { it.id == pl.id }
                        if (existing != null) {
                            downloads.add(existing)
                        } else {
                            // Build a minimal LibraryItem for public playlists
                            val subtitle = if (pl.songCount > 0) "Playlist • ${pl.songCount} canciones" else "Playlist"
                            var imageUrl: String? = null
                            try {
                                if (pl.coverArt != null && musicRepository.serverUrl != null) {
                                    val (u, t, s) = musicRepository.getAuthParams()
                                    imageUrl = pl.getCoverArtUrl(musicRepository.serverUrl!!, u, t, s, 200)
                                }
                            } catch (_: Exception) { /* ignore */ }

                            downloads.add(
                                LibraryItem(
                                    id = pl.id,
                                    title = pl.name,
                                    subtitle = subtitle,
                                    imageUrl = imageUrl,
                                    type = LibraryItemType.PLAYLIST
                                )
                            )
                        }
                    }
                }
            } catch (_: Exception) {
                // ignore playlist download computation errors; keep others
            }

            // Agregar Álbumes descargados
            for (al in albumsMap.values) {
                if (!coroutineContext.isActive) return
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
                    albums.find { it.id == al.id }?.let { existingItem ->
                        downloads.add(existingItem)
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
            downloads.sortWith(compareBy({ it.type != LibraryItemType.PLAYLIST }, { it.title.lowercase() }))

            downloadsComputed = true
        }
    }

    // Helper: show/hide Downloads chip based on whether there are downloaded items
    private fun updateDownloadsChipVisibility(force: Boolean = false) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Compute on IO to avoid blocking main
                withContext(Dispatchers.IO) {
                    computeDownloadsIfNeeded(force = force)
                }
                if (!isAdded || _binding == null) return@launch

                val hasDownloads = downloads.isNotEmpty()
                val newIds = downloads.map { it.id }.toSet()

                // Siempre informar al adapter (el adapter puede ser nuevo tras recrear la vista)
                libraryAdapter.updateDownloadedIds(newIds)
                lastDownloadedIds = newIds

                // Reaplicar visibilidad siempre, ya que la vista pudo recrearse
                binding.chipDownloads.visibility = if (hasDownloads) View.VISIBLE else View.GONE
                lastDownloadsVisible = hasDownloads

                // If current filter is downloads but none available, fallback to 'all'
                if (!hasDownloads && currentFilter == "downloads") {
                    currentFilter = "all"
                    applyCheckedChipFromFilter()
                    filterContent()
                }
            } catch (_: Exception) {
                if (!isAdded || _binding == null) return@launch
                // On error, hide downloads chip to avoid broken navigation
                lastDownloadsVisible = false
                binding.chipDownloads.visibility = View.GONE
                if (currentFilter == "downloads") {
                    currentFilter = "all"
                    applyCheckedChipFromFilter()
                    filterContent()
                }
            }
        }
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
                    } else {
                        // Fallback: downloaded public playlist not present in playlists list
                        val bundle = Bundle().apply {
                            putString("playlistId", item.id)
                            putString("playlistName", item.title)
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
                LibraryItemType.DOWNLOADS -> {
                    // Navegar a Descargas (canciones locales)
                    findNavController().navigate(R.id.action_library_to_downloads)
                }
            }
        }
    }

    private fun showLoading(show: Boolean) {
        if (_binding == null) return
        // If we are showing the fragment-level loader, ensure the global overlay is not blocking
        if (show) loadingHost()?.showGlobalLoading(false)
        binding.progressBar.visibility = if (show) View.VISIBLE else View.GONE
        binding.rvLibraryItems.visibility = if (show) View.GONE else View.VISIBLE
    }

    @Suppress("UNUSED_PARAMETER")
    private fun showError(message: String) {
        if (_binding == null) return
        binding.layoutEmpty.visibility = View.VISIBLE
        binding.rvLibraryItems.visibility = View.GONE
        // TODO: Mostrar mensaje de error específico
    }

    private fun showEmptyState() {
        if (_binding == null) return
        binding.rvLibraryItems.visibility = View.GONE
        binding.layoutEmpty.visibility = View.VISIBLE
    }

    private fun hideEmptyState() {
        if (_binding == null) return
        binding.layoutEmpty.visibility = View.GONE
        binding.rvLibraryItems.visibility = View.VISIBLE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Safety: ensure global overlay isn't left visible when the view is destroyed
        loadingHost()?.showGlobalLoading(false)
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
            // Re-evaluar descargas por si cambiaron fuera de este fragmento (sin forzar si ya está calculado)
            updateDownloadsChipVisibility(force = false)

            // Refrescar listas para reflejar cambios de favoritos de playlists públicas
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    loadPlaylists()
                    buildAllItemsList()
                    filterContent()
                } catch (_: Exception) { /* ignore refresh errors */ }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("currentFilter", currentFilter)
    }
}
