package com.arantec.castafiore.ui.dialogs

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import com.arantec.castafiore.R
import com.arantec.castafiore.data.models.Playlist
import com.arantec.castafiore.data.models.Song
import com.arantec.castafiore.data.repository.MusicRepository
import com.arantec.castafiore.databinding.BottomSheetPlaylistSelectorBinding
import com.arantec.castafiore.ui.adapters.PlaylistSelectorAdapter
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class PlaylistSelectorBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetPlaylistSelectorBinding? = null
    private val binding get() = _binding!!

    private lateinit var musicRepository: MusicRepository
    private lateinit var playlistAdapter: PlaylistSelectorAdapter
    private var song: Song? = null
    private var playlists = mutableListOf<Playlist>()

    companion object {
        private const val ARG_SONG = "song"

        fun newInstance(song: Song): PlaylistSelectorBottomSheet {
            val fragment = PlaylistSelectorBottomSheet()
            val args = Bundle().apply {
                putParcelable(ARG_SONG, song)
            }
            fragment.arguments = args
            return fragment
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            song = it.getParcelable(ARG_SONG)
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
        song?.let { currentSong ->
            binding.tvSongTitle.text = "Agregar \"${currentSong.title}\" a playlist"
        }

        // Configurar RecyclerView
        playlistAdapter = PlaylistSelectorAdapter { playlist ->
            addSongToPlaylist(playlist)
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
        binding.progressBar.visibility = View.VISIBLE
        binding.rvPlaylists.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val result = musicRepository.getPlaylists()
                result.fold(
                    onSuccess = { playlistList ->
                        playlists.clear()
                        playlists.addAll(playlistList)
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
                        binding.progressBar.visibility = View.GONE
                        binding.tvEmptyState.visibility = View.VISIBLE
                        binding.tvEmptyState.text = "Error al cargar playlists: ${error.message}"
                        Toast.makeText(requireContext(), "Error al cargar playlists", Toast.LENGTH_SHORT).show()
                    }
                )
            } catch (e: Exception) {
                binding.progressBar.visibility = View.GONE
                binding.tvEmptyState.visibility = View.VISIBLE
                binding.tvEmptyState.text = "Error: ${e.message}"
                Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_SHORT).show()
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
                    Toast.makeText(requireContext(), "Ingresa un nombre para la playlist", Toast.LENGTH_SHORT).show()
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
                        // Agregar la nueva playlist a la lista y actualizar el adapter
                        playlists.add(0, newPlaylist)
                        playlistAdapter.updatePlaylists(playlists)

                        // Actualizar visibilidad
                        binding.tvEmptyState.visibility = View.GONE
                        binding.rvPlaylists.visibility = View.VISIBLE

                        Toast.makeText(requireContext(), "Playlist creada: $name", Toast.LENGTH_SHORT).show()

                        // Agregar automáticamente la canción a la nueva playlist
                        song?.let { currentSong ->
                            addSongToPlaylist(newPlaylist)
                        }
                    },
                    onFailure = { error ->
                        Toast.makeText(requireContext(), "Error al crear playlist: ${error.message}", Toast.LENGTH_SHORT).show()
                    }
                )
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun addSongToPlaylist(playlist: Playlist) {
        song?.let { currentSong ->
            lifecycleScope.launch {
                try {
                    // Primero, evitar duplicados: verificar si la canción ya está en la playlist
                    val existingSongsResult = musicRepository.getPlaylistSongs(playlist.id)
                    existingSongsResult.fold(
                        onSuccess = { songs ->
                            val alreadyInPlaylist = songs.any { it.id == currentSong.id }
                            if (alreadyInPlaylist) {
                                Toast.makeText(
                                    requireContext(),
                                    "\"${currentSong.title}\" ya está en \"${playlist.name}\"",
                                    Toast.LENGTH_SHORT
                                ).show()
                                return@launch
                            }
                        },
                        onFailure = { error ->
                            Toast.makeText(
                                requireContext(),
                                "No se pudo verificar duplicados: ${error.message}",
                                Toast.LENGTH_SHORT
                            ).show()
                            return@launch
                        }
                    )

                    // Si no está, agregarla
                    val result = musicRepository.addSongToPlaylist(playlist.id, currentSong.id)
                    result.fold(
                        onSuccess = {
                            Toast.makeText(
                                requireContext(),
                                "\"${currentSong.title}\" agregada a \"${playlist.name}\"",
                                Toast.LENGTH_SHORT
                            ).show()
                            dismiss()
                        },
                        onFailure = { error ->
                            Toast.makeText(
                                requireContext(),
                                "Error al agregar canción: ${error.message}",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    )
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
