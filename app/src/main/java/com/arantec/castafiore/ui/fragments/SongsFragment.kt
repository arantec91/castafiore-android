package com.arantec.castafiore.ui.fragments

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.arantec.castafiore.data.models.Song
import com.arantec.castafiore.data.repository.MusicRepository
import com.arantec.castafiore.databinding.FragmentMusicListBinding
import com.arantec.castafiore.service.MusicService
import com.arantec.castafiore.ui.adapters.SongAdapter
import com.arantec.castafiore.ui.helpers.HasContentState
import kotlinx.coroutines.*

class SongsFragment : Fragment(), HasContentState {

    private var _binding: FragmentMusicListBinding? = null
    private val binding get() = _binding!!

    private lateinit var musicRepository: MusicRepository
    private lateinit var songAdapter: SongAdapter
    private var musicService: MusicService? = null
    private var isBound = false

    companion object {
        private const val TAG = "SongsFragment"
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MusicService.MusicBinder
            musicService = binder.getService()
            isBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            musicService = null
            isBound = false
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMusicListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        musicRepository = MusicRepository.getInstance(requireContext())
        setupRecyclerView()
        loadRandomSongs()
    }

    private fun setupRecyclerView() {
        songAdapter = SongAdapter(
            onSongClick = { song, position ->
                playSong(song)
            },
            onSongMoreClick = { song ->
                showSongOptions(song)
            }
        )

        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = songAdapter
        }
    }

    private fun bindMusicService() {
        val intent = Intent(requireContext(), MusicService::class.java)
        requireContext().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun unbindMusicService() {
        if (isBound) {
            requireContext().unbindService(serviceConnection)
            isBound = false
            musicService = null
        }
    }

    private fun playSong(song: Song) {
        if (isBound && musicService != null) {
            musicService?.playSong(
                song,
                MusicService.PlaybackSource(
                    MusicService.SourceType.SONGS,
                    null,
                    "Canciones"
                )
            )
        }
    }

    private fun showSongOptions(song: Song) {
        // TODO: Implementar menú de opciones para la canción
    }

    private fun loadRandomSongs() {
        Log.d(TAG, "Starting to load random songs...")
        // Only show local loader if list already has content (refresh behavior)
        if (hasContent()) {
            binding.progressBar.visibility = View.VISIBLE
        }
        binding.tvEmpty.visibility = View.GONE
        // Don’t pre-hide recyclerView on initial loads; global overlay will cover it

        CoroutineScope(Dispatchers.Main).launch {
            try {
                Log.d(TAG, "Making network request...")
                val result = withContext(Dispatchers.IO) {
                    musicRepository.getRandomSongs()
                }

                result.onSuccess { songs ->
                    Log.d(TAG, "Successfully received ${songs.size} songs")
                    if (songs.isNotEmpty()) {
                        songAdapter.updateSongs(songs)
                        binding.recyclerView.visibility = View.VISIBLE
                        binding.tvEmpty.visibility = View.GONE
                    } else {
                        Log.w(TAG, "No songs returned from server")
                        binding.tvEmpty.text = "No hay canciones disponibles"
                        binding.tvEmpty.visibility = View.VISIBLE
                        binding.recyclerView.visibility = View.GONE
                    }
                }.onFailure { error ->
                    Log.e(TAG, "Error loading songs: ${error.message}", error)
                    binding.tvEmpty.text = "Error: ${error.message}"
                    binding.tvEmpty.visibility = View.VISIBLE
                    binding.recyclerView.visibility = View.GONE
                }

                binding.progressBar.visibility = View.GONE
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error in loadRandomSongs: ${e.message}", e)
                binding.progressBar.visibility = View.GONE
                binding.tvEmpty.text = "Error inesperado: ${e.message}"
                binding.tvEmpty.visibility = View.VISIBLE
                binding.recyclerView.visibility = View.GONE
            }
        }
    }

    fun searchSongs(query: String) {
        if (query.isBlank()) {
            loadRandomSongs()
            return
        }

        // Only show local loader if list already has content (refresh behavior)
        if (hasContent()) {
            binding.progressBar.visibility = View.VISIBLE
        }
        binding.tvEmpty.visibility = View.GONE
        // Don’t pre-hide recyclerView on initial loads; global overlay will cover it

        CoroutineScope(Dispatchers.Main).launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    musicRepository.searchMusic(query)
                }

                result.onSuccess { (songs, _, _) ->
                    if (songs.isNotEmpty()) {
                        songAdapter.updateSongs(songs)
                        binding.recyclerView.visibility = View.VISIBLE
                        binding.tvEmpty.visibility = View.GONE
                    } else {
                        binding.tvEmpty.text = "No se encontraron canciones"
                        binding.tvEmpty.visibility = View.VISIBLE
                        binding.recyclerView.visibility = View.GONE
                    }
                }.onFailure { error ->
                    binding.tvEmpty.text = "Error en búsqueda: ${error.message}"
                    binding.tvEmpty.visibility = View.VISIBLE
                    binding.recyclerView.visibility = View.GONE
                }

            } catch (e: Exception) {
                binding.tvEmpty.text = "Error al buscar: ${e.message}"
                binding.tvEmpty.visibility = View.VISIBLE
                binding.recyclerView.visibility = View.GONE
            } finally {
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    override fun onStart() {
        super.onStart()
        bindMusicService()
    }

    override fun onStop() {
        super.onStop()
        unbindMusicService()
    }

    override fun onDestroy() {
        super.onDestroy()
        unbindMusicService()
        _binding = null
    }

    override fun hasContent(): Boolean {
        return this::songAdapter.isInitialized && songAdapter.itemCount > 0
    }
}
