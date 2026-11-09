package com.example.newsmonitor.ui.monitor

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.newsmonitor.R
import com.example.newsmonitor.data.LogEntry
import com.example.newsmonitor.databinding.ItemLogBinding
import com.example.newsmonitor.util.TimeUtils

class LogAdapter : ListAdapter<LogEntry, LogAdapter.Holder>(Diff) {

    inner class Holder(val binding: ItemLogBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemLogBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val log = getItem(position)
        val time = TimeUtils.logTime(log.createdAt)
        holder.binding.tvLogLine.text = "[$time] ${log.level}  ${log.message}"

        val colorRes = when (log.level) {
            "ERROR" -> R.color.level_error
            "WARN" -> R.color.level_warn
            else -> R.color.level_info
        }
        holder.binding.tvLogLine.setTextColor(
            ContextCompat.getColor(holder.binding.root.context, colorRes)
        )
    }

    private object Diff : DiffUtil.ItemCallback<LogEntry>() {
        override fun areItemsTheSame(oldItem: LogEntry, newItem: LogEntry): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: LogEntry, newItem: LogEntry): Boolean =
            oldItem == newItem
    }
}
