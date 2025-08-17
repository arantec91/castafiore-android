package com.arantec.castafiore.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.arantec.castafiore.R
import com.arantec.castafiore.data.models.Artist
import com.arantec.castafiore.data.repository.MusicRepository
import com.arantec.castafiore.databinding.ItemArtistHorizontalBinding
import com.arantec.castafiore.utils.ImageLoader
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.shape.RelativeCornerSize

class ArtistHorizontalAdapter(
    private val onArtistClick: (Artist) -> Unit
) : RecyclerView.Adapter<ArtistHorizontalAdapter.ArtistViewHolder>() {

    private var artists: List<Artist> = emptyList()

    fun submit(list: List<Artist>) {
        artists = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ArtistViewHolder {
        val binding = ItemArtistHorizontalBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ArtistViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ArtistViewHolder, position: Int) {
        holder.bind(artists[position])
    }

    override fun getItemCount(): Int = artists.size

    inner class ArtistViewHolder(
        private val binding: ItemArtistHorizontalBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(artist: Artist) {
            binding.tvArtistName.text = artist.name

            // Make image circular
            (binding.ivArtistImage as? ShapeableImageView)?.let { iv ->
                iv.shapeAppearanceModel = iv.shapeAppearanceModel.toBuilder()
                    .setAllCornerSizes(RelativeCornerSize(0.5f))
                    .build()
            }

            val context = binding.root.context
            val repo = MusicRepository.getInstance(context)
            try {
                val server = repo.serverUrl
                if (!server.isNullOrEmpty()) {
                    val (u, t, s) = repo.getAuthParams()
                    val url = ImageLoader.buildArtistImageUrl(server, artist.id, u, t, s, 300)
                    ImageLoader.loadArtistImage(context, binding.ivArtistImage, url)
                } else {
                    binding.ivArtistImage.setImageResource(R.drawable.ic_person)
                }
            } catch (e: Exception) {
                binding.ivArtistImage.setImageResource(R.drawable.ic_person)
            }

            binding.root.setOnClickListener { onArtistClick(artist) }
        }
    }
}

