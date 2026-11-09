package com.example.newsmonitor.ui.news

import android.content.Context
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.newsmonitor.R
import com.example.newsmonitor.data.News
import com.example.newsmonitor.databinding.ItemNewsBinding
import com.example.newsmonitor.util.dp

class NewsAdapter(
    private val onOpen: (News) -> Unit,
    private val onSelectionChanged: (Set<Long>) -> Unit
) : ListAdapter<News, NewsAdapter.Holder>(Diff) {

    private val selectedIds = linkedSetOf<Long>()

    inner class Holder(val binding: ItemNewsBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemNewsBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val news = getItem(position)
        val binding = holder.binding
        val context = binding.root.context

        binding.cbSelect.setOnCheckedChangeListener(null)
        binding.cbSelect.isChecked = selectedIds.contains(news.id)
        binding.cbSelect.setOnCheckedChangeListener { _, checked ->
            if (checked) selectedIds.add(news.id) else selectedIds.remove(news.id)
            onSelectionChanged(selectedIds.toSet())
        }

        binding.tvTime.text = formatListTime(news.publishTime)
        binding.tvSource.text = news.websiteName?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.unknown_source)
        binding.tvTitle.text = news.titleZh?.takeIf { it.isNotBlank() } ?: news.title
        binding.tvBadge.isVisible = news.titleZh.isNullOrBlank()

        bindTags(binding.llTags, news.matchKeyword)
        binding.root.setOnClickListener { onOpen(news) }
    }

    fun selectedIds(): Set<Long> = selectedIds.toSet()

    fun clearSelection() {
        if (selectedIds.isEmpty()) return
        selectedIds.clear()
        notifyItemRangeChanged(0, itemCount)
    }

    fun selectAll(items: List<News>) {
        selectedIds.clear()
        items.forEach { selectedIds.add(it.id) }
        notifyItemRangeChanged(0, itemCount)
    }

    private fun bindTags(container: LinearLayout, matchKeyword: String?) {
        container.removeAllViews()
        val tags = matchKeyword
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

        tags.take(2).forEach { tag -> container.addView(createTag(container.context, tag)) }
        val remaining = tags.size - 2
        if (remaining > 0) {
            container.addView(createTag(container.context, "+$remaining"))
        }
    }

    private fun createTag(context: Context, text: String): TextView {
        return TextView(context).apply {
            this.text = text
            textSize = 11f
            setTextColor(ContextCompat.getColor(context, R.color.tag_text))
            setBackgroundResource(R.drawable.bg_tag)
            val horizontal = context.dp(5)
            val vertical = context.dp(1)
            setPadding(horizontal, vertical, horizontal, vertical)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = context.dp(3)
            }
        }
    }

    private fun formatListTime(publishTime: String?): String {
        if (publishTime.isNullOrBlank()) return ""
        return if (publishTime.length >= 16) publishTime.substring(5, 16) else publishTime
    }

    private object Diff : DiffUtil.ItemCallback<News>() {
        override fun areItemsTheSame(oldItem: News, newItem: News): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: News, newItem: News): Boolean =
            oldItem == newItem
    }
}
