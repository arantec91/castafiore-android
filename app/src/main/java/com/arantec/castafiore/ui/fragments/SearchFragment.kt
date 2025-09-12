package com.arantec.castafiore.ui.fragments

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.arantec.castafiore.R
import android.content.res.ColorStateList
import com.arantec.castafiore.data.models.Album
import com.arantec.castafiore.data.models.Artist
import com.arantec.castafiore.data.models.Song
import com.arantec.castafiore.data.models.Playlist
import com.arantec.castafiore.data.repository.MusicRepository
import com.arantec.castafiore.databinding.FragmentSearchBinding
import com.arantec.castafiore.ui.adapters.PublicPlaylistsGridAdapter
import com.arantec.castafiore.ui.adapters.SearchHistoryAdapter
import com.arantec.castafiore.ui.adapters.SearchResultsAdapter
import com.arantec.castafiore.ui.dialogs.PlaylistSelectorBottomSheet
import com.arantec.castafiore.ui.dialogs.SongOptionsBottomSheet
import com.arantec.castafiore.ui.helpers.HasContentState
import com.arantec.castafiore.ui.viewmodels.SearchViewModel
import com.arantec.castafiore.utils.StatusBarUtils
import com.arantec.castafiore.data.local.SearchHistoryManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import java.util.*
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import com.arantec.castafiore.service.MusicService
import java.text.Normalizer
import kotlin.math.max
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.GridLayoutManager
import androidx.navigation.fragment.findNavController

class SearchFragment : Fragment(), HasContentState {

    companion object {
        private const val SEARCH_DELAY_MS = 350L
    }

    private var _binding: FragmentSearchBinding? = null
    private val binding get() = _binding!!

    private lateinit var musicRepository: MusicRepository
    private lateinit var searchAdapter: SearchResultsAdapter
    private val viewModel: SearchViewModel by viewModels()
    private lateinit var publicPlaylistsAdapter: PublicPlaylistsGridAdapter
    private lateinit var historyAdapter: SearchHistoryAdapter

    // Estado de búsqueda
    private var searchJob: Job? = null
    private var currentSearchQuery = ""
    private var isVoiceSearchActive = false
    private var currentViewMode = ViewMode.RESULTS
    private var keepHistoryVisible: Boolean = false
    // Suppress history during first render to avoid flicker
    private var isInitializing: Boolean = true

    // Estado de filtros (solo resultados)
    private var filterArtists = false
    private var filterAlbums = false
    private var filterSongs = false

    private var authUsername: String? = null
    private var authToken: String? = null
    private var authSalt: String? = null
    private var serverUrl: String? = null

    // Servicio de música
    private var musicService: MusicService? = null
    private var isBound = false
    private var pendingPlaySong: Song? = null
    // New: pending queue for batch playback when service is not yet bound
    private var pendingPlayQueue: List<Song>? = null

    // Listener para cambios de canción y resaltar en historial
    private val historySongChangeListener: (Song?) -> Unit = { song ->
        if (this::historyAdapter.isInitialized) {
            historyAdapter.setPlayingSongId(song?.id)
        }
    }

