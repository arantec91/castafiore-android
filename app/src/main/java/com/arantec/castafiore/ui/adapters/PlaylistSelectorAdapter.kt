package com.arantec.castafiore.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.arantec.castafiore.R
import com.arantec.castafiore.data.models.Playlist
import com.arantec.castafiore.databinding.ItemPlaylistSelectorBinding
import com.arantec.castafiore.utils.ImageLoader
import com.arantec.castafiore.data.repository.MusicRepository

class PlaylistSelectorAdapter(
    private val onPlaylistClick: (Playlist) -> Unit
) : RecyclerView.Adapter<PlaylistSelectorAdapter.PlaylistViewHolder>() {

    private var playlists = listOf<Playlist>()

    fun updatePlaylists(newPlaylists: List<Playlist>) {
        playlists = newPlaylists
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlaylistViewHolder {
        val binding = ItemPlaylistSelectorBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return PlaylistViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PlaylistViewHolder, position: Int) {
        holder.bind(playlists[position])
    }

    override fun getItemCount(): Int = playlists.size

    inner class PlaylistViewHolder(
        private val binding: ItemPlaylistSelectorBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(playlist: Playlist) {
            binding.tvPlaylistName.text = playlist.name
            binding.tvSongCount.text = "${playlist.songCount} canciones"

            // Cargar imagen de la playlist (offline-aware)
            loadPlaylistCover(playlist)

            binding.root.setOnClickListener {
                onPlaylistClick(playlist)
            }
        }

        private fun loadPlaylistCover(playlist: Playlist) {
            // Si no hay coverArt, usar icono por defecto
            val coverId = playlist.coverArt
            if (coverId.isNullOrEmpty()) {
                binding.ivPlaylistCover.setImageResource(R.drawable.ic_queue_music)
                return
            }

            try {
                val context = binding.root.context
                val repo = MusicRepository.getInstance(context)
                val server = repo.serverUrl
                if (server.isNullOrEmpty()) {
                    binding.ivPlaylistCover.setImageResource(R.drawable.ic_queue_music)
                    return
                }
                val (u, t, s) = repo.getAuthParams()
                val coverUrl = ImageLoader.buildCoverArtUrl(server, coverId, u, t, s, 200)
                ImageLoader.loadThumbnail(context, binding.ivPlaylistCover, coverUrl)
            } catch (_: Exception) {
                binding.ivPlaylistCover.setImageResource(R.drawable.ic_queue_music)
            }
        }
    }
}
