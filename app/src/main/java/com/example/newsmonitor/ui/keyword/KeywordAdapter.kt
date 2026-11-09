package com.example.newsmonitor.ui.keyword

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.newsmonitor.data.Keyword
import com.example.newsmonitor.databinding.ItemKeywordBinding

class KeywordAdapter(
    private val onEdit: (Keyword) -> Unit,
    private val onDelete: (Keyword) -> Unit,
    private val onSelectionChanged: (Set<Long>) -> Unit
) : ListAdapter<Keyword, KeywordAdapter.Holder>(Diff) {

    private val selectedIds = linkedSetOf<Long>()

    inner class Holder(val binding: ItemKeywordBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemKeywordBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val keyword = getItem(position)
        val binding = holder.binding

        binding.cbSelect.setOnCheckedChangeListener(null)
        binding.cbSelect.isChecked = selectedIds.contains(keyword.id)
        binding.cbSelect.setOnCheckedChangeListener { _, checked ->
            if (checked) selectedIds.add(keyword.id) else selectedIds.remove(keyword.id)
            onSelectionChanged(selectedIds.toSet())
        }

        binding.tvKeyword.text = keyword.keyword
        binding.root.setOnClickListener { onEdit(keyword) }
        binding.btnDelete.setOnClickListener { onDelete(keyword) }
    }

    fun selectedIds(): Set<Long> = selectedIds.toSet()

    fun clearSelection() {
        if (selectedIds.isEmpty()) return
        selectedIds.clear()
        notifyItemRangeChanged(0, itemCount)
    }

    private object Diff : DiffUtil.ItemCallback<Keyword>() {
        override fun areItemsTheSame(oldItem: Keyword, newItem: Keyword): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: Keyword, newItem: Keyword): Boolean =
            oldItem == newItem
    }
}
