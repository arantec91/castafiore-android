package com.arantec.castafiore.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.arantec.castafiore.R
import com.arantec.castafiore.data.models.Album
import com.arantec.castafiore.data.repository.MusicRepository
import com.arantec.castafiore.databinding.ItemArtistAlbumHorizontalBinding
import com.arantec.castafiore.utils.ImageLoader
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions

class ArtistAlbumHorizontalAdapter(
    private val onAlbumClick: (Album) -> Unit
) : RecyclerView.Adapter<ArtistAlbumHorizontalAdapter.ArtistAlbumViewHolder>() {

    private var albums = listOf<Album>()

    companion object {
        private const val VIEW_TYPE_ARTIST_ALBUM = 1003 // Unique view type for artist album items
    }

    fun updateAlbums(newAlbums: List<Album>) {
        albums = newAlbums
        notifyDataSetChanged()
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
        // Add type safety check to prevent ClassCastException
        if (holder is ArtistAlbumViewHolder && position < albums.size) {
            holder.bind(albums[position])
        }
    }

    override fun getItemCount(): Int = albums.size

    inner class ArtistAlbumViewHolder(
        private val binding: ItemArtistAlbumHorizontalBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(album: Album) {
            // Configurar información del álbum (sin año)
            binding.tvAlbumName.text = album.name
            binding.tvArtistName.text = album.artist

            // Cargar imagen del álbum usando ImageLoader optimizado
            val context = binding.root.context
            val musicRepository = MusicRepository.getInstance(context)

            try {
                if (album.coverArt != null && musicRepository.serverUrl != null) {
                    val (username, token, salt) = musicRepository.getAuthParams()
                    val coverUrl = ImageLoader.buildCoverArtUrl(
                        musicRepository.serverUrl!!,
                        album.coverArt!!,
                        username,
                        token,
                        salt,
                        300 // Tamaño optimizado para las imágenes horizontales
                    )

                    // Usar Glide con crossfade para una transición suave
                    com.bumptech.glide.Glide.with(context)
                        .load(coverUrl)
                        .placeholder(R.drawable.ic_album_placeholder)
                        .error(R.drawable.ic_album_placeholder)
                        .transition(DrawableTransitionOptions.withCrossFade(200))
                        .into(binding.ivAlbumCover)
                } else {
                    binding.ivAlbumCover.setImageResource(R.drawable.ic_album_placeholder)
                }
            } catch (e: Exception) {
                binding.ivAlbumCover.setImageResource(R.drawable.ic_album_placeholder)
            }

            // Configurar click listener
            binding.root.setOnClickListener {
                onAlbumClick(album)
            }
        }
    }
}
