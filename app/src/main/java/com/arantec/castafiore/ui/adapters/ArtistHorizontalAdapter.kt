package com.arantec.castafiore.ui.adapters

import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.arantec.castafiore.R
import com.arantec.castafiore.data.models.Artist
import com.arantec.castafiore.data.repository.MusicRepository
import com.arantec.castafiore.databinding.ItemArtistHorizontalBinding
import com.arantec.castafiore.utils.ImageLoader
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.shape.RelativeCornerSize

class ArtistHorizontalAdapter(
    private val onArtistClick: (Artist) -> Unit,
    private val imageSizeDp: Int? = null,
    private val textWidthDp: Int? = null,
    private val textSizeSp: Float? = null,
    private val centerText: Boolean = false
) : ListAdapter<Artist, ArtistHorizontalAdapter.ArtistViewHolder>(DIFF) {

    companion object {
        private const val VIEW_TYPE_ARTIST = 1001

        private val DIFF = object : DiffUtil.ItemCallback<Artist>() {
            override fun areItemsTheSame(oldItem: Artist, newItem: Artist): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: Artist, newItem: Artist): Boolean =
                oldItem == newItem
        }
    }

    init {
        setHasStableIds(true)
    }

    fun submit(list: List<Artist>) {
        // Usar submitList en lugar de notifyDataSetChanged para preservar ViewHolders
        submitList(list.toList())
    }

    override fun getItemId(position: Int): Long {
        return getItem(position).id.hashCode().toLong()
    }

    override fun getItemViewType(position: Int): Int {
        return VIEW_TYPE_ARTIST
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ArtistViewHolder {
        val binding = ItemArtistHorizontalBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ArtistViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ArtistViewHolder, position: Int) {
        if (holder is ArtistViewHolder && position < itemCount) {
            holder.bind(getItem(position))
        }
    }

    inner class ArtistViewHolder(
        private val binding: ItemArtistHorizontalBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        // Track current URL to avoid unnecessary reloads
        private var currentUrl: String? = null

        fun bind(artist: Artist) {
            // Apply per-instance sizing if provided
            val density = binding.root.resources.displayMetrics.density
            imageSizeDp?.let { dp ->
                val px = (dp * density).toInt()
                val lp = binding.ivArtistImage.layoutParams
                if (lp.width != px || lp.height != px) {
                    lp.width = px
                    lp.height = px
                    binding.ivArtistImage.layoutParams = lp
                }
            }
            textWidthDp?.let { dp ->
                val px = (dp * density).toInt()
                val tlp = binding.tvArtistName.layoutParams
                if (tlp.width != px) {
                    tlp.width = px
                    binding.tvArtistName.layoutParams = tlp
                }
            }
            textSizeSp?.let { sp ->
                binding.tvArtistName.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            }
            if (centerText) {
                binding.tvArtistName.textAlignment = View.TEXT_ALIGNMENT_CENTER
                binding.tvArtistName.gravity = Gravity.CENTER_HORIZONTAL
            } else {
                binding.tvArtistName.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                binding.tvArtistName.gravity = Gravity.START
            }

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
                val targetUrl: String? = if (!server.isNullOrEmpty()) {
                    val (u, t, s) = repo.getAuthParams()
                    ImageLoader.buildArtistImageUrl(server, artist.id, u, t, s, 300)
                } else {
                    null
                }

                // CRÍTICO: Solo recargar si la URL cambió
                // Esto evita que Glide ponga el placeholder cuando la imagen ya está cargada
                if (targetUrl != currentUrl) {
                    currentUrl = targetUrl

                    if (targetUrl != null) {
                        ImageLoader.loadArtistImage(context, binding.ivArtistImage, targetUrl)
                    } else {
                        binding.ivArtistImage.setImageResource(R.drawable.ic_person)
                    }
                }
                // Si targetUrl == currentUrl, NO hacer nada
                // La imagen ya está en el ImageView

            } catch (_: Exception) {
                currentUrl = null
                binding.ivArtistImage.setImageResource(R.drawable.ic_person)
            }

            binding.root.setOnClickListener { onArtistClick(artist) }
        }
    }
}
