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
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class AlbumOptionsBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetAlbumOptionsBinding? = null
    private val binding get() = _binding!!

    private var album: Album? = null
    private var isFavorited = false // Agregar estado de favoritos
    private var onDownloadClickListener: ((Album) -> Unit)? = null
    private var onAddToFavoritesClickListener: ((Album) -> Unit)? = null
    private var onAddToQueueClickListener: ((Album) -> Unit)? = null
    private var onShareClickListener: ((Album) -> Unit)? = null
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

                // Configurar la ventana para transparencia sin interferir con navigation bar
                bottomSheetDialog.window?.let { window ->
                    window.setDimAmount(0.5f) // Mantener el dimming
                    window.statusBarColor = android.graphics.Color.TRANSPARENT

                    // Usar un color semi-transparente para la navigation bar en lugar de transparente
                    window.navigationBarColor = android.graphics.Color.parseColor("#80000000")

                    // Remover las flags que causan el problema con navigation bar
                    window.decorView.systemUiVisibility = (
                        android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                        android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    )
                }

                // Hacer que el BottomSheet use nuestro background personalizado
                bottomSheet?.clipToOutline = true
            }
        }

        setupViews()
        setupClickListeners()
        checkFavoriteStatus() // Verificar estado inicial de favoritos
    }

    private fun setupViews() {
        album?.let { albumData ->
            binding.tvAlbumTitle.text = albumData.name
            binding.tvArtistName.text = albumData.artist

            // Cargar la imagen del álbum
            loadAlbumCover(albumData)

            // Actualizar UI de favoritos
            updateFavoriteButton()
        }
    }

    private fun checkFavoriteStatus() {
        album?.let { albumData ->
            lifecycleScope.launch {
                try {
                    val musicRepository = com.arantec.castafiore.data.repository.MusicRepository.getInstance(requireContext())
                    val result = withContext(Dispatchers.IO) {
                        musicRepository.isAlbumStarred(albumData.id)
                    }

                    result.fold(
                        onSuccess = { isStarred ->
                            isFavorited = isStarred
                            updateFavoriteButton()
                        },
                        onFailure = { _ ->
                            // Si falla la verificación, asumir que no es favorito
                            isFavorited = false
                            updateFavoriteButton()
                        }
                    )
                } catch (e: Exception) {
                    isFavorited = false
                    updateFavoriteButton()
                }
            }
        }
    }

    private fun updateFavoriteButton() {
        if (!isAdded || _binding == null) return

        // Actualizar el botón de favorito en el header
        if (isFavorited) {
            binding.btnAlbumFavorite.setImageResource(com.arantec.castafiore.R.drawable.ic_favorite)
            binding.btnAlbumFavorite.setColorFilter(android.graphics.Color.parseColor("#FF2D55")) // Color principal
        } else {
            binding.btnAlbumFavorite.setImageResource(com.arantec.castafiore.R.drawable.ic_favorite_border)
            binding.btnAlbumFavorite.setColorFilter(android.graphics.Color.parseColor("#B3FFFFFF")) // Color texto secundario
        }

        // Acceder a los elementos hijos del LinearLayout llAddToFavorites
        val favoriteContainer = binding.llAddToFavorites
        val favoriteImageView = favoriteContainer.getChildAt(0) as? android.widget.ImageView
        val favoriteTextView = favoriteContainer.getChildAt(1) as? android.widget.TextView

        if (isFavorited) {
            // Álbum es favorito - mostrar "Quitar de favoritos"
            favoriteTextView?.text = "Quitar de favoritos"
            favoriteImageView?.setImageResource(com.arantec.castafiore.R.drawable.ic_favorite)
            favoriteImageView?.setColorFilter(android.graphics.Color.parseColor("#FF2D55")) // Color principal
        } else {
            // Álbum no es favorito - mostrar "Agregar a favoritos"
            favoriteTextView?.text = "Agregar a favoritos"
            favoriteImageView?.setImageResource(com.arantec.castafiore.R.drawable.ic_favorite_border)
            favoriteImageView?.setColorFilter(android.graphics.Color.parseColor("#FFFFFF")) // Color texto primario
        }
    }

    private fun loadAlbumCover(album: Album) {
        try {
            // Obtener la instancia del repositorio para acceder a la configuración del servidor
            val musicRepository = com.arantec.castafiore.data.repository.MusicRepository.getInstance(requireContext())

            if (musicRepository.serverUrl != null && album.coverArt != null) {
                val (username, token, salt) = musicRepository.getAuthParams()
                val coverUrl = album.getCoverArtUrl(
                    musicRepository.serverUrl!!,
                    username,
                    token,
                    salt
                )

                com.bumptech.glide.Glide.with(this)
                    .load(coverUrl)
                    .placeholder(com.arantec.castafiore.R.drawable.ic_album_placeholder)
                    .error(com.arantec.castafiore.R.drawable.ic_album_placeholder)
                    .centerCrop()
                    .into(binding.ivAlbumCover)
            } else {
                // Si no hay coverArt o serverUrl, usar placeholder
                binding.ivAlbumCover.setImageResource(com.arantec.castafiore.R.drawable.ic_album_placeholder)
            }
        } catch (e: Exception) {
            // En caso de error, usar placeholder
            binding.ivAlbumCover.setImageResource(com.arantec.castafiore.R.drawable.ic_album_placeholder)
        }
    }

    private fun setupClickListeners() {
        // Botón de favorito en el header
        binding.btnAlbumFavorite.setOnClickListener {
            album?.let { albumData ->
                // Cambiar el estado inmediatamente para una mejor UX
                isFavorited = !isFavorited
                updateFavoriteButton()

                // Llamar al listener externo
                onAddToFavoritesClickListener?.invoke(albumData)
            }
        }

        binding.llDownload.setOnClickListener {
            album?.let { onDownloadClickListener?.invoke(it) }
            dismiss()
        }

        binding.llAddToFavorites.setOnClickListener {
            album?.let { albumData ->
                // Cambiar el estado inmediatamente para una mejor UX
                isFavorited = !isFavorited
                updateFavoriteButton()

                // Llamar al listener externo
                onAddToFavoritesClickListener?.invoke(albumData)
            }
            dismiss()
        }

        binding.llAddToQueue.setOnClickListener {
            album?.let { onAddToQueueClickListener?.invoke(it) }
            dismiss()
        }

        binding.llShare.setOnClickListener {
            album?.let { onShareClickListener?.invoke(it) }
            dismiss()
        }

        binding.llAlbumInfo.setOnClickListener {
            album?.let { onAlbumInfoClickListener?.invoke(it) }
            dismiss()
        }
    }

    // Builder pattern methods
    fun setOnDownloadClickListener(listener: (Album) -> Unit): AlbumOptionsBottomSheet {
        onDownloadClickListener = listener
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

    fun setOnShareClickListener(listener: (Album) -> Unit): AlbumOptionsBottomSheet {
        onShareClickListener = listener
        return this
    }

    fun setOnAlbumInfoClickListener(listener: (Album) -> Unit): AlbumOptionsBottomSheet {
        onAlbumInfoClickListener = listener
        return this
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
