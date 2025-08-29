package com.arantec.castafiore.ui.adapters

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.RecyclerView
import com.arantec.castafiore.data.models.Playlist
import com.arantec.castafiore.databinding.ItemPublicPlaylistBinding
import com.google.android.material.card.MaterialCardView

class PublicPlaylistsGridAdapter(
    private val onPlaylistClick: (Playlist) -> Unit
) : RecyclerView.Adapter<PublicPlaylistsGridAdapter.PlaylistVH>() {

    private val items = mutableListOf<Playlist>()

    // Dark vibrant palette for card backgrounds
    private val palette = listOf(
        Color.parseColor("#1F3A5B"),
        Color.parseColor("#3A1F5B"),
        Color.parseColor("#5B1F39"),
        Color.parseColor("#1F5B3A"),
        Color.parseColor("#5B3A1F"),
        Color.parseColor("#0F3D3E"),
        Color.parseColor("#2C255B"),
        Color.parseColor("#153B50")
    )

    fun setItems(list: List<Playlist>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlaylistVH {
        val binding = ItemPublicPlaylistBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PlaylistVH(binding)
    }

    override fun onBindViewHolder(holder: PlaylistVH, position: Int) {
        holder.bind(items[position], position)
    }

    override fun getItemCount(): Int = items.size

    inner class PlaylistVH(private val binding: ItemPublicPlaylistBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(p: Playlist, position: Int) {
            binding.tvPlaylistName.text = p.name
            val color = palette[position % palette.size]
            (binding.root as MaterialCardView).setCardBackgroundColor(color)
            binding.tvPlaylistName.setTextColor(Color.WHITE)
            ViewCompat.setElevation(binding.root, 0f)
            binding.root.setOnClickListener { onPlaylistClick(p) }
        }
    }
}
