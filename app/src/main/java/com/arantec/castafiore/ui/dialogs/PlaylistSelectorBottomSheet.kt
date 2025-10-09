package com.arantec.castafiore.ui.dialogs

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.arantec.castafiore.R
import com.arantec.castafiore.data.models.Playlist
import com.arantec.castafiore.data.models.Song
import com.arantec.castafiore.data.repository.MusicRepository
import com.arantec.castafiore.databinding.BottomSheetPlaylistSelectorBinding
import com.arantec.castafiore.ui.adapters.PlaylistSelectorAdapter
import com.arantec.castafiore.utils.snack
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.launch
import com.arantec.castafiore.data.download.SongDownloadManager
import java.io.File
import android.os.Build

class PlaylistSelectorBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetPlaylistSelectorBinding? = null
    private val binding get() = _binding ?: throw IllegalStateException("Binding is null")

    private lateinit var musicRepository: MusicRepository
    private lateinit var playlistAdapter: PlaylistSelectorAdapter
    private var song: Song? = null
    private var songs: ArrayList<Song>? = null
    private var playlists = mutableListOf<Playlist>()

    companion object {
        private const val ARG_SONG = "song"
        private const val ARG_SONGS = "songs"

        fun newInstance(song: Song): PlaylistSelectorBottomSheet {
            val fragment = PlaylistSelectorBottomSheet()
            val args = Bundle().apply {
                putParcelable(ARG_SONG, song)
            }
            fragment.arguments = args
            return fragment
        }

        fun newInstance(songs: ArrayList<Song>): PlaylistSelectorBottomSheet {
            val fragment = PlaylistSelectorBottomSheet()
            val args = Bundle().apply {
                putParcelableArrayList(ARG_SONGS, songs)
            }
            fragment.arguments = args
            return fragment
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            song = if (Build.VERSION.SDK_INT >= 33) {
                it.getParcelable(ARG_SONG, Song::class.java)
            } else {
                @Suppress("DEPRECATION")
                it.getParcelable(ARG_SONG)
            }
            songs = it.getParcelableArrayList(ARG_SONGS)
        }
        musicRepository = MusicRepository.getInstance(requireContext())
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetPlaylistSelectorBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Configurar el BottomSheetDialog para fondo transparente
        dialog?.let { dialog ->
            dialog.setOnShowListener {
                val bottomSheetDialog = it as com.google.android.material.bottomsheet.BottomSheetDialog
                val bottomSheet = bottomSheetDialog.findViewById<android.widget.FrameLayout>(
                    com.google.android.material.R.id.design_bottom_sheet
                )
                bottomSheet?.background = null

                // Mantener dimming, no tocar status/navigation bar para evitar parpadeos
                bottomSheetDialog.window?.setDimAmount(0.5f)
            }
        }

        setupUI()
        setupClickListeners()
        loadPlaylists()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun setupUI() {
        val currentSong = song
        val currentSongs = songs
        binding.tvSongTitle.text = when {
            currentSongs != null && currentSongs.isNotEmpty() ->
                "Agregar ${currentSongs.size} canciones a playlist"
            currentSong != null ->
                "Agregar \"${currentSong.title}\" a playlist"
            else -> "Selecciona una playlist"
        }

        // Configurar RecyclerView
        playlistAdapter = PlaylistSelectorAdapter { playlist ->
            if (currentSongs != null && currentSongs.isNotEmpty()) {
                addSongsToPlaylist(playlist, currentSongs)
            } else if (currentSong != null) {
                addSongToPlaylist(playlist)
            }
        }

        binding.rvPlaylists.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = playlistAdapter
        }
    }

    private fun setupClickListeners() {
        binding.btnCreatePlaylist.setOnClickListener {
            showCreatePlaylistDialog()
        }

        binding.btnCancel.setOnClickListener {
            dismiss()
        }
    }

    private fun loadPlaylists() {
        if (_binding == null) return
        
        binding.progressBar.visibility = View.VISIBLE
        binding.rvPlaylists.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val result = musicRepository.getPlaylists()
                result.fold(
                    onSuccess = { playlistList ->
                        // Verificar si el binding aún existe antes de actualizar la UI
                        if (_binding == null) return@fold
                        
                        // Filtrar playlists públicas: solo mostrar privadas
                        val privatePlaylists = playlistList.filter { !it.public }
                        playlists.clear()
                        playlists.addAll(privatePlaylists)
                        playlistAdapter.updatePlaylists(playlists)

                        binding.progressBar.visibility = View.GONE
                        if (playlists.isEmpty()) {
                            binding.tvEmptyState.visibility = View.VISIBLE
                            binding.rvPlaylists.visibility = View.GONE
                        } else {
                            binding.tvEmptyState.visibility = View.GONE
                            binding.rvPlaylists.visibility = View.VISIBLE
                        }
                    },
                    onFailure = { error ->
                        // Verificar si el binding aún existe antes de actualizar la UI
                        if (_binding == null) return@fold
                        
                        binding.progressBar.visibility = View.GONE
                        binding.tvEmptyState.visibility = View.VISIBLE
                        binding.tvEmptyState.text = "Error al cargar playlists: ${error.message}"
                        snack("Error al cargar playlists")
                    }
                )
            } catch (e: Exception) {
                // Verificar si el binding aún existe antes de actualizar la UI
                if (_binding == null) return@launch
                
                binding.progressBar.visibility = View.GONE
                binding.tvEmptyState.visibility = View.VISIBLE
                binding.tvEmptyState.text = "Error: ${e.message}"
                snack("Error: ${e.message}")
            }
        }
    }

    private fun showCreatePlaylistDialog() {
        val editText = EditText(requireContext()).apply {
            hint = "Nombre de la playlist"
            setPadding(50, 30, 50, 30)
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Crear nueva playlist")
            .setView(editText)
            .setPositiveButton("Crear") { _, _ ->
                val playlistName = editText.text.toString().trim()
                if (playlistName.isNotEmpty()) {
                    createPlaylist(playlistName)
                } else {
                    snack("Ingresa un nombre para la playlist")
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun createPlaylist(name: String) {
        lifecycleScope.launch {
            try {
                val result = musicRepository.createPlaylist(name)
                result.fold(
                    onSuccess = { newPlaylist ->
                        // Verificar si el binding aún existe antes de actualizar la UI
                        if (_binding == null) return@fold
                        
                        // Agregar la nueva playlist a la lista y actualizar el adapter solo si no es pública
                        if (!newPlaylist.public) {
                            playlists.add(0, newPlaylist)
                            playlistAdapter.updatePlaylists(playlists)

                            // Actualizar visibilidad
                            binding.tvEmptyState.visibility = View.GONE
                            binding.rvPlaylists.visibility = View.VISIBLE

                            snack("Playlist creada: $name")

                            // Agregar automáticamente la canción a la nueva playlist
                            song?.let { currentSong ->
                                addSongToPlaylist(newPlaylist)
                            }
                            songs?.let { currentSongs ->
                                addSongsToPlaylist(newPlaylist, currentSongs)
                            }
                        }
                    },
                    onFailure = { error ->
                        if (_binding == null) return@fold
                        snack("Error al crear playlist: ${error.message}")
                    }
                )
            } catch (e: Exception) {
                if (_binding == null) return@launch
                snack("Error: ${e.message}")
            }
        }
    }

    private fun addSongToPlaylist(playlist: Playlist) {
        song?.let { currentSong ->
            lifecycleScope.launch {
                try {
                    // Verificar si el binding aún existe antes de continuar
                    if (_binding == null) return@launch
                    
                    val dm = SongDownloadManager.getInstance(requireContext())
                    var wasFullyDownloaded = false

                    // Primero, evitar duplicados: verificar si la canción ya está en la playlist
                    val existingSongsResult = musicRepository.getPlaylistSongs(playlist.id)
                    existingSongsResult.fold(
                        onSuccess = { songs ->
                            if (_binding == null) return@fold
                            
                            val alreadyInPlaylist = songs.any { it.id == currentSong.id }
                            if (alreadyInPlaylist) {
                                snack("\"${currentSong.title}\" ya está en \"${playlist.name}\"")
                                return@launch
                            }
                            // Determinar si la playlist estaba completamente descargada ANTES de agregar la canción
                            wasFullyDownloaded = songs.isNotEmpty() && songs.all {
                                val downloadPath = dm.createDownloadPath(it.id)
                                downloadPath != null && File(downloadPath).exists()
                            }
                        },
                        onFailure = { error ->
                            if (_binding == null) return@fold
                            snack("No se pudo verificar duplicados: ${error.message}")
                            return@launch
                        }
                    )

                    // Si no está, agregarla
                    val result = musicRepository.addSongToPlaylist(playlist.id, currentSong.id)
                    result.fold(
                        onSuccess = {
                            if (_binding == null) return@fold
                            
                            snack("\"${currentSong.title}\" agregada a \"${playlist.name}\"")
                            // Si la playlist estaba completamente descargada, descargar la nueva canción automáticamente
                            if (wasFullyDownloaded && !dm.isSongDownloaded(currentSong.id) && !dm.isSongDownloading(currentSong.id)) {
                                dm.downloadSong(
                                    songId = currentSong.id,
                                    title = currentSong.title,
                                    artist = currentSong.artist,
                                    album = currentSong.album,
                                    track = currentSong.track,
                                    durationSec = currentSong.duration,
                                    suffix = currentSong.suffix,
                                    albumId = currentSong.albumId,
                                    coverArtId = currentSong.coverArt
                                )
                            }
                            dismiss()
                        },
                        onFailure = { error ->
                            if (_binding == null) return@fold
                            snack("Error al agregar canción: ${error.message}")
                        }
                    )
                } catch (e: Exception) {
                    if (_binding == null) return@launch
                    snack("Error: ${e.message}")
                }
            }
        }
    }

    // Muestra/oculta estado de carga durante la adición masiva
    private fun setAddingState(isAdding: Boolean, message: String? = null) {
        if (_binding == null) return
        
        binding.progressBar.visibility = if (isAdding) View.VISIBLE else View.GONE
        binding.rvPlaylists.isEnabled = !isAdding
        binding.btnCreatePlaylist.isEnabled = !isAdding
        binding.btnCancel.isEnabled = !isAdding
        message?.let { binding.tvSongTitle.text = it }
    }

    // Agrega múltiples canciones con chequeo de duplicados y feedback visual/progreso
    private fun addSongsToPlaylist(playlist: Playlist, songsToAdd: List<Song>) {
        lifecycleScope.launch {
            try {
                // Verificar si el binding aún existe antes de continuar
                if (_binding == null) return@launch
                
                setAddingState(true, "Preparando…")

                val dm = SongDownloadManager.getInstance(requireContext())
                var wasFullyDownloaded = false

                // Traer canciones existentes para deduplicar
                val existingSongsResult = musicRepository.getPlaylistSongs(playlist.id)
                val existing = existingSongsResult.getOrElse { emptyList() }
                wasFullyDownloaded = existing.isNotEmpty() && existing.all {
                    val downloadPath = dm.createDownloadPath(it.id)
                    downloadPath != null && File(downloadPath).exists()
                }
                val existingIds = existing.map { it.id }.toHashSet()

                // Filtrar solo nuevas
                val uniqueNew = songsToAdd.filter { it.id !in existingIds }
                if (uniqueNew.isEmpty()) {
                    if (_binding == null) return@launch
                    setAddingState(false)
                    snack("Todas las canciones ya están en \"${playlist.name}\"")
                    return@launch
                }

                var addedCount = 0
                if (_binding == null) return@launch
                setAddingState(true, "Agregando 0/${uniqueNew.size} a \"${playlist.name}\"")

                uniqueNew.forEachIndexed { index, s ->
                    // Verificar si el fragmento aún existe antes de continuar
                    if (_binding == null) return@launch
                    
                    val r = musicRepository.addSongToPlaylist(playlist.id, s.id)
                    if (r.isSuccess) {
                        addedCount++
                        if (wasFullyDownloaded && !dm.isSongDownloaded(s.id) && !dm.isSongDownloading(s.id)) {
                            dm.downloadSong(
                                songId = s.id,
                                title = s.title,
                                artist = s.artist,
                                album = s.album,
                                track = s.track,
                                durationSec = s.duration,
                                suffix = s.suffix,
                                albumId = s.albumId,
                                coverArtId = s.coverArt
                            )
                        }
                    }
                    // Actualizar mensaje cada pocos elementos para evitar exceso de redibujos
                    if (index == uniqueNew.lastIndex || index % 3 == 0) {
                        setAddingState(true, "Agregando ${index + 1}/${uniqueNew.size} a \"${playlist.name}\"")
                    }
                }

                // Verificar si el binding aún existe antes de finalizar
                if (_binding == null) return@launch
                
                setAddingState(false)
                snack("Agregadas ${addedCount}/${songsToAdd.size} a \"${playlist.name}\"")
                dismiss()
            } catch (e: Exception) {
                if (_binding == null) return@launch
                setAddingState(false)
                snack("Error: ${e.message}")
            }
        }
    }
}
