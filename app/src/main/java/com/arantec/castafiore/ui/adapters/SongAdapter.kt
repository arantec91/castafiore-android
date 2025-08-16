package com.arantec.castafiore.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.arantec.castafiore.data.models.Song
import com.arantec.castafiore.databinding.ItemSongBinding

class SongAdapter(
    private val onSongClick: (Song, Int) -> Unit,
    private val onSongMoreClick: (Song) -> Unit
) : RecyclerView.Adapter<SongAdapter.SongViewHolder>() {

    private var songs = mutableListOf<Song>()
    private var playingSongId: String? = null

    fun updateSongs(newSongs: List<Song>) {
        val diffCallback = SongDiffCallback(songs, newSongs)
        val diffResult = DiffUtil.calculateDiff(diffCallback)

        songs.clear()
        songs.addAll(newSongs)
        diffResult.dispatchUpdatesTo(this)
    }

    fun setPlayingSong(songId: String?) {
        playingSongId = songId
        notifyDataSetChanged()
    }

    inner class SongViewHolder(
        private val binding: ItemSongBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(song: Song, position: Int) {
            binding.apply {
                // Ocultar el número de track
                tvTrackNumber.visibility = android.view.View.GONE

                tvSongTitle.text = song.title
                tvSongArtist.text = song.artist
                // tvDuration removed from layout; no longer set

                // Cambiar solo el color del título si es la canción actual
                if (song.id == playingSongId) {
                    tvSongTitle.setTextColor(android.graphics.Color.parseColor("#FF2D55")) // Color principal
                } else {
                    tvSongTitle.setTextColor(android.graphics.Color.WHITE)
                }

                // Click en la canción
                root.setOnClickListener {
                    onSongClick(song, position)
                }

                // Click en el botón más opciones
                btnSongMore.setOnClickListener {
                    onSongMoreClick(song)
                }
            }
        }
    }

    private class SongDiffCallback(
        private val oldList: List<Song>,
        private val newList: List<Song>
    ) : DiffUtil.Callback() {

        override fun getOldListSize(): Int = oldList.size

        override fun getNewListSize(): Int = newList.size

        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            return oldList[oldItemPosition].id == newList[newItemPosition].id
        }

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            val oldSong = oldList[oldItemPosition]
            val newSong = newList[newItemPosition]
            return oldSong.title == newSong.title &&
                   oldSong.artist == newSong.artist &&
                   oldSong.duration == newSong.duration
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SongViewHolder {
        val binding = ItemSongBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return SongViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SongViewHolder, position: Int) {
        holder.bind(songs[position], position)
    }

    override fun getItemCount(): Int = songs.size
}
