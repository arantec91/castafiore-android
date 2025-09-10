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
import android.view.ViewTreeObserver
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.arantec.castafiore.R
import com.arantec.castafiore.data.models.Song
import com.arantec.castafiore.databinding.FragmentDownloadsBinding
import com.arantec.castafiore.service.MusicService
import com.arantec.castafiore.ui.adapters.SongAdapter
import com.arantec.castafiore.ui.helpers.HasContentState
import com.arantec.castafiore.ui.helpers.LoadingHost
import com.arantec.castafiore.utils.StatusBarUtils
import com.arantec.castafiore.utils.snack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random
import android.os.SystemClock

class DownloadsFragment : Fragment(), HasContentState {

    private var _binding: FragmentDownloadsBinding? = null
    private val binding get() = _binding!!

    private lateinit var songAdapter: SongAdapter
    private var musicService: MusicService? = null
    private var isBound = false

    private val downloadedSongs = mutableListOf<Song>()
    private var isPlaying = false

    private var playbackStateListener: ((Boolean) -> Unit)? = null
    private var songChangeListener: ((Song?) -> Unit)? = null

    private lateinit var downloadManager: com.arantec.castafiore.data.download.SongDownloadManager

    // Throttle UI updates for download states
    private var lastDownloadUiUpdateMs: Long = 0L

    // Track if loading was triggered by pull-to-refresh
    private var isManualRefresh: Boolean = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? MusicService.MusicBinder ?: return
            musicService = binder.getService()
            isBound = true
            setupMusicServiceListeners()
            val serviceIsPlaying = musicService?.isPlaying() == true
            val inContext = isDownloadsQueuePlaying()
            isPlaying = serviceIsPlaying && inContext
            updatePlayButton()
            val currentId = musicService?.getCurrentSong()?.id
            if (isAdded && _binding != null) {
                songAdapter.setPlayingSong(if (inContext) currentId else null)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            cleanupListeners()
            musicService = null
            isBound = false
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        StatusBarUtils.setStatusBarColor(this)
        _binding = FragmentDownloadsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        downloadManager = com.arantec.castafiore.data.download.SongDownloadManager.getInstance(requireContext())

        setupToolbar()
        setupRecyclerView()
        setupFab()
        setupDownloadObservers()
        setupRefreshAndRetry()
        // Initial load with shimmer
        loadDownloads(isRefresh = false)
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener { findNavController().popBackStack() }
    }

