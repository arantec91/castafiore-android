package com.arantec.castafiore.ui.fragments

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.arantec.castafiore.R
import com.arantec.castafiore.data.models.Playlist
import com.arantec.castafiore.data.models.Song
import com.arantec.castafiore.data.repository.MusicRepository
import com.arantec.castafiore.databinding.FragmentPlaylistDetailBinding
import com.arantec.castafiore.service.MusicService
import com.arantec.castafiore.ui.adapters.SongAdapter
import com.arantec.castafiore.utils.ImageLoader
import com.arantec.castafiore.utils.StatusBarUtils
import com.arantec.castafiore.utils.PlaylistFavoritesManager
import com.arantec.castafiore.ui.helpers.HasContentState
import com.arantec.castafiore.ui.helpers.LoadingHost
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import com.bumptech.glide.Glide
import java.util.Locale
import kotlin.random.Random
import androidx.palette.graphics.Palette
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import androidx.core.content.ContextCompat
import android.content.res.ColorStateList
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import android.graphics.drawable.Drawable
import com.arantec.castafiore.utils.snack
import android.os.SystemClock
import kotlinx.coroutines.FlowPreview
import android.view.ViewTreeObserver
import android.util.Log

class PlaylistDetailFragment : Fragment(), HasContentState {

    // Remember last dynamic status bar color to reapply on resume
    private var lastStatusBarTopColor: Int? = null

    private var _binding: FragmentPlaylistDetailBinding? = null
    private val binding get() = _binding!!

    private lateinit var musicRepository: MusicRepository
    private lateinit var songAdapter: SongAdapter
    private var musicService: MusicService? = null
    private var isBound = false

    private var playlistId: String? = null
    private var playlistName: String? = null
    private var playlistInfo: Playlist? = null

    private val playlistSongs = mutableListOf<Song>()
    private var isPlaying = false
    // Auto-favorite guard to avoid repeated toggles
    private var autoFavApplied: Boolean = false

    // Track last time we updated UI for download states to throttle updates
    private var lastDownloadUiUpdateMs: Long = 0L

    private var playbackStateListener: ((Boolean) -> Unit)? = null
    private var songChangeListener: ((Song?) -> Unit)? = null

    private lateinit var downloadManager: com.arantec.castafiore.data.download.SongDownloadManager

    // Keep last known download states to compute aggregate status accurately in real-time
    private var latestDownloadStates: Map<String, com.arantec.castafiore.data.download.SongDownloadManager.DownloadState> = emptyMap()
    private var lastAllDownloaded: Boolean = false

    // Track whether we've successfully applied a dynamic gradient to avoid redundant work
    private var gradientApplied: Boolean = false

    // Track if loading was triggered by pull-to-refresh to adjust UX
    private var isManualRefresh: Boolean = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MusicService.MusicBinder
            musicService = binder.getService()
            isBound = true
            setupMusicServiceListeners()
            // Estado inicial
            val serviceIsPlaying = musicService?.isPlaying() == true
            val inContext = isPlaylistQueuePlaying()
            isPlaying = serviceIsPlaying && inContext
            updatePlayButton()
            // Solo resaltar canción si este contexto está activo
            val currentId = musicService?.getCurrentSong()?.id
            songAdapter.setPlayingSong(if (inContext) currentId else null)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            cleanupListeners()
            musicService = null
            isBound = false
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        StatusBarUtils.setStatusBarColor(this)
        _binding = FragmentPlaylistDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        musicRepository = MusicRepository.getInstance(requireContext())
        downloadManager = com.arantec.castafiore.data.download.SongDownloadManager.getInstance(requireContext())

        // Args
        playlistId = arguments?.getString("playlistId")
        playlistName = arguments?.getString("playlistName")
        if (playlistId.isNullOrEmpty()) {
            findNavController().popBackStack()
            return
        }

        // Hide More by default; will be shown only if playlist is not public
        binding.btnMore.visibility = View.GONE

        setupToolbar()
        setupRecyclerView()
        setupFab()
        setupMoreButton()
        setupDownloadObservers()
        setupRefreshAndRetry()

        // Cancel group downloads (playlist)
        binding.btnCancelGroupDownload.setOnClickListener { cancelPlaylistGroupDownloads() }

        // Initial load
        loadPlaylist(isRefresh = false)

