package com.arantec.castafiore.ui.lyrics

import android.os.Build
import android.text.Layout
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.arantec.castafiore.R
import com.arantec.castafiore.data.lyrics.LyricsLine
import com.arantec.castafiore.databinding.ItemLyricsLineBinding
import kotlin.math.max

class LyricsAdapter : RecyclerView.Adapter<LyricsAdapter.VH>() {

    private val items = mutableListOf<LyricsLine>()
    private var currentPosMs: Long = 0L
    private var currentIndex: Int = -1

    var onActiveIndexChanged: ((Int) -> Unit)? = null

    fun setLines(lines: List<LyricsLine>) {
        items.clear()
        items.addAll(lines)
        currentIndex = -1
        notifyDataSetChanged()
    }

    fun getActiveIndex(): Int = currentIndex

    fun getLineAt(index: Int): LyricsLine? = items.getOrNull(index)

    fun updateProgress(positionMs: Long) {
        currentPosMs = positionMs
        val newIdx = findIndexFor(positionMs)
        if (newIdx != currentIndex) {
            val old = currentIndex
            currentIndex = newIdx
            if (old >= 0) notifyItemChanged(old)
            if (newIdx >= 0) notifyItemChanged(newIdx)
            onActiveIndexChanged?.invoke(newIdx)
        }
        // No else-branch updates: avoid rebinding the same item each tick
    }

    private fun findIndexFor(position: Long): Int {
        if (items.isEmpty()) return -1
        var lo = 0
        var hi = items.lastIndex
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (items[mid].timeMs <= position) {
                ans = mid
                lo = mid + 1
            } else hi = mid - 1
        }
        return ans
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemLyricsLineBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        // Ensure no justification so text stays left-aligned
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            binding.tvLine.justificationMode = Layout.JUSTIFICATION_MODE_NONE
        }
        return VH(binding)
    }

    override fun getItemCount(): Int = max(1, items.size)

    override fun onBindViewHolder(holder: VH, position: Int) {
        if (items.isEmpty()) {
            holder.binding.tvLine.text = holder.binding.root.context.getString(R.string.no_lyrics)
            holder.binding.tvLine.setActive(false)
            holder.binding.tvLine.setProgressFraction(0f)
            return
        }
        val line = items[position]
        holder.binding.tvLine.text = line.text
        val active = position == currentIndex
        holder.binding.tvLine.setActive(active)
        if (active) {
            // With full-line highlight, progress fraction is irrelevant, but keep API call harmless
            holder.binding.tvLine.setProgressFraction(1f)
        } else {
            holder.binding.tvLine.setProgressFraction(0f)
        }
    }

    class VH(val binding: ItemLyricsLineBinding) : RecyclerView.ViewHolder(binding.root)
}
