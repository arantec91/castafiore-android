package com.arantec.castafiore.ui.dialogs

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.toColorInt
import androidx.core.os.BundleCompat
import androidx.lifecycle.lifecycleScope
import com.arantec.castafiore.R
import com.arantec.castafiore.data.download.SongDownloadManager
import com.arantec.castafiore.data.models.Song
import com.arantec.castafiore.data.repository.MusicRepository
import com.arantec.castafiore.databinding.BottomSheetSongOptionsBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.arantec.castafiore.service.MusicService
import com.arantec.castafiore.utils.ImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.android.material.snackbar.Snackbar

class SongOptionsBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetSongOptionsBinding? = null
    private val binding get() = _binding!!

    private var song: Song? = null
    private var isSongFavorited = false
    private lateinit var musicRepository: MusicRepository
    private lateinit var downloadManager: SongDownloadManager

    // Variables para controlar la visibilidad de opciones
    private var hideAddToQueue = false
    private var hidePlayNext = false

    // Callbacks para las acciones
    private var onAddToQueueClick: ((Song) -> Unit)? = null
    private var onPlayNextClick: ((Song) -> Unit)? = null
    private var onAddToPlaylistClick: ((Song) -> Unit)? = null
    private var onViewAlbumClick: ((Song) -> Unit)? = null
    private var onViewArtistClick: ((Song) -> Unit)? = null
    private var onShareClick: ((Song) -> Unit)? = null
    private var onSongInfoClick: ((Song) -> Unit)? = null
    private var onRemoveFromPlaylistClick: ((Song) -> Unit)? = null
    private var onSongRadioClick: ((Song, List<Song>) -> Unit)? = null

    private var musicService: MusicService? = null
    private var isBound: Boolean = false
    private var pendingAction: (() -> Unit)? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MusicService.MusicBinder
            musicService = binder.getService()
            isBound = true
            pendingAction?.invoke()
            pendingAction = null
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            musicService = null
        }
    }

    companion object {
        private const val ARG_SONG = "song"
        private const val ARG_IS_FAVORITED = "is_favorited"

        fun newInstance(song: Song, isFavorited: Boolean = false): SongOptionsBottomSheet {
            val fragment = SongOptionsBottomSheet()
            val args = Bundle().apply {
                putParcelable(ARG_SONG, song)
                putBoolean(ARG_IS_FAVORITED, isFavorited)
            }
            fragment.arguments = args
            return fragment
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            song = BundleCompat.getParcelable(it, ARG_SONG, Song::class.java)
            isSongFavorited = it.getBoolean(ARG_IS_FAVORITED, false)
        }
        musicRepository = MusicRepository.getInstance(requireContext())
        // Initialize download manager
        downloadManager = SongDownloadManager.getInstance(requireContext())
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetSongOptionsBinding.inflate(inflater, container, false)
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
        setupDownloadOptions()
        checkSongFavoriteStatus() // Verificar el estado inicial de favoritos
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        if (isBound) {
            try { requireContext().unbindService(serviceConnection) } catch (_: Exception) {}
            isBound = false
            musicService = null
        }
    }

    private fun setupUI() {
        song?.let { currentSong ->
            // Configurar información de la canción
            binding.tvSongTitle.text = currentSong.title
            binding.tvSongArtist.text = currentSong.artist

            // Configurar imagen de la canción
            loadSongCover(currentSong)

            // Configurar botón de favorito
            updateFavoriteButton()

            // Ocultar opciones según las variables de control
            if (hideAddToQueue) {
                binding.optionAddToQueue.visibility = View.GONE
            }
            if (hidePlayNext) {
                binding.optionPlayNext.visibility = View.GONE
            }

            // Mostrar/ocultar "Eliminar de la playlist" según disponibilidad de callback
            val inPlaylistContext = onRemoveFromPlaylistClick != null
            binding.optionRemoveFromPlaylist.visibility = if (inPlaylistContext) View.VISIBLE else View.GONE
            // Si ya está en la lista (contexto playlist), ocultar "Agregar a playlist" para evitar confusión
            if (inPlaylistContext) {
                binding.optionAddToPlaylist.visibility = View.GONE
            } else {
                binding.optionAddToPlaylist.visibility = View.VISIBLE
            }
        }
    }

    private fun loadSongCover(song: Song) {
        try {
            // Preferir portada local si está disponible (descargas)
            val localCoverPath = try { downloadManager.createCoverPath(song) } catch (_: Exception) { null }
            if (!localCoverPath.isNullOrEmpty()) {
                val file = java.io.File(localCoverPath)
                if (file.exists()) {
                    ImageLoader.loadLocalThumbnail(requireContext(), binding.ivSongCover, localCoverPath)
                    return
                }
            }

            // Fallback a URL remota si hay servidor/config disponible
            if (musicRepository.serverUrl != null) {
                val (username, token, salt) = musicRepository.getAuthParams()
                val coverUrl = song.getCoverArtUrl(
                    musicRepository.serverUrl!!,
                    username,
                    token,
                    salt
                )

                // Usar ImageLoader para manejo de caché consistente
                ImageLoader.loadThumbnail(requireContext(), binding.ivSongCover, coverUrl)
            } else {
                binding.ivSongCover.setImageResource(R.drawable.ic_music_note)
            }
        } catch (_: Exception) {
            binding.ivSongCover.setImageResource(R.drawable.ic_music_note)
        }
    }

    private fun updateFavoriteButton() {
        // Verificar que el binding no sea null antes de usarlo
        val currentBinding = _binding ?: return
        
        if (isSongFavorited) {
            currentBinding.btnSongFavorite.setImageResource(R.drawable.ic_favorite)
            currentBinding.btnSongFavorite.setColorFilter("#FF2D55".toColorInt()) // Color principal
        } else {
            currentBinding.btnSongFavorite.setImageResource(R.drawable.ic_favorite_border)
            currentBinding.btnSongFavorite.setColorFilter("#B3FFFFFF".toColorInt()) // Color texto secundario
        }
    }

    private fun bindMusicService() {
        try {
            val intent = Intent(requireContext(), MusicService::class.java)
            requireContext().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        } catch (_: Exception) {
            // ignore
        }
    }

    private fun setupDownloadOptions() {
        val currentSong = song ?: return
        val isDownloaded = downloadManager.isSongDownloaded(currentSong.id)
        binding.optionDownload.visibility = if (isDownloaded) View.GONE else View.VISIBLE
        binding.optionDeleteDownload.visibility = if (isDownloaded) View.VISIBLE else View.GONE
    }

    // Centraliza la visualización de mensajes usando Snackbar
    private fun showSnackbar(message: String) {
        // Preferir la raíz de la Activity para que el mensaje persista si se cierra el BottomSheet
        val rootView = (activity?.findViewById<View>(android.R.id.content)
            ?: view)
            ?: return
        Snackbar.make(rootView, message, Snackbar.LENGTH_SHORT).show()
    }

    private fun setupClickListeners() {
        song?.let { currentSong ->

            // Botón de favorito en el header
            binding.btnSongFavorite.setOnClickListener {
                toggleSongFavorite(currentSong)
            }

            // Agregar a cola
            binding.optionAddToQueue.setOnClickListener {
                onAddToQueueClick?.invoke(currentSong)
                dismiss()
            }

            // Reproducir siguiente
            binding.optionPlayNext.setOnClickListener {
                onPlayNextClick?.invoke(currentSong)
                dismiss()
            }

            // Agregar a playlist
            binding.optionAddToPlaylist.setOnClickListener {
                onAddToPlaylistClick?.invoke(currentSong)
                dismiss()
            }

            // Eliminar de la playlist
            binding.optionRemoveFromPlaylist.setOnClickListener {
                onRemoveFromPlaylistClick?.invoke(currentSong)
                dismiss()
            }

            // Ver álbum
            binding.optionViewAlbum.setOnClickListener {
                onViewAlbumClick?.invoke(currentSong)
                dismiss()
            }

            // Ver artista
            binding.optionViewArtist.setOnClickListener {
                onViewArtistClick?.invoke(currentSong)
                dismiss()
            }

            // Información de la canción
            binding.optionSongInfo.setOnClickListener {
                onSongInfoClick?.invoke(currentSong)
                dismiss()
            }

            // Radio de la canción
            binding.optionSongRadio.setOnClickListener {
                // UI: show spinner and disable option to avoid double taps
                binding.progressSongRadio.visibility = View.VISIBLE
                binding.optionSongRadio.isEnabled = false
                binding.iconSongRadio.alpha = 0.5f

                viewLifecycleOwner.lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        musicRepository.getSimilarSongs(currentSong.id)
                    }
                    result.fold(
                        onSuccess = { similarSongs ->
                            val filtered = similarSongs.filter { it.id != currentSong.id }
                            val queue = listOf(currentSong) + filtered
                            if (queue.size > 1) {
                                val playAction: () -> Unit = {
                                    musicService?.playQueue(
                                        queue,
                                        0,
                                        MusicService.PlaybackSource(
                                            MusicService.SourceType.SONGS,
                                            null,
                                            "Radio"
                                        )
                                    )
                                }
                                if (isBound && musicService != null) {
                                    playAction()
                                } else {
                                    pendingAction = playAction
                                    bindMusicService()
                                }
                                dismiss()
                            } else {
                                showSnackbar(getString(R.string.error_loading_favorites))
                                // Restore UI when keeping the sheet open
                                binding.progressSongRadio.visibility = View.GONE
                                binding.optionSongRadio.isEnabled = true
                                binding.iconSongRadio.alpha = 1f
                            }
                        },
                        onFailure = { e ->
                            showSnackbar("Error al cargar radio: ${e.message}")
                            // Restore UI on error
                            binding.progressSongRadio.visibility = View.GONE
                            binding.optionSongRadio.isEnabled = true
                            binding.iconSongRadio.alpha = 1f
                        }
                    )
                }
            }

            // Descargar a dispositivo
            binding.optionDownload.setOnClickListener {
                if (!downloadManager.isSongDownloaded(currentSong.id)) {
                    downloadManager.downloadSong(currentSong)
                    showSnackbar(getString(R.string.download_started))
                    dismiss()
                } else {
                    showSnackbar(getString(R.string.song_already_downloaded))
                    setupDownloadOptions()
                }
            }

            // Eliminar descarga
            binding.optionDeleteDownload.setOnClickListener {
                val ok = downloadManager.deleteSong(currentSong.id)
                if (ok) {
                    showSnackbar(getString(R.string.download_deleted))
                    setupDownloadOptions()
                    dismiss()
                } else {
                    showSnackbar(getString(R.string.download_delete_error))
                }
            }
        }
    }

    private fun toggleSongFavorite(song: Song) {
        // Implementar lógica real de favoritos para canciones conectada con la API de Navidrome
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    if (isSongFavorited) {
                        musicRepository.unstarSong(song.id)
                    } else {
                        musicRepository.starSong(song.id)
                    }
                }

                // Verificar que la vista aún exista antes de actualizar la UI
                if (_binding != null) {
                    result.fold(
                        onSuccess = {
                            isSongFavorited = !isSongFavorited
                            updateFavoriteButton()
                            // Streaming-only: remove auto-download behavior when favorites were fully downloaded
                        },
                        onFailure = { error ->
                            showSnackbar(
                                "Error al actualizar favoritos: ${error.message}"
                            )
                        }
                    )
                }
            } catch (e: Exception) {
                // Verificar que la vista aún exista antes de mostrar el mensaje
                if (_binding != null) {
                    showSnackbar(
                        "Error: ${e.message}"
                    )
                }
            }
        }
    }

    private fun checkSongFavoriteStatus() {
        song?.let { currentSong ->
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val result = withContext(Dispatchers.IO) {
                        musicRepository.isSongStarred(currentSong.id)
                    }

                    // Verificar que la vista aún exista antes de actualizar la UI
                    if (_binding != null) {
                        result.fold(
                            onSuccess = { isStarred ->
                                isSongFavorited = isStarred
                                updateFavoriteButton()
                            },
                            onFailure = {
                                // Si falla la verificación, asumir que no está marcado como favorito
                                isSongFavorited = false
                                updateFavoriteButton()
                            }
                        )
                    }
                } catch (_: Exception) {
                    // Verificar que la vista aún exista antes de actualizar la UI
                    if (_binding != null) {
                        isSongFavorited = false
                        updateFavoriteButton()
                    }
                }
            }
        }
    }

    // Métodos para configurar los callbacks
    @Deprecated("Las opciones de descarga han sido eliminadas de la UI; este método no hace nada.")
    fun setOnDownloadClickListener(@Suppress("UNUSED_PARAMETER") listener: (Song) -> Unit): SongOptionsBottomSheet {
        // No-op
        return this
    }

    @Deprecated("Las opciones de descarga han sido eliminadas de la UI; este método no hace nada.")
    fun setOnDeleteDownloadClickListener(@Suppress("UNUSED_PARAMETER") listener: (Song) -> Unit): SongOptionsBottomSheet {
        // No-op
        return this
    }

    fun setOnAddToQueueClickListener(listener: (Song) -> Unit): SongOptionsBottomSheet {
        onAddToQueueClick = listener
        return this
    }

    fun setOnPlayNextClickListener(listener: (Song) -> Unit): SongOptionsBottomSheet {
        onPlayNextClick = listener
        return this
    }

    fun setOnAddToPlaylistClickListener(listener: (Song) -> Unit): SongOptionsBottomSheet {
        onAddToPlaylistClick = listener
        return this
    }

    fun setOnViewAlbumClickListener(listener: (Song) -> Unit): SongOptionsBottomSheet {
        onViewAlbumClick = listener
        return this
    }

    fun setOnViewArtistClickListener(listener: (Song) -> Unit): SongOptionsBottomSheet {
        onViewArtistClick = listener
        return this
    }

    fun setOnShareClickListener(listener: (Song) -> Unit): SongOptionsBottomSheet {
        onShareClick = listener
        return this
    }

    fun setOnSongInfoClickListener(listener: (Song) -> Unit): SongOptionsBottomSheet {
        onSongInfoClick = listener
        return this
    }

    fun setOnRemoveFromPlaylistClickListener(listener: (Song) -> Unit): SongOptionsBottomSheet {
        onRemoveFromPlaylistClick = listener
        return this
    }

    fun setOnSongRadioClickListener(listener: (Song, List<Song>) -> Unit): SongOptionsBottomSheet {
        onSongRadioClick = listener
        return this
    }

    /**
     * Oculta la opción "Agregar a cola" de la UI
     * Útil cuando la canción ya está en reproducción y no tiene sentido agregarla de nuevo
     */
    fun hideAddToQueueOption(): SongOptionsBottomSheet {
        hideAddToQueue = true
        _binding?.optionAddToQueue?.visibility = View.GONE
        return this
    }

    /**
     * Oculta la opción "Reproducir siguiente" de la UI
     * Útil cuando la canción ya está reproduciéndose y no tiene sentido reproductirla siguiente
     */
    fun hidePlayNextOption(): SongOptionsBottomSheet {
        hidePlayNext = true
        _binding?.optionPlayNext?.visibility = View.GONE
        return this
    }

    /**
     * Oculta la opción "Ver álbum" de la UI
     * Útil cuando ya estamos viendo el álbum actual y no tiene sentido navegar al mismo álbum
     */
    fun hideViewAlbumOption(): SongOptionsBottomSheet {
        _binding?.optionViewAlbum?.visibility = View.GONE
        return this
    }

    /**
     * Oculta la opción "Ver artista" de la UI
     * Útil cuando no se dispone del ID de artista (por ejemplo, en descargas sin metadatos)
     */
    fun hideViewArtistOption(): SongOptionsBottomSheet {
        _binding?.optionViewArtist?.visibility = View.GONE
        return this
    }
}
