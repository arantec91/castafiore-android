package com.arantec.castafiore.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.arantec.castafiore.databinding.ItemAccountFieldBinding

class AccountInfoAdapter(
    private var items: List<Pair<String, String>> = emptyList()
) : RecyclerView.Adapter<AccountInfoAdapter.VH>() {

    class VH(val binding: ItemAccountFieldBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemAccountFieldBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val (key, value) = items[position]
        holder.binding.tvKey.text = key
        holder.binding.tvValue.text = value
    }

    override fun getItemCount(): Int = items.size

    fun submitList(newItems: List<Pair<String, String>>) {
        items = newItems
        notifyDataSetChanged()
    }
}
