package com.arantec.castafiore.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.arantec.castafiore.R
import com.arantec.castafiore.data.models.Playlist
import com.arantec.castafiore.databinding.ItemPlaylistSelectorBinding
import com.bumptech.glide.Glide

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

            // Cargar imagen de la playlist
            loadPlaylistCover(playlist)

            binding.root.setOnClickListener {
                onPlaylistClick(playlist)
            }
        }

        private fun loadPlaylistCover(playlist: Playlist) {
            // Intentar cargar la imagen de la playlist si tiene coverArt
            if (!playlist.coverArt.isNullOrEmpty()) {
                try {
                    // Obtener los parámetros de autenticación del contexto
                    val context = binding.root.context
                    val musicRepository = com.arantec.castafiore.data.repository.MusicRepository.getInstance(context)
                    val (username, token, salt) = musicRepository.getAuthParams()
                    val serverUrl = musicRepository.serverUrl

                    if (serverUrl != null) {
                        val coverUrl = playlist.getCoverArtUrl(serverUrl, username, token, salt)

                        if (coverUrl != null) {
                            Glide.with(context)
                                .load(coverUrl)
                                .placeholder(R.drawable.ic_queue_music)
                                .error(R.drawable.ic_queue_music)
                                .into(binding.ivPlaylistCover)
                        } else {
                            binding.ivPlaylistCover.setImageResource(R.drawable.ic_queue_music)
                        }
                    } else {
                        binding.ivPlaylistCover.setImageResource(R.drawable.ic_queue_music)
                    }
                } catch (e: Exception) {
                    // En caso de error, usar el icono predeterminado
                    binding.ivPlaylistCover.setImageResource(R.drawable.ic_queue_music)
                }
            } else {
                // Si no hay coverArt, usar el icono predeterminado
                binding.ivPlaylistCover.setImageResource(R.drawable.ic_queue_music)
            }
        }
    }
}
