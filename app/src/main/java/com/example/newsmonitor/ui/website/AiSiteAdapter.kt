package com.example.newsmonitor.ui.website

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.newsmonitor.databinding.ItemAiSiteBinding
import com.example.newsmonitor.network.RecommendedSite

class AiSiteAdapter : ListAdapter<RecommendedSite, AiSiteAdapter.Holder>(Diff) {

    private val selected = linkedSetOf<RecommendedSite>()

    inner class Holder(val binding: ItemAiSiteBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemAiSiteBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val site = getItem(position)
        val binding = holder.binding

        binding.cbSelect.setOnCheckedChangeListener(null)
        binding.cbSelect.isChecked = selected.contains(site)
        binding.cbSelect.setOnCheckedChangeListener { _, checked ->
            if (checked) selected.add(site) else selected.remove(site)
        }

        binding.tvName.text = site.name
        binding.tvUrl.text = site.url
        binding.tvRemark.text = site.remark
        binding.tvRemark.visibility = if (site.remark.isBlank()) {
            android.view.View.GONE
        } else {
            android.view.View.VISIBLE
        }

        binding.root.setOnClickListener {
            binding.cbSelect.isChecked = !binding.cbSelect.isChecked
        }
    }

    override fun submitList(list: List<RecommendedSite>?) {
        super.submitList(list)
        selected.clear()
        list?.let { selected.addAll(it) }
    }

    fun selectedSites(): List<RecommendedSite> {
        return currentList.filter { selected.contains(it) }
    }

    private object Diff : DiffUtil.ItemCallback<RecommendedSite>() {
        override fun areItemsTheSame(
            oldItem: RecommendedSite,
            newItem: RecommendedSite
        ): Boolean = oldItem.name == newItem.name && oldItem.url == newItem.url

        override fun areContentsTheSame(
            oldItem: RecommendedSite,
            newItem: RecommendedSite
        ): Boolean = oldItem == newItem
    }
}
