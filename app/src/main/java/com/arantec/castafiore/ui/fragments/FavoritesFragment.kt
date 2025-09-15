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
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.arantec.castafiore.R
import com.arantec.castafiore.data.models.Song
import com.arantec.castafiore.data.repository.MusicRepository
import com.arantec.castafiore.databinding.FragmentFavoritesBinding
import com.arantec.castafiore.service.MusicService
import com.arantec.castafiore.ui.adapters.SongAdapter
import com.arantec.castafiore.utils.StatusBarUtils
import com.arantec.castafiore.utils.snack
import kotlinx.coroutines.launch
import com.bumptech.glide.Glide
import java.util.Locale
import kotlin.random.Random
import com.arantec.castafiore.ui.helpers.HasContentState
import android.content.res.ColorStateList
import com.arantec.castafiore.data.download.SongDownloadManager
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.FlowPreview
import com.arantec.castafiore.ui.helpers.LoadingHost

class FavoritesFragment : Fragment(), HasContentState {

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

    private lateinit var downloadManager: SongDownloadManager

    // Track if loading was triggered by pull-to-refresh
    private var isManualRefresh: Boolean = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? MusicService.MusicBinder ?: return
            musicService = binder.getService()
            isBound = true
            setupMusicServiceListeners()
            // Estado inicial
            val serviceIsPlaying = musicService?.isPlaying() == true
            val inContext = isFavoritesQueuePlaying()
            isPlaying = serviceIsPlaying && inContext
            updatePlayButton()
            // Solo marcar canción si este contexto está activo
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
        // Ensure consistent status bar color using utility
        StatusBarUtils.setStatusBarColor(this)

        _binding = FragmentFavoritesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        musicRepository = MusicRepository.getInstance(requireContext())
        downloadManager = SongDownloadManager.getInstance(requireContext())

