package com.arantec.castafiore.ui.adapters

import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.arantec.castafiore.R
import com.arantec.castafiore.data.models.Song
import com.arantec.castafiore.data.repository.MusicRepository
import com.arantec.castafiore.databinding.ItemQueueSongBinding
import com.bumptech.glide.Glide
import java.util.Collections

interface ItemTouchHelperAdapter {
    fun onItemMove(fromPosition: Int, toPosition: Int): Boolean
    fun onItemDismiss(position: Int)
}

class QueueAdapter(
    private val musicRepository: MusicRepository,
    private val onSongClick: (Song, Int) -> Unit,
    private val onRemoveSong: (Song, Int) -> Unit,
    private val onMoveSong: (Int, Int) -> Unit, // Nuevo callback para movimientos
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit
) : RecyclerView.Adapter<QueueAdapter.QueueViewHolder>(), ItemTouchHelperAdapter {

    private var songs = mutableListOf<Song>()

    inner class QueueViewHolder(private val binding: ItemQueueSongBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(song: Song, position: Int) {
            binding.tvSongTitle.text = song.title
            binding.tvArtist.text = song.artist

            // Cargar album art
            loadAlbumArt(song)

            // Click en la canción
            binding.root.setOnClickListener {
                onSongClick(song, position)
            }

            // Configurar drag handle
            binding.ivDragHandle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    onStartDrag(this)
                }
                false
            }
        }

        private fun loadAlbumArt(song: Song) {
            try {
                val (username, token, salt) = musicRepository.getAuthParams()
                val coverUrl = if (song.albumId != null) {
                    "${musicRepository.serverUrl}/rest/getCoverArt.view?id=${song.albumId}&u=$username&t=$token&s=$salt&v=1.16.1&c=Castafiore&size=200"
                } else {
                    null
                }

                if (coverUrl != null) {
                    Glide.with(binding.ivAlbumArt.context)
                        .load(coverUrl)
                        .placeholder(R.drawable.ic_album_placeholder)
                        .error(R.drawable.ic_album_placeholder)
                        .into(binding.ivAlbumArt)
                } else {
                    binding.ivAlbumArt.setImageResource(R.drawable.ic_album_placeholder)
                }
            } catch (_: Exception) {
                binding.ivAlbumArt.setImageResource(R.drawable.ic_album_placeholder)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QueueViewHolder {
        val binding = ItemQueueSongBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return QueueViewHolder(binding)
    }

    override fun onBindViewHolder(holder: QueueViewHolder, position: Int) {
        holder.bind(songs[position], position)
    }

    override fun getItemCount(): Int = songs.size

    fun updateSongs(newSongs: List<Song>) {
        songs.clear()
        songs.addAll(newSongs)
        notifyDataSetChanged()
    }

    fun removeSong(position: Int) {
        if (position in 0 until songs.size) {
            songs.removeAt(position)
            notifyItemRemoved(position)
        }
    }

    // ItemTouchHelper methods
    override fun onItemMove(fromPosition: Int, toPosition: Int): Boolean {
        if (fromPosition < toPosition) {
            for (i in fromPosition until toPosition) {
                Collections.swap(songs, i, i + 1)
            }
        } else {
            for (i in fromPosition downTo toPosition + 1) {
                Collections.swap(songs, i, i - 1)
            }
        }
        notifyItemMoved(fromPosition, toPosition)
        // Notificar el movimiento a través del nuevo callback
        onMoveSong(fromPosition, toPosition)
        return true
    }

    override fun onItemDismiss(position: Int) {
        if (position in 0 until songs.size) {
            val song = songs[position]
            songs.removeAt(position)
            notifyItemRemoved(position)
            // Notificar al callback para sincronizar con el servicio
            onRemoveSong(song, position)
        }
    }
}