        // Favorite button toggling
        binding.btnFavorite.setOnClickListener {
            val nowFav = PlaylistFavoritesManager.toggleFavorite(requireContext(), playlistId)
            binding.btnFavorite.setImageResource(if (nowFav) R.drawable.ic_favorite else R.drawable.ic_favorite_border)
            val tintColor = if (nowFav) R.color.primary else R.color.white
            binding.btnFavorite.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), tintColor))

            // If unfavorited, and playlist is public and fully downloaded, remove downloads silently
            val info = playlistInfo
            if (!nowFav && info?.public == true && isPlaylistFullyDownloaded()) {
                val downloadedIds = playlistSongs.filter { downloadManager.isSongDownloaded(it.id) }.map { it.id }
                if (downloadedIds.isNotEmpty()) {
                    val removed = downloadManager.deleteMultipleSongs(downloadedIds)
                    if (removed > 0) {
                        updateDownloadButtonTint()
                        // Optional feedback without confirmation
                        snack("Descargas eliminadas: ${removed}")
                    }
                }
            }
        }

        // Download button behavior
        setupDownloadButton()
        // Apply initial tint if songs already downloaded
        updateDownloadButtonTint()
        // Initialize group progress UI state
        updatePlaylistGroupDownloadUi(downloadManager.downloadStates.value)
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }
    }

    private fun setupRecyclerView() {
        songAdapter = SongAdapter(
            onSongClick = { song, position ->
                // Reproducir la playlist desde la canción seleccionada
                musicService?.playQueue(
                    playlistSongs,
                    position,
                    MusicService.PlaybackSource(
                        MusicService.SourceType.PLAYLIST,
                        playlistId,
                        playlistName
                    )
                )
            },
            onSongMoreClick = { song ->
                showSongOptions(song)
            },
            // Habilitar la visualización de portadas de álbum en los ítems
            showCover = true,
            circularDownloadInIcon = true
        )
        binding.rvSongs.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = songAdapter
            isNestedScrollingEnabled = false
            // Important: do NOT mark fixed size when inside NestedScrollView with wrap_content height,
            // otherwise RecyclerView may not expand after async data is set.
            setHasFixedSize(false)
            // Disable change animations to avoid flicker on frequent partial updates
            itemAnimator = null
        }
    }

    private fun setupFab() {
        binding.fabPlay.setOnClickListener {
            val service = musicService
            if (service != null) {
                if (isPlaylistQueuePlaying()) {
                    if (service.isPlaying()) {
                        service.pause()
                    } else {
                        service.play()
                    }
                } else if (playlistSongs.isNotEmpty()) {
                    val startIndex = if (service.getShuffleEnabled() && playlistSongs.size > 1) Random.nextInt(playlistSongs.size) else 0
                    service.playQueue(
                        playlistSongs,
                        startIndex,
                        MusicService.PlaybackSource(
                            MusicService.SourceType.PLAYLIST,
                            playlistId,
                            playlistName
                        )
                    )
                }
            } else {
                bindMusicService()
            }
        }

        binding.fabRandom.setOnClickListener {
            val service = musicService
            if (service != null) {
                if (playlistSongs.isNotEmpty()) {
                    val shuffled = playlistSongs.shuffled()
                    service.playQueue(
                        shuffled,
                        0,
                        MusicService.PlaybackSource(
                            MusicService.SourceType.PLAYLIST,
                            playlistId,
                            playlistName
                        )
                    )
                }
            } else {
                bindMusicService()
            }
        }
    }

    private fun setupMoreButton() {
        binding.btnMore.setOnClickListener {
            com.arantec.castafiore.ui.dialogs.PlaylistOptionsBottomSheet()
                .setOnEditNameClickListener { showRenameDialog() }
                .setOnDeleteListClickListener { confirmDeletePlaylist() }
                .show(childFragmentManager, "PlaylistOptionsBottomSheet")
        }
    }

    private fun setupRefreshAndRetry() {
        // Swipe-to-refresh colors and listener
        binding.swipeRefreshLayout.setColorSchemeResources(
            R.color.primary,
            R.color.primary_dark,
            R.color.secondary
        )
        binding.swipeRefreshLayout.setOnRefreshListener {
            isManualRefresh = true
            // Do not hide existing content; just refresh data
            loadPlaylist(isRefresh = true)
        }
        // Retry button in error state
        binding.btnRetry.setOnClickListener {
            // Show shimmer for a clean retry
            isManualRefresh = false
            loadPlaylist(isRefresh = false)
        }
    }

    @OptIn(FlowPreview::class)
    private fun setupDownloadObservers() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                downloadManager.downloadStates
                    .sample(250)
                    .collect { states ->
                        if (!isAdded || _binding == null) return@collect

                        // Cache full map for aggregate checks
                        latestDownloadStates = states

                        // Update only visible items to reduce binding churn
                        val lm = binding.rvSongs.layoutManager as? LinearLayoutManager
                        val first = lm?.findFirstVisibleItemPosition() ?: -1
                        val last = lm?.findLastVisibleItemPosition() ?: -1
                        if (first >= 0 && last >= first && playlistSongs.isNotEmpty()) {
                            val safeFirst = first.coerceAtLeast(0)
                            val safeLast = last.coerceAtMost(playlistSongs.size - 1)
                            val visibleIds = playlistSongs.subList(safeFirst, safeLast + 1).map { it.id }.toSet()
                            states.values.forEach { state ->
                                if (state.songId in visibleIds) {
                                    songAdapter.updateDownloadState(state)
                                }
                            }
                        }

                        // Immediate update if we just transitioned to fully downloaded
                        val nowAll = isPlaylistFullyDownloaded()
                        if (nowAll != lastAllDownloaded) {
                            lastAllDownloaded = nowAll
                            updateDownloadButtonTint()
                            if (nowAll) maybeAutoFavorite()
                        }

                        // Update group progress UI for playlist
                        updatePlaylistGroupDownloadUi(states)

                        // Rate-limit periodic UI refresh as a safety net
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastDownloadUiUpdateMs >= 1000L) {
                            lastDownloadUiUpdateMs = now
                            updateDownloadButtonTint()
                            maybeAutoFavorite()
                        }
                    }
            }
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

    // New: shimmer for title
    private fun showTitleShimmer(show: Boolean) {
        if (!isAdded || _binding == null) return
        val shimmerTitle = binding.shimmerTitle
        if (show) {
            shimmerTitle.visibility = View.VISIBLE
            // Remove the title view from layout flow to avoid extra vertical gap
            binding.tvTitle.visibility = View.GONE
            try { shimmerTitle.startShimmer() } catch (_: Exception) {}
        } else {
            try { shimmerTitle.stopShimmer() } catch (_: Exception) {}
            shimmerTitle.visibility = View.GONE
            binding.tvTitle.visibility = View.VISIBLE
        }
    }

    // New: shimmer for info text
    private fun showInfoShimmer(show: Boolean) {
        if (!isAdded || _binding == null) return
        val shimmerInfo = binding.shimmerInfo
        if (show) {
            shimmerInfo.visibility = View.VISIBLE
            binding.tvInfo.visibility = View.INVISIBLE
            try { shimmerInfo.startShimmer() } catch (_: Exception) {}
        } else {
            try { shimmerInfo.stopShimmer() } catch (_: Exception) {}
            shimmerInfo.visibility = View.GONE
            binding.tvInfo.visibility = View.VISIBLE
        }
    }

    private fun loadPlaylist(isRefresh: Boolean = false) {
        // Loading UX
        if (!isRefresh) {
            // Use shimmer instead of full-screen overlay for a lighter feel
            showShimmer(true)
            showTitleShimmer(true)
            showInfoShimmer(true)
            binding.emptyLayout.visibility = View.GONE
            binding.btnRetry.visibility = View.GONE
            binding.rvSongs.visibility = View.GONE
            binding.loadingOverlay.visibility = View.GONE
            binding.progressBar.visibility = View.GONE
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val id = playlistId ?: return@launch

            // Fetch info and songs concurrently off the main thread
            val infoDeferred = async(Dispatchers.IO) { musicRepository.getPlaylistInfo(id) }
            val songsDeferred = async(Dispatchers.IO) { musicRepository.getPlaylistSongs(id) }

            // Apply playlist info as soon as it's available
            infoDeferred.await().onSuccess { info ->
                playlistInfo = info
                binding.tvTitle.text = info.name
                showTitleShimmer(false)

                // Show or hide More depending on public status
                binding.btnMore.visibility = if (info.public) View.GONE else View.VISIBLE
                // Update favorites button for public playlists
                updateFavoriteButtonVisibilityAndState()

                // Cover art loading - defer heavy Palette processing
                val (username, token, salt) = musicRepository.getAuthParams()
                val coverUrl = info.getCoverArtUrl(
                    musicRepository.serverUrl ?: "",
                    username,
                    token,
                    salt,
                    300
                )

                // Load image for display immediately
                ImageLoader.loadAlbumCover(requireContext(), binding.ivHeaderCover, coverUrl)

                // Set static background immediately, then load dynamic one in background
                setStaticBackground()

                // Load dynamic gradient asynchronously without blocking UI
                if (!coverUrl.isNullOrEmpty()) {
                    try {
                        Glide.with(this@PlaylistDetailFragment)
                            .asBitmap()
                            .load(coverUrl)
                            .into(object : CustomTarget<Bitmap>() {
                                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                                    if (!isAdded || _binding == null) return
                                    applyDynamicAppBarGradientFromBitmap(resource)
                                }
                                override fun onLoadCleared(placeholder: Drawable?) { /* no-op */ }
                                override fun onLoadFailed(errorDrawable: Drawable?) {
                                    // Try a fallback based on the first song cover if available
                                    tryApplyGradientFromFirstAvailableArt()
                                }
                            })
                    } catch (_: Exception) {
                        // Fallback to first song cover if possible
                        tryApplyGradientFromFirstAvailableArt()
                    }
                } else {
                    // No playlist cover -> try using the first song cover
                    tryApplyGradientFromFirstAvailableArt()
                }
            }

            // Process songs result
            songsDeferred.await().fold(
                onSuccess = { songs ->
                    // Batch UI updates to reduce redraws
                    playlistSongs.clear()
                    playlistSongs.addAll(songs)

                    // Update adapter with new data
                    songAdapter.updateSongs(playlistSongs)

                    // Batch visibility and layout updates
                    if (playlistSongs.isEmpty()) {
                        binding.emptyLayout.visibility = View.VISIBLE
                        binding.tvEmpty.text = getString(R.string.no_songs)
                        binding.btnRetry.visibility = View.GONE
                        binding.rvSongs.visibility = View.GONE
                    } else {
                        binding.rvSongs.visibility = View.VISIBLE
                        binding.emptyLayout.visibility = View.GONE
                        binding.btnRetry.visibility = View.GONE

                        // Ensure adapter is attached
                        if (binding.rvSongs.adapter !== songAdapter) {
                            binding.rvSongs.adapter = songAdapter
                        }
                    }

                    // Update info text
                    binding.tvInfo.text = buildInfoText(playlistSongs)
                    showInfoShimmer(false)

                    // Update UI state
                    isPlaying = musicService?.isPlaying() == true && isPlaylistQueuePlaying()
                    updatePlayButton()

                    // Hide loading
                    showShimmer(false)
                    if (isRefresh || isManualRefresh) {
                        binding.swipeRefreshLayout.isRefreshing = false
                        isManualRefresh = false
                    }
                    hideLoadingOverlay()

                    // Defer expensive operations until after UI is shown
                    launch(Dispatchers.IO) {
                        // Cache download states for performance
                        val downloadStates = playlistSongs.associateWith { song ->
                            downloadManager.isSongDownloadedFast(song.id)
                        }

                        launch(Dispatchers.Main) {
                            // Update download-related UI with cached data
                            updateDownloadButtonTint()
                            lastAllDownloaded = downloadStates.values.all { it }
                            maybeAutoFavorite()

                            // Initialize group download UI
                            updatePlaylistGroupDownloadUi(downloadManager.downloadStates.value)
                        }
                    }
                },
                onFailure = {
                    binding.emptyLayout.visibility = View.VISIBLE
                    binding.tvEmpty.text = getString(R.string.error_loading_playlist)
                    binding.btnRetry.visibility = View.VISIBLE
                    binding.rvSongs.visibility = View.GONE
                    showShimmer(false)
                    showTitleShimmer(false)
                    showInfoShimmer(false)
                    if (isRefresh || isManualRefresh) {
                        binding.swipeRefreshLayout.isRefreshing = false
                        isManualRefresh = false
                    }
                    hideLoadingOverlay()
                }
            )
        }
    }

    private fun hideLoadingOverlay() {
        if (!isAdded || _binding == null) return
        binding.loadingOverlay.visibility = View.GONE
        binding.progressBar.visibility = View.GONE
    }

    private fun hideLoadingOverlayAfterNextDraw() {
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
        val durationText = when {
            hours > 0 && remMin > 0 -> String.format(Locale.getDefault(), "%d h %d min", hours, remMin)
            hours > 0 -> String.format(Locale.getDefault(), "%d h", hours)
            else -> String.format(Locale.getDefault(), "%d min", minutes)
        }
        val songsText = resources.getQuantityString(R.plurals.songs_count, count, count)
        return getString(R.string.playlist_info, songsText, durationText)
    }

    private fun isPlaylistQueuePlaying(): Boolean {
        val service = musicService ?: return false
        val src = service.getPlaybackSource() ?: return false
        return src.type == MusicService.SourceType.PLAYLIST && src.id == playlistId
    }

    private fun setupMusicServiceListeners() {
        musicService?.let { service ->
            cleanupListeners()

            playbackStateListener = { playing ->
                if (isAdded && _binding != null) {
                    requireActivity().runOnUiThread {
                        isPlaying = playing && isPlaylistQueuePlaying()
                        updatePlayButton()
                    }
                }
            }

            songChangeListener = { song ->
                if (isAdded && _binding != null) {
                    requireActivity().runOnUiThread {
                        val inContext = isPlaylistQueuePlaying()
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

    private fun confirmRemoveSong(song: Song) {
        val id = playlistId ?: return
        val index = playlistSongs.indexOfFirst { it.id == song.id }
        if (index == -1) {
            snack(getString(R.string.remove_from_playlist_error))
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            musicRepository.removeSongFromPlaylist(id, index).fold(
                onSuccess = {
                    // Update local list and UI
                    playlistSongs.removeAt(index)
                    songAdapter.updateSongs(playlistSongs)
                    // Ensure the container recalculates height
                    binding.rvSongs.post { binding.rvSongs.requestLayout() }
                    binding.tvInfo.text = buildInfoText(playlistSongs)
                    if (playlistSongs.isEmpty()) {
                        binding.emptyLayout.visibility = View.VISIBLE
                        binding.rvSongs.visibility = View.GONE
                    }
                    snack(getString(R.string.removed_from_playlist))
                },
                onFailure = {
                    snack(getString(R.string.remove_from_playlist_error))
                }
            )
        }
    }

    private fun showSongInfo(song: Song) {
        val dialogBuilder = AlertDialog.Builder(requireContext())
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

        tvInfoTitle.text = song.title
        tvInfoArtist.text = song.artist
        tvInfoAlbum.text = song.album
        tvInfoDuration.text = formatSongDuration(song.duration)

        tvInfoGenre.text = song.genre ?: "Desconocido"
        tvInfoYear.text = song.year?.toString() ?: "Desconocido"
        tvInfoBitrate.text = if (song.bitRate != null) "${song.bitRate} kbps" else "Desconocido"
        tvInfoFormat.text = song.suffix?.uppercase() ?: "Desconocido"
        tvInfoFileSize.text = song.size?.let { formatFileSize(it) } ?: "Desconocido"

        try {
            if (musicRepository.serverUrl != null && song.coverArt != null) {
                val (username, token, salt) = musicRepository.getAuthParams()
                val coverUrl = song.getCoverArtUrl(musicRepository.serverUrl!!, username, token, salt)
                Glide.with(this)
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

    private fun formatSongDuration(seconds: Int): String {
        val minutes = seconds / 60
        val remainingSeconds = seconds % 60
        return String.format(Locale.getDefault(), "%d:%02d", minutes, remainingSeconds)
    }

    private fun formatFileSize(sizeInBytes: Long): String {
        val kb = 1024.0
        val mb = kb * 1024
        val gb = mb * 1024
        return when {
            sizeInBytes >= gb -> String.format(Locale.getDefault(), "%.1f GB", sizeInBytes / gb)
            sizeInBytes >= mb -> String.format(Locale.getDefault(), "%.1f MB", sizeInBytes / mb)
            sizeInBytes >= kb -> String.format(Locale.getDefault(), "%.1f KB", sizeInBytes / kb)
            else -> "$sizeInBytes bytes"
        }
    }

    private fun showRenameDialog() {
        val id = playlistId ?: return
        val input = EditText(requireContext())
        input.setText(playlistInfo?.name ?: playlistName ?: "")
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.rename_playlist)
            .setView(input)
            .setPositiveButton(R.string.rename) { _, _ ->
                val newName = input.text?.toString()?.trim().orEmpty()
                if (newName.isNotEmpty()) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        musicRepository.updatePlaylistMetadata(id, name = newName).fold(
                            onSuccess = {
                                binding.tvTitle.text = newName
                                playlistName = newName
                                snack(getString(R.string.playlist_rename_success))
                            },
                            onFailure = {
                                snack(getString(R.string.playlist_rename_error))
                            }
                        )
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDeletePlaylist() {
        val id = playlistId ?: return
        AlertDialog.Builder(requireContext())
            .setTitle("Eliminar playlist")
            .setMessage("¿Estás seguro de que quieres eliminar esta playlist?")
            .setPositiveButton("Eliminar") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    musicRepository.deletePlaylist(id).fold(
                        onSuccess = {
                            snack("Playlist eliminada")
                            findNavController().popBackStack()
                        },
                        onFailure = {
                            snack("Error al eliminar la playlist")
                        }
                    )
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun setStaticBackground() {
        if (!isAdded || _binding == null) return

        // Aplicar color estático de fondo oscuro
        val staticColor = 0xFF121212.toInt()
        binding.gradientBackground.setBackgroundColor(staticColor)
        binding.collapsingToolbar.setContentScrimColor(staticColor)
        binding.collapsingToolbar.setStatusBarScrimColor(staticColor)

        // Usar iconos blancos para el toolbar
        binding.toolbar.navigationIcon?.setTint(android.graphics.Color.WHITE)

        // Reset dynamic status bar color memory and apply static
        lastStatusBarTopColor = null
        StatusBarUtils.setStatusBarColor(this)
    }

    private fun applyDynamicAppBarGradientFromBitmap(bitmap: Bitmap) {
        if (!isAdded || _binding == null) return

        Palette.from(bitmap).generate { palette ->
            if (!isAdded || _binding == null) return@generate

            // Prefer more vibrant swatches first to avoid near-black gradients
            val topColor = palette?.darkVibrantSwatch?.rgb
                ?: palette?.vibrantSwatch?.rgb
                ?: palette?.darkMutedSwatch?.rgb
                ?: 0xFF2A2A2A.toInt()

            requireActivity().runOnUiThread {
                if (!isAdded || _binding == null) return@runOnUiThread
                applyAppBarGradient(topColor)
            }
        }
    }

    private fun tryApplyGradientFromFirstAvailableArt() {
        if (!isAdded || _binding == null) return
        if (gradientApplied) return
        val first = playlistSongs.firstOrNull() ?: return
        if (first.coverArt == null) return
        val server = musicRepository.serverUrl ?: return
        val (username, token, salt) = musicRepository.getAuthParams()
        val url = first.getCoverArtUrl(server, username, token, salt)
        if (url.isNullOrEmpty()) return
        try {
            Glide.with(this)
                .asBitmap()
                .load(url)
                .into(object : CustomTarget<Bitmap>() {
                    override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                        if (!isAdded || _binding == null) return
                        applyDynamicAppBarGradientFromBitmap(resource)
                    }
                    override fun onLoadCleared(placeholder: Drawable?) { /* no-op */ }
                    override fun onLoadFailed(errorDrawable: Drawable?) { /* no-op */ }
                })
        } catch (_: Exception) {
            // no-op
        }
    }

    private fun applyAppBarGradient(topColor: Int) {
        if (!isAdded || _binding == null) return

        val baseColor = 0xFF121212.toInt()

        // Construir un degradado suave para el fondo
        val bgGradient = buildSmoothGradient(baseColor, topColor)
        binding.gradientBackground.background = bgGradient
        // Force a redraw in case we're mid-layout
        binding.gradientBackground.post { binding.gradientBackground.invalidate() }
        Log.d("PlaylistDetail", "Applied gradient topColor=" + String.format("#%08X", topColor))

        // Para el AppBar, usar colores sólidos estables
        binding.appBarLayout.background = android.graphics.drawable.ColorDrawable(baseColor)
        binding.collapsingToolbar.setContentScrimColor(baseColor)
        binding.collapsingToolbar.setStatusBarScrimColor(baseColor)
        binding.toolbar.navigationIcon?.setTint(android.graphics.Color.WHITE)
        // Aplicar también el color dinámico al status bar con contraste automático y recordarlo
        lastStatusBarTopColor = topColor
        StatusBarUtils.setStatusBarColor(this, topColor)

        gradientApplied = true
    }

    private fun buildSmoothGradient(baseColor: Int, topColor: Int): GradientDrawable {
        // Crear múltiples colores intermedios con transición más temprana
        val color1 = blendColors(baseColor, topColor, 0.92f)
        val color2 = blendColors(baseColor, topColor, 0.82f)
        val color3 = blendColors(baseColor, topColor, 0.68f)
        val color4 = blendColors(baseColor, topColor, 0.52f)
        val color5 = blendColors(baseColor, topColor, 0.35f)
        val color6 = blendColors(baseColor, topColor, 0.18f)
        val color7 = blendColors(baseColor, topColor, 0.05f)

        val colors = intArrayOf(baseColor, baseColor, color1, color2, color3, color4, color5, color6, color7, topColor)

        return GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, colors).apply {
            shape = GradientDrawable.RECTANGLE
            gradientType = GradientDrawable.LINEAR_GRADIENT
            setDither(true)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setColors(colors, floatArrayOf(0f, 0.1f, 0.22f, 0.35f, 0.5f, 0.65f, 0.78f, 0.88f, 0.95f, 1f))
            }
        }
    }

    private fun blendColors(color1: Int, color2: Int, ratio: Float): Int {
        val inverseRatio = 1f - ratio
        val r = (android.graphics.Color.red(color1) * ratio + android.graphics.Color.red(color2) * inverseRatio).toInt()
        val g = (android.graphics.Color.green(color1) * ratio + android.graphics.Color.green(color2) * inverseRatio).toInt()
        val b = (android.graphics.Color.blue(color1) * ratio + android.graphics.Color.blue(color2) * inverseRatio).toInt()
        val a = (android.graphics.Color.alpha(color1) * ratio + android.graphics.Color.alpha(color2) * inverseRatio).toInt()
        return android.graphics.Color.argb(a, r, g, b)
    }

    private fun updateFavoriteButtonVisibilityAndState() {
        val info = playlistInfo
        if (info?.public == true) {
            binding.btnFavorite.visibility = View.VISIBLE
            val isFav = com.arantec.castafiore.utils.PlaylistFavoritesManager.isFavorite(requireContext(), playlistId)
            binding.btnFavorite.setImageResource(if (isFav) R.drawable.ic_favorite else R.drawable.ic_favorite_border)
            val tintColor = if (isFav) R.color.primary else R.color.white
            binding.btnFavorite.imageTintList = ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), tintColor)
            )
        } else {
            binding.btnFavorite.visibility = View.GONE
        }
    }

    private fun showSongOptions(song: com.arantec.castafiore.data.models.Song) {
        val sheet = com.arantec.castafiore.ui.dialogs.SongOptionsBottomSheet
            .newInstance(song)
            .setOnAddToQueueClickListener { s: com.arantec.castafiore.data.models.Song -> musicService?.addToQueue(s) }
            .setOnPlayNextClickListener { s: com.arantec.castafiore.data.models.Song -> musicService?.playNext(s) }
            .setOnAddToPlaylistClickListener { s: com.arantec.castafiore.data.models.Song ->
                com.arantec.castafiore.ui.dialogs.PlaylistSelectorBottomSheet.newInstance(s)
                    .show(childFragmentManager, "playlistSelector")
            }
            .setOnSongInfoClickListener { s: com.arantec.castafiore.data.models.Song ->
                showSongInfo(s)
            }
            .setOnViewArtistClickListener { s: com.arantec.castafiore.data.models.Song ->
                val artistId = s.artistId
                if (!artistId.isNullOrEmpty()) {
                    val args = bundleOf(
                        "artistId" to artistId,
                        "artistName" to s.artist
                    )
                    findNavController().navigate(com.arantec.castafiore.R.id.artistDetailFragment, args)
                } else {
                    snack("Artista no disponible")
                }
            }
            .setOnViewAlbumClickListener { s: com.arantec.castafiore.data.models.Song ->
                val albumId = s.albumId
                if (!albumId.isNullOrEmpty()) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        musicRepository.getAlbumDetail(albumId).onSuccess { album ->
                            val args = bundleOf("album" to album)
                            findNavController().navigate(com.arantec.castafiore.R.id.albumDetailFragment, args)
                        }.onFailure {
                            snack("No se pudo abrir el álbum")
                        }
                    }
                } else {
                    snack("Álbum no disponible")
                }
            }

        // Solo permitir "Quitar de la playlist" si la playlist NO es pública
        if (playlistInfo?.public != true) {
            sheet.setOnRemoveFromPlaylistClickListener { s: com.arantec.castafiore.data.models.Song ->
                confirmRemoveSong(s)
            }
        }

        sheet.show(childFragmentManager, "SongOptionsBottomSheet")
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
        // Ensure any global overlay is hidden on this screen
        (activity as? LoadingHost)?.showGlobalLoading(false)
        // Keep status bar consistent with current app bar theme
        lastStatusBarTopColor?.let { StatusBarUtils.setStatusBarColor(this, it) }
            ?: StatusBarUtils.setStatusBarColor(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        unbindMusicService()
        _binding = null
    }

    private fun bindMusicService() {
        if (!isBound) {
            val intent = Intent(requireContext(), MusicService::class.java)
            requireContext().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    private fun unbindMusicService() {
        if (isBound) {
            cleanupListeners()
            requireContext().unbindService(serviceConnection)
            isBound = false
            musicService = null
        }
    }

    override fun hasContent(): Boolean {
        return this::songAdapter.isInitialized && songAdapter.itemCount > 0
    }

    // --- Download button logic and helpers ---
    private fun setDownloadButtonTintPrimary() {
        try {
            binding.btnDownload.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.primary))
        } catch (_: Exception) {
            binding.btnDownload.setColorFilter(ContextCompat.getColor(requireContext(), R.color.primary))
        }
    }

    private fun setDownloadButtonTintSecondary() {
        // For this screen, default icons use white tint
        try {
            binding.btnDownload.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.white))
        } catch (_: Exception) {
            binding.btnDownload.setColorFilter(ContextCompat.getColor(requireContext(), R.color.white))
        }
    }

    private fun updateDownloadButtonTint() {
        if (!isAdded || _binding == null) return
        if (isPlaylistFullyDownloaded()) setDownloadButtonTintPrimary() else setDownloadButtonTintSecondary()
    }

    private fun isPlaylistFullyDownloaded(): Boolean {
        if (playlistSongs.isEmpty()) return false
        // Prefer live states for accuracy; fall back to fast cache
        val states = latestDownloadStates
        if (states.isNotEmpty()) {
            for (song in playlistSongs) {
                val st = states[song.id]
                val completedByState = st?.status == com.arantec.castafiore.data.download.SongDownloadManager.DownloadStatus.COMPLETED
                val completedByCache = downloadManager.isSongDownloadedFast(song.id)
                if (!completedByState && !completedByCache) return false
            }
            return true
        }
        return playlistSongs.all { song -> downloadManager.isSongDownloadedFast(song.id) }
    }

    private fun setupDownloadButton() {
        binding.btnDownload.setOnClickListener {
            if (playlistSongs.isEmpty()) {
                snack("No hay canciones para descargar")
                return@setOnClickListener
            }
            // If all are downloaded, offer deletion
            if (isPlaylistFullyDownloaded()) {
                showDeletePlaylistDownloadsConfirm()
                return@setOnClickListener
            }
            // Queue non-downloaded songs in current playlist order
            val toQueue = playlistSongs.filter { !downloadManager.isSongDownloadedFast(it.id) }
            if (toQueue.isEmpty()) {
                // Race: nothing to download now -> offer deletion
                showDeletePlaylistDownloadsConfirm()
                return@setOnClickListener
            }
            downloadManager.downloadSongsSequentially(toQueue, com.arantec.castafiore.data.download.DownloadOrigin.PLAYLIST)
            // Toggle to progress UI immediately
            binding.btnDownload.visibility = View.GONE
            binding.groupDownloadProgress.visibility = View.VISIBLE
            val msg = resources.getQuantityString(R.plurals.downloading_count, toQueue.size, toQueue.size)
            snack(msg)
        }
    }

    // --- Group download UI helpers ---
    private fun updatePlaylistGroupDownloadUi(states: Map<String, com.arantec.castafiore.data.download.SongDownloadManager.DownloadState>) {
        if (!isAdded || _binding == null) return
        if (playlistSongs.isEmpty()) {
            binding.groupDownloadProgress.visibility = View.GONE
            binding.btnDownload.visibility = View.VISIBLE
            return
        }
        val ids = playlistSongs.map { it.id }.toSet()
        val hasActive = states.values.any { it.songId in ids && (it.status == com.arantec.castafiore.data.download.SongDownloadManager.DownloadStatus.PENDING || it.status == com.arantec.castafiore.data.download.SongDownloadManager.DownloadStatus.DOWNLOADING) }

        if (!hasActive) {
            binding.groupDownloadProgress.visibility = View.GONE
            binding.btnDownload.visibility = View.VISIBLE
            return
        }

        binding.btnDownload.visibility = View.GONE
        binding.groupDownloadProgress.visibility = View.VISIBLE

        // Aggregate progress across the whole playlist
        val totalCount = playlistSongs.size.coerceAtLeast(1)
        var units = 0.0
        var hasDeterminate = false
        playlistSongs.forEach { song ->
            val st = states[song.id]
            when (st?.status) {
                com.arantec.castafiore.data.download.SongDownloadManager.DownloadStatus.COMPLETED -> units += 1.0
                com.arantec.castafiore.data.download.SongDownloadManager.DownloadStatus.DOWNLOADING -> {
                    val p = st.progress.coerceIn(0, 100) / 100.0
                    units += p
                    if (st.progress > 0) hasDeterminate = true
                }
                com.arantec.castafiore.data.download.SongDownloadManager.DownloadStatus.PENDING -> { /* +0 */ }
                else -> {
                    // Count as completed if already downloaded (fast cache)
                    if (downloadManager.isSongDownloadedFast(song.id)) units += 1.0
                }
            }
        }
        val percent = ((units / totalCount) * 100.0).toInt().coerceIn(0, 100)
        if (hasDeterminate || percent > 0) {
            binding.cpiGroupDownload.isIndeterminate = false
            try { binding.cpiGroupDownload.setProgressCompat(percent, true) } catch (_: Exception) { binding.cpiGroupDownload.progress = percent }
        } else {
            binding.cpiGroupDownload.isIndeterminate = true
        }
    }

    private fun cancelPlaylistGroupDownloads() {
        if (playlistSongs.isEmpty()) return
        val states = downloadManager.downloadStates.value
        val ids = playlistSongs.map { it.id }.toSet()
        states.values
            .filter { it.songId in ids }
            .filter { it.status == com.arantec.castafiore.data.download.SongDownloadManager.DownloadStatus.PENDING || it.status == com.arantec.castafiore.data.download.SongDownloadManager.DownloadStatus.DOWNLOADING }
            .forEach { st -> downloadManager.cancelDownload(st.songId) }
        binding.groupDownloadProgress.visibility = View.GONE
        binding.btnDownload.visibility = View.VISIBLE
    }

    private fun showDeletePlaylistDownloadsConfirm() {
        val downloadedIds = playlistSongs.filter { downloadManager.isSongDownloadedFast(it.id) }.map { it.id }
        if (downloadedIds.isEmpty()) {
            snack("No hay descargas que eliminar")
            updateDownloadButtonTint()
            return
        }
        val count = downloadedIds.size
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.delete_downloads_title))
            .setMessage(resources.getQuantityString(R.plurals.delete_playlist_downloads_message, count, count))
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.delete)) { d, _ ->
                val removed = downloadManager.deleteMultipleSongs(downloadedIds)
                if (removed > 0) {
                    setDownloadButtonTintSecondary()
                    snack("Descargas eliminadas: ${removed}")
                } else {
                    snack("No se eliminaron descargas")
                }
                d.dismiss()
            }
            .show()
    }

    private fun maybeAutoFavorite() {
        if (!isAdded || _binding == null) return
        val info = playlistInfo ?: return
        val id = playlistId ?: return
        if (!info.public) return
        if (autoFavApplied) return
        if (!isPlaylistFullyDownloaded()) return
        val isFav = PlaylistFavoritesManager.isFavorite(requireContext(), id)
        if (!isFav) {
            PlaylistFavoritesManager.toggleFavorite(requireContext(), id)
            autoFavApplied = true
            updateFavoriteButtonVisibilityAndState()
        } else {
            autoFavApplied = true
        }
    }
}
