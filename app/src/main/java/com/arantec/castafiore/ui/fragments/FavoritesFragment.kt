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
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.arantec.castafiore.R
import com.arantec.castafiore.data.models.Song
import com.arantec.castafiore.data.repository.MusicRepository
import com.arantec.castafiore.databinding.FragmentFavoritesBinding
import com.arantec.castafiore.service.MusicService
import com.arantec.castafiore.ui.adapters.SongAdapter
import kotlinx.coroutines.launch

class FavoritesFragment : Fragment() {

    private var _binding: FragmentFavoritesBinding? = null
    private val binding get() = _binding!!

    private lateinit var musicRepository: MusicRepository
    private lateinit var songAdapter: SongAdapter
    private var musicService: MusicService? = null
    private var isBound = false

    private val favoriteSongs = mutableListOf<Song>()
    private var isPlaying = false

    private var playbackStateListener: ((Boolean) -> Unit)? = null
    private var songChangeListener: ((Song?) -> Unit)? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MusicService.MusicBinder
            musicService = binder.getService()
            isBound = true
            setupMusicServiceListeners()
            // Estado inicial
            isPlaying = musicService?.isPlaying() == true && isFavoritesQueuePlaying()
            updatePlayButton()
            songAdapter.setPlayingSong(musicService?.getCurrentSong()?.id)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            cleanupListeners()
            musicService = null
            isBound = false
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentFavoritesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        musicRepository = MusicRepository.getInstance(requireContext())

