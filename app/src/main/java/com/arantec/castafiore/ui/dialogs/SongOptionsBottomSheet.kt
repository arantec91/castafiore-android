package com.arantec.castafiore.ui.dialogs

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.arantec.castafiore.R
import com.arantec.castafiore.data.models.Song
import com.arantec.castafiore.data.repository.MusicRepository
import com.arantec.castafiore.databinding.BottomSheetSongOptionsBinding
import com.bumptech.glide.Glide
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class SongOptionsBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetSongOptionsBinding? = null
    private val binding get() = _binding!!

    private var song: Song? = null
    private var isSongFavorited = false
    private lateinit var musicRepository: MusicRepository

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
            song = it.getParcelable(ARG_SONG)
            isSongFavorited = it.getBoolean(ARG_IS_FAVORITED, false)
        }
        musicRepository = MusicRepository.getInstance(requireContext())
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
        checkSongFavoriteStatus() // Verificar el estado inicial de favoritos
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
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
        }
    }

    private fun loadSongCover(song: Song) {
        try {
            if (musicRepository.serverUrl != null) {
                val (username, token, salt) = musicRepository.getAuthParams()
                val coverUrl = song.getCoverArtUrl(
                    musicRepository.serverUrl!!,
                    username,
                    token,
                    salt
                )

                Glide.with(this)
                    .load(coverUrl)
                    .placeholder(R.drawable.ic_music_note)
                    .error(R.drawable.ic_music_note)
                    .into(binding.ivSongCover)
            } else {
                binding.ivSongCover.setImageResource(R.drawable.ic_music_note)
            }
        } catch (e: Exception) {
            binding.ivSongCover.setImageResource(R.drawable.ic_music_note)
        }
    }

    private fun updateFavoriteButton() {
        if (isSongFavorited) {
            binding.btnSongFavorite.setImageResource(R.drawable.ic_favorite)
            binding.btnSongFavorite.setColorFilter(android.graphics.Color.parseColor("#FF2D55")) // Color principal
        } else {
            binding.btnSongFavorite.setImageResource(R.drawable.ic_favorite_border)
            binding.btnSongFavorite.setColorFilter(android.graphics.Color.parseColor("#B3FFFFFF")) // Color texto secundario
        }
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

                result.fold(
                    onSuccess = {
                        isSongFavorited = !isSongFavorited
                        updateFavoriteButton()
                    },
                    onFailure = { error ->
                        Toast.makeText(
                            requireContext(),
                            "Error al actualizar favoritos: ${error.message}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                )
            } catch (e: Exception) {
                Toast.makeText(
                    requireContext(),
                    "Error: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
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
                } catch (e: Exception) {
                    isSongFavorited = false
                    updateFavoriteButton()
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
}
