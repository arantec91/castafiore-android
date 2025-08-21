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
import com.arantec.castafiore.utils.StatusBarUtils
import com.arantec.castafiore.utils.snack
import kotlinx.coroutines.launch
import com.bumptech.glide.Glide
import java.util.Locale
import kotlin.random.Random
import com.arantec.castafiore.data.download.SongDownloadManager
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.Dispatchers
import androidx.core.content.ContextCompat
import android.content.res.ColorStateList
import java.io.File

class FavoritesFragment : Fragment() {

    private var _binding: FragmentFavoritesBinding? = null
    private val binding get() = _binding!!

    private lateinit var musicRepository: MusicRepository
    private lateinit var songAdapter: SongAdapter
    private var musicService: MusicService? = null
    private var isBound = false

    private val favoriteSongs = mutableListOf<Song>()
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
            val inContext = isFavoritesQueuePlaying()
            isPlaying = serviceIsPlaying && inContext
            updatePlayButton()
            // Solo marcar canción si este contexto está activo
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
        // Ensure consistent status bar color using utility
        StatusBarUtils.setStatusBarColor(this)

        _binding = FragmentFavoritesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        musicRepository = MusicRepository.getInstance(requireContext())

        setupToolbar()
        setupRecyclerView()
        // Removed eager bind here; we'll bind in onStart so only visible fragment listens
        // bindMusicService()
        loadFavorites()
        setupFab()

        // Download button and observers
        binding.btnDownload.setOnClickListener {
            if (isFavoritesFullyDownloaded()) {
                showConfirmDeleteFavoritesDownloads()
            } else {
                downloadFavorites()
            }
        }
        observeDownloadStates()
        updateDownloadUIState()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }
        // Removed solid color override to allow XML gradient to show
        // binding.gradientBackground.setBackgroundColor(0xFF121212.toInt())
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
                    updateDownloadUIState()
                    songAdapter.notifyDataSetChanged()

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

    // --- Downloads for Favorites ---
    private fun observeDownloadStates() {
        val dm = SongDownloadManager.getInstance(requireContext())
        viewLifecycleOwner.lifecycleScope.launch {
            dm.downloadStates
                .map {
                    val songs = favoriteSongs.toList()
                    val completed = songs.asSequence()
                        .map { it.id to File(dm.createDownloadPath(it)).exists() }
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
        val songs = favoriteSongs.toList()
        val anyDownloading = songs.any { dm.isSongDownloading(it.id) }
        val allDownloaded = songs.isNotEmpty() && songs.all { File(dm.createDownloadPath(it)).exists() }

        binding.progressDownload.visibility = if (anyDownloading) View.VISIBLE else View.GONE
        binding.btnDownload.visibility = if (anyDownloading) View.INVISIBLE else View.VISIBLE

        val tintColorRes = if (allDownloaded) R.color.primary else R.color.text_secondary
        binding.btnDownload.imageTintList = ColorStateList.valueOf(
            ContextCompat.getColor(requireContext(), tintColorRes)
        )
    }

    private fun downloadFavorites() {
        if (favoriteSongs.isEmpty()) {
            snack("No hay canciones para descargar")
            return
        }
        val dm = SongDownloadManager.getInstance(requireContext())
        val alreadyDownloaded = favoriteSongs.count { dm.isSongDownloaded(it.id) }
        val currentlyDownloading = favoriteSongs.count { dm.isSongDownloading(it.id) }
        val toDownload = favoriteSongs.filter { !dm.isSongDownloaded(it.id) && !dm.isSongDownloading(it.id) }
        when {
            alreadyDownloaded == favoriteSongs.size -> {
                snack("Todas las favoritas ya están descargadas")
            }
            toDownload.isEmpty() && currentlyDownloading > 0 -> {
                snack("Descargando favoritas ($currentlyDownloading pendientes)")
            }
            else -> {
                toDownload.forEach { song -> dm.downloadSong(song) }
                val message = if (alreadyDownloaded > 0) {
                    "Descargando ${toDownload.size} canciones restantes"
                } else {
                    "Descargando favoritas (${toDownload.size} canciones)"
                }
                snack(message)
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
                        snack(getString(R.string.song_already_downloaded))
                    }
                    downloadManager.isSongDownloading(selectedSong.id) -> {
                        downloadManager.cancelDownload(selectedSong.id)
                        snack(getString(R.string.download_canceled, selectedSong.title))
                    }
                    else -> {
                        downloadManager.downloadSong(selectedSong)
                        snack(getString(R.string.download_started, selectedSong.title))
                    }
                }
            }
            .setOnDeleteDownloadClickListener { selectedSong ->
                val downloadManager = com.arantec.castafiore.data.download.SongDownloadManager.getInstance(requireContext())
                if (downloadManager.isSongDownloaded(selectedSong.id)) {
                    val builder = androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    builder.setTitle(R.string.delete_download)
                    builder.setMessage(getString(R.string.delete_download_confirm, selectedSong.title))
                    builder.setPositiveButton(R.string.delete) { _: android.content.DialogInterface, _: Int ->
                        val success = downloadManager.deleteSong(selectedSong.id)
                        val msg = if (success) R.string.download_deleted else R.string.download_delete_error
                        snack(getString(msg))
                    }
                    builder.setNegativeButton(R.string.cancel, null)
                    builder.show()
                } else {
                    snack(getString(R.string.song_not_downloaded))
                }
            }
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
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Extra safety: ensure unbound
        unbindMusicService()
        _binding = null
    }

    private fun isFavoritesFullyDownloaded(): Boolean {
        val dm = SongDownloadManager.getInstance(requireContext())
        val songs = favoriteSongs.toList()
        if (songs.isEmpty()) return false
        return songs.all { File(dm.createDownloadPath(it)).exists() }
    }

    private fun showConfirmDeleteFavoritesDownloads() {
        val dm = SongDownloadManager.getInstance(requireContext())
        val downloadedSongs = favoriteSongs.filter { File(dm.createDownloadPath(it)).exists() }
        if (downloadedSongs.isEmpty()) {
            snack("No hay descargas para eliminar")
            return
        }
        val count = downloadedSongs.size
        val title = "Eliminar descargas de favoritas"
        val message = if (count == 1) {
            "Se eliminará 1 canción descargada de tus favoritas. ¿Deseas continuar?"
        } else {
            "Se eliminarán $count canciones descargadas de tus favoritas. ¿Deseas continuar?"
        }
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Eliminar") { _, _ ->
                deleteFavoritesDownloads(downloadedSongs)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun deleteFavoritesDownloads(songsToDelete: List<Song>) {
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
