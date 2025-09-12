package com.arantec.castafiore.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isGone
import androidx.recyclerview.widget.RecyclerView
import com.arantec.castafiore.R
import com.arantec.castafiore.data.local.SearchHistoryManager
import com.arantec.castafiore.data.repository.MusicRepository
import com.arantec.castafiore.utils.ImageLoader

class SearchHistoryAdapter(
    private val onEntryClick: (SearchHistoryManager.Entry) -> Unit
) : RecyclerView.Adapter<SearchHistoryAdapter.Holder>() {

    private val items = mutableListOf<SearchHistoryManager.Entry>()
    private var playingSongId: String? = null

    fun submit(list: List<SearchHistoryManager.Entry>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun setPlayingSongId(songId: String?) {
        if (playingSongId == songId) return
        val old = playingSongId
        playingSongId = songId
        // Update only affected rows
        val oldIdx = old?.let { id -> items.indexOfFirst { it.type == SearchHistoryManager.Entry.Type.SONG && it.id == id } } ?: -1
        val newIdx = songId?.let { id -> items.indexOfFirst { it.type == SearchHistoryManager.Entry.Type.SONG && it.id == id } } ?: -1
        if (oldIdx >= 0) notifyItemChanged(oldIdx)
        if (newIdx >= 0 && newIdx != oldIdx) notifyItemChanged(newIdx)
        if (oldIdx < 0 && newIdx < 0) notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_search_history_entry, parent, false)
        return Holder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    inner class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivIcon: ImageView = itemView.findViewById(R.id.ivIcon)
        private val tvTitle: TextView = itemView.findViewById(R.id.tvTitle)
        private val tvSubtitle: TextView = itemView.findViewById(R.id.tvSubtitle)
        fun bind(entry: SearchHistoryManager.Entry) {
            tvTitle.text = entry.title
            val typeLabelRes = when (entry.type) {
                SearchHistoryManager.Entry.Type.SONG -> R.string.type_song
                SearchHistoryManager.Entry.Type.ARTIST -> R.string.type_artist
                SearchHistoryManager.Entry.Type.ALBUM -> R.string.type_album
                SearchHistoryManager.Entry.Type.PLAYLIST -> R.string.type_playlist
            }
            val typeLabel = itemView.context.getString(typeLabelRes)
            val subtitleParts = mutableListOf<String>()
            entry.subtitle?.let { if (it.isNotBlank()) subtitleParts.add(it) }
            subtitleParts.add(typeLabel)
            val subtitle = subtitleParts.joinToString(" • ")
            tvSubtitle.text = subtitle
            tvSubtitle.isGone = subtitle.isBlank()

            // Highlight if this song is currently playing
            if (entry.type == SearchHistoryManager.Entry.Type.SONG && entry.id == playingSongId) {
                tvTitle.setTextColor(itemView.context.getColor(R.color.primary))
            } else {
                tvTitle.setTextColor(itemView.context.getColor(R.color.text_primary))
            }

            // Cargar imagen en el icono según tipo
            loadEntryArtwork(entry)

            itemView.setOnClickListener { onEntryClick(entry) }
        }

        private fun loadEntryArtwork(entry: SearchHistoryManager.Entry) {
            val ctx = itemView.context
            try {
                val repo = MusicRepository.getInstance(ctx)
                val server = repo.serverUrl
                if (server.isNullOrEmpty()) {
                    setIconFallback(entry)
                    return
                }
                val (username, token, salt) = try {
                    repo.getAuthParams()
                } catch (_: Exception) {
                    setIconFallback(entry)
                    return
                }

                when (entry.type) {
                    SearchHistoryManager.Entry.Type.SONG -> {
                        // Quitar tint para bitmaps reales
                        ivIcon.imageTintList = null
                        val url = ImageLoader.buildCoverArtUrl(server, entry.id, username, token, salt, 200)
                        ImageLoader.loadThumbnail(ctx, ivIcon, url)
                    }
                    SearchHistoryManager.Entry.Type.ALBUM -> {
                        ivIcon.imageTintList = null
                        val url = ImageLoader.buildCoverArtUrl(server, entry.id, username, token, salt, 200)
                        ImageLoader.loadThumbnail(ctx, ivIcon, url)
                    }
                    SearchHistoryManager.Entry.Type.ARTIST -> {
                        ivIcon.imageTintList = null
                        val url = ImageLoader.buildArtistImageUrl(server, entry.id, username, token, salt, 200)
                        ImageLoader.loadArtistImage(ctx, ivIcon, url)
                    }
                    SearchHistoryManager.Entry.Type.PLAYLIST -> {
                        // Mantener icono para playlist
                        setIconFallback(entry)
                    }
                }
            } catch (_: Exception) {
                setIconFallback(entry)
            }
        }

        private fun setIconFallback(entry: SearchHistoryManager.Entry) {
            val ctx = itemView.context
            when (entry.type) {
                SearchHistoryManager.Entry.Type.SONG -> {
                    ivIcon.imageTintList = null
                    ivIcon.setImageResource(R.drawable.ic_music_note)
                }
                SearchHistoryManager.Entry.Type.ALBUM -> {
                    ivIcon.imageTintList = null
                    ivIcon.setImageResource(R.drawable.ic_album_placeholder)
                }
                SearchHistoryManager.Entry.Type.ARTIST -> {
                    ivIcon.imageTintList = null
                    ivIcon.setImageResource(R.drawable.ic_person)
                }
                SearchHistoryManager.Entry.Type.PLAYLIST -> {
                    // Reaplicar tint para icono de playlist
                    ivIcon.imageTintList = ContextCompat.getColorStateList(ctx, R.color.text_secondary)
                    ivIcon.setImageResource(R.drawable.ic_playlist)
                }
            }
        }
    }
}
