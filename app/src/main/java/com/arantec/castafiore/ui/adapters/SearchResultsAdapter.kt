package com.arantec.castafiore.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.arantec.castafiore.data.models.Album
import com.arantec.castafiore.data.models.Artist
import com.arantec.castafiore.data.models.Song
import com.arantec.castafiore.databinding.ItemSearchResultBinding
import com.arantec.castafiore.utils.ImageLoader
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.shape.RelativeCornerSize

class SearchResultsAdapter(
    private val onSongClick: (Song) -> Unit,
    private val onAlbumClick: (Album) -> Unit,
    private val onArtistClick: (Artist) -> Unit,
    private val onSongMoreClick: (Song) -> Unit = {}
) : ListAdapter<SearchResultsAdapter.Item, SearchResultsAdapter.SearchResultViewHolder>(Diff) {

    sealed class Item {
        data class SongItem(val song: Song) : Item()
        data class AlbumItem(val album: Album) : Item()
        data class ArtistItem(val artist: Artist) : Item()
    }

    private var serverUrl: String = ""
    private var username: String = ""
    private var token: String = ""
    private var salt: String = ""

    fun updateAuth(serverUrl: String, username: String, token: String, salt: String) {
        this.serverUrl = serverUrl
        this.username = username
        this.token = token
        this.salt = salt
        notifyDataSetChanged()
    }

    fun submitData(items: List<Item>) {
        submitList(items)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SearchResultViewHolder {
        val binding = ItemSearchResultBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return SearchResultViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SearchResultViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class SearchResultViewHolder(private val binding: ItemSearchResultBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: Item) {
            when (item) {
                is Item.SongItem -> bindSong(item.song)
                is Item.AlbumItem -> bindAlbum(item.album)
                is Item.ArtistItem -> bindArtist(item.artist)
            }
        }

        private fun bindSong(song: Song) {
            binding.tvTitle.text = song.title
            binding.tvType.text = "Canción"
            binding.tvType.isVisible = true
            binding.tvDuration.isVisible = true
            binding.tvDuration.text = song.getFormattedDuration()
            binding.tvSubtitle.text = listOfNotNull(song.artist, song.album).joinToString(" • ")
            binding.btnMore.isVisible = true

            // Restablecer a contenedor no circular por posible reciclado
            (binding.ivArtwork as? ShapeableImageView)?.let { iv ->
                iv.shapeAppearanceModel = iv.shapeAppearanceModel.toBuilder()
                    .setAllCornerSizes(0f)
                    .build()
            }

            val coverUrl = song.getCoverArtUrl(serverUrl, username, token, salt)
            ImageLoader.loadThumbnail(
                binding.root.context,
                binding.ivArtwork,
                coverUrl
            )

            binding.root.setOnClickListener { onSongClick(song) }
            binding.btnMore.setOnClickListener { onSongMoreClick(song) }
        }

        private fun bindAlbum(album: Album) {
            binding.tvTitle.text = album.name
            binding.tvType.text = "Álbum"
            binding.tvType.isVisible = true
            binding.tvDuration.isVisible = false
            binding.tvSubtitle.text = listOfNotNull(album.artist, album.year?.toString()).joinToString(" • ")
            binding.btnMore.isGone = true

            // Restablecer a contenedor no circular por posible reciclado
            (binding.ivArtwork as? ShapeableImageView)?.let { iv ->
                iv.shapeAppearanceModel = iv.shapeAppearanceModel.toBuilder()
                    .setAllCornerSizes(0f)
                    .build()
            }

            val coverUrl = album.getCoverArtUrl(serverUrl, username, token, salt)
            ImageLoader.loadThumbnail(
                binding.root.context,
                binding.ivArtwork,
                coverUrl
            )

            binding.root.setOnClickListener { onAlbumClick(album) }
        }

        private fun bindArtist(artist: Artist) {
            binding.tvTitle.text = artist.name
            binding.tvType.text = "Artista"
            binding.tvType.isVisible = true
            binding.tvDuration.isVisible = false
            val albumsText = artist.albumCount?.let { "$it álbum(es)" }
            binding.tvSubtitle.text = albumsText ?: ""
            binding.btnMore.isGone = true

            // Hacer el contenedor circular (50% del tamaño)
            (binding.ivArtwork as? ShapeableImageView)?.let { iv ->
                iv.shapeAppearanceModel = iv.shapeAppearanceModel.toBuilder()
                    .setAllCornerSizes(RelativeCornerSize(0.5f))
                    .build()
            }

            val artistImageUrl = ImageLoader.buildArtistImageUrl(serverUrl, artist.id, username, token, salt, 300)
            // Cargar como circular para distinguir artistas
            ImageLoader.loadArtistImage(
                binding.root.context,
                binding.ivArtwork,
                artistImageUrl
            )

            binding.root.setOnClickListener { onArtistClick(artist) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<Item>() {
        override fun areItemsTheSame(oldItem: Item, newItem: Item): Boolean {
            return when {
                oldItem is Item.SongItem && newItem is Item.SongItem -> oldItem.song.id == newItem.song.id
                oldItem is Item.AlbumItem && newItem is Item.AlbumItem -> oldItem.album.id == newItem.album.id
                oldItem is Item.ArtistItem && newItem is Item.ArtistItem -> oldItem.artist.id == newItem.artist.id
                else -> false
            }
        }

        override fun areContentsTheSame(oldItem: Item, newItem: Item): Boolean {
            return oldItem == newItem
        }
    }
}