        setupToolbar()
        setupRecyclerView()
        setupFab()
        setupDownloadObservers()
        setupDownloadButton()
        setupGroupCancelButton()
        setupRefreshAndRetry()
        // Initial tint in case favorites are already downloaded
        updateDownloadButtonTint()
        // Initial load
        loadFavorites(isRefresh = false)
        // Initialize group progress visibility based on current state
        updateFavoritesGroupDownloadUi(downloadManager.downloadStates.value)
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }
    }

    private fun setupRecyclerView() {
        songAdapter = SongAdapter(
            onSongClick = { _, position ->
                if (favoriteSongs.isNotEmpty()) {
                    musicService?.playQueue(
                        favoriteSongs,
                        position,
                        MusicService.PlaybackSource(
                            MusicService.SourceType.FAVORITES,
                            null,
                            getString(R.string.favorite_songs_title)
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

        binding.rvSongs.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = songAdapter
            isNestedScrollingEnabled = false
            // Disable change animations to avoid flicker on frequent partial updates
            itemAnimator = null
            // Important with NestedScrollView: do not fix size so height can grow after async data
            setHasFixedSize(false)
        }
    }

    private fun setupFab() {
        binding.fabPlay.setOnClickListener {
            val service = musicService ?: return@setOnClickListener
            if (isFavoritesQueuePlaying()) {
                if (service.isPlaying()) service.pause() else service.play()
            } else if (favoriteSongs.isNotEmpty()) {
                val startIndex = if (service.getShuffleEnabled() && favoriteSongs.size > 1) {
                    Random.nextInt(favoriteSongs.size)
                } else 0
                service.playQueue(
                    favoriteSongs,
                    startIndex,
                    MusicService.PlaybackSource(
                        MusicService.SourceType.FAVORITES,
                        null,
                        getString(R.string.favorite_songs_title)
                    )
                )
            }
        }

        binding.fabRandom.setOnClickListener {
            val service = musicService ?: return@setOnClickListener
            if (favoriteSongs.isNotEmpty()) {
                val shuffled = favoriteSongs.shuffled()
                service.playQueue(
                    shuffled,
                    0,
                    MusicService.PlaybackSource(
                        MusicService.SourceType.FAVORITES,
                        null,
                        getString(R.string.favorite_songs_title)
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
            // Remove listeners and unbind so this fragment stops receiving updates when not visible
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
            loadFavorites(isRefresh = true)
        }
        binding.btnRetry.setOnClickListener {
            isManualRefresh = false
            loadFavorites(isRefresh = false)
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

    private fun loadFavorites(isRefresh: Boolean = false) {
        // Loading UX
        if (!isRefresh) {
            // Use shimmer for initial/retry load
            showShimmer(true)
            binding.emptyLayout.visibility = View.GONE
            binding.rvSongs.visibility = View.GONE
            binding.loadingOverlay.visibility = View.GONE
            binding.progressBar.visibility = View.GONE
        } else {
            // For manual refresh, rely on SwipeRefreshLayout spinner
            // Keep current content visible
        }

        viewLifecycleOwner.lifecycleScope.launch {
            musicRepository.getStarredSongs().fold(
                onSuccess = { songs ->
                    if (!isAdded || _binding == null) return@fold

                    favoriteSongs.clear()
                    favoriteSongs.addAll(songs)
                    songAdapter.updateSongs(favoriteSongs)

                    binding.tvTitle.text = getString(R.string.favorite_songs_title)
                    binding.tvInfo.text = buildInfoText(favoriteSongs)

                    if (favoriteSongs.isEmpty()) {
                        binding.emptyLayout.visibility = View.VISIBLE
                        binding.tvEmpty.text = getString(R.string.empty_favorites)
                        binding.btnRetry.visibility = View.GONE
                        binding.rvSongs.visibility = View.GONE
                    } else {
                        binding.rvSongs.visibility = View.VISIBLE
                        binding.emptyLayout.visibility = View.GONE
                        binding.btnRetry.visibility = View.GONE
                    }

                    isPlaying = musicService?.isPlaying() == true && isFavoritesQueuePlaying()
                    updatePlayButton()
                    updateDownloadButtonTint()

                    // Stop loading visuals
                    showShimmer(false)
                    if (binding.swipeRefreshLayout.isRefreshing || isManualRefresh) {
                        binding.swipeRefreshLayout.isRefreshing = false
                        isManualRefresh = false
                    }
                    // Ensure overlay is hidden
                    binding.loadingOverlay.visibility = View.GONE
                    binding.progressBar.visibility = View.GONE
                },
                onFailure = {
                    if (!isAdded || _binding == null) return@fold
                    binding.emptyLayout.visibility = View.VISIBLE
                    binding.tvEmpty.text = getString(R.string.error_loading_favorites)
                    binding.btnRetry.visibility = View.VISIBLE
                    binding.rvSongs.visibility = View.GONE

                    // Stop loading visuals
                    showShimmer(false)
                    if (binding.swipeRefreshLayout.isRefreshing || isManualRefresh) {
                        binding.swipeRefreshLayout.isRefreshing = false
                        isManualRefresh = false
                    }
                    binding.loadingOverlay.visibility = View.GONE
                    binding.progressBar.visibility = View.GONE

                    updateDownloadButtonTint()
                }
            )
        }
    }

    private fun hideLoadingOverlayAfterNextDraw() {
        // Unused with shimmer-based loading; keep for compatibility if needed
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
        val durationText = if (hours > 0) "$hours h ${remMin} min" else "${minutes} min"
        return "$count canciones • $durationText"
    }

    private fun isFavoritesQueuePlaying(): Boolean {
        val service = musicService ?: return false
        val src = service.getPlaybackSource()
        // Solo considerar FAVORITES explícito
        return src?.type == MusicService.SourceType.FAVORITES
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
                        val inContext = isFavoritesQueuePlaying()
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
                        musicRepository.getAlbumDetail(albumId).fold(
                            onSuccess = { album ->
                                val args = Bundle().apply {
                                    putParcelable("album", album)
                                }
                                try {
                                    findNavController().navigate(R.id.albumDetailFragment, args)
                                } catch (_: Exception) {
                                    snack("No se pudo abrir el álbum")
                                }
                            },
                            onFailure = {
                                snack("No se pudo abrir el álbum")
                            }
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
                    try {
                        findNavController().navigate(R.id.artistDetailFragment, args)
                    } catch (_: Exception) {
                        snack("No se pudo abrir el artista")
                    }
                } else {
                    snack("Artista no disponible")
                }
            }
            .setOnSongInfoClickListener { selectedSong ->
                showSongInfo(selectedSong)
            }
        bottomSheet.show(childFragmentManager, "SongOptionsBottomSheet")
    }

    private fun showSongInfo(song: Song) {
        val dialogBuilder = androidx.appcompat.app.AlertDialog.Builder(requireContext())
        val inflater = LayoutInflater.from(requireContext())
        val dialogView = inflater.inflate(R.layout.dialog_song_info, null)

        // Referencias a las vistas del diálogo
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

        // Configurar la información básica
        tvInfoTitle.text = song.title
        tvInfoArtist.text = song.artist
        tvInfoAlbum.text = song.album
        tvInfoDuration.text = formatSongDuration(song.duration)

        // Configurar información opcional
        tvInfoGenre.text = song.genre ?: "Desconocido"
        tvInfoYear.text = song.year?.toString() ?: "Desconocido"
        tvInfoBitrate.text = if (song.bitRate != null) "${song.bitRate} kbps" else "Desconocido"
        tvInfoFormat.text = song.suffix?.uppercase() ?: "Desconocido"

        // Formatear el tamaño del archivo
        tvInfoFileSize.text = if (song.size != null) {
            formatFileSize(song.size)
        } else {
            "Desconocido"
        }

        // Cargar la imagen de la canción
        try {
            if (musicRepository.serverUrl != null && song.coverArt != null) {
                val (username, token, salt) = musicRepository.getAuthParams()
                val coverUrl = song.getCoverArtUrl(
                    musicRepository.serverUrl!!,
                    username,
                    token,
                    salt
                )

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

    @OptIn(FlowPreview::class)
    private fun setupDownloadObservers() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                downloadManager.downloadStates
                    .sample(250)
                    .collect { states: Map<String, SongDownloadManager.DownloadState> ->
                        if (!isAdded || _binding == null) return@collect

                        // Update only visible items to minimize rebind churn and flicker
                        val lm = binding.rvSongs.layoutManager as? LinearLayoutManager
                        val first = lm?.findFirstVisibleItemPosition() ?: -1
                        val last = lm?.findLastVisibleItemPosition() ?: -1
                        if (first >= 0 && last >= first && favoriteSongs.isNotEmpty()) {
                            val safeFirst = first.coerceAtLeast(0)
                            val safeLast = last.coerceAtMost(favoriteSongs.size - 1)
                            val visibleIds = favoriteSongs.subList(safeFirst, safeLast + 1).map { it.id }.toSet()
                            states.values.forEach { state ->
                                if (state.songId in visibleIds) {
                                    songAdapter.updateDownloadState(state)
                                }
                            }
                        }

                        // Update download button tint based on aggregate state
                        updateDownloadButtonTint()
                        // Update group progress UI for favorites
                        updateFavoritesGroupDownloadUi(states)
                    }
            }
        }
    }

    // Update group circular progress and toggle visibility like in AlbumDetail
    private fun updateFavoritesGroupDownloadUi(states: Map<String, SongDownloadManager.DownloadState>) {
        if (!isAdded || _binding == null) return
        if (favoriteSongs.isEmpty()) {
            binding.groupDownloadProgressFav.visibility = View.GONE
            binding.btnDownload.visibility = View.VISIBLE
            return
        }
        val ids = favoriteSongs.map { it.id }.toSet()
        val active = states.values.any { it.songId in ids && (it.status == SongDownloadManager.DownloadStatus.PENDING || it.status == SongDownloadManager.DownloadStatus.DOWNLOADING) }

        if (!active) {
            binding.groupDownloadProgressFav.visibility = View.GONE
            binding.btnDownload.visibility = View.VISIBLE
            return
        }

        binding.btnDownload.visibility = View.GONE
        binding.groupDownloadProgressFav.visibility = View.VISIBLE

        val totalCount = favoriteSongs.size.coerceAtLeast(1)
        var units = 0.0
        var hasDeterminate = false
        favoriteSongs.forEach { song ->
            val st = states[song.id]
            when (st?.status) {
                SongDownloadManager.DownloadStatus.COMPLETED -> units += 1.0
                SongDownloadManager.DownloadStatus.DOWNLOADING -> {
                    val p = st.progress.coerceIn(0, 100) / 100.0
                    units += p
                    if (st.progress > 0) hasDeterminate = true
                }
                SongDownloadManager.DownloadStatus.PENDING -> { /* +0 */ }
                else -> {
                    if (downloadManager.isSongDownloadedFast(song.id)) units += 1.0
                }
            }
        }
        val percent = ((units / totalCount) * 100.0).toInt().coerceIn(0, 100)
        if (hasDeterminate || percent > 0) {
            binding.cpiGroupDownloadFav.isIndeterminate = false
            try { binding.cpiGroupDownloadFav.setProgressCompat(percent, true) } catch (_: Exception) { binding.cpiGroupDownloadFav.progress = percent }
        } else {
            binding.cpiGroupDownloadFav.isIndeterminate = true
        }
    }

    private fun setupGroupCancelButton() {
        binding.btnCancelGroupDownloadFav.setOnClickListener {
            if (favoriteSongs.isEmpty()) return@setOnClickListener
            val ids = favoriteSongs.map { it.id }.toSet()
            val states = downloadManager.downloadStates.value
            states.values.filter { it.songId in ids }
                .filter { it.status == SongDownloadManager.DownloadStatus.PENDING || it.status == SongDownloadManager.DownloadStatus.DOWNLOADING }
                .forEach { st -> downloadManager.cancelDownload(st.songId) }
            binding.groupDownloadProgressFav.visibility = View.GONE
            binding.btnDownload.visibility = View.VISIBLE
        }
    }

    private fun setupDownloadButton() {
        binding.btnDownload.setOnClickListener {
            if (favoriteSongs.isEmpty()) {
                snack("No hay canciones para descargar")
                return@setOnClickListener
            }
            // Si todas están descargadas, ofrecer eliminar
            if (isFavoritesFullyDownloaded()) {
                showDeleteFavoritesDownloadsConfirm()
                return@setOnClickListener
            }
            // Ordenar de forma estable por artista/álbum/track, luego título
            val ordered = favoriteSongs.sortedWith(compareBy<Song>({ it.artist.lowercase(Locale.getDefault()) }, { it.album.lowercase(Locale.getDefault()) }, { it.track ?: Int.MAX_VALUE }, { it.title.lowercase(Locale.getDefault()) }))
            val toQueue = ordered.filter { !downloadManager.isSongDownloadedFast(it.id) }
            if (toQueue.isEmpty()) {
                // Nada por descargar (posible estado de carrera): ofrecer eliminar
                showDeleteFavoritesDownloadsConfirm()
                return@setOnClickListener
            }
            downloadManager.downloadSongsSequentially(toQueue, com.arantec.castafiore.data.download.DownloadOrigin.PLAYLIST)
            // Provide immediate visual feedback that downloads are in-progress
            setDownloadButtonTintSecondary()
            binding.btnDownload.visibility = View.GONE
            binding.groupDownloadProgressFav.visibility = View.VISIBLE
            snack("Descargando: ${toQueue.size} canciones")
        }
    }

    private fun showDeleteFavoritesDownloadsConfirm() {
        val downloadedIds = favoriteSongs.filter { downloadManager.isSongDownloadedFast(it.id) }.map { it.id }
        if (downloadedIds.isEmpty()) {
            snack("No hay descargas que eliminar")
            updateDownloadButtonTint()
            return
        }
        val count = downloadedIds.size
        val dialog = androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Eliminar descargas")
            .setMessage("¿Eliminar las descargas de $count canciones favoritas?")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Eliminar") { d, _ ->
                val removed = downloadManager.deleteMultipleSongs(downloadedIds)
                if (removed > 0) {
                    setDownloadButtonTintSecondary()
                    snack("Se eliminaron ${removed} descargas")
                } else {
                    snack("No se eliminaron descargas")
                }
                d.dismiss()
            }
            .create()
        dialog.show()
    }

    // --- Download button tint helpers ---
    private fun isFavoritesFullyDownloaded(): Boolean {
        if (favoriteSongs.isEmpty()) return false
        return favoriteSongs.all { song -> downloadManager.isSongDownloadedFast(song.id) }
    }

    private fun setDownloadButtonTintPrimary() {
        try {
            binding.btnDownload.imageTintList = ColorStateList.valueOf(requireContext().getColor(R.color.primary))
        } catch (_: Exception) {
            binding.btnDownload.setColorFilter(requireContext().getColor(R.color.primary))
        }
    }

    private fun setDownloadButtonTintSecondary() {
        try {
            binding.btnDownload.imageTintList = ColorStateList.valueOf(requireContext().getColor(R.color.text_secondary))
        } catch (_: Exception) {
            binding.btnDownload.setColorFilter(requireContext().getColor(R.color.text_secondary))
        }
    }

    private fun updateDownloadButtonTint() {
        if (!isAdded || _binding == null) return
        if (Thread.currentThread() == requireActivity().mainLooper.thread) {
            if (!isAdded || _binding == null) return
            if (isFavoritesFullyDownloaded()) setDownloadButtonTintPrimary() else setDownloadButtonTintSecondary()
        } else {
            requireActivity().runOnUiThread {
                if (!isAdded || _binding == null) return@runOnUiThread
                if (isFavoritesFullyDownloaded()) setDownloadButtonTintPrimary() else setDownloadButtonTintSecondary()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Bind when fragment becomes visible
        bindMusicService()
    }

    override fun onStop() {
        super.onStop()
        // Unbind when fragment is no longer visible
        unbindMusicService()
    }

    override fun onResume() {
        super.onResume()
        // Ensure consistent status bar color on resume
        StatusBarUtils.setStatusBarColor(this)
        // Ensure any global overlay is hidden on this screen
        (activity as? LoadingHost)?.showGlobalLoading(false)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun hasContent(): Boolean {
        return this::songAdapter.isInitialized && songAdapter.itemCount > 0
    }
}
