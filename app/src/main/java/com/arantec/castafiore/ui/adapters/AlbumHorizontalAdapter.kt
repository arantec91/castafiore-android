package com.arantec.castafiore.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.arantec.castafiore.R
import com.arantec.castafiore.data.models.Album
import com.arantec.castafiore.data.repository.MusicRepository
import com.arantec.castafiore.databinding.ItemAlbumHorizontalBinding
import com.arantec.castafiore.utils.ImageLoader

class AlbumHorizontalAdapter(
    private val onAlbumClick: (Album) -> Unit
) : ListAdapter<Album, AlbumHorizontalAdapter.AlbumViewHolder>(DIFF) {

    init {
        setHasStableIds(true)
    }

    // Backwards-compatible helper so callers don't need to change
    fun updateAlbums(newAlbums: List<Album>) {
        // Never clear existing non-empty UI with an empty update
        if (newAlbums.isEmpty() && itemCount > 0) {
            android.util.Log.d("AlbumHorizontalAdapter", "Ignoring empty update; keeping ${'$'}itemCount existing items")
            return
        }
        submitList(newAlbums)
    }

    override fun getItemId(position: Int): Long {
        // Stable ID based on album id
        return getItem(position).id.hashCode().toLong()
    }

    override fun getItemViewType(position: Int): Int {
        return VIEW_TYPE_ALBUM
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AlbumViewHolder {
        val binding = ItemAlbumHorizontalBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return AlbumViewHolder(binding)
    }

    override fun onBindViewHolder(holder: AlbumViewHolder, position: Int) {
        // Add type safety check to prevent ClassCastException
        if (holder is AlbumViewHolder && position < itemCount) {
            holder.bind(getItem(position))
        }
    }

    inner class AlbumViewHolder(
        private val binding: ItemAlbumHorizontalBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(album: Album) {
            // Cancel previous image load to avoid incorrect images on fast scroll
            com.bumptech.glide.Glide.with(binding.root.context).clear(binding.ivAlbumCover)

            // Immediate placeholder to clear recycled state
            binding.ivAlbumCover.setImageResource(R.drawable.ic_album_placeholder)

            binding.tvAlbumName.text = album.name
            binding.tvArtistName.text = album.artist

            // Optimized thumbnail loading with local-first and offline-safe behavior
            try {
                // 1) Try local cover first (if songs from this album were downloaded)
                val dm = com.arantec.castafiore.data.download.SongDownloadManager.getInstance(binding.root.context)
                val localPath = dm.createAlbumCoverPath(album.artist, album.name)
                val localFile = java.io.File(localPath)
                if (localFile.exists()) {
                    ImageLoader.loadLocalThumbnail(binding.root.context, binding.ivAlbumCover, localPath)
                } else {
                    // 2) Fallback to server URL (will use cache-only when offline)
                    val musicRepo = MusicRepository.getInstance(binding.root.context)
                    val (username, token, salt) = musicRepo.getAuthParams()

                    if (album.coverArt != null && musicRepo.serverUrl != null) {
                        val coverUrl = ImageLoader.buildCoverArtUrl(
                            musicRepo.serverUrl!!,
                            album.coverArt,
                            username,
                            token,
                            salt,
                            200 // thumbnail size
                        )

                        ImageLoader.loadThumbnail(binding.root.context, binding.ivAlbumCover, coverUrl)
                    } else {
                        binding.ivAlbumCover.setImageResource(R.drawable.ic_album_placeholder)
                    }
                }
            } catch (_: Exception) {
                binding.ivAlbumCover.setImageResource(R.drawable.ic_album_placeholder)
            }

            binding.root.setOnClickListener {
                onAlbumClick(album)
            }
        }
    }

    companion object {
        private const val VIEW_TYPE_ALBUM = 1002 // Unique view type for album items

        private val DIFF = object : DiffUtil.ItemCallback<Album>() {
            override fun areItemsTheSame(oldItem: Album, newItem: Album): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: Album, newItem: Album): Boolean =
                oldItem == newItem
        }
    }
}
