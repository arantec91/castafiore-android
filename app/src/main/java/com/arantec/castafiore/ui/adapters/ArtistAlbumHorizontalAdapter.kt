package com.arantec.castafiore.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.arantec.castafiore.R
import com.arantec.castafiore.data.models.Album
import com.arantec.castafiore.data.repository.MusicRepository
import com.arantec.castafiore.databinding.ItemArtistAlbumHorizontalBinding
import com.arantec.castafiore.utils.ImageLoader

class ArtistAlbumHorizontalAdapter(
    private val onAlbumClick: (Album) -> Unit
) : ListAdapter<Album, ArtistAlbumHorizontalAdapter.ArtistAlbumViewHolder>(DIFF) {

    companion object {
        private const val VIEW_TYPE_ARTIST_ALBUM = 1003

        private val DIFF = object : DiffUtil.ItemCallback<Album>() {
            override fun areItemsTheSame(oldItem: Album, newItem: Album): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: Album, newItem: Album): Boolean =
                oldItem == newItem
        }
    }

    init {
        setHasStableIds(true)
    }

    fun updateAlbums(newAlbums: List<Album>) {
        // Usar submitList en lugar de notifyDataSetChanged para preservar ViewHolders
        submitList(newAlbums.toList())
    }

    override fun getItemId(position: Int): Long {
        return getItem(position).id.hashCode().toLong()
    }

    override fun getItemViewType(position: Int): Int {
        return VIEW_TYPE_ARTIST_ALBUM
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ArtistAlbumViewHolder {
        val binding = ItemArtistAlbumHorizontalBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ArtistAlbumViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ArtistAlbumViewHolder, position: Int) {
        if (holder is ArtistAlbumViewHolder && position < itemCount) {
            holder.bind(getItem(position))
        }
    }

    inner class ArtistAlbumViewHolder(
        private val binding: ItemArtistAlbumHorizontalBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        // Track current URL to avoid unnecessary reloads
        private var currentUrl: String? = null

        fun bind(album: Album) {
            // Configurar información del álbum (sin año)
            binding.tvAlbumName.text = album.name
            binding.tvArtistName.text = album.artist

            // Cargar imagen del álbum usando ImageLoader optimizado
            val context = binding.root.context
            val musicRepository = MusicRepository.getInstance(context)

            try {
                val targetUrl: String? = if (album.coverArt != null && musicRepository.serverUrl != null) {
                    val (username, token, salt) = musicRepository.getAuthParams()
                    ImageLoader.buildCoverArtUrl(
                        musicRepository.serverUrl!!,
                        album.coverArt!!,
                        username,
                        token,
                        salt,
                        300 // Tamaño optimizado para las imágenes horizontales
                    )
                } else {
                    null
                }

                // CRÍTICO: Solo recargar si la URL cambió
                // Esto evita que Glide ponga el placeholder cuando la imagen ya está cargada
                if (targetUrl != currentUrl) {
                    currentUrl = targetUrl

                    if (targetUrl != null) {
                        ImageLoader.loadAlbumCover(context, binding.ivAlbumCover, targetUrl)
                    } else {
                        binding.ivAlbumCover.setImageResource(R.drawable.ic_album_placeholder)
                    }
                }
                // Si targetUrl == currentUrl, NO hacer nada
                // La imagen ya está en el ImageView

            } catch (e: Exception) {
                currentUrl = null
                binding.ivAlbumCover.setImageResource(R.drawable.ic_album_placeholder)
            }

            // Configurar click listener
            binding.root.setOnClickListener {
                onAlbumClick(album)
            }
        }
    }
}
