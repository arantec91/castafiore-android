package com.arantec.castafiore.ui.dialogs

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.arantec.castafiore.data.models.Album
import com.arantec.castafiore.databinding.BottomSheetAlbumOptionsBinding
import com.arantec.castafiore.utils.ImageLoader
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class AlbumOptionsBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetAlbumOptionsBinding? = null
    private val binding get() = _binding!!

    private var album: Album? = null
    private var isFavorited = false
    private var onAddToQueueClickListener: ((Album) -> Unit)? = null
    private var onAddToFavoritesClickListener: ((Album) -> Unit)? = null
    private var onAddAlbumToPlaylistClickListener: ((Album) -> Unit)? = null
    private var onAlbumInfoClickListener: ((Album) -> Unit)? = null

    companion object {
        private const val ARG_ALBUM = "album"

        fun newInstance(album: Album): AlbumOptionsBottomSheet {
            val fragment = AlbumOptionsBottomSheet()
            val args = Bundle()
            args.putParcelable(ARG_ALBUM, album)
            fragment.arguments = args
            return fragment
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        album = arguments?.getParcelable(ARG_ALBUM)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetAlbumOptionsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Configurar el BottomSheetDialog para fondo transparente y esquinas redondeadas
        dialog?.let { dialog ->
            dialog.setOnShowListener {
                val bottomSheetDialog = it as com.google.android.material.bottomsheet.BottomSheetDialog
                val bottomSheet = bottomSheetDialog.findViewById<android.widget.FrameLayout>(
                    com.google.android.material.R.id.design_bottom_sheet
                )

                // Eliminar el fondo por defecto del BottomSheet para evitar el gris
                bottomSheet?.background = null

                // Mantener dimming, no tocar status/navigation bar para evitar parpadeos
                bottomSheetDialog.window?.setDimAmount(0.5f)
            }
        }

        setupViews()
        setupClickListeners()
        checkFavoriteStatus()
    }

    private fun setupViews() {
        album?.let { albumData ->
            binding.tvAlbumTitle.text = albumData.name
            binding.tvArtistName.text = albumData.artist

            // Cargar la imagen del álbum
            loadAlbumCover(albumData)

            // Actualizar UI de favoritos (solo botón de cabecera)
            updateFavoriteButton()
        }
    }

    private fun updateFavoriteButton() {
        if (!isAdded || _binding == null) return

        if (isFavorited) {
            binding.btnAlbumFavorite.setImageResource(com.arantec.castafiore.R.drawable.ic_favorite)
            binding.btnAlbumFavorite.setColorFilter(android.graphics.Color.parseColor("#FF2D55"))
        } else {
            binding.btnAlbumFavorite.setImageResource(com.arantec.castafiore.R.drawable.ic_favorite_border)
            binding.btnAlbumFavorite.setColorFilter(android.graphics.Color.parseColor("#B3FFFFFF"))
        }
    }

    private fun checkFavoriteStatus() {
        album?.let { albumData ->
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val musicRepository = com.arantec.castafiore.data.repository.MusicRepository.getInstance(requireContext())
                    val result = withContext(Dispatchers.IO) {
                        musicRepository.isAlbumStarred(albumData.id)
                    }
                    val isStarred = result.getOrElse { false }

                    isFavorited = isStarred
                    updateFavoriteButton()
                } catch (e: Exception) {
                    isFavorited = false
                    updateFavoriteButton()
                }
            }
        }
    }

    private fun loadAlbumCover(album: Album) {
        try {
            val musicRepository = com.arantec.castafiore.data.repository.MusicRepository.getInstance(requireContext())

            if (musicRepository.serverUrl != null && album.coverArt != null) {
                val (username, token, salt) = musicRepository.getAuthParams()
                val coverUrl = ImageLoader.buildCoverArtUrl(
                    musicRepository.serverUrl!!,
                    album.coverArt!!,
                    username,
                    token,
                    salt
                )

                ImageLoader.loadAlbumCover(requireContext(), binding.ivAlbumCover, coverUrl)
            } else {
                binding.ivAlbumCover.setImageResource(com.arantec.castafiore.R.drawable.ic_album_placeholder)
            }
        } catch (_: Exception) {
            binding.ivAlbumCover.setImageResource(com.arantec.castafiore.R.drawable.ic_album_placeholder)
        }
    }

    private fun setupClickListeners() {
        // Botón de favorito en el header
        binding.btnAlbumFavorite.setOnClickListener {
            album?.let { albumData ->
                isFavorited = !isFavorited
                updateFavoriteButton()
                onAddToFavoritesClickListener?.invoke(albumData)
            }
        }

        binding.llAddToQueue.setOnClickListener {
            album?.let { onAddToQueueClickListener?.invoke(it) }
            dismiss()
        }

        binding.llAddAlbumToPlaylist.setOnClickListener {
            album?.let { onAddAlbumToPlaylistClickListener?.invoke(it) }
            dismiss()
        }

        binding.llAlbumInfo.setOnClickListener {
            album?.let { onAlbumInfoClickListener?.invoke(it) }
            dismiss()
        }
    }

    // Builder pattern methods
    @Deprecated("Download option removed in streaming-only mode; this is a no-op.")
    fun setOnDownloadClickListener(@Suppress("UNUSED_PARAMETER") listener: (Album) -> Unit): AlbumOptionsBottomSheet {
        // No-op to preserve compatibility
        return this
    }

    fun setOnAddToFavoritesClickListener(listener: (Album) -> Unit): AlbumOptionsBottomSheet {
        onAddToFavoritesClickListener = listener
        return this
    }

    fun setOnAddToQueueClickListener(listener: (Album) -> Unit): AlbumOptionsBottomSheet {
        onAddToQueueClickListener = listener
        return this
    }

    @Deprecated("Share option removed from AlbumOptionsBottomSheet UI")
    fun setOnShareClickListener(@Suppress("UNUSED_PARAMETER") listener: (Album) -> Unit): AlbumOptionsBottomSheet {
        // No-op to preserve compatibility
        return this
    }

    fun setOnAlbumInfoClickListener(listener: (Album) -> Unit): AlbumOptionsBottomSheet {
        onAlbumInfoClickListener = listener
        return this
    }

    fun setOnAddAlbumToPlaylistClickListener(listener: (Album) -> Unit): AlbumOptionsBottomSheet {
        onAddAlbumToPlaylistClickListener = listener
        return this
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
