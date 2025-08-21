package com.arantec.castafiore.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.arantec.castafiore.data.models.Song
import com.arantec.castafiore.databinding.ItemSongBinding
import com.arantec.castafiore.data.repository.MusicRepository
import com.arantec.castafiore.utils.ImageLoader
import com.arantec.castafiore.R
import com.arantec.castafiore.data.download.SongDownloadManager
import android.view.View
import java.io.File

class SongAdapter(
    private val onSongClick: (Song, Int) -> Unit,
    private val onSongMoreClick: (Song) -> Unit,
    private val showCover: Boolean = true
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
        if (playingSongId == songId) return
        val oldId = playingSongId
        playingSongId = songId
        val oldIndex = oldId?.let { id -> songs.indexOfFirst { it.id == id } } ?: -1
        val newIndex = songId?.let { id -> songs.indexOfFirst { it.id == id } } ?: -1
        if (oldIndex >= 0) notifyItemChanged(oldIndex)
        if (newIndex >= 0 && newIndex != oldIndex) notifyItemChanged(newIndex)
        if (oldIndex < 0 && newIndex < 0) notifyDataSetChanged() // fallback if we can't find items
    }

    inner class SongViewHolder(
        private val binding: ItemSongBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(song: Song) {
            binding.apply {

                // Mostrar/ocultar portada según configuración
                if (!showCover) {
                    ivSongCover.visibility = View.GONE
                } else {
                    ivSongCover.visibility = View.VISIBLE
                    // Cargar portada de la canción (thumbnail)
                    try {
                        // Placeholder inmediato mientras se resuelve la URL
                        ivSongCover.setImageResource(R.drawable.ic_music_note)

                        val repo = MusicRepository.getInstance(root.context)
                        val server = repo.serverUrl
                        val coverId = song.coverArt
                        if (!server.isNullOrEmpty() && !coverId.isNullOrEmpty()) {
                            val (username, token, salt) = repo.getAuthParams()
                            val coverUrl = ImageLoader.buildCoverArtUrl(
                                server,
                                coverId,
                                username,
                                token,
                                salt,
                                200 // tamaño optimizado para lista
                            )
                            ImageLoader.loadThumbnail(root.context, ivSongCover, coverUrl)
                        } else {
                            ivSongCover.setImageResource(R.drawable.ic_music_note)
                        }
                    } catch (_: Exception) {
                        ivSongCover.setImageResource(R.drawable.ic_music_note)
                    }
                }

                tvSongTitle.text = song.title
                tvSongArtist.text = song.artist

                // Indicador de descarga
                runCatching {
                    val dm = SongDownloadManager.getInstance(root.context)
                    val path = dm.createDownloadPath(song)
                    if (File(path).exists()) {
                        ivDownloadStatus.visibility = View.VISIBLE
                    } else {
                        ivDownloadStatus.visibility = View.GONE
                    }
                }

                // Cambiar solo el color del título si es la canción actual
                if (song.id == playingSongId) {
                    tvSongTitle.setTextColor(android.graphics.Color.parseColor("#FF2D55")) // Color principal
                } else {
                    tvSongTitle.setTextColor(android.graphics.Color.WHITE)
                }

                // Click en la canción
                root.setOnClickListener {
                    val pos = bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        onSongClick(song, pos)
                    }
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
        holder.bind(songs[position])
    }

    override fun getItemCount(): Int = songs.size
}
