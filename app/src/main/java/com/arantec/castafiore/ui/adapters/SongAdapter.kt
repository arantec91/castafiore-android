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
import androidx.recyclerview.widget.AsyncListDiffer

class SongAdapter(
    private val onSongClick: (Song, Int) -> Unit,
    private val onSongMoreClick: (Song) -> Unit,
    private val showCover: Boolean = true,
    private val circularDownloadInIcon: Boolean = false
) : RecyclerView.Adapter<SongAdapter.SongViewHolder>() {

    // Enable stable IDs for better change animations and less rebind churn
    init { setHasStableIds(true) }

    private var playingSongId: String? = null
    private val downloadStates = mutableMapOf<String, com.arantec.castafiore.data.download.SongDownloadManager.DownloadState>()

    // Async differ to compute diffs off the main thread
    private val diffCallback = object : DiffUtil.ItemCallback<Song>() {
        override fun areItemsTheSame(oldItem: Song, newItem: Song): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Song, newItem: Song): Boolean =
            oldItem.title == newItem.title &&
            oldItem.artist == newItem.artist &&
            oldItem.duration == newItem.duration
    }

    private val differ = AsyncListDiffer(this, diffCallback)

    private val songs: List<Song>
        get() = differ.currentList

    fun updateSongs(newSongs: List<Song>) {
        // Always submit a new list instance to avoid identity short-circuiting
        differ.submitList(newSongs.toList())
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

                // Reset visibilities for download UI
                cpiDownload.visibility = View.GONE
                ivDownloaded.visibility = View.GONE
                containerIconDownload.visibility = View.GONE

                // Progreso de descarga (si aplica)
                val dState = downloadStates[song.id]
                if (dState != null) {
                    when (dState.status) {
                        com.arantec.castafiore.data.download.SongDownloadManager.DownloadStatus.PENDING -> {
                            if (circularDownloadInIcon) {
                                // En modo circular en icono, ocultar pendiente para que sólo RUNNING muestre spinner
                                containerDownload.visibility = View.GONE
                                containerIconDownload.visibility = View.GONE
                                cpiDownload.visibility = View.GONE
                            } else {
                                containerDownload.visibility = View.VISIBLE
                                progressDownload.isIndeterminate = true
                                progressDownload.progress = 0
                                tvDownloadStatus.text = root.context.getString(R.string.downloading_pending)
                            }
                        }
                        com.arantec.castafiore.data.download.SongDownloadManager.DownloadStatus.DOWNLOADING -> {
                            if (circularDownloadInIcon) {
                                containerDownload.visibility = View.GONE
                                containerIconDownload.visibility = View.VISIBLE
                                val p = dState.progress.coerceIn(0, 100)
                                when {
                                    p <= 0 -> {
                                        // No progreso aún: mantener animación indeterminada para evitar anillo "vacío"
                                        cpiDownload.visibility = View.VISIBLE
                                        cpiDownload.isIndeterminate = true
                                    }
                                    p in 1..99 -> {
                                        // Progreso real: cambiar a modo determinado
                                        cpiDownload.visibility = View.VISIBLE
                                        if (cpiDownload.isIndeterminate) cpiDownload.isIndeterminate = false
                                        try {
                                            cpiDownload.setProgressCompat(p, true)
                                        } catch (_: Exception) {
                                            cpiDownload.progress = p
                                        }
                                    }
                                    else -> {
                                        // p == 100: tratar como completado en el siguiente bloque
                                        cpiDownload.visibility = View.GONE
                                    }
                                }
                            } else {
                                containerDownload.visibility = View.VISIBLE
                                progressDownload.isIndeterminate = false
                                progressDownload.progress = dState.progress.coerceIn(0, 100)
                                tvDownloadStatus.text = root.context.getString(R.string.downloading_progress, dState.progress.coerceIn(0, 100))
                            }
                        }
                        com.arantec.castafiore.data.download.SongDownloadManager.DownloadStatus.COMPLETED -> {
                            containerDownload.visibility = View.GONE
                            cpiDownload.visibility = View.GONE
                            // containerIconDownload handled after we compute isDownloaded
                        }
                        com.arantec.castafiore.data.download.SongDownloadManager.DownloadStatus.FAILED -> {
                            if (circularDownloadInIcon) {
                                containerDownload.visibility = View.GONE
                                cpiDownload.visibility = View.GONE
                                containerIconDownload.visibility = View.GONE
                            } else {
                                containerDownload.visibility = View.VISIBLE
                                progressDownload.isIndeterminate = false
                                progressDownload.progress = 0
                                tvDownloadStatus.text = root.context.getString(R.string.downloading_failed)
                            }
                        }
                        com.arantec.castafiore.data.download.SongDownloadManager.DownloadStatus.CANCELLED -> {
                            containerDownload.visibility = View.GONE
                            cpiDownload.visibility = View.GONE
                            containerIconDownload.visibility = View.GONE
                        }
                    }
                } else {
                    containerDownload.visibility = View.GONE
                    cpiDownload.visibility = View.GONE
                    containerIconDownload.visibility = View.GONE
                }

                // Icono de descargado: visible cuando la canción está descargada
                try {
                    val dm = com.arantec.castafiore.data.download.SongDownloadManager.getInstance(root.context)
                    // Use fast check to avoid content resolver I/O on main thread
                    val isDownloaded = dm.isSongDownloadedFast(song.id)
                        || (dState?.status == com.arantec.castafiore.data.download.SongDownloadManager.DownloadStatus.COMPLETED)
                    val isSpinnerVisible = cpiDownload.visibility == View.VISIBLE
                    if (!isSpinnerVisible && isDownloaded) {
                        ivDownloaded.visibility = View.VISIBLE
                        containerIconDownload.visibility = View.VISIBLE
                    } else if (!isSpinnerVisible) {
                        // ensure no gap
                        ivDownloaded.visibility = View.GONE
                        containerIconDownload.visibility = View.GONE
                    }
                } catch (_: Exception) {
                    ivDownloaded.visibility = View.GONE
                    if (cpiDownload.visibility != View.VISIBLE) {
                        containerIconDownload.visibility = View.GONE
                    }
                }

                // Click handlers
                root.setOnClickListener {
                    onSongClick(song, bindingAdapterPosition)
                }

                btnSongMore.setOnClickListener {
                    onSongMoreClick(song)
                }
            }
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

    // Provide stable ID based on song id
    override fun getItemId(position: Int): Long {
        return songs.getOrNull(position)?.id?.hashCode()?.toLong() ?: RecyclerView.NO_ID
    }
}