    private fun setupRecyclerView() {
        songAdapter = SongAdapter(
            onSongClick = { _, position ->
                val service = musicService ?: return@SongAdapter
                if (downloadedSongs.isNotEmpty()) {
                    service.playQueue(
                        downloadedSongs,
                        position,
                        MusicService.PlaybackSource(
                            MusicService.SourceType.DOWNLOADS,
                            null,
                            getString(R.string.bottom_downloads)
                        )
                    )
                }
            },
            onSongMoreClick = { song ->
                showSongOptions(song)
            },
            showCover = true,
            circularDownloadInIcon = true
        )

        val nonScrollableLm = object : LinearLayoutManager(context) {
            override fun canScrollVertically(): Boolean = false
        }

        binding.rvSongs.apply {
            layoutManager = nonScrollableLm
            adapter = songAdapter
            isNestedScrollingEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }

        // When the differ applies updates, force a layout pass to make sure items are rendered
        songAdapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() { binding.rvSongs.requestLayout() }
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) { binding.rvSongs.requestLayout() }
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) { binding.rvSongs.requestLayout() }
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int) { binding.rvSongs.requestLayout() }
            override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) { binding.rvSongs.requestLayout() }
        })
    }

    private fun setupFab() {
        binding.fabPlay.setOnClickListener {
            val service = musicService ?: return@setOnClickListener
            if (isDownloadsQueuePlaying()) {
                if (service.isPlaying()) service.pause() else service.play()
            } else if (downloadedSongs.isNotEmpty()) {
                val startIndex = if (service.getShuffleEnabled() && downloadedSongs.size > 1) {
                    Random.nextInt(downloadedSongs.size)
                } else 0
                service.playQueue(
                    downloadedSongs,
                    startIndex,
                    MusicService.PlaybackSource(
                        MusicService.SourceType.DOWNLOADS,
                        null,
                        getString(R.string.bottom_downloads)
                    )
                )
            }
        }
    }

    private fun bindMusicService() {
        val intent = Intent(requireContext(), MusicService::class.java)
        requireContext().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun unbindMusicService() {
        if (isBound) {
            cleanupListeners()
            requireContext().unbindService(serviceConnection)
            isBound = false
            musicService = null
        }
    }

    private fun setupRefreshAndRetry() {
        binding.swipeRefreshLayout.setColorSchemeResources(
            R.color.primary,
            R.color.primary_dark,
            R.color.secondary
        )
        binding.swipeRefreshLayout.setOnRefreshListener {
            isManualRefresh = true
            loadDownloads(isRefresh = true)
        }
        binding.btnRetry.setOnClickListener {
            isManualRefresh = false
            loadDownloads(isRefresh = false)
        }
    }

    private fun showShimmer(show: Boolean) {
        if (!isAdded || _binding == null) return
        val shimmer = binding.shimmerSongs
        if (show) {
            shimmer.visibility = View.VISIBLE
            try { shimmer.startShimmer() } catch (_: Exception) {}
        } else {
            try { shimmer.stopShimmer() } catch (_: Exception) {}
            shimmer.visibility = View.GONE
        }
    }

    private fun loadDownloads(isRefresh: Boolean = false) {
        // Loading UX
        if (!isRefresh) {
            showShimmer(true)
            binding.emptyLayout.visibility = View.GONE
            binding.rvSongs.visibility = View.GONE
            binding.loadingOverlay.visibility = View.GONE
            binding.progressBar.visibility = View.GONE
        }

        viewLifecycleOwner.lifecycleScope.launch {
            // Offload I/O to a background dispatcher to avoid blocking the main thread
            val songs = withContext(Dispatchers.IO) {
                try {
                    downloadManager.getAllDownloadedSongs()
                } catch (_: Exception) {
                    emptyList()
                }
            }
            if (!isAdded || _binding == null) return@launch

            downloadedSongs.clear()
            downloadedSongs.addAll(songs)
            songAdapter.updateSongs(downloadedSongs)
            // Force a layout pass so RecyclerView measures itself after async diff apply
            binding.rvSongs.post { binding.rvSongs.requestLayout() }

            binding.tvTitle.text = getString(R.string.bottom_downloads)
            updateInfoAndEmptyState()

            isPlaying = musicService?.isPlaying() == true && isDownloadsQueuePlaying()
            updatePlayButton()

            // Stop loading visuals
            showShimmer(false)
            if (binding.swipeRefreshLayout.isRefreshing || isManualRefresh) {
                binding.swipeRefreshLayout.isRefreshing = false
                isManualRefresh = false
            }
            binding.loadingOverlay.visibility = View.GONE
            binding.progressBar.visibility = View.GONE
        }
    }

    private fun hideLoadingOverlayAfterNextDraw() {
        // Retained for compatibility; shimmer now handles loading UX
        if (!isAdded || _binding == null) return
        val root = binding.root
        val listener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (!isAdded || _binding == null) return true
                root.viewTreeObserver.removeOnPreDrawListener(this)
                binding.loadingOverlay.visibility = View.GONE
                binding.progressBar.visibility = View.GONE
                return true
            }
        }
        root.viewTreeObserver.addOnPreDrawListener(listener)
    }

    private fun buildInfoText(list: List<Song>): String {
        val count = list.size
        val totalSeconds = list.sumOf { it.duration }
        val minutes = totalSeconds / 60
        val hours = minutes / 60
        val remMin = minutes % 60
        val durationText = if (hours > 0) "$hours h $remMin min" else "$minutes min"
        return "$count canciones • $durationText"
    }

    private fun isDownloadsQueuePlaying(): Boolean {
        val service = musicService ?: return false
        val src = service.getPlaybackSource()
        return src?.type == MusicService.SourceType.DOWNLOADS
    }

    private fun setupMusicServiceListeners() {
        musicService?.let { service ->
            cleanupListeners()

            playbackStateListener = { playing ->
                if (isAdded && _binding != null) {
                    requireActivity().runOnUiThread {
                        isPlaying = playing && isDownloadsQueuePlaying()
                        updatePlayButton()
                    }
                }
            }

            songChangeListener = { song ->
                if (isAdded && _binding != null) {
                    requireActivity().runOnUiThread {
                        val inContext = isDownloadsQueuePlaying()
                        isPlaying = service.isPlaying() && inContext
                        updatePlayButton()
                        songAdapter.setPlayingSong(if (inContext) song?.id else null)
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

    private fun setupDownloadObservers() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                downloadManager.downloadStates
                    .collect { states ->
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastDownloadUiUpdateMs < 250L) return@collect
                        lastDownloadUiUpdateMs = now

                        // Update per-item download UI
                        states.values.forEach { state ->
                            songAdapter.updateDownloadState(state)
                        }
                        // Remove items that were deleted in real time
                        val toRemove = states.values
                            .filter { st ->
                                (st.status == com.arantec.castafiore.data.download.SongDownloadManager.DownloadStatus.CANCELLED ||
                                 st.status == com.arantec.castafiore.data.download.SongDownloadManager.DownloadStatus.FAILED) &&
                                downloadedSongs.any { it.id == st.songId } &&
                                !downloadManager.isSongDownloaded(st.songId)
                            }
                            .map { it.songId }
                            .toSet()

                        if (toRemove.isNotEmpty()) {
                            downloadedSongs.removeAll { it.id in toRemove }
                            if (isAdded && _binding != null) {
                                songAdapter.updateSongs(downloadedSongs)
                                updateInfoAndEmptyState()
                            }
                        }
                    }
            }
        }
    }

    private fun updateInfoAndEmptyState() {
        if (!isAdded || _binding == null) return
        binding.tvInfo.text = buildInfoText(downloadedSongs)
        if (downloadedSongs.isEmpty()) {
            binding.emptyLayout.visibility = View.VISIBLE
            binding.btnRetry.visibility = View.GONE
            binding.rvSongs.visibility = View.GONE
        } else {
            binding.rvSongs.visibility = View.VISIBLE
            binding.emptyLayout.visibility = View.GONE
            binding.btnRetry.visibility = View.GONE
        }
    }

    private fun showSongOptions(song: Song) {
        val bottomSheet = com.arantec.castafiore.ui.dialogs.SongOptionsBottomSheet
            .newInstance(song, false)
            .setOnAddToQueueClickListener { selectedSong ->
                val service = musicService
                if (service != null) {
                    service.addToQueue(selectedSong)
                    snack(getString(R.string.added_to_queue, selectedSong.title))
                } else {
                    snack(getString(R.string.music_service_unavailable))
                    bindMusicService()
                }
            }
            .setOnPlayNextClickListener { selectedSong ->
                val service = musicService
                if (service != null) {
                    service.playNext(selectedSong)
                    snack(getString(R.string.will_play_next, selectedSong.title))
                } else {
                    snack(getString(R.string.music_service_unavailable))
                    bindMusicService()
                }
            }
            .setOnAddToPlaylistClickListener { selectedSong ->
                com.arantec.castafiore.ui.dialogs.PlaylistSelectorBottomSheet
                    .newInstance(selectedSong)
                    .show(childFragmentManager, "PlaylistSelectorBottomSheet")
            }
            .setOnViewAlbumClickListener { selectedSong ->
                val albumId = selectedSong.albumId
                if (!albumId.isNullOrEmpty()) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        com.arantec.castafiore.data.repository.MusicRepository.getInstance(requireContext()).getAlbumDetail(albumId).fold(
                            onSuccess = { album ->
                                val args = Bundle().apply { putParcelable("album", album) }
                                try { findNavController().navigate(R.id.albumDetailFragment, args) } catch (_: Exception) { snack("No se pudo abrir el álbum") }
                            },
                            onFailure = { snack("No se pudo abrir el álbum") }
                        )
                    }
                } else {
                    snack("Álbum no disponible")
                }
            }
            .setOnViewArtistClickListener { selectedSong ->
                val artistId = selectedSong.artistId
                if (!artistId.isNullOrEmpty()) {
                    val args = Bundle().apply {
                        putString("artistId", artistId)
                        putString("artistName", selectedSong.artist)
                    }
                    try { findNavController().navigate(R.id.artistDetailFragment, args) } catch (_: Exception) { snack("No se pudo abrir el artista") }
                } else {
                    snack("Artista no disponible")
                }
            }
            .setOnSongInfoClickListener { selectedSong ->
                // Reuse Favorites dialog for consistency
                val dialogBuilder = androidx.appcompat.app.AlertDialog.Builder(requireContext())
                val inflater = LayoutInflater.from(requireContext())
                val dialogView = inflater.inflate(R.layout.dialog_song_info, null)
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
                tvInfoTitle.text = selectedSong.title
                tvInfoArtist.text = selectedSong.artist
                tvInfoAlbum.text = selectedSong.album
                tvInfoDuration.text = formatSongDuration(selectedSong.duration)
                tvInfoGenre.text = selectedSong.genre ?: "Desconocido"
                tvInfoYear.text = selectedSong.year?.toString() ?: "Desconocido"
                tvInfoBitrate.text = if (selectedSong.bitRate != null) "${'$'}{selectedSong.bitRate} kbps" else "Desconocido"
                tvInfoFormat.text = selectedSong.suffix?.uppercase() ?: "Desconocido"
                tvInfoFileSize.text = com.arantec.castafiore.data.download.SongDownloadManager.getInstance(requireContext()).formatFileSize(
                    com.arantec.castafiore.data.download.SongDownloadManager.getInstance(requireContext()).getSongFileSize(selectedSong.id)
                )
                try {
                    val repo = com.arantec.castafiore.data.repository.MusicRepository.getInstance(requireContext())
                    if (repo.serverUrl != null && selectedSong.coverArt != null) {
                        val (username, token, salt) = repo.getAuthParams()
                        val coverUrl = selectedSong.getCoverArtUrl(repo.serverUrl!!, username, token, salt)
                        com.bumptech.glide.Glide.with(this)
                            .load(coverUrl)
                            .placeholder(R.drawable.ic_album_placeholder)
                            .error(R.drawable.ic_album_placeholder)
                            .into(ivInfoCover)
                    } else {
                        ivInfoCover.setImageResource(R.drawable.ic_album_placeholder)
                    }
                } catch (_: Exception) {
                    ivInfoCover.setImageResource(R.drawable.ic_album_placeholder)
                }
                dialogBuilder.setView(dialogView)
                    .setTitle("Información de la canción")
                    .setPositiveButton("Cerrar") { dialog, _ -> dialog.dismiss() }
                    .show()
            }
        bottomSheet.show(childFragmentManager, "SongOptionsBottomSheet")
    }

    private fun formatSongDuration(seconds: Int): String {
        val minutes = seconds / 60
        val remainingSeconds = seconds % 60
        return String.format(java.util.Locale.getDefault(), "%d:%02d", minutes, remainingSeconds)
    }

    override fun onStart() {
        super.onStart()
        bindMusicService()
    }

    override fun onStop() {
        super.onStop()
        unbindMusicService()
    }

    override fun onResume() {
        super.onResume()
        StatusBarUtils.setStatusBarColor(this)
        // Ensure any global overlay is hidden on this screen
        (activity as? LoadingHost)?.showGlobalLoading(false)
        // Silent refresh to avoid showing the SwipeRefresh spinner (which can appear centered briefly)
        isManualRefresh = false
        loadDownloads(isRefresh = true)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun hasContent(): Boolean {
        return this::songAdapter.isInitialized && songAdapter.itemCount > 0
    }
}
