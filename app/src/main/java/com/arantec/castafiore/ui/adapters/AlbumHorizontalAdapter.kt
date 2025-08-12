package com.arantec.castafiore.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.arantec.castafiore.R
import com.arantec.castafiore.data.models.Album
import com.arantec.castafiore.data.repository.MusicRepository
import com.arantec.castafiore.databinding.ItemAlbumHorizontalBinding
import com.arantec.castafiore.utils.ImageLoader

class AlbumHorizontalAdapter(
    private val onAlbumClick: (Album) -> Unit
) : RecyclerView.Adapter<AlbumHorizontalAdapter.AlbumViewHolder>() {

    private var albums = listOf<Album>()

    fun updateAlbums(newAlbums: List<Album>) {
        albums = newAlbums
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AlbumViewHolder {
        val binding = ItemAlbumHorizontalBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return AlbumViewHolder(binding)
    }

    override fun onBindViewHolder(holder: AlbumViewHolder, position: Int) {
        val album = albums[position]
        holder.bind(album)
    }

    override fun getItemCount(): Int = albums.size

    inner class AlbumViewHolder(
        private val binding: ItemAlbumHorizontalBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(album: Album) {
            // Cancelar cualquier carga de imagen anterior para evitar conflictos
            com.bumptech.glide.Glide.with(binding.root.context).clear(binding.ivAlbumCover)

            // Establecer placeholder inmediatamente para limpiar la vista
            binding.ivAlbumCover.setImageResource(R.drawable.ic_album_placeholder)

            binding.tvAlbumName.text = album.name
            binding.tvArtistName.text = album.artist

            // Usar ImageLoader optimizado para thumbnails
            try {
                val musicRepo = MusicRepository.getInstance(binding.root.context)
                val (username, token, salt) = musicRepo.getAuthParams()

                if (album.coverArt != null && musicRepo.serverUrl != null) {
                    val coverUrl = ImageLoader.buildCoverArtUrl(
                        musicRepo.serverUrl!!,
                        album.coverArt!!,
                        username,
                        token,
                        salt,
                        200 // Tamaño optimizado para thumbnails
                    )

                    ImageLoader.loadThumbnail(binding.root.context, binding.ivAlbumCover, coverUrl)
                } else {
                    binding.ivAlbumCover.setImageResource(R.drawable.ic_album_placeholder)
                }
            } catch (e: Exception) {
                binding.ivAlbumCover.setImageResource(R.drawable.ic_album_placeholder)
            }

            binding.root.setOnClickListener {
                onAlbumClick(album)
            }
        }
    }
}
