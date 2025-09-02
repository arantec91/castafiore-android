package com.arantec.castafiore.ui.fragments

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.arantec.castafiore.data.download.SongDownloadManager
import com.arantec.castafiore.data.models.Song
import com.arantec.castafiore.databinding.FragmentDownloadsBinding
import com.arantec.castafiore.service.MusicService
import com.arantec.castafiore.ui.adapters.SongAdapter
import com.arantec.castafiore.ui.helpers.HasContentState
import com.arantec.castafiore.utils.StatusBarUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.arantec.castafiore.R
import com.arantec.castafiore.ui.dialogs.SongOptionsBottomSheet
import com.arantec.castafiore.ui.dialogs.PlaylistSelectorBottomSheet
import androidx.navigation.fragment.findNavController
import androidx.core.os.bundleOf
import com.arantec.castafiore.data.repository.MusicRepository
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.flow.collect

class DownloadsFragment : Fragment(), HasContentState {

    private var _binding: FragmentDownloadsBinding? = null
    private val binding get() = _binding!!

    private lateinit var songAdapter: SongAdapter
    private lateinit var downloadManager: SongDownloadManager
    private var musicService: MusicService? = null
    private var isBound = false
    private var songs: List<Song> = emptyList()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MusicService.MusicBinder
            musicService = binder.getService()
            isBound = true
            // Highlight currently playing if belongs to downloads
            musicService?.addSongChangeListener { song ->
                songAdapter.setPlayingSong(song?.id)
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            musicService = null
            isBound = false
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDownloadsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        downloadManager = SongDownloadManager.getInstance(requireContext())

        // Evitar que el contenido se dibuje detrás de la status bar (comportamiento consistente con Home)
        StatusBarUtils.applyStatusBarTopPadding(binding.root)

        setupRecyclerView()
        setupDownloadObservers()
        loadDownloads()
    }

    private fun setupDownloadObservers() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                downloadManager.downloadStates.collect { states ->
                    states.values.forEach { state ->
                        songAdapter.updateDownloadState(state)
                    }
                }
            }
        }
    }

    private fun setupRecyclerView() {
        songAdapter = SongAdapter(
            onSongClick = { song, _ -> playSong(song) },
            onSongMoreClick = { song -> showMoreOptions(song) },
            showCover = true
        )
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = songAdapter
        }
    }

    private fun showMoreOptions(song: Song) {
        val sheet = SongOptionsBottomSheet
            .newInstance(song)
            .setOnAddToQueueClickListener { s ->
                if (isBound) musicService?.addToQueue(s)
            }
            .setOnPlayNextClickListener { s ->
                if (isBound) musicService?.playNext(s)
            }
            .setOnAddToPlaylistClickListener { s ->
                // Abrir selector de playlist
                val selector = PlaylistSelectorBottomSheet.newInstance(s)
                selector.show(childFragmentManager, "PlaylistSelectorBottomSheet")
            }
            .setOnViewAlbumClickListener { s ->
                val albumId = s.albumId
                if (!albumId.isNullOrEmpty()) {
                    // Cargar álbum y navegar
                    viewLifecycleOwner.lifecycleScope.launch {
                        val repo = MusicRepository.getInstance(requireContext())
                        val result = withContext(Dispatchers.IO) { repo.getAlbumDetail(albumId) }
                        result.fold(
                            onSuccess = { album ->
                                try {
                                    findNavController().navigate(
                                        R.id.albumDetailFragment,
                                        bundleOf("album" to album)
                                    )
                                } catch (_: Exception) {}
                            },
                            onFailure = {
                                android.widget.Toast.makeText(requireContext(), getString(R.string.error_loading_favorites), android.widget.Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }
            }
            .setOnViewArtistClickListener { s ->
                val artistId = s.artistId
                if (!artistId.isNullOrEmpty()) {
                    try {
                        findNavController().navigate(
                            R.id.artistDetailFragment,
                            bundleOf(
                                "artistId" to artistId,
                                "artistName" to (s.artist)
                            )
                        )
                    } catch (_: Exception) {}
                }
            }
        // Ocultar "Ver álbum" y "Ver artista" si no tenemos IDs disponibles en modo descargas
        if (song.albumId.isNullOrEmpty()) {
            sheet.hideViewAlbumOption()
        }
        if (song.artistId.isNullOrEmpty()) {
            sheet.hideViewArtistOption()
        }
        sheet.show(childFragmentManager, "SongOptionsBottomSheet")
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
            // Play within this list context
            val source = MusicService.PlaybackSource(MusicService.SourceType.DOWNLOADS, null, getString(R.string.bottom_downloads))
            musicService?.playQueue(songs, songs.indexOfFirst { it.id == song.id }.coerceAtLeast(0), source)
        }
    }

    private fun loadDownloads() {
        binding.progressBar.visibility = if (hasContent()) View.VISIBLE else View.GONE
        binding.tvEmpty.visibility = View.GONE
        CoroutineScope(Dispatchers.Main).launch {
            val list = withContext(Dispatchers.IO) { downloadManager.getAllDownloadedSongs() }
            songs = list
            if (list.isNotEmpty()) {
                songAdapter.updateSongs(list)
                binding.recyclerView.visibility = View.VISIBLE
                binding.tvEmpty.visibility = View.GONE
            } else {
                binding.tvEmpty.text = getString(R.string.downloads_empty)
                binding.tvEmpty.visibility = View.VISIBLE
                binding.recyclerView.visibility = View.GONE
            }
            binding.progressBar.visibility = View.GONE
        }
    }

    override fun onStart() {
        super.onStart()
        bindMusicService()
    }

    override fun onResume() {
        super.onResume()
        loadDownloads()
    }

    override fun onStop() {
        super.onStop()
        unbindMusicService()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun hasContent(): Boolean = this::songAdapter.isInitialized && songAdapter.itemCount > 0
}
