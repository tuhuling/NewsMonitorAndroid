package com.example.newsmonitor.ui.website

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.newsmonitor.data.Website
import com.example.newsmonitor.databinding.ItemWebsiteBinding

class WebsiteAdapter(
    private val onEdit: (Website) -> Unit,
    private val onDelete: (Website) -> Unit,
    private val onSelectionChanged: (Set<Long>) -> Unit
) : ListAdapter<Website, WebsiteAdapter.Holder>(Diff) {

    private val selectedIds = linkedSetOf<Long>()

    inner class Holder(val binding: ItemWebsiteBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemWebsiteBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val website = getItem(position)
        val binding = holder.binding

        binding.cbSelect.setOnCheckedChangeListener(null)
        binding.cbSelect.isChecked = selectedIds.contains(website.id)
        binding.cbSelect.setOnCheckedChangeListener { _, checked ->
            if (checked) selectedIds.add(website.id) else selectedIds.remove(website.id)
            onSelectionChanged(selectedIds.toSet())
        }

        binding.tvName.text = website.name
        val remark = website.remark?.takeIf { it.isNotBlank() }
        binding.tvDetail.text = if (remark != null) {
            "${website.url} · $remark"
        } else {
            website.url
        }

        binding.root.setOnClickListener { onEdit(website) }
        binding.btnDelete.setOnClickListener { onDelete(website) }
    }

    fun selectedIds(): Set<Long> = selectedIds.toSet()

    fun clearSelection() {
        if (selectedIds.isEmpty()) return
        selectedIds.clear()
        notifyItemRangeChanged(0, itemCount)
    }

    fun selectAll(items: List<Website>) {
        selectedIds.clear()
        items.forEach { selectedIds.add(it.id) }
        notifyItemRangeChanged(0, itemCount)
    }

    private object Diff : DiffUtil.ItemCallback<Website>() {
        override fun areItemsTheSame(oldItem: Website, newItem: Website): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: Website, newItem: Website): Boolean =
            oldItem == newItem
    }
}
