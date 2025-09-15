package com.arantec.castafiore.ui.adapters

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.arantec.castafiore.R
import com.arantec.castafiore.data.models.Album
import com.arantec.castafiore.data.models.Artist
import com.arantec.castafiore.data.models.Song
import com.arantec.castafiore.databinding.ItemSearchResultBinding
import com.arantec.castafiore.utils.ImageLoader
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.shape.RelativeCornerSize
import com.google.android.material.card.MaterialCardView

class SearchResultsAdapter(
    private val onSongClick: (Song) -> Unit,
    private val onAlbumClick: (Album) -> Unit,
    private val onArtistClick: (Artist) -> Unit,
    private val onSongMoreClick: (Song) -> Unit = {},
    private val onSongFavoriteClick: (Song) -> Unit = {},
    private val onAlbumFavoriteClick: (Album) -> Unit = {},
    private val onArtistFavoriteClick: (Artist) -> Unit = {},
    // New callbacks for best-artist quick actions
    private val onBestArtistTopSongsClick: (Artist) -> Unit = {},
    private val onBestArtistRadioClick: (Artist) -> Unit = {}
) : ListAdapter<SearchResultsAdapter.Item, RecyclerView.ViewHolder>(Diff) {

    enum class Section { BEST, SONGS, ARTISTS, ALBUMS }

    sealed class Item {
        data class HeaderItem(val section: Section, val title: String) : Item()
        data class SongItem(val song: Song, val isBest: Boolean = false) : Item()
        data class AlbumItem(val album: Album, val isBest: Boolean = false) : Item()
        data class ArtistItem(val artist: Artist, val isBest: Boolean = false) : Item()
    }

    private var serverUrl: String = ""
    private var username: String = ""
    private var token: String = ""
    private var salt: String = ""

    // Favorite state sets provided by the Fragment
    private var favoriteSongIds: Set<String> = emptySet()
    private var favoriteAlbumIds: Set<String> = emptySet()
    private var favoriteArtistIds: Set<String> = emptySet()

    fun updateAuth(serverUrl: String, username: String, token: String, salt: String) {
        this.serverUrl = serverUrl
        this.username = username
        this.token = token
        this.salt = salt
        notifyDataSetChanged()
    }

    fun updateFavorites(songIds: Set<String>, albumIds: Set<String>, artistIds: Set<String>) {
        favoriteSongIds = songIds
        favoriteAlbumIds = albumIds
        favoriteArtistIds = artistIds
        notifyDataSetChanged()
    }

    fun submitData(items: List<Item>) {
        submitList(items)
    }

    private companion object {
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_ITEM = 1
    }

    // Callback para clic en headers de sección
    private var onHeaderClickListener: ((Section) -> Unit)? = null
    fun setOnHeaderClickListener(listener: (Section) -> Unit) {
        onHeaderClickListener = listener
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is Item.HeaderItem -> VIEW_TYPE_HEADER
            else -> VIEW_TYPE_ITEM
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == VIEW_TYPE_HEADER) {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_section_header, parent, false)
            HeaderViewHolder(view)
        } else {
            val binding = ItemSearchResultBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            SearchResultViewHolder(binding)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is Item.HeaderItem -> (holder as HeaderViewHolder).bind(item)
            is Item.SongItem -> (holder as SearchResultViewHolder).bindSong(item.song, item.isBest)
            is Item.AlbumItem -> (holder as SearchResultViewHolder).bindAlbum(item.album, item.isBest)
            is Item.ArtistItem -> (holder as SearchResultViewHolder).bindArtist(item.artist, item.isBest)
        }
    }

    inner class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvTitle: TextView = view.findViewById(R.id.tvSectionTitle)
        private val headerRow: View = view.findViewById(R.id.headerRow)
        private val ivChevron: View = view.findViewById(R.id.ivChevron)
        private val originalBackground = headerRow.background
        fun bind(item: Item.HeaderItem) {
            tvTitle.text = item.title
            // Ocultar ripple/click y chevron para la sección BEST (Mejor Resultado)
            if (item.section == Section.BEST) {
                ivChevron.isGone = true
                headerRow.isClickable = false
                headerRow.isFocusable = false
                headerRow.background = null
                headerRow.setOnClickListener(null)
            } else {
                ivChevron.isVisible = true
                headerRow.isClickable = true
                headerRow.isFocusable = true
                headerRow.background = originalBackground
                // Propagar clic del header a un callback según sección
                headerRow.setOnClickListener {
                    when (item.section) {
                        Section.SONGS -> onHeaderClickListener?.invoke(Section.SONGS)
                        Section.ARTISTS -> onHeaderClickListener?.invoke(Section.ARTISTS)
                        Section.ALBUMS -> onHeaderClickListener?.invoke(Section.ALBUMS)
                        Section.BEST -> onHeaderClickListener?.invoke(Section.BEST)
                    }
                }
            }
        }
    }

    inner class SearchResultViewHolder(private val binding: ItemSearchResultBinding) : RecyclerView.ViewHolder(binding.root) {
        private fun styleBest(isBest: Boolean) {
            val card = binding.root as MaterialCardView
            val ctx = card.context
            val surfaceVariant = ContextCompat.getColor(ctx, R.color.surface_variant)
            val transparent = ContextCompat.getColor(ctx, android.R.color.transparent)
            fun dp(value: Int): Float = value * card.resources.displayMetrics.density
            if (isBest) {
                card.strokeWidth = 0
                card.cardElevation = dp(2)
                card.setCardBackgroundColor(surfaceVariant)
                card.radius = dp(12)
            } else {
                card.strokeWidth = 0
                card.cardElevation = 0f
                card.setCardBackgroundColor(transparent)
                card.radius = 0f
            }
        }

        private fun setContainerPadding(isBest: Boolean) {
            val context = binding.clickableContainer.context
            val dp12 = (12 * context.resources.displayMetrics.density).toInt()
            if (isBest) {
                // Best item: uniform 12dp padding
                binding.clickableContainer.setPadding(dp12, dp12, dp12, dp12)
            } else {
                // Non-best item: 12dp only at the bottom
                binding.clickableContainer.setPadding(0, 0, 0, dp12)
            }
        }

        fun bindSong(song: Song, isBest: Boolean) {
            styleBest(isBest)
            setContainerPadding(isBest)
            // Ensure artist-only actions are hidden for songs
            binding.actionsRow.isGone = true
            binding.btnTopSongs.setOnClickListener(null)
            binding.btnRadio.setOnClickListener(null)

            binding.tvTitle.text = song.title
            binding.tvSubtitle.text = listOfNotNull(song.artist, song.album).joinToString(" • ")
            binding.btnMore.isVisible = true
            binding.btnFavorite.isVisible = true
            val isFav = favoriteSongIds.contains(song.id)
            binding.btnFavorite.setImageResource(if (isFav) R.drawable.ic_favorite else R.drawable.ic_favorite_border)
            // Tint: primary when favorite, secondary otherwise
            run {
                val ctx = binding.root.context
                val color = if (isFav) ContextCompat.getColor(ctx, R.color.primary) else ContextCompat.getColor(ctx, R.color.text_secondary)
                binding.btnFavorite.imageTintList = ColorStateList.valueOf(color)
            }

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

            binding.clickableContainer.setOnClickListener { onSongClick(song) }
            binding.btnMore.setOnClickListener { onSongMoreClick(song) }
            binding.btnFavorite.setOnClickListener { onSongFavoriteClick(song) }
        }

        fun bindAlbum(album: Album, isBest: Boolean) {
            styleBest(isBest)
            setContainerPadding(isBest)
            // Ensure artist-only actions are hidden for albums
            binding.actionsRow.isGone = true
            binding.btnTopSongs.setOnClickListener(null)
            binding.btnRadio.setOnClickListener(null)

            binding.tvTitle.text = album.name
            binding.tvSubtitle.text = listOfNotNull(album.artist, album.year?.toString()).joinToString(" • ")
            binding.btnMore.isGone = true
            binding.btnFavorite.isVisible = true
            val isFav = favoriteAlbumIds.contains(album.id)
            binding.btnFavorite.setImageResource(if (isFav) R.drawable.ic_favorite else R.drawable.ic_favorite_border)
            // Tint: primary when favorite, secondary otherwise
            run {
                val ctx = binding.root.context
                val color = if (isFav) ContextCompat.getColor(ctx, R.color.primary) else ContextCompat.getColor(ctx, R.color.text_secondary)
                binding.btnFavorite.imageTintList = ColorStateList.valueOf(color)
            }

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

            binding.clickableContainer.setOnClickListener { onAlbumClick(album) }
            binding.btnFavorite.setOnClickListener { onAlbumFavoriteClick(album) }
        }

        fun bindArtist(artist: Artist, isBest: Boolean) {
            styleBest(isBest)
            setContainerPadding(isBest)
            binding.tvTitle.text = artist.name
            val albumsText = artist.albumCount?.let { "$it álbum(es)" }
            binding.tvSubtitle.text = albumsText ?: ""
            binding.btnMore.isGone = true
            binding.btnFavorite.isVisible = true
            val isFav = favoriteArtistIds.contains(artist.id)
            binding.btnFavorite.setImageResource(if (isFav) R.drawable.ic_favorite else R.drawable.ic_favorite_border)
            // Tint: primary when favorite, secondary otherwise
            run {
                val ctx = binding.root.context
                val color = if (isFav) ContextCompat.getColor(ctx, R.color.primary) else ContextCompat.getColor(ctx, R.color.text_secondary)
                binding.btnFavorite.imageTintList = ColorStateList.valueOf(color)
            }

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

            // Show quick actions only if this is the best artist item
            if (isBest) {
                binding.actionsRow.isVisible = true
                binding.btnTopSongs.setOnClickListener { onBestArtistTopSongsClick(artist) }
                binding.btnRadio.setOnClickListener { onBestArtistRadioClick(artist) }
            } else {
                binding.actionsRow.isGone = true
                binding.btnTopSongs.setOnClickListener(null)
                binding.btnRadio.setOnClickListener(null)
            }

            binding.clickableContainer.setOnClickListener { onArtistClick(artist) }
            binding.btnFavorite.setOnClickListener { onArtistFavoriteClick(artist) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<Item>() {
        override fun areItemsTheSame(oldItem: Item, newItem: Item): Boolean {
            return when {
                oldItem is Item.HeaderItem && newItem is Item.HeaderItem -> oldItem.section == newItem.section && oldItem.title == newItem.title
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