        setupToolbar()
        setupRecyclerView()
        bindMusicService()
        loadFavorites()
        setupFab()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }
        // Fondo estático oscuro para mantener coherencia con AlbumDetail
        binding.gradientBackground.setBackgroundColor(0xFF121212.toInt())
    }

    private fun setupRecyclerView() {
        songAdapter = SongAdapter(
            onSongClick = { _, position ->
                if (favoriteSongs.isNotEmpty()) {
                    musicService?.playQueue(favoriteSongs, position)
                }
            },
            onSongMoreClick = { song ->
                showSongOptions(song)
            }
        )

        binding.rvSongs.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = songAdapter
            isNestedScrollingEnabled = false
        }
    }

    private fun setupFab() {
        binding.fabPlay.setOnClickListener {
            val service = musicService ?: return@setOnClickListener
            if (isFavoritesQueuePlaying()) {
                if (service.isPlaying()) service.pause() else service.play()
            } else if (favoriteSongs.isNotEmpty()) {
                service.playQueue(favoriteSongs, 0)
            }
        }
    }

    private fun bindMusicService() {
        val intent = Intent(requireContext(), MusicService::class.java)
        requireContext().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun loadFavorites() {
        binding.progressBar.visibility = View.VISIBLE
        binding.emptyLayout.visibility = View.GONE
        binding.rvSongs.visibility = View.GONE

        viewLifecycleOwner.lifecycleScope.launch {
            musicRepository.getStarredSongs().fold(
                onSuccess = { songs ->
                    favoriteSongs.clear()
                    favoriteSongs.addAll(songs)
                    songAdapter.updateSongs(favoriteSongs)

                    // Info header
                    binding.tvTitle.text = getString(R.string.favorite_songs_title)
                    binding.tvInfo.text = buildInfoText(favoriteSongs)

                    binding.progressBar.visibility = View.GONE
                    if (favoriteSongs.isEmpty()) {
                        binding.emptyLayout.visibility = View.VISIBLE
                    } else {
                        binding.rvSongs.visibility = View.VISIBLE
                    }

                    // Actualizar botón play según estado actual
                    isPlaying = musicService?.isPlaying() == true && isFavoritesQueuePlaying()
                    updatePlayButton()
                },
                onFailure = {
                    binding.progressBar.visibility = View.GONE
                    binding.emptyLayout.visibility = View.VISIBLE
                    binding.tvEmpty.text = getString(R.string.error_loading_favorites)
                }
            )
        }
    }

    private fun buildInfoText(list: List<Song>): String {
        val count = list.size
        val totalSeconds = list.sumOf { it.duration }
        val minutes = totalSeconds / 60
        val hours = minutes / 60
        val remMin = minutes % 60
        val durationText = if (hours > 0) "$hours h ${remMin} min" else "${minutes} min"
        return "$count canciones • $durationText"
    }

    private fun isFavoritesQueuePlaying(): Boolean {
        val current = musicService?.getCurrentSong() ?: return false
        return favoriteSongs.any { it.id == current.id }
    }

    private fun setupMusicServiceListeners() {
        musicService?.let { service ->
            cleanupListeners()

            playbackStateListener = { playing ->
                if (isAdded && _binding != null) {
                    requireActivity().runOnUiThread {
                        isPlaying = playing && isFavoritesQueuePlaying()
                        updatePlayButton()
                    }
                }
            }

            songChangeListener = { song ->
                if (isAdded && _binding != null) {
                    requireActivity().runOnUiThread {
                        isPlaying = service.isPlaying() && isFavoritesQueuePlaying()
                        updatePlayButton()
                        songAdapter.setPlayingSong(song?.id)
                    }
                }
            }

            playbackStateListener?.let { service.addPlaybackStateListener(it) }
            songChangeListener?.let { service.addSongChangeListener(it) }
        }
    }

    private fun cleanupListeners() {
        musicService?.let { service ->
            playbackStateListener?.let { service.removePlaybackStateListener(it) }
            songChangeListener?.let { service.removeSongChangeListener(it) }
        }
        playbackStateListener = null
        songChangeListener = null
    }

    private fun updatePlayButton() {
        if (!isAdded || _binding == null) return
        if (Thread.currentThread() == requireActivity().mainLooper.thread) {
            binding.fabPlay.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        } else {
            requireActivity().runOnUiThread {
                if (isAdded && _binding != null) {
                    binding.fabPlay.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
                }
            }
        }
    }

    private fun showSongOptions(song: Song) {
        val bottomSheet = com.arantec.castafiore.ui.dialogs.SongOptionsBottomSheet
            .newInstance(song, false)
            .setOnDownloadClickListener { selectedSong ->
                val downloadManager = com.arantec.castafiore.data.download.SongDownloadManager.getInstance(requireContext())
                when {
                    downloadManager.isSongDownloaded(selectedSong.id) -> {
                        android.widget.Toast.makeText(requireContext(), getString(R.string.song_already_downloaded), android.widget.Toast.LENGTH_SHORT).show()
                    }
                    downloadManager.isSongDownloading(selectedSong.id) -> {
                        downloadManager.cancelDownload(selectedSong.id)
                        android.widget.Toast.makeText(requireContext(), getString(R.string.download_canceled, selectedSong.title), android.widget.Toast.LENGTH_SHORT).show()
                    }
                    else -> {
                        downloadManager.downloadSong(selectedSong)
                        android.widget.Toast.makeText(requireContext(), getString(R.string.download_started, selectedSong.title), android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setOnDeleteDownloadClickListener { selectedSong ->
                val downloadManager = com.arantec.castafiore.data.download.SongDownloadManager.getInstance(requireContext())
                if (downloadManager.isSongDownloaded(selectedSong.id)) {
                    val builder = android.app.AlertDialog.Builder(requireContext())
                    builder.setTitle(R.string.delete_download)
                    builder.setMessage(getString(R.string.delete_download_confirm, selectedSong.title))
                    builder.setPositiveButton(R.string.delete) { dialogInterface: android.content.DialogInterface, _: Int ->
                        val success = downloadManager.deleteSong(selectedSong.id)
                        val msg = if (success) R.string.download_deleted else R.string.download_delete_error
                        android.widget.Toast.makeText(requireContext(), getString(msg), android.widget.Toast.LENGTH_SHORT).show()
                    }
                    builder.setNegativeButton(R.string.cancel, null)
                    builder.show()
                } else {
                    android.widget.Toast.makeText(requireContext(), getString(R.string.song_not_downloaded), android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        bottomSheet.show(parentFragmentManager, "SongOptionsBottomSheet")
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanupListeners()
        if (isBound) {
            requireContext().unbindService(serviceConnection)
            isBound = false
        }
        _binding = null
    }
}
