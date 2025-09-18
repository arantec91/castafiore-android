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

    // Disable stable IDs to avoid RecyclerView conflicts when external updates/animations overlap
    // DiffUtil already uses Song.id for identity, which is sufficient for animations
    init { setHasStableIds(false) }

    companion object {
        private const val PAYLOAD_DOWNLOAD = "PAYLOAD_DOWNLOAD"
    }

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
        // Also, enforce unique items by id to satisfy stable ID requirements
        val unique = newSongs.distinctBy { it.id }
        differ.submitList(unique)
    }

    fun setPlayingSong(songId: String?) {
        if (playingSongId == songId) return
        val oldId = playingSongId
        playingSongId = songId
        val oldIndex = oldId?.let { id -> songs.indexOfFirst { it.id == id } } ?: -1
        val newIndex = songId?.let { id -> songs.indexOfFirst { it.id == id } } ?: -1
        if (oldIndex >= 0) notifyItemChanged(oldIndex)
        if (newIndex >= 0 && newIndex != oldIndex) notifyItemChanged(newIndex)
        // Avoid notifyDataSetChanged() with stable IDs to prevent animation conflicts
    }

    fun updateDownloadState(state: com.arantec.castafiore.data.download.SongDownloadManager.DownloadState) {
        downloadStates[state.songId] = state
        val idx = songs.indexOfFirst { it.id == state.songId }
        if (idx >= 0) notifyItemChanged(idx, PAYLOAD_DOWNLOAD)
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
                    // Cargar portada sólo en bind completo, nunca en payloads
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
                    tvSongArtist.setTextColor(root.context.getColor(R.color.text_secondary))
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
                applyDownloadUi(song, dState)

                // Click handlers
                root.setOnClickListener {
                    onSongClick(song, bindingAdapterPosition)
                }

                btnSongMore.setOnClickListener {
                    onSongMoreClick(song)
                }
            }
        }

        // Partial update handler for download status changes only
        fun partialUpdateDownloadUi(song: Song, dState: com.arantec.castafiore.data.download.SongDownloadManager.DownloadState?) {
            binding.apply {
                applyDownloadUi(song, dState)
            }
        }

        private fun applyDownloadUi(song: Song, dState: com.arantec.castafiore.data.download.SongDownloadManager.DownloadState?) {
            binding.apply {
                // Reset visibilities minimally; do not touch title/artist/cover here
                when (dState?.status) {
                    com.arantec.castafiore.data.download.SongDownloadManager.DownloadStatus.PENDING -> {
                        if (circularDownloadInIcon) {
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
                                    cpiDownload.visibility = View.VISIBLE
                                    cpiDownload.isIndeterminate = true
                                }
                                p in 1..99 -> {
                                    cpiDownload.visibility = View.VISIBLE
                                    if (cpiDownload.isIndeterminate) cpiDownload.isIndeterminate = false
                                    try { cpiDownload.setProgressCompat(p, true) } catch (_: Exception) { cpiDownload.progress = p }
                                }
                                else -> {
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
                        // handled below with isDownloaded check
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
                    else -> {
                        containerDownload.visibility = View.GONE
                        cpiDownload.visibility = View.GONE
                        containerIconDownload.visibility = View.GONE
                    }
                }

                // Downloaded icon handling (avoid toggling while spinner visible)
                try {
                    val dm = com.arantec.castafiore.data.download.SongDownloadManager.getInstance(root.context)
                    val isDownloaded = dm.isSongDownloadedFast(song.id)
                        || (dState?.status == com.arantec.castafiore.data.download.SongDownloadManager.DownloadStatus.COMPLETED)
                    val isSpinnerVisible = cpiDownload.visibility == View.VISIBLE
                    if (!isSpinnerVisible && isDownloaded) {
                        ivDownloaded.visibility = View.VISIBLE
                        containerIconDownload.visibility = View.VISIBLE
                    } else if (!isSpinnerVisible) {
                        ivDownloaded.visibility = View.GONE
                        containerIconDownload.visibility = View.GONE
                    }
                } catch (_: Exception) {
                    ivDownloaded.visibility = View.GONE
                    if (cpiDownload.visibility != View.VISIBLE) {
                        containerIconDownload.visibility = View.GONE
                    }
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

    override fun onBindViewHolder(holder: SongViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_DOWNLOAD)) {
            // Partial update: only refresh download UI, avoid rebinding text/cover
            holder.partialUpdateDownloadUi(songs[position], downloadStates[songs[position].id])
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    override fun getItemCount(): Int = songs.size

    // Provide a 64-bit stable ID derived from the song's string id to avoid hash collisions
    override fun getItemId(position: Int): Long {
        val idStr = songs.getOrNull(position)?.id ?: return RecyclerView.NO_ID
        // 64-bit rolling hash (very low collision probability compared to String.hashCode())
        var h = 1125899906842597L // prime-ish seed
        for (ch in idStr) {
            h = (h * 1315423911L) xor ch.code.toLong()
        }
        return h
    }
}
