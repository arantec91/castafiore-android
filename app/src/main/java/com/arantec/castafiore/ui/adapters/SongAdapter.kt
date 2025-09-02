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
import android.view.View
import java.io.File

class SongAdapter(
    private val onSongClick: (Song, Int) -> Unit,
    private val onSongMoreClick: (Song) -> Unit,
    private val showCover: Boolean = true
) : RecyclerView.Adapter<SongAdapter.SongViewHolder>() {

    private var songs = mutableListOf<Song>()
    private var playingSongId: String? = null
    private val downloadStates = mutableMapOf<String, com.arantec.castafiore.data.download.SongDownloadManager.DownloadState>()

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

    fun updateDownloadState(state: com.arantec.castafiore.data.download.SongDownloadManager.DownloadState) {
        downloadStates[state.songId] = state
        val idx = songs.indexOfFirst { it.id == state.songId }
        if (idx >= 0) notifyItemChanged(idx)
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
                    // Cargar portada de la canción (thumbnail) con preferencia por local
                    try {
                        // Placeholder inmediato
                        ivSongCover.setImageResource(R.drawable.ic_music_note)

                        // 1) Local-first: si existe una portada descargada para el álbum
                        val dm = com.arantec.castafiore.data.download.SongDownloadManager.getInstance(root.context)
                        val localPath = try { dm.createCoverPath(song) } catch (_: Exception) { null }
                        if (!localPath.isNullOrEmpty() && File(localPath).exists()) {
                            ImageLoader.loadLocalThumbnail(root.context, ivSongCover, localPath)
                        } else {
                            // 2) Fallback a URL remota; ImageLoader manejará modo offline usando solo caché
                            val repo = MusicRepository.getInstance(root.context)
                            val server = repo.serverUrl
                            val coverId = song.coverArt ?: song.albumId
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
                        }
                    } catch (_: Exception) {
                        ivSongCover.setImageResource(R.drawable.ic_music_note)
                    }
                }

                tvSongTitle.text = song.title
                tvSongArtist.text = song.artist

                // Highlight de canción reproduciéndose actualmente
                val isPlaying = song.id == playingSongId
                if (isPlaying) {
                    tvSongTitle.setTextColor(root.context.getColor(R.color.primary))
                    tvSongArtist.setTextColor(root.context.getColor(R.color.primary))
                } else {
                    tvSongTitle.setTextColor(root.context.getColor(R.color.text_primary))
                    tvSongArtist.setTextColor(root.context.getColor(R.color.text_secondary))
                }

                // Progreso de descarga (si aplica)
                val dState = downloadStates[song.id]
                if (dState != null) {
                    when (dState.status) {
                        com.arantec.castafiore.data.download.SongDownloadManager.DownloadStatus.PENDING -> {
                            containerDownload.visibility = View.VISIBLE
                            progressDownload.isIndeterminate = true
                            progressDownload.progress = 0
                            tvDownloadStatus.text = root.context.getString(R.string.downloading_pending)
                        }
                        com.arantec.castafiore.data.download.SongDownloadManager.DownloadStatus.DOWNLOADING -> {
                            containerDownload.visibility = View.VISIBLE
                            progressDownload.isIndeterminate = false
                            progressDownload.progress = dState.progress.coerceIn(0, 100)
                            tvDownloadStatus.text = root.context.getString(R.string.downloading_progress, dState.progress.coerceIn(0, 100))
                        }
                        com.arantec.castafiore.data.download.SongDownloadManager.DownloadStatus.COMPLETED -> {
                            containerDownload.visibility = View.GONE
                        }
                        com.arantec.castafiore.data.download.SongDownloadManager.DownloadStatus.FAILED -> {
                            containerDownload.visibility = View.VISIBLE
                            progressDownload.isIndeterminate = false
                            progressDownload.progress = 0
                            tvDownloadStatus.text = root.context.getString(R.string.downloading_failed)
                        }
                        com.arantec.castafiore.data.download.SongDownloadManager.DownloadStatus.CANCELLED -> {
                            containerDownload.visibility = View.GONE
                        }
                    }
                } else {
                    containerDownload.visibility = View.GONE
                }

                // Icono de descargado: visible cuando la canción está descargada
                try {
                    val dm = com.arantec.castafiore.data.download.SongDownloadManager.getInstance(root.context)
                    val isDownloaded = dm.isSongDownloaded(song.id)
                        || (dState?.status == com.arantec.castafiore.data.download.SongDownloadManager.DownloadStatus.COMPLETED)
                    ivDownloaded.visibility = if (isDownloaded) View.VISIBLE else View.GONE
                } catch (_: Exception) {
                    ivDownloaded.visibility = View.GONE
                }

                // Click handlers
                root.setOnClickListener {
                    onSongClick(song, adapterPosition)
                }

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
