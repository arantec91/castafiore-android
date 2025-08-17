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
import android.widget.EditText
import android.widget.Toast
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
import com.bumptech.glide.Glide
import java.util.Locale

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

        setupToolbar()
        setupRecyclerView()
        setupFab()
        setupMoreButton()
        // Defer binding to onStart so only visible fragment attaches listeners
        // bindMusicService()
        loadPlaylist()
    }

    private fun setupMoreButton() {
        binding.btnMore.setOnClickListener {
            com.arantec.castafiore.ui.dialogs.PlaylistOptionsBottomSheet()
                .setOnEditNameClickListener { showRenameDialog() }
                .setOnDeleteListClickListener { confirmDeletePlaylist() }
                // Do not set download listener yet as requested
                .show(childFragmentManager, "PlaylistOptionsBottomSheet")
        }
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener { findNavController().popBackStack() }
        // Removed toolbar menu to eliminate "more options" and "delete playlist" icons
        binding.tvTitle.text = playlistName ?: getString(R.string.app_name)
        binding.gradientBackground.setBackgroundColor(0xFF121212.toInt())
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
                            Toast.makeText(requireContext(), getString(R.string.added_to_queue, selectedSong.title), Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(requireContext(), getString(R.string.music_service_unavailable), Toast.LENGTH_SHORT).show()
                            bindMusicService()
                        }
                    }
                    .setOnPlayNextClickListener { selectedSong ->
                        val service = musicService
                        if (service != null) {
                            service.playNext(selectedSong)
                            Toast.makeText(requireContext(), getString(R.string.will_play_next, selectedSong.title), Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(requireContext(), getString(R.string.music_service_unavailable), Toast.LENGTH_SHORT).show()
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
                                            Toast.makeText(requireContext(), "No se pudo abrir el álbum", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    onFailure = {
                                        Toast.makeText(requireContext(), "No se pudo abrir el álbum", Toast.LENGTH_SHORT).show()
                                    }
                                )
                            }
                        } else {
                            Toast.makeText(requireContext(), "Álbum no disponible", Toast.LENGTH_SHORT).show()
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
                                Toast.makeText(requireContext(), "No se pudo abrir el artista", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            Toast.makeText(requireContext(), "Artista no disponible", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setOnSongInfoClickListener { selectedSong ->
                        showSongInfo(selectedSong)
                    }
                    .setOnRemoveFromPlaylistClickListener { selectedSong ->
                        // Eliminar la canción de la playlist directamente
                        confirmRemoveSong(selectedSong)
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
                service.playQueue(
                    playlistSongs,
                    0,
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
                // Cover art
                val (username, token, salt) = musicRepository.getAuthParams()
                val coverUrl = info.getCoverArtUrl(
                    musicRepository.serverUrl ?: "",
                    username,
                    token,
                    salt,
                    300
                )
                ImageLoader.loadAlbumCover(requireContext(), binding.ivHeaderCover, coverUrl)
            }

            // Canciones
            musicRepository.getPlaylistSongs(id).fold(
                onSuccess = { songs ->
                    playlistSongs.clear()
                    playlistSongs.addAll(songs)
                    songAdapter.updateSongs(playlistSongs)

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
            Toast.makeText(requireContext(), getString(R.string.remove_from_playlist_error), Toast.LENGTH_SHORT).show()
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
                    Toast.makeText(requireContext(), getString(R.string.removed_from_playlist), Toast.LENGTH_SHORT).show()
                },
                onFailure = {
                    Toast.makeText(requireContext(), getString(R.string.remove_from_playlist_error), Toast.LENGTH_SHORT).show()
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
                                Toast.makeText(requireContext(), getString(R.string.playlist_rename_success), Toast.LENGTH_SHORT).show()
                            },
                            onFailure = {
                                Toast.makeText(requireContext(), getString(R.string.playlist_rename_error), Toast.LENGTH_SHORT).show()
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
        val name = playlistInfo?.name ?: playlistName ?: ""
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.delete_playlist)
            .setMessage(getString(R.string.delete_playlist_confirm, name))
            .setPositiveButton(R.string.delete) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    musicRepository.deletePlaylist(id).fold(
                        onSuccess = {
                            Toast.makeText(requireContext(), getString(R.string.playlist_deleted_success), Toast.LENGTH_SHORT).show()
                            findNavController().popBackStack()
                        },
                        onFailure = {
                            Toast.makeText(requireContext(), getString(R.string.playlist_delete_error), Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
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
}
