package com.arantec.castafiore.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.arantec.castafiore.databinding.ItemLibraryBinding
import com.arantec.castafiore.data.models.LibraryItem
import com.arantec.castafiore.data.models.LibraryItemType
import com.arantec.castafiore.utils.ImageLoader
import com.arantec.castafiore.R

class LibraryAdapter(
    private val onItemClick: (LibraryItem) -> Unit
) : RecyclerView.Adapter<LibraryAdapter.LibraryViewHolder>() {

    private var items = mutableListOf<LibraryItem>()

    fun updateItems(newItems: List<LibraryItem>) {
        val diffCallback = LibraryDiffCallback(items, newItems)
        val diffResult = DiffUtil.calculateDiff(diffCallback)

        items.clear()
        items.addAll(newItems)
        diffResult.dispatchUpdatesTo(this)
    }

    inner class LibraryViewHolder(
        private val binding: ItemLibraryBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: LibraryItem) {
            binding.apply {
                tvTitle.text = item.title
                tvSubtitle.text = item.subtitle

                // Reset reusable state that may linger from recycled views
                ivCover.background = null

                // Configurar icono/imagen según el tipo
                when (item.type) {
                    LibraryItemType.LIKED_SONGS -> {
                        ivCover.setImageResource(R.drawable.ic_favorite_filled)
                        ivCover.setBackgroundResource(R.drawable.liked_songs_background)
                    }
                    LibraryItemType.PLAYLIST -> {
                        if (item.imageUrl != null) {
                            ImageLoader.loadThumbnail(itemView.context, ivCover, item.imageUrl)
                            // Ensure no stale background when using real cover
                            ivCover.background = null
                        } else {
                            ivCover.setImageResource(R.drawable.ic_playlist)
                            ivCover.setBackgroundResource(R.drawable.playlist_background)
                        }
                    }
                    LibraryItemType.ARTIST -> {
                        if (item.imageUrl != null) {
                            // Make artist images circular
                            ImageLoader.loadArtistImage(itemView.context, ivCover, item.imageUrl)
                            // No extra background needed; circular crop handles shape
                            ivCover.background = null
                        } else {
                            ivCover.setImageResource(R.drawable.ic_person)
                            ivCover.setBackgroundResource(R.drawable.circle_background)
                        }
                    }
                    LibraryItemType.ALBUM -> {
                        if (item.imageUrl != null) {
                            ImageLoader.loadThumbnail(itemView.context, ivCover, item.imageUrl)
                            ivCover.background = null
                        } else {
                            ivCover.setImageResource(R.drawable.ic_album_placeholder)
                            ivCover.background = null
                        }
                    }
                }

                // Click listener
                root.setOnClickListener {
                    onItemClick(item)
                }
            }
        }
    }

    private class LibraryDiffCallback(
        private val oldList: List<LibraryItem>,
        private val newList: List<LibraryItem>
    ) : DiffUtil.Callback() {

        override fun getOldListSize(): Int = oldList.size

        override fun getNewListSize(): Int = newList.size

        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            return oldList[oldItemPosition].id == newList[newItemPosition].id
        }

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            val oldItem = oldList[oldItemPosition]
            val newItem = newList[newItemPosition]
            return oldItem.title == newItem.title &&
                   oldItem.subtitle == newItem.subtitle &&
                   oldItem.imageUrl == newItem.imageUrl &&
                   oldItem.type == newItem.type
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LibraryViewHolder {
        val binding = ItemLibraryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return LibraryViewHolder(binding)
    }

    override fun onBindViewHolder(holder: LibraryViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size
}