    private val voiceSearchLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        handleVoiceSearchResult(result.resultCode, result.data)
    }

    enum class ViewMode {
        SUGGESTIONS,
        HISTORY,
        RESULTS
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSearchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        musicRepository = MusicRepository.getInstance(requireContext())
        serverUrl = musicRepository.serverUrl

        // Asegurar que el contenido no se dibuje bajo la status bar, sin añadir insets inferiores
        StatusBarUtils.applyStatusBarTopPadding(binding.root)

        setupUI()
        setupRecycler()
        setupPublicPlaylistsGrid()
        setupHistoryRecycler()
        setupSearchFunctionality()
        setupFilterChips()

        // Cargar favoritos iniciales
        loadFavorites()

        // Restaurar resultados si existen en ViewModel para evitar parpadeo
        ensureAuth()
        val cachedItems = viewModel.items.value.orEmpty()
        val cachedQuery = viewModel.query.value ?: ""
        if (cachedItems.isNotEmpty()) {
            searchAdapter.updateAuth(
                serverUrl = serverUrl ?: "",
                username = authUsername ?: "",
                token = authToken ?: "",
                salt = authSalt ?: ""
            )
            // Pasar estado de favoritos
            searchAdapter.updateFavorites(favoriteSongIds, favoriteAlbumIds, favoriteArtistIds)
            // Build display based on current filters (no headers when filtering)
            searchAdapter.submitData(buildDisplayItems())
            showResults()
            // Rellenar el texto sin disparar el watcher
            suppressTextWatcher = true
            binding.etSearch.setText(cachedQuery)
            binding.etSearch.setSelection(cachedQuery.length)
            suppressTextWatcher = false
            // En modo resultados, ocultar grid
            binding.rvPublicPlaylists.isGone = true
            // Mostrar chips si hay texto
            binding.chipGroupFilters.isVisible = cachedQuery.isNotBlank()
            updateChipStyles()
        } else {
            if (cachedQuery.isNotBlank()) {
                if (hasContent()) {
                    showLoading(true)
                }
                performSearch(cachedQuery)
                binding.chipGroupFilters.isVisible = true
                updateChipStyles()
            } else {
                // Ensure search field doesn't steal focus initially to avoid triggering history
                binding.etSearch.clearFocus()
                binding.root.isFocusableInTouchMode = true
                binding.root.requestFocus()
                loadPublicPlaylistsIfNeeded()
                binding.chipGroupFilters.isGone = true
                updateChipStyles()
            }
        }
        // End of initial rendering phase
        isInitializing = false
    }

    override fun onStart() {
        super.onStart()
        // Enlazar servicio para poder reproducir
        val intent = Intent(requireContext(), MusicService::class.java)
        requireContext().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            try { musicService?.removeSongChangeListener(historySongChangeListener) } catch (_: Exception) {}
            requireContext().unbindService(serviceConnection)
            isBound = false
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? MusicService.MusicBinder
            musicService = binder?.getService()
            isBound = true
            // Registrar listener para reflejar canción actual en historial
            musicService?.addSongChangeListener(historySongChangeListener)
            // Setear estado inicial
            val current = musicService?.getCurrentSong()
            historyAdapter.setPlayingSongId(current?.id)
            // If there is a pending queue, play it first
            pendingPlayQueue?.let { queue ->
                musicService?.playQueue(queue)
                pendingPlayQueue = null
            }
            // Si había una canción pendiente, reproducir ahora
            pendingPlaySong?.let { song ->
                musicService?.playSong(song)
                pendingPlaySong = null
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            musicService = null
        }
    }

    private fun setupUI() {
        // No llamar showEmptyState() aquí; se decide en onViewCreated según ViewModel

        // Botón limpiar
        binding.btnClearSearch.setOnClickListener {
            binding.etSearch.setText("")
        }

        // Retry en error
        binding.btnRetry.setOnClickListener {
            performSearch(currentSearchQuery)
        }

        binding.btnClearHistory.setOnClickListener {
            SearchHistoryManager.clear(requireContext())
            // Asegurar que no forzamos mostrar historial después de limpiar
            keepHistoryVisible = false
            refreshHistory()
            // Mostrar playlists públicas tras limpiar historial si no hay query
            if (currentSearchQuery.isBlank()) {
                loadPublicPlaylistsIfNeeded()
            }
        }

        // Nuevo: volver a ver el grid de playlists desde historial
        binding.btnShowPlaylists.setOnClickListener {
            // Desanclar historial y mostrar playlists
            keepHistoryVisible = false
            binding.etSearch.clearFocus()
            // Ocultar lista de historial para evitar parpadeo
            binding.rvSearchHistory.isGone = true
            // Ocultar chips cuando no hay búsqueda activa
            binding.chipGroupFilters.isGone = true
            // Mostrar grid de playlists
            loadPublicPlaylistsIfNeeded()
        }
    }

    private fun setupRecycler() {
        searchAdapter = SearchResultsAdapter(
            onSongClick = { song -> onSongSelected(song) },
            onAlbumClick = { album -> handleAlbumNavigation(album) },
            onArtistClick = { artist -> handleArtistNavigation(artist) },
            onSongMoreClick = { song -> showSongOptions(song) },
            onSongFavoriteClick = { song -> toggleSongFavorite(song) },
            onAlbumFavoriteClick = { album -> toggleAlbumFavorite(album) },
            onArtistFavoriteClick = { artist -> toggleArtistFavorite(artist) },
            onBestArtistTopSongsClick = { artist -> playBestArtistTopSongs(artist) },
            onBestArtistRadioClick = { artist -> playBestArtistRadio(artist) }
        )
        binding.rvSearchResults.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = searchAdapter
            setHasFixedSize(true
            )
        }
    }

    private fun setupPublicPlaylistsGrid() {
        publicPlaylistsAdapter = PublicPlaylistsGridAdapter { playlist ->
            navigateToPlaylistDetail(playlist)
        }
        binding.rvPublicPlaylists.apply {
            layoutManager = GridLayoutManager(requireContext(), 2)
            adapter = publicPlaylistsAdapter
            setHasFixedSize(true
            )
        }
    }

    private fun setupHistoryRecycler() {
        historyAdapter = SearchHistoryAdapter { entry -> onHistoryEntryClicked(entry) }
        binding.rvSearchHistory.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = historyAdapter
            setHasFixedSize(true
            )
        }
        refreshHistory()
    }

    private fun onHistoryEntryClicked(entry: SearchHistoryManager.Entry) {
        if (_binding == null) return

        when (entry.type) {
            SearchHistoryManager.Entry.Type.SONG -> {
                // Mantener historial visible y resaltar canción
                keepHistoryVisible = true
                showHistory()
                // Intentar obtener la canción y reproducirla
                viewLifecycleOwner.lifecycleScope.launch {
                    val result = musicRepository.getSong(entry.id)
                    result.onSuccess { song ->
                        // Optimista: resaltar de inmediato
                        historyAdapter.setPlayingSongId(song.id)
                        onSongSelected(song)
                    }.onFailure {
                        Toast.makeText(requireContext(), "No se pudo abrir la canción", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            SearchHistoryManager.Entry.Type.ALBUM -> {
                // Ocultar historial al navegar
                binding.historyHeader.isGone = true
                binding.rvSearchHistory.isGone = true
                binding.etSearch.clearFocus()
                viewLifecycleOwner.lifecycleScope.launch {
                    val result = musicRepository.getAlbumDetail(entry.id)
                    result.onSuccess { album ->
                        handleAlbumNavigation(album)
                    }.onFailure {
                        Toast.makeText(requireContext(), "No se pudo abrir el álbum", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            SearchHistoryManager.Entry.Type.ARTIST -> {
                binding.historyHeader.isGone = true
                binding.rvSearchHistory.isGone = true
                binding.etSearch.clearFocus()
                val action = SearchFragmentDirections.actionSearchToArtistDetail(entry.id, entry.title)
                try {
                    findNavController().navigate(action)
                } catch (_: Exception) {
                    Toast.makeText(requireContext(), "No se pudo abrir el artista", Toast.LENGTH_SHORT).show()
                }
            }
            SearchHistoryManager.Entry.Type.PLAYLIST -> {
                binding.historyHeader.isGone = true
                binding.rvSearchHistory.isGone = true
                binding.etSearch.clearFocus()
                val playlist = Playlist(id = entry.id, name = entry.title)
                navigateToPlaylistDetail(playlist)
            }
        }
    }

    private fun refreshHistory() {
        if (_binding == null) return
        val history = SearchHistoryManager.getHistory(requireContext())
        if (history.isNotEmpty()) {
            historyAdapter.submit(history)
            // Avoid showing history during initial render when query is blank to prevent flicker
            val shouldShow = !(isInitializing && currentSearchQuery.isBlank() && !binding.etSearch.hasFocus() && !keepHistoryVisible)
            binding.historyHeader.isVisible = shouldShow
            binding.btnClearHistory.isVisible = shouldShow
            binding.rvSearchHistory.isVisible = shouldShow
        } else {
            binding.historyHeader.isGone = true
            binding.btnClearHistory.isGone = true
            binding.rvSearchHistory.isGone = true
        }
    }

    private fun navigateToPlaylistDetail(playlist: Playlist) {
        // Guardar en historial playlist
        SearchHistoryManager.addPlaylist(requireContext(), playlist.id, playlist.name)
        val b = _binding ?: return
        val action = SearchFragmentDirections.actionSearchToPlaylistDetail(
            playlist.id,
            playlist.name
        )
        b.rvPublicPlaylists.isNestedScrollingEnabled = false
        b.root.post {
            try {
                if (isAdded && _binding != null) {
                    findNavController().navigate(action)
                }
            } catch (_: Exception) {
                _binding?.rvPublicPlaylists?.isNestedScrollingEnabled = true
                _binding?.let { Toast.makeText(requireContext(), "No se pudo abrir la playlist", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun handleAlbumNavigation(album: Album) {
        // Guardar en historial álbum
        SearchHistoryManager.addAlbum(requireContext(), album.id, album.name, album.artist)
        val b = _binding ?: return
        b.rvSearchResults.isNestedScrollingEnabled = false
        b.root.post {
            try {
                if (isAdded && _binding != null) {
                    val action = SearchFragmentDirections.actionSearchToAlbumDetail(album)
                    findNavController().navigate(action)
                }
            } catch (_: Exception) {
                _binding?.rvSearchResults?.isNestedScrollingEnabled = true
            }
        }
    }

    private fun handleArtistNavigation(artist: Artist) {
        // Guardar en historial artista
        SearchHistoryManager.addArtist(requireContext(), artist.id, artist.name)
        val b = _binding ?: return
        b.rvSearchResults.isNestedScrollingEnabled = false
        b.root.post {
            try {
                if (isAdded && _binding != null) {
                    val action = SearchFragmentDirections.actionSearchToArtistDetail(artist.id, artist.name)
                    findNavController().navigate(action)
                }
            } catch (_: Exception) {
                _binding?.rvSearchResults?.isNestedScrollingEnabled = true
            }
        }
    }

    private var suppressTextWatcher = false

    private fun setupSearchFunctionality() {
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (suppressTextWatcher) return
                val query = s?.toString()?.trim() ?: ""
                binding.btnClearSearch.isVisible = query.isNotEmpty()
                // Mostrar chips solo cuando se escribe
                binding.chipGroupFilters.isVisible = query.isNotEmpty()
                handleSearchTextChange(query)
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val q = binding.etSearch.text.toString().trim()
                performSearch(q)
                true
            } else false
        }

        binding.etSearch.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                if (binding.etSearch.text.isNullOrBlank()) {
                    if (!isInitializing) {
                        showHistory()
                    }
                }
            } else {
                // Al perder foco y sin texto, mantener historial si está fijado tras tap en historial
                if (binding.etSearch.text.isNullOrBlank()) {
                    if (!keepHistoryVisible) {
                        loadPublicPlaylistsIfNeeded()
                    } else {
                        // Only show history if not in initialization to avoid flicker
                        if (!isInitializing) showHistory()
                    }
                }
            }
        }
    }

    private fun ensureAuth() {
        if (authUsername == null || authToken == null || authSalt == null) {
            try {
                val (u, t, s) = musicRepository.getAuthParams()
                authUsername = u
                authToken = t
                authSalt = s
            } catch (_: Exception) {
                // No configurado
            }
        }
    }

    private fun showHistory() {
        // Refrescar datos y decidir visibilidad en base al estado real almacenado
        refreshHistory()
        val hasHistory = try {
            SearchHistoryManager.getHistory(requireContext()).isNotEmpty()
        } catch (_: Exception) { false }
        if (!hasHistory) {
            // Si no hay historial, no mostrar header/lista; ir a playlists públicas
            loadPublicPlaylistsIfNeeded()
            return
        }
        val b = _binding ?: return
        b.historyHeader.isVisible = true
        b.rvSearchHistory.isVisible = true
        b.rvPublicPlaylists.isGone = true
        b.rvSearchResults.isGone = true
        b.emptyState.isGone = true
        b.errorState.isGone = true
        b.loadingState.isGone = true
        switchToViewMode(ViewMode.HISTORY)
    }

    private fun showInitialContent() {
        // Prioridad: historial -> playlists públicas -> estado vacío
        val history = SearchHistoryManager.getHistory(requireContext())
        if (history.isNotEmpty()) {
            showHistory()
        } else {
            loadPublicPlaylistsIfNeeded()
        }
    }

    private fun handleSearchTextChange(query: String) {
        currentSearchQuery = query
        viewModel.query.value = query
        searchJob?.cancel()

        if (query.isBlank()) {
            // Ocultar chips si no hay texto
            binding.chipGroupFilters.isGone = true
            // Sin texto: si el input está enfocado mostrar historial; si no, playlists públicas
            if (binding.etSearch.hasFocus()) {
                if (!isInitializing) {
                    showHistory()
                } else {
                    // During initialization, prefer playlists to avoid flicker
                    loadPublicPlaylistsIfNeeded()
                }
            } else {
                if (!keepHistoryVisible) {
                    loadPublicPlaylistsIfNeeded()
                } else {
                    if (!isInitializing) showHistory() else loadPublicPlaylistsIfNeeded()
                }
            }
            return
        } else {
            // Al escribir, liberar pin de historial y mostrar chips
            keepHistoryVisible = false
            binding.chipGroupFilters.isVisible = true
        }

        // Texto no vacío: ocultar historial y hacer búsqueda (debounce)
        binding.historyHeader.isGone = true
        binding.rvSearchHistory.isGone = true

        searchJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(SEARCH_DELAY_MS)
            if (query == currentSearchQuery) {
                performSearch(query)
            }
        }
    }

    private fun loadPublicPlaylistsIfNeeded() {
        // No interceptar con historial aquí; el historial se maneja por foco del input
        if (!musicRepository.isConfigured()) {
            // Sin configuración, mostrar estado vacío
            showEmptyState()
            return
        }
        val cached = viewModel.publicPlaylists.value.orEmpty()
        if (cached.isNotEmpty()) {
            // Mostrar solo playlists públicas; ya no se filtra por query aquí
            showPublicPlaylists(cached.filter { it.public })
            return
        }
        // Mostrar grid vacío mientras carga
        binding.rvPublicPlaylists.isVisible = true
        binding.rvSearchResults.isGone = true
        binding.loadingState.isGone = true
        binding.errorState.isGone = true
        binding.emptyState.isGone = true

        viewLifecycleOwner.lifecycleScope.launch {
            val result = musicRepository.getPlaylists()
            result.onSuccess { playlists ->
                val publics = playlists.filter { it.public }
                viewModel.publicPlaylists.value = publics
                if (currentSearchQuery.isBlank()) {
                    showPublicPlaylists(publics)
                }
            }.onFailure {
                // Si falla, mostrar estado vacío (solo si no hay texto y no hay foco)
                if (currentSearchQuery.isBlank() && !binding.etSearch.hasFocus()) {
                    showEmptyState()
                }
            }
        }
    }

    private fun showPublicPlaylists(list: List<Playlist>) {
        // Ocultar historial al mostrar playlists
        binding.historyHeader.isGone = true
        binding.rvSearchHistory.isGone = true
        // Ocultar chips cuando estamos viendo playlists
        binding.chipGroupFilters.isGone = true

        if (list.isEmpty()) {
            showErrorState(
                title = "Sin resultados",
                message = "No hay playlists que coincidan"
            )
            return
        }
        publicPlaylistsAdapter.setItems(list)
        binding.rvPublicPlaylists.isVisible = true
        binding.rvSearchResults.isGone = true
        binding.loadingState.isGone = true
        binding.errorState.isGone = true
        binding.emptyState.isGone = true
    }

    private fun performSearch(query: String) {
        if (query.isBlank()) return
        if (!musicRepository.isConfigured()) {
            showErrorState(
                title = getString(R.string.app_name),
                message = "Configura tu servidor en Ajustes para buscar"
            )
            return
        }
        if (hasContent()) {
            showLoading(true)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val result = musicRepository.searchMusic(query)
            result.onSuccess { (songs, albums, artists) ->
                // Skip outdated responses if user changed/cleared the query
                if (query != currentSearchQuery || currentSearchQuery.isBlank()) return@onSuccess

                var usedTopSongs = false
                val finalSongs = try {
                    val matchedArtistName = artists.firstOrNull { it.name.equals(query, ignoreCase = true) }?.name
                        ?: artists.firstOrNull { it.name.contains(query, ignoreCase = true) }?.name
                        ?: songs.firstOrNull { it.artist.equals(query, ignoreCase = true) }?.artist
                    if (matchedArtistName != null) {
                        val topRes = musicRepository.getArtistTopSongs(matchedArtistName, 100)
                        if (topRes.isSuccess) {
                            val top = topRes.getOrNull().orEmpty()
                            if (top.isNotEmpty()) {
                                usedTopSongs = true
                                top
                            } else songs.filter { it.artist.equals(matchedArtistName, ignoreCase = true) }
                        } else songs.filter { it.artist.equals(matchedArtistName, ignoreCase = true) }
                    } else songs
                } catch (_: Exception) { songs }

                val sortedAlbums = albums.sortedByDescending { computeAlbumRelevance(query, it) }
                val sortedSongs = if (usedTopSongs) finalSongs else finalSongs.sortedByDescending { computeSongRelevance(query, it) }
                val sortedArtists = artists.sortedByDescending { computeArtistRelevance(query, it) }

                val qn = normalizeText(query)
                val exactArtist = sortedArtists.firstOrNull { normalizeText(it.name) == qn }
                val exactSong = sortedSongs.firstOrNull { normalizeText(it.title) == qn }
                val exactAlbum = sortedAlbums.firstOrNull { normalizeText(it.name) == qn }

                val bestItem: SearchResultsAdapter.Item? = when {
                    exactArtist != null -> SearchResultsAdapter.Item.ArtistItem(exactArtist)
                    exactSong != null -> SearchResultsAdapter.Item.SongItem(exactSong)
                    exactAlbum != null -> SearchResultsAdapter.Item.AlbumItem(exactAlbum)
                    else -> {
                        val topSong = sortedSongs.firstOrNull()
                        val topAlbum = sortedAlbums.firstOrNull()
                        val topArtist = sortedArtists.firstOrNull()
                        val songScore = topSong?.let { computeSongRelevance(query, it) } ?: Int.MIN_VALUE
                        val albumScore = topAlbum?.let { computeAlbumRelevance(query, it) } ?: Int.MIN_VALUE
                        val artistScore = topArtist?.let { computeArtistRelevance(query, it) } ?: Int.MIN_VALUE
                        when (maxOf(songScore, albumScore, artistScore)) {
                            songScore -> topSong?.let { SearchResultsAdapter.Item.SongItem(it) }
                            albumScore -> topAlbum?.let { SearchResultsAdapter.Item.AlbumItem(it) }
                            else -> topArtist?.let { SearchResultsAdapter.Item.ArtistItem(it) }
                        }
                    }
                }

                // Persist full results and best item
                viewModel.fullSongs.value = sortedSongs
                viewModel.fullAlbums.value = sortedAlbums
                viewModel.fullArtists.value = sortedArtists
                viewModel.bestItem.value = bestItem
                viewModel.query.value = query

                val display = buildDisplayItems()
                if (display.isEmpty()) {
                    showErrorState(
                        title = "Sin resultados",
                        message = "Intenta con otros términos"
                    )
                } else {
                    viewModel.items.value = display
                    showResults()
                    searchAdapter.updateAuth(
                        serverUrl = serverUrl ?: "",
                        username = authUsername ?: "",
                        token = authToken ?: "",
                        salt = authSalt ?: ""
                    )
                    searchAdapter.submitData(display)
                }
            }.onFailure { e ->
                // Skip outdated errors if query changed/cleared
                if (query != currentSearchQuery || currentSearchQuery.isBlank()) return@onFailure
                showErrorState(
                    title = "Error",
                    message = e.message ?: "Error al buscar"
                )
            }
            showLoading(false)
        }
    }

    // Rebuild UI list depending on filters: flat lists without headers for a selected type, or sectioned with headers otherwise
    private fun buildDisplayItems(): List<SearchResultsAdapter.Item> {
        val songs = viewModel.fullSongs.value.orEmpty()
        val albums = viewModel.fullAlbums.value.orEmpty()
        val artists = viewModel.fullArtists.value.orEmpty()
        val best = viewModel.bestItem.value

        // Any filter active? Show ALL items of that type without headers and without best item
        if (filterSongs || filterAlbums || filterArtists) {
            return when {
                filterSongs -> songs.map { SearchResultsAdapter.Item.SongItem(it) }
                filterAlbums -> albums.map { SearchResultsAdapter.Item.AlbumItem(it) }
                filterArtists -> artists.map { SearchResultsAdapter.Item.ArtistItem(it) }
                else -> emptyList()
            }
        }

        // No filters: build sectioned list with best result and top 3 per section (excluding best)
        val items = mutableListOf<SearchResultsAdapter.Item>()

        fun <T> excludeBest(list: List<T>): List<T> {
            return when (best) {
                is SearchResultsAdapter.Item.SongItem -> if (list is List<*>) list.filter { (it as? com.arantec.castafiore.data.models.Song)?.id != best.song.id } as List<T> else list
                is SearchResultsAdapter.Item.AlbumItem -> if (list is List<*>) list.filter { (it as? com.arantec.castafiore.data.models.Album)?.id != best.album.id } as List<T> else list
                is SearchResultsAdapter.Item.ArtistItem -> if (list is List<*>) list.filter { (it as? com.arantec.castafiore.data.models.Artist)?.id != best.artist.id } as List<T> else list
                else -> list
            }
        }

        if (best != null) {
            items.add(SearchResultsAdapter.Item.HeaderItem(SearchResultsAdapter.Section.BEST, "Mejor resultado"))
            val flaggedBest = when (best) {
                is SearchResultsAdapter.Item.SongItem -> SearchResultsAdapter.Item.SongItem(best.song, isBest = true)
                is SearchResultsAdapter.Item.AlbumItem -> SearchResultsAdapter.Item.AlbumItem(best.album, isBest = true)
                is SearchResultsAdapter.Item.ArtistItem -> SearchResultsAdapter.Item.ArtistItem(best.artist, isBest = true)
                else -> null
            }
            flaggedBest?.let { items.add(it) }
        }
        val songsSection = excludeBest(songs).take(3)
        if (songsSection.isNotEmpty()) {
            items.add(SearchResultsAdapter.Item.HeaderItem(SearchResultsAdapter.Section.SONGS, "Canciones"))
            songsSection.forEach { items.add(SearchResultsAdapter.Item.SongItem(it)) }
        }
        val artistsSection = excludeBest(artists).take(3)
        if (artistsSection.isNotEmpty()) {
            items.add(SearchResultsAdapter.Item.HeaderItem(SearchResultsAdapter.Section.ARTISTS, "Artistas"))
            artistsSection.forEach { items.add(SearchResultsAdapter.Item.ArtistItem(it)) }
        }
        val albumsSection = excludeBest(albums).take(3)
        if (albumsSection.isNotEmpty()) {
            items.add(SearchResultsAdapter.Item.HeaderItem(SearchResultsAdapter.Section.ALBUMS, "Álbumes"))
            albumsSection.forEach { items.add(SearchResultsAdapter.Item.AlbumItem(it)) }
        }
        return items
    }

    // --- Filtros UI ---
    private fun setupFilterChips() {
        // Estado inicial: sin filtros activos
        binding.chipArtists.isChecked = false
        binding.chipAlbums.isChecked = false
        binding.chipSongs.isChecked = false
        syncFiltersFromChips()
        updateChipStyles()

        // Listener de selección única; mostrar/ocultar icono cerrar y aplicar color
        val onCheckedChanged: (Boolean) -> Unit = { _ ->
            syncFiltersFromChips()
            updateChipStyles()
            val display = buildDisplayItems()
            if (display.isNotEmpty()) {
                showResults()
                searchAdapter.submitData(display)
            }
            // Si no hay ningún filtro activo tras el cambio, volver al inicio de la lista
            if (!filterArtists && !filterAlbums && !filterSongs) {
                binding.rvSearchResults.post {
                    binding.rvSearchResults.smoothScrollToPosition(0)
                }
            }
        }
        binding.chipArtists.setOnCheckedChangeListener { _, isChecked -> onCheckedChanged(isChecked) }
        binding.chipAlbums.setOnCheckedChangeListener { _, isChecked -> onCheckedChanged(isChecked) }
        binding.chipSongs.setOnCheckedChangeListener { _, isChecked -> onCheckedChanged(isChecked) }

        // Clic en icono cerrar para quitar filtro activo (el listener onCheckedChange manejará el scroll)
        binding.chipArtists.setOnCloseIconClickListener {
            binding.chipArtists.isChecked = false
        }
        binding.chipAlbums.setOnCloseIconClickListener {
            binding.chipAlbums.isChecked = false
        }
        binding.chipSongs.setOnCloseIconClickListener {
            binding.chipSongs.isChecked = false
        }
    }

    private fun updateChipStyles() {
        val primary = ContextCompat.getColor(requireContext(), R.color.primary)
        val surfaceVariant = ContextCompat.getColor(requireContext(), R.color.surface_variant)
        val textPrimary = ContextCompat.getColor(requireContext(), R.color.text_primary)
        val textOnPrimary = ContextCompat.getColor(requireContext(), android.R.color.white)

        fun styleChip(chip: com.google.android.material.chip.Chip, checked: Boolean) {
            chip.isCloseIconVisible = checked
            if (checked) {
                chip.chipBackgroundColor = ColorStateList.valueOf(primary)
                chip.setTextColor(textOnPrimary)
            } else {
                chip.chipBackgroundColor = ColorStateList.valueOf(surfaceVariant)
                chip.setTextColor(textPrimary)
            }
        }
        styleChip(binding.chipArtists, binding.chipArtists.isChecked)
        styleChip(binding.chipAlbums, binding.chipAlbums.isChecked)
        styleChip(binding.chipSongs, binding.chipSongs.isChecked)
    }

    private fun syncFiltersFromChips() {
        // Solo uno activo a la vez por singleSelection; si ninguno, mostrar todos
        filterArtists = binding.chipArtists.isChecked
        filterAlbums = binding.chipAlbums.isChecked
        filterSongs = binding.chipSongs.isChecked
    }


    private fun handleVoiceSearchResult(resultCode: Int, data: Intent?) {
        isVoiceSearchActive = false
        if (resultCode == Activity.RESULT_OK && data != null) {
            val results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            if (!results.isNullOrEmpty()) {
                val spokenText = results[0]
                _binding?.let { b ->
                    b.etSearch.setText(spokenText)
                    b.etSearch.setSelection(spokenText.length)
                    // Mostrar chips al tener texto y disparar búsqueda
                    b.chipGroupFilters.isVisible = spokenText.isNotBlank()
                    performSearch(spokenText)
                }
            }
        }
    }

    private fun onSongSelected(song: Song) {
        // Guardar en historial
        SearchHistoryManager.addSong(requireContext(), song.id, song.title, song.artist)
        // Reproducir canción usando el servicio
        musicService?.playSong(song) ?: run {
            pendingPlaySong = song
            val intent = Intent(requireContext(), MusicService::class.java)
            requireContext().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            Toast.makeText(requireContext(), "Iniciando reproductor...", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showSongOptions(song: Song) {
        val bottomSheet = SongOptionsBottomSheet
            .newInstance(song)
            .setOnAddToQueueClickListener { s ->
                musicService?.addToQueue(s)
            }
            .setOnPlayNextClickListener { s ->
                musicService?.playNext(s)
            }
            .setOnAddToPlaylistClickListener { s ->
                PlaylistSelectorBottomSheet.newInstance(s)
                    .show(childFragmentManager, "playlistSelector")
            }
            .setOnViewAlbumClickListener { s ->
                val albumId = s.albumId
                if (!albumId.isNullOrEmpty()) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        musicRepository.getAlbumDetail(albumId).fold(
                            onSuccess = { album ->
                                val action = SearchFragmentDirections.actionSearchToAlbumDetail(album)
                                findNavController().navigate(action)
                            },
                            onFailure = {
                                Toast.makeText(requireContext(), "No se pudo abrir el álbum", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                } else {
                    Toast.makeText(requireContext(), "Álbum no disponible", Toast.LENGTH_SHORT).show()
                }
            }
            .setOnViewArtistClickListener { s ->
                val artistId = s.artistId
                if (!artistId.isNullOrEmpty()) {
                    val action = SearchFragmentDirections.actionSearchToArtistDetail(artistId, s.artist)
                    findNavController().navigate(action)
                } else {
                    Toast.makeText(requireContext(), "Artista no disponible", Toast.LENGTH_SHORT).show()
                }
            }
        bottomSheet.show(childFragmentManager, "songOptions")
    }

    private fun switchToViewMode(mode: ViewMode) {
        if (currentViewMode == mode) return
        currentViewMode = mode
        // No animation for consistency with HomeFragment
    }

    private fun showLoading(show: Boolean) {
        val b = _binding ?: return
        b.loadingState.isVisible = show
        if (show) {
            b.rvSearchResults.isGone = true
            b.rvPublicPlaylists.isGone = true
            b.emptyState.isGone = true
            b.errorState.isGone = true
            // Ensure history is hidden while loading to avoid overlap
            b.historyHeader.isGone = true
            b.rvSearchHistory.isGone = true
        }
    }

    private fun showEmptyState() {
        val b = _binding ?: return
        b.emptyState.isVisible = true
        b.loadingState.isGone = true
        b.rvSearchResults.isGone = true
        b.rvPublicPlaylists.isGone = true
        b.errorState.isGone = true
        // Hide history when showing empty state
        b.historyHeader.isGone = true
        b.rvSearchHistory.isGone = true
    }

    private fun showResults() {
        val b = _binding ?: return
        b.rvSearchResults.isVisible = true
        b.rvPublicPlaylists.isGone = true
        b.emptyState.isGone = true
        b.loadingState.isGone = true
        b.errorState.isGone = true
        // Hide history to avoid overlapping with results
        b.historyHeader.isGone = true
        b.rvSearchHistory.isGone = true
        switchToViewMode(ViewMode.RESULTS)
    }

    private fun showErrorState(title: String, message: String) {
        val b = _binding ?: return
        b.tvErrorTitle.text = title
        b.tvErrorMessage.text = message
        b.errorState.isVisible = true
        b.rvPublicPlaylists.isGone = true
        b.emptyState.isGone = true
        b.loadingState.isGone = true
        b.rvSearchResults.isGone = true
        // Hide history when showing error
        b.historyHeader.isGone = true
        b.rvSearchHistory.isGone = true
    }

    override fun onDestroyView() {
        super.onDestroyView()
        searchJob?.cancel()
        _binding = null
    }

    override fun onResume() {
        super.onResume()
        // Aplicar el color estático consistente de la app
        StatusBarUtils.setStatusBarColor(this)

        // Restaurar el comportamiento de scroll normal cuando regresemos al fragmento
        if (_binding != null) {
            binding.rvSearchResults.isNestedScrollingEnabled = true
        }
    }

    override fun hasContent(): Boolean {
        val hasResults = (this::searchAdapter.isInitialized && searchAdapter.itemCount > 0)
        val hasPlaylists = (this::publicPlaylistsAdapter.isInitialized && publicPlaylistsAdapter.itemCount > 0)
        val hasHistory = (this::historyAdapter.isInitialized && historyAdapter.itemCount > 0)
        return hasResults || hasPlaylists || hasHistory
    }

    // --- Relevancia de búsqueda (ordenar por mejor coincidencia) ---
    private fun normalizeText(input: String?): String {
        if (input.isNullOrBlank()) return ""
        val lower = input.lowercase(Locale.getDefault())
        val normalized = Normalizer.normalize(lower, Normalizer.Form.NFD)
        return normalized.replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
    }

    private fun wholeWordContains(text: String, needle: String): Boolean {
        if (needle.isEmpty() || text.isEmpty()) return false
        val pattern = Regex("\\b" + Regex.escape(needle) + "\\b")
        return pattern.containsMatchIn(text)
    }

    private fun levenshtein(a: String, b: String, maxDistance: Int = 6): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        val n = a.length
        val m = b.length
        if (kotlin.math.abs(n - m) > maxDistance) return maxDistance
        val prev = IntArray(m + 1) { it }
        val curr = IntArray(m + 1)
        for (i in 1..n) {
            curr[0] = i
            val ca = a[i - 1]
            for (j in 1..m) {
                val cost = if (ca == b[j - 1]) 0 else 1
                curr[j] = minOf(
                    curr[j - 1] + 1,         // insertion
                    prev[j] + 1,             // deletion
                    prev[j - 1] + cost       // substitution
                )
            }
            System.arraycopy(curr, 0, prev, 0, m + 1)
        }
        return minOf(prev[m], maxDistance)
    }

    private fun computeSongRelevance(query: String, song: Song): Int {
        val q = normalizeText(query)
        val title = normalizeText(song.title)
        val artist = normalizeText(song.artist)

        var score = 0
        if (title == q) score = max(score, 1000)
        if (artist == q) score = max(score, 950)

        if (title.startsWith(q)) score = max(score, 900)
        if (wholeWordContains(title, q)) score = max(score, 860)
        if (title.contains(q)) score = max(score, 820)

        if (artist.startsWith(q)) score = max(score, 780)
        if (wholeWordContains(artist, q)) score = max(score, 760)
        if (artist.contains(q)) score = max(score, 740)

        // Pequeño bono por cercanía (a menor distancia, mayor bono)
        val dist = levenshtein(title, q)
        val proximityBonus = (200 - dist * 30).coerceAtLeast(0)
        score += proximityBonus

        // Penalizar títulos extremadamente largos cuando solo contienen parcialmente
        if (!title.startsWith(q) && title.contains(q)) {
            val lenPenalty = (title.length - q.length).coerceAtLeast(0)
            score -= (lenPenalty / 10)
        }

        return score
    }

    private fun computeAlbumRelevance(query: String, album: Album): Int {
        val q = normalizeText(query)
        val name = normalizeText(album.name)
        val artist = normalizeText(album.artist)

        var score = 0
        if (name == q) score = max(score, 1000)
        if (artist == q) score = max(score, 930)

        if (name.startsWith(q)) score = max(score, 900)
        if (wholeWordContains(name, q)) score = max(score, 860)
        if (name.contains(q)) score = max(score, 820)

        if (artist.startsWith(q)) score = max(score, 780)
        if (wholeWordContains(artist, q)) score = max(score, 760)
        if (artist.contains(q)) score = max(score, 740)

        val dist = levenshtein(name, q)
        val proximityBonus = (180 - dist * 30).coerceAtLeast(0)
        score += proximityBonus

        if (!name.startsWith(q) && name.contains(q)) {
            val lenPenalty = (name.length - q.length).coerceAtLeast(0)
            score -= (lenPenalty / 10)
        }

        return score
    }

    private fun computeArtistRelevance(query: String, artist: Artist): Int {
        val q = normalizeText(query)
        val name = normalizeText(artist.name)
        var score = 0
        if (name == q) score = max(score, 1000)
        if (name.startsWith(q)) score = max(score, 900)
        if (wholeWordContains(name, q)) score = max(score, 860)
        if (name.contains(q)) score = max(score, 820)
        val dist = levenshtein(name, q)
        val proximityBonus = (160 - dist * 30).coerceAtLeast(0)
        score += proximityBonus
        if (!name.startsWith(q) && name.contains(q)) {
            val lenPenalty = (name.length - q.length).coerceAtLeast(0)
            score -= (lenPenalty / 10)
        }

        return score
    }

    // Favoritos actuales (ids) para pintar iconos y togglear optimistamente
    private var favoriteSongIds: MutableSet<String> = mutableSetOf()
    private var favoriteAlbumIds: MutableSet<String> = mutableSetOf()
    private var favoriteArtistIds: MutableSet<String> = mutableSetOf()

    private fun loadFavorites() {
        if (!this::musicRepository.isInitialized || !musicRepository.isConfigured()) return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val songsDeferred = async { musicRepository.getStarredSongs() }
                val albumsDeferred = async { musicRepository.getStarredAlbums() }
                val artistsDeferred = async { musicRepository.getStarredArtists() }

                val songsRes = songsDeferred.await()
                val albumsRes = albumsDeferred.await()
                val artistsRes = artistsDeferred.await()

                songsRes.onSuccess { list ->
                    favoriteSongIds = list.map { it.id }.toMutableSet()
                }
                albumsRes.onSuccess { list ->
                    favoriteAlbumIds = list.map { it.id }.toMutableSet()
                }
                artistsRes.onSuccess { list ->
                    favoriteArtistIds = list.map { it.id }.toMutableSet()
                }
            } catch (_: Exception) {
                // Ignorar errores de carga inicial
            }
            if (this@SearchFragment::searchAdapter.isInitialized) {
                searchAdapter.updateFavorites(favoriteSongIds, favoriteAlbumIds, favoriteArtistIds)
            }
        }
    }

    private fun toggleSongFavorite(song: Song) {
        val wasFav = favoriteSongIds.contains(song.id)
        // Optimista
        if (wasFav) favoriteSongIds.remove(song.id) else favoriteSongIds.add(song.id)
        if (this::searchAdapter.isInitialized) {
            searchAdapter.updateFavorites(favoriteSongIds, favoriteAlbumIds, favoriteArtistIds)
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val result = if (!wasFav) musicRepository.starSong(song.id) else musicRepository.unstarSong(song.id)
            result.onSuccess {
                // No Snackbar feedback as requested
            }.onFailure {
                // Revertir
                if (wasFav) favoriteSongIds.add(song.id) else favoriteSongIds.remove(song.id)
                if (this@SearchFragment::searchAdapter.isInitialized) {
                    searchAdapter.updateFavorites(favoriteSongIds, favoriteAlbumIds, favoriteArtistIds)
                }
                // No Snackbar feedback as requested
            }
        }
    }

    private fun toggleAlbumFavorite(album: Album) {
        val wasFav = favoriteAlbumIds.contains(album.id)
        if (wasFav) favoriteAlbumIds.remove(album.id) else favoriteAlbumIds.add(album.id)
        if (this::searchAdapter.isInitialized) {
            searchAdapter.updateFavorites(favoriteSongIds, favoriteAlbumIds, favoriteArtistIds)
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val result = if (!wasFav) musicRepository.starAlbum(album.id) else musicRepository.unstarAlbum(album.id)
            result.onSuccess {
                // No Snackbar feedback as requested
            }.onFailure {
                if (wasFav) favoriteAlbumIds.add(album.id) else favoriteAlbumIds.remove(album.id)
                if (this@SearchFragment::searchAdapter.isInitialized) {
                    searchAdapter.updateFavorites(favoriteSongIds, favoriteAlbumIds, favoriteArtistIds)
                }
                // No Snackbar feedback as requested
            }
        }
    }

    private fun toggleArtistFavorite(artist: Artist) {
        val wasFav = favoriteArtistIds.contains(artist.id)
        if (wasFav) favoriteArtistIds.remove(artist.id) else favoriteArtistIds.add(artist.id)
        if (this::searchAdapter.isInitialized) {
            searchAdapter.updateFavorites(favoriteSongIds, favoriteAlbumIds, favoriteArtistIds)
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val result = if (!wasFav) musicRepository.starArtist(artist.id) else musicRepository.unstarArtist(artist.id)
            result.onSuccess {
                // No Snackbar feedback as requested
            }.onFailure {
                if (wasFav) favoriteArtistIds.add(artist.id) else favoriteArtistIds.remove(artist.id)
                if (this@SearchFragment::searchAdapter.isInitialized) {
                    searchAdapter.updateFavorites(favoriteSongIds, favoriteAlbumIds, favoriteArtistIds)
                }
                // No Snackbar feedback as requested
            }
        }
    }

    private fun playBestArtistTopSongs(artist: Artist) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = musicRepository.getArtistTopSongs(artist.name, count = 100)
                val songs = result.getOrNull().orEmpty()
                if (songs.isNotEmpty()) {
                    if (isBound) {
                        musicService?.playQueue(songs)
                    } else {
                        pendingPlayQueue = songs
                        val intent = Intent(requireContext(), MusicService::class.java)
                        requireContext().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
                        Toast.makeText(requireContext(), "Iniciando reproductor...", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(requireContext(), "No hay canciones top para ${artist.name}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Error al cargar canciones top", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun playBestArtistRadio(artist: Artist) {
        // Show a loading dialog and fetch radio list first; don't auto-play a seed
        val ctx = requireContext()
        val dialogView = layoutInflater.inflate(R.layout.dialog_similar_loading, null)
        val builder = androidx.appcompat.app.AlertDialog.Builder(ctx, R.style.ThemeOverlay_Castafiore_AlertDialog_AppCompat)
            .setView(dialogView)
            .setCancelable(false)
            .setNegativeButton("Cancelar", null)
        val dialog = builder.create()
        dialog.show()

        var fetchJob: Job? = null
        // Wire cancel to job
        dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)?.setOnClickListener {
            fetchJob?.cancel()
            dialog.dismiss()
        }

        fetchJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Seed: top track of the artist; fallback to random 1
                val seed: Song? = try {
                    musicRepository.getArtistTopSongs(artist.name, count = 1).getOrNull()?.firstOrNull()
                } catch (_: Exception) { null }
                val seedOrRandom: Song? = seed ?: try {
                    musicRepository.getRandomSongs(size = 1).getOrNull()?.firstOrNull()
                } catch (_: Exception) { null }

                // Build radio list
                val similar: List<Song> = seedOrRandom?.let {
                    musicRepository.getSimilarSongs(it.id, size = 25).getOrNull().orEmpty()
                } ?: emptyList()

                var toPlay: List<Song> = similar.filter { it.id != seedOrRandom?.id }
                if (toPlay.isEmpty()) {
                    toPlay = musicRepository.getRandomSongs(size = 15).getOrNull().orEmpty()
                }

                if (!isActive) return@launch // cancelled

                if (toPlay.isNotEmpty()) {
                    if (isBound) {
                        musicService?.playQueue(toPlay)
                    } else {
                        pendingPlayQueue = toPlay
                        val intent = Intent(ctx, MusicService::class.java)
                        ctx.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
                    }
                } else {
                    Toast.makeText(ctx, "No se pudo iniciar la radio", Toast.LENGTH_SHORT).show()
                }
            } catch (_: Exception) {
                if (!isActive) return@launch
                Toast.makeText(ctx, "Error al iniciar la radio", Toast.LENGTH_SHORT).show()
            } finally {
                if (dialog.isShowing) dialog.dismiss()
            }
        }
    }
}
