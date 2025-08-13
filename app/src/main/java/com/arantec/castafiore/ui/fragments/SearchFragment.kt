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
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.arantec.castafiore.R
import com.arantec.castafiore.data.models.Album
import com.arantec.castafiore.data.models.Artist
import com.arantec.castafiore.data.models.Song
import com.arantec.castafiore.data.repository.MusicRepository
import com.arantec.castafiore.databinding.FragmentSearchBinding
import com.arantec.castafiore.ui.adapters.SearchResultsAdapter
import com.arantec.castafiore.ui.viewmodels.SearchViewModel
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.*

// Nuevos imports para servicio y opciones
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import com.arantec.castafiore.service.MusicService
import com.arantec.castafiore.ui.dialogs.SongOptionsBottomSheet
import com.arantec.castafiore.ui.dialogs.PlaylistSelectorBottomSheet
import com.arantec.castafiore.data.download.SongDownloadManager

class SearchFragment : Fragment() {

    companion object {
        private const val SEARCH_DELAY_MS = 350L
        private const val ANIMATION_DURATION = 250L
    }

    private var _binding: FragmentSearchBinding? = null
    private val binding get() = _binding!!

    private lateinit var musicRepository: MusicRepository
    private lateinit var searchAdapter: SearchResultsAdapter
    private val viewModel: SearchViewModel by viewModels()

    // Estado de búsqueda
    private var searchJob: Job? = null
    private var currentSearchQuery = ""
    private var isVoiceSearchActive = false
    private var currentViewMode = ViewMode.RESULTS

    private var authUsername: String? = null
    private var authToken: String? = null
    private var authSalt: String? = null
    private var serverUrl: String? = null

    // Servicio de música
    private var musicService: MusicService? = null
    private var isBound = false
    private var pendingPlaySong: Song? = null

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

