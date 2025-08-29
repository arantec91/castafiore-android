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
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.Dispatchers
import com.bumptech.glide.Glide
import java.util.Locale
import kotlin.random.Random
import androidx.core.graphics.toColorInt
import androidx.palette.graphics.Palette
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import androidx.core.content.ContextCompat
import android.content.res.ColorStateList
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import android.graphics.drawable.Drawable
import com.arantec.castafiore.data.download.SongDownloadManager
import java.io.File
import com.arantec.castafiore.utils.snack

class PlaylistDetailFragment : Fragment() {

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
    // Cached summary to avoid excessive UI updates during downloads
    private var lastCompletedIds: Set<String> = emptySet()
    private var lastAnyDownloading: Boolean = false
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

        // Download button: start playlist download or confirm delete if fully downloaded
        binding.btnDownload.setOnClickListener {
            if (isPlaylistFullyDownloaded()) {
                showConfirmDeletePlaylistDownloads()
            } else {
                downloadPlaylist()
            }
        }
        // Observe download states and update UI immediately
        observeDownloadStates()
        updateDownloadUIState()

        // Defer binding to onStart so only visible fragment attaches listeners
        // bindMusicService()
        loadPlaylist()
    }

    private fun setupMoreButton() {
        binding.btnMore.setOnClickListener {
            com.arantec.castafiore.ui.dialogs.PlaylistOptionsBottomSheet()
                .setOnEditNameClickListener { showRenameDialog() }
                .setOnDeleteListClickListener { confirmDeletePlaylist() }
                .setOnDownloadClickListener { downloadPlaylist() }
                .show(childFragmentManager, "PlaylistOptionsBottomSheet")
        }
    }

    private fun downloadPlaylist() {
        if (playlistSongs.isEmpty()) {
            snack("No hay canciones para descargar")
            return
        }
        val downloadManager = com.arantec.castafiore.data.download.SongDownloadManager.getInstance(requireContext())
        val alreadyDownloaded = playlistSongs.count { downloadManager.isSongDownloaded(it.id) }
        val currentlyDownloading = playlistSongs.count { downloadManager.isSongDownloading(it.id) }
        val toDownload = playlistSongs.filter { !downloadManager.isSongDownloaded(it.id) && !downloadManager.isSongDownloading(it.id) }
        when {
            alreadyDownloaded == playlistSongs.size -> {
                snack("La playlist ya está completamente descargada")
            }
            toDownload.isEmpty() && currentlyDownloading > 0 -> {
                snack("La playlist se está descargando ($currentlyDownloading canciones pendientes)")
            }
            else -> {
                toDownload.forEach { song -> downloadManager.downloadSong(song) }
                val message = if (alreadyDownloaded > 0) {
                    "Descargando ${toDownload.size} canciones restantes de la playlist"
                } else {
                    "Descargando playlist completa (${toDownload.size} canciones)"
                }
                snack(message)
            }
        }
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener { findNavController().popBackStack() }
        // Removed toolbar menu to eliminate "more options" and "delete playlist" icons
        binding.tvTitle.text = playlistName ?: getString(R.string.app_name)
        // Aplicar gradiente estático inicial, se actualizará cuando se cargue la imagen
        setStaticBackground()
    }

    private fun setupRecyclerView() {
        songAdapter = SongAdapter(
            onSongClick = { _, position ->
                if (playlistSongs.isNotEmpty()) {
                    musicService?.playQueue(
                        playlistSongs,
                        position,
                        MusicService.PlaybackSource(
                            MusicService.SourceType.PLAYLIST,
                            playlistId,
                            playlistName
                        )
                    )
                }
            },
            onSongMoreClick = { song ->
                // Abrir directamente el bottom sheet de opciones de canción, incluyendo "Eliminar de la playlist"
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
                                        val args = Bundle().apply { putParcelable("album", album) }
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
                // Mostrar "Quitar de la playlist" solo si la playlist NO es pública
                if (playlistInfo?.public == false) {
                    bottomSheet.setOnRemoveFromPlaylistClickListener { selectedSong ->
                        confirmRemoveSong(selectedSong)
                    }
                }
                bottomSheet.show(childFragmentManager, "SongOptionsBottomSheet")
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
            if (isPlaylistQueuePlaying()) {
                if (service.isPlaying()) service.pause() else service.play()
            } else if (playlistSongs.isNotEmpty()) {
                val startIndex = if (service.getShuffleEnabled() && playlistSongs.size > 1) {
                    Random.nextInt(playlistSongs.size)
                } else 0
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
        }
    }

    private fun bindMusicService() {
        val intent = Intent(requireContext(), MusicService::class.java)
        requireContext().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun unbindMusicService() {
        if (isBound) {
            // Remove listeners and unbind when fragment is not visible
            cleanupListeners()
            requireContext().unbindService(serviceConnection)
            isBound = false
            musicService = null
        }
    }

    private fun loadPlaylist() {
        binding.progressBar.visibility = View.VISIBLE
        binding.emptyLayout.visibility = View.GONE
        binding.rvSongs.visibility = View.GONE

        viewLifecycleOwner.lifecycleScope.launch {
            val id = playlistId ?: return@launch

            // Info básica de la playlist (nombre, cover, etc.)
            musicRepository.getPlaylistInfo(id).onSuccess { info ->
                playlistInfo = info
                binding.tvTitle.text = info.name

                // Show or hide More depending on public status
                binding.btnMore.visibility = if (info.public) View.GONE else View.VISIBLE

                // Cover art - Cargar tanto para mostrar como para extraer Palette
                val (username, token, salt) = musicRepository.getAuthParams()
                val coverUrl = info.getCoverArtUrl(
                    musicRepository.serverUrl ?: "",
                    username,
                    token,
                    salt,
                    300
                )

                // Cargar imagen para mostrar
                ImageLoader.loadAlbumCover(requireContext(), binding.ivHeaderCover, coverUrl)

                // Cargar como Bitmap para extraer Palette y aplicar gradiente dinámico
                if (!coverUrl.isNullOrEmpty()) {
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
                                // Mantener gradiente estático si la imagen falla
                                setStaticBackground()
                            }
                        })
                } else {
                    // No hay imagen, usar gradiente estático
                    setStaticBackground()
                }
            }

            // Canciones
            musicRepository.getPlaylistSongs(id).fold(
                onSuccess = { songs ->
                    playlistSongs.clear()
                    playlistSongs.addAll(songs)
                    songAdapter.updateSongs(playlistSongs)
                    updateDownloadUIState()
                    songAdapter.notifyDataSetChanged()

                    // Info
                    binding.tvInfo.text = buildInfoText(playlistSongs)

                    binding.progressBar.visibility = View.GONE
                    if (playlistSongs.isEmpty()) {
                        binding.emptyLayout.visibility = View.VISIBLE
                    } else {
                        binding.rvSongs.visibility = View.VISIBLE
                    }

                    // Actualizar botón play según estado actual
                    isPlaying = musicService?.isPlaying() == true && isPlaylistQueuePlaying()
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

    private fun observeDownloadStates() {
        val dm = SongDownloadManager.getInstance(requireContext())
        viewLifecycleOwner.lifecycleScope.launch {
            dm.downloadStates
                .map {
                    val songs = playlistSongs.toList()
                    val completed = songs.asSequence()
                        .map { it.id to java.io.File(dm.createDownloadPath(it)).exists() }
                        .filter { it.second }
                        .map { it.first }
                        .toSet()
                    val anyDownloading = songs.any { dm.isSongDownloading(it.id) }
                    Pair(completed, anyDownloading)
                }
                .distinctUntilChanged()
                .flowOn(Dispatchers.Default)
                .collect { (completedIds, anyDownloading) ->
                    if (!isAdded || _binding == null) return@collect
                    val changed = completedIds != lastCompletedIds || anyDownloading != lastAnyDownloading
                    if (changed) {
                        lastCompletedIds = completedIds
                        lastAnyDownloading = anyDownloading
                        updateDownloadUIState()
                        songAdapter.notifyDataSetChanged()
                    }
                }
        }
    }

    private fun updateDownloadUIState() {
        if (!isAdded || _binding == null) return
        val dm = SongDownloadManager.getInstance(requireContext())
        val songs = playlistSongs.toList()
        val anyDownloading = songs.any { dm.isSongDownloading(it.id) }
        val allDownloaded = songs.isNotEmpty() && songs.all { File(dm.createDownloadPath(it)).exists() }

        binding.progressDownload.visibility = if (anyDownloading) View.VISIBLE else View.GONE
        binding.btnDownload.visibility = if (anyDownloading) View.INVISIBLE else View.VISIBLE

        val tintColorRes = if (allDownloaded) R.color.primary else R.color.text_secondary
        binding.btnDownload.imageTintList = ColorStateList.valueOf(
            ContextCompat.getColor(requireContext(), tintColorRes)
        )
    }

    private fun confirmDeletePlaylist() {
        val id = playlistId ?: return
        val name = playlistInfo?.name ?: playlistName ?: ""
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.delete_playlist)
            .setMessage(getString(R.string.delete_playlist_confirm, name))
            .setPositiveButton(R.string.delete) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    musicRepository.deletePlaylist(id).fold(
                        onSuccess = {
                            snack(getString(R.string.playlist_deleted_success))
                            findNavController().popBackStack()
                        },
                        onFailure = {
                            snack(getString(R.string.playlist_delete_error))
                        }
                    )
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun applyDynamicAppBarGradientFromBitmap(bitmap: Bitmap) {
        Palette.from(bitmap).generate { palette ->
            if (!isAdded || _binding == null) return@generate

            val darkMuted = palette?.darkVibrantSwatch?.rgb
                ?: palette?.vibrantSwatch?.rgb
                ?: palette?.darkMutedSwatch?.rgb
                ?: "#2A2A2A".toColorInt()

            applyAppBarGradient(darkMuted)
        }
    }

    private fun applyAppBarGradient(topColor: Int) {
        val baseColor = "#121212".toColorInt()

        // Mantener el degradado para el fondo estático detrás del contenido
        val bgGradient = buildSmoothGradient(baseColor, topColor)
        binding.gradientBackground.background = bgGradient

        // Para AppBar y scrims, usar color sólido estable
        binding.appBarLayout.background = android.graphics.drawable.ColorDrawable(baseColor)
        binding.collapsingToolbar.setContentScrimColor(baseColor)
        // Usar color base estable en lugar de dinámico para evitar parpadeos
        binding.collapsingToolbar.setStatusBarScrimColor(baseColor)
        binding.toolbar.navigationIcon?.setTint(android.graphics.Color.WHITE)
        // Usar color fijo estable en lugar de dinámico para evitar parpadeos/crashes
        StatusBarUtils.setStatusBarColor(this)
    }

    private fun buildSmoothGradient(baseColor: Int, topColor: Int): GradientDrawable {
        // Crear múltiples colores intermedios con transición más temprana
        val color1 = blendColors(baseColor, topColor, 0.92f)  // 92% base, 8% top
        val color2 = blendColors(baseColor, topColor, 0.82f)  // 82% base, 18% top
        val color3 = blendColors(baseColor, topColor, 0.68f)  // 68% base, 32% top
        val color4 = blendColors(baseColor, topColor, 0.52f)  // 52% base, 48% top
        val color5 = blendColors(baseColor, topColor, 0.35f)  // 35% base, 65% top
        val color6 = blendColors(baseColor, topColor, 0.18f)  // 18% base, 82% top
        val color7 = blendColors(baseColor, topColor, 0.05f)  // 5% base, 95% top

        // Array de colores con transición suave desde 10%
        val colors = intArrayOf(baseColor, baseColor, color1, color2, color3, color4, color5, color6, color7, topColor)

        return GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, colors).apply {
            shape = GradientDrawable.RECTANGLE
            gradientType = GradientDrawable.LINEAR_GRADIENT
            setDither(true)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Zona sólida solo del 10%, transición suave distribuida en el 90% restante
                setColors(colors, floatArrayOf(0f, 0.1f, 0.22f, 0.35f, 0.5f, 0.65f, 0.78f, 0.88f, 0.95f, 1f))
            }
        }
    }

    // Función auxiliar para mezclar colores
    private fun blendColors(color1: Int, color2: Int, ratio: Float): Int {
        val inverseRatio = 1f - ratio
        val r = (android.graphics.Color.red(color1) * ratio + android.graphics.Color.red(color2) * inverseRatio).toInt()
        val g = (android.graphics.Color.green(color1) * ratio + android.graphics.Color.green(color2) * inverseRatio).toInt()
        val b = (android.graphics.Color.blue(color1) * ratio + android.graphics.Color.blue(color2) * inverseRatio).toInt()
        val a = (android.graphics.Color.alpha(color1) * ratio + android.graphics.Color.alpha(color2) * inverseRatio).toInt()
        return android.graphics.Color.argb(a, r, g, b)
    }

    private fun setStaticBackground() {
        if (!isAdded || _binding == null) return

        // Aplicar color estático independientemente del bitmap
        val staticColor = 0xFF121212.toInt()
        binding.gradientBackground.setBackgroundColor(staticColor)
        binding.collapsingToolbar.setContentScrimColor(staticColor)
        binding.collapsingToolbar.setStatusBarScrimColor(staticColor)

        // Usar iconos blancos para el toolbar (apropiado para fondo oscuro)
        binding.toolbar.navigationIcon?.setTint(android.graphics.Color.WHITE)

        // Use centralized status bar color utility
        StatusBarUtils.setStatusBarColor(this)
    }

    override fun onResume() {
        super.onResume()
        StatusBarUtils.setStatusBarColor(this)
    }

    override fun onStart() {
        super.onStart()
        bindMusicService()
    }

    override fun onStop() {
        super.onStop()
        unbindMusicService()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        // Ensure unbound
        unbindMusicService()
    }

    private fun isPlaylistFullyDownloaded(): Boolean {
        val dm = SongDownloadManager.getInstance(requireContext())
        val songs = playlistSongs.toList()
        if (songs.isEmpty()) return false
        return songs.all { File(dm.createDownloadPath(it)).exists() }
    }

    private fun showConfirmDeletePlaylistDownloads() {
        val dm = SongDownloadManager.getInstance(requireContext())
        val downloadedSongs = playlistSongs.filter { File(dm.createDownloadPath(it)).exists() }
        if (downloadedSongs.isEmpty()) {
            snack("No hay descargas para eliminar")
            return
        }
        val count = downloadedSongs.size
        val message = if (count == 1) {
            "Se eliminará 1 canción descargada de esta playlist. ¿Deseas continuar?"
        } else {
            "Se eliminarán $count canciones descargadas de esta playlist. ¿Deseas continuar?"
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Eliminar descargas de la playlist")
            .setMessage(message)
            .setPositiveButton("Eliminar") { _, _ ->
                deletePlaylistDownloads(downloadedSongs)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun deletePlaylistDownloads(songsToDelete: List<Song>) {
        val dm = SongDownloadManager.getInstance(requireContext())
        var deleted = 0
        songsToDelete.forEach { song ->
            val removed = dm.deleteSong(song.id)
            if (removed) {
                deleted++
            } else {
                try {
                    val path = dm.createDownloadPath(song)
                    val f = File(path)
                    if (f.exists() && f.delete()) {
                        deleted++
                    }
                    dm.cancelDownload(song.id)
                } catch (_: Exception) { /* ignore */ }
            }
        }
        updateDownloadUIState()
        songAdapter.notifyDataSetChanged()
        val msg = when (deleted) {
            0 -> "No se pudo eliminar ninguna descarga"
            1 -> "Se eliminó 1 descarga"
            else -> "Se eliminaron $deleted descargas"
        }
        snack(msg)
    }
}
