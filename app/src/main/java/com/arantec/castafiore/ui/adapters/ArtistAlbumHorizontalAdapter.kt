package com.arantec.castafiore.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.arantec.castafiore.R
import com.arantec.castafiore.data.models.Album
import com.arantec.castafiore.data.repository.MusicRepository
import com.arantec.castafiore.databinding.ItemArtistAlbumHorizontalBinding
import com.arantec.castafiore.utils.ImageLoader

class ArtistAlbumHorizontalAdapter(
    private val onAlbumClick: (Album) -> Unit
) : RecyclerView.Adapter<ArtistAlbumHorizontalAdapter.ArtistAlbumViewHolder>() {

    private var albums = listOf<Album>()

    fun updateAlbums(newAlbums: List<Album>) {
        albums = newAlbums
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ArtistAlbumViewHolder {
        val binding = ItemArtistAlbumHorizontalBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ArtistAlbumViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ArtistAlbumViewHolder, position: Int) {
        holder.bind(albums[position])
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

                    // Usar Glide directamente ya que no existe loadImageWithGlide
                    com.bumptech.glide.Glide.with(context)
                        .load(coverUrl)
                        .placeholder(R.drawable.ic_album_placeholder)
                        .error(R.drawable.ic_album_placeholder)
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