        setupUI()
        setupRecycler()
        setupSearchFunctionality()

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
            searchAdapter.submitData(cachedItems)
            showResults()
            // Rellenar el texto sin disparar el watcher
            suppressTextWatcher = true
            binding.etSearch.setText(cachedQuery)
            binding.etSearch.setSelection(cachedQuery.length)
            suppressTextWatcher = false
        } else {
            if (cachedQuery.isNotBlank()) {
                // Si hay query pero no items, disparar búsqueda sin mostrar estado vacío
                showLoading(true)
                performSearch(cachedQuery)
            } else {
                showEmptyState()
            }
        }
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
            requireContext().unbindService(serviceConnection)
            isBound = false
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? MusicService.MusicBinder
            musicService = binder?.getService()
            isBound = true
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
        // Animación de entrada
        binding.root.alpha = 0f
        binding.root.translationY = -100f
        binding.root.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(ANIMATION_DURATION)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()

        // No llamar showEmptyState() aquí; se decide en onViewCreated según ViewModel

        // Botón limpiar
        binding.btnClearSearch.setOnClickListener {
            binding.etSearch.setText("")
        }

        // Retry en error
        binding.btnRetry.setOnClickListener {
            performSearch(currentSearchQuery)
        }
    }

    private fun setupRecycler() {
        searchAdapter = SearchResultsAdapter(
            onSongClick = { song -> onSongSelected(song) },
            onAlbumClick = { album -> onAlbumSelected(album) },
            onArtistClick = { artist -> onArtistSelected(artist) },
            onSongMoreClick = { song -> showSongOptions(song) }
        )
        binding.rvSearchResults.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = searchAdapter
            setHasFixedSize(true)
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
                handleSearchTextChange(query)
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performSearch(binding.etSearch.text.toString().trim())
                true
            } else false
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

    private fun handleSearchTextChange(query: String) {
        currentSearchQuery = query
        viewModel.query.value = query
        searchJob?.cancel()

        if (query.isBlank()) {
            // Volver a estado vacío/historial
            showEmptyState()
            searchAdapter.submitData(emptyList<SearchResultsAdapter.Item>())
            return
        }

        // Debounce
        searchJob = lifecycleScope.launch {
            delay(SEARCH_DELAY_MS)
            if (query == currentSearchQuery) {
                performSearch(query)
            }
        }
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

        ensureAuth()
        showLoading(true)

        lifecycleScope.launch {
            val result = musicRepository.searchMusic(query)
            result.onSuccess { (songs, albums, artists) ->
                val items = mutableListOf<SearchResultsAdapter.Item>()
                artists.forEach { items.add(SearchResultsAdapter.Item.ArtistItem(it)) }
                albums.forEach { items.add(SearchResultsAdapter.Item.AlbumItem(it)) }
                songs.forEach { items.add(SearchResultsAdapter.Item.SongItem(it)) }

                if (items.isEmpty()) {
                    showErrorState(
                        title = "Sin resultados",
                        message = "Intenta con otros términos"
                    )
                } else {
                    // Guardar en ViewModel y mostrar
                    viewModel.items.value = items
                    viewModel.query.value = query
                    showResults()
                    searchAdapter.updateAuth(
                        serverUrl = serverUrl ?: "",
                        username = authUsername ?: "",
                        token = authToken ?: "",
                        salt = authSalt ?: ""
                    )
                    searchAdapter.submitData(items)
                }
            }.onFailure { e ->
                showErrorState(
                    title = "Error",
                    message = e.message ?: "Error al buscar"
                )
            }
            showLoading(false)
        }
    }

    private fun onSongSelected(song: Song) {
        // Reproducir canción usando el servicio
        musicService?.playSong(song) ?: run {
            pendingPlaySong = song
            val intent = Intent(requireContext(), MusicService::class.java)
            requireContext().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            Toast.makeText(requireContext(), "Iniciando reproductor...", Toast.LENGTH_SHORT).show()
        }
    }

    private fun onAlbumSelected(album: Album) {
        // Navegar a detalle de álbum con Safe Args
        val action = SearchFragmentDirections.actionSearchToAlbumDetail(album)
        findNavController().navigate(action)
    }

    private fun onArtistSelected(artist: Artist) {
        // Navegar a detalle de artista con Safe Args
        val action = SearchFragmentDirections.actionSearchToArtistDetail(
            artistId = artist.id,
            artistName = artist.name
        )
        findNavController().navigate(action)
    }

    private fun showSongOptions(song: Song) {
        val bottomSheet = SongOptionsBottomSheet
            .newInstance(song)
            .setOnDownloadClickListener { s ->
                SongDownloadManager.getInstance(requireContext()).downloadSong(s)
            }
            .setOnDeleteDownloadClickListener { s ->
                val ok = SongDownloadManager.getInstance(requireContext()).deleteSong(s.id)
                if (!ok) Toast.makeText(requireContext(), "No se pudo eliminar la descarga", Toast.LENGTH_SHORT).show()
            }
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

    private fun startVoiceSearch() {
        try {
            isVoiceSearchActive = true
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Habla ahora...")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            voiceSearchLauncher.launch(intent)
        } catch (_: Exception) {
            isVoiceSearchActive = false
            Toast.makeText(context, "Búsqueda por voz no soportada", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleVoiceSearchResult(resultCode: Int, data: Intent?) {
        isVoiceSearchActive = false
        if (resultCode == Activity.RESULT_OK && data != null) {
            val results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            if (!results.isNullOrEmpty()) {
                val spokenText = results[0]
                binding.etSearch.setText(spokenText)
                binding.etSearch.setSelection(spokenText.length)
                performSearch(spokenText)
            }
        }
    }

    private fun switchToViewMode(mode: ViewMode) {
        if (currentViewMode == mode) return
        currentViewMode = mode
        // Animación ligera
        binding.rvSearchResults.animate()
            .alpha(0.5f)
            .setDuration(ANIMATION_DURATION / 2)
            .withEndAction {
                binding.rvSearchResults.animate().alpha(1f).setDuration(ANIMATION_DURATION / 2).start()
            }
            .start()
    }

    private fun showLoading(show: Boolean) {
        binding.loadingState.isVisible = show
        if (show) {
            // Al mostrar loading, ocultar todo lo demás
            binding.rvSearchResults.isGone = true
            binding.emptyState.isGone = true
            binding.errorState.isGone = true
        }
        // Cuando show es false, no cambiamos otros estados; quienes llamen a este método
        // deben haber mostrado el estado correcto (resultados, error o vacío).
    }

    private fun showEmptyState() {
        binding.emptyState.isVisible = true
        binding.loadingState.isGone = true
        binding.rvSearchResults.isGone = true
        binding.errorState.isGone = true
    }

    private fun showResults() {
        binding.rvSearchResults.isVisible = true
        binding.emptyState.isGone = true
        binding.loadingState.isGone = true
        binding.errorState.isGone = true
        switchToViewMode(ViewMode.RESULTS)
    }

    private fun showErrorState(title: String, message: String) {
        binding.tvErrorTitle.text = title
        binding.tvErrorMessage.text = message
        binding.errorState.isVisible = true
        binding.emptyState.isGone = true
        binding.loadingState.isGone = true
        binding.rvSearchResults.isGone = true
    }

    private fun showSnackbar(message: String, isError: Boolean = false) {
        val snackbar = Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT)
        if (isError) {
            // Usa color por defecto de Material si no existe R.color.error
        }
        snackbar.show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        searchJob?.cancel()
        _binding = null
    }
}
