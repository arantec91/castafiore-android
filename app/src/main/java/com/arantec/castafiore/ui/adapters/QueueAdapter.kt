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
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.arantec.castafiore.utils.ImageLoader
import com.arantec.castafiore.utils.NetworkUtils
import com.arantec.castafiore.data.download.SongDownloadManager
import com.bumptech.glide.request.RequestOptions
import java.io.File
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
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit,
    private val onSongLongPress: (Song, Int) -> Unit
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

            // Long click para opciones de canción
            binding.root.setOnLongClickListener {
                onSongLongPress(song, position)
                true
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
                val context = binding.ivAlbumArt.context
                val (username, token, salt) = musicRepository.getAuthParams()
                val server = musicRepository.serverUrl

                // Construir URL remota si es posible
                val coverId = song.coverArt ?: song.albumId
                val coverUrl = if (!server.isNullOrEmpty() && !coverId.isNullOrEmpty()) {
                    ImageLoader.buildCoverArtUrl(
                        server,
                        coverId,
                        username,
                        token,
                        salt,
                        200
                    )
                } else null

                // 1) Preferir portada local descargada si existe
                val dm = SongDownloadManager.getInstance(context)
                val localCoverFile = File(dm.createCoverPath(song))
                if (localCoverFile.exists()) {
                    Glide.with(context)
                        .load(localCoverFile)
                        .placeholder(R.drawable.ic_album_placeholder)
                        .error(R.drawable.ic_album_placeholder)
                        .transition(DrawableTransitionOptions.withCrossFade(200))
                        .into(binding.ivAlbumArt)
                    return
                }

                // 2) Si no hay red, intentar cargar solo desde caché de Glide
                if (!NetworkUtils.isNetworkAvailable(context) && coverUrl != null) {
                    Glide.with(context)
                        .load(coverUrl)
                        .apply(RequestOptions().onlyRetrieveFromCache(true))
                        .placeholder(R.drawable.ic_album_placeholder)
                        .error(R.drawable.ic_album_placeholder)
                        .transition(DrawableTransitionOptions.withCrossFade(200))
                        .into(binding.ivAlbumArt)
                    return
                }

                // 3) Carga normal desde URL
                if (coverUrl != null) {
                    Glide.with(binding.ivAlbumArt.context)
                        .load(coverUrl)
                        .placeholder(R.drawable.ic_album_placeholder)
                        .error(R.drawable.ic_album_placeholder)
                        .transition(DrawableTransitionOptions.withCrossFade(200))
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
