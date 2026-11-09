package com.example.newsmonitor.ui.keyword

import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.newsmonitor.R
import com.example.newsmonitor.data.AppDatabase
import com.example.newsmonitor.data.Keyword
import com.example.newsmonitor.databinding.DialogKeywordEditBinding
import com.example.newsmonitor.databinding.FragmentKeywordBinding
import com.example.newsmonitor.util.TimeUtils
import com.example.newsmonitor.util.toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class KeywordFragment : Fragment() {

    private var _binding: FragmentKeywordBinding? = null
    private val binding get() = _binding!!
    private val db by lazy { AppDatabase.get(requireContext().applicationContext) }
    private lateinit var adapter: KeywordAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentKeywordBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = KeywordAdapter(
            onEdit = { showEditDialog(it) },
            onDelete = { confirmDeleteOne(it) },
            onSelectionChanged = { updateSelectionUi() }
        )
        binding.rvKeywords.layoutManager = LinearLayoutManager(requireContext())
        binding.rvKeywords.adapter = adapter

        binding.btnAdd.setOnClickListener { showEditDialog(null) }
        binding.btnBatchDelete.setOnClickListener { confirmDeleteSelected() }
        updateSelectionUi()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                db.keywordDao().observeAll().collect { list ->
                    adapter.submitList(list)
                    binding.tvEmpty.isVisible = list.isEmpty()
                    binding.tvCount.text = "共 ${list.size} 个关键词"
                    updateSelectionUi()
                }
            }
        }
    }

    private fun updateSelectionUi() {
        if (_binding == null) return
        val count = adapter.selectedIds().size
        binding.btnBatchDelete.isEnabled = count > 0
        binding.btnBatchDelete.text = if (count > 0) {
            "批量删除($count)"
        } else {
            getString(R.string.batch_delete)
        }
    }

    private fun showEditDialog(keyword: Keyword?) {
        val dialogBinding = DialogKeywordEditBinding.inflate(layoutInflater)
        keyword?.let { dialogBinding.etKeyword.setText(it.keyword) }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (keyword == null) R.string.add_keyword else R.string.edit_keyword)
            .setView(dialogBinding.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = dialogBinding.etKeyword.text?.toString()?.trim().orEmpty()
                if (value.isEmpty()) {
                    dialogBinding.tilKeyword.error = "请输入关键词"
                    return@setOnClickListener
                }
                dialogBinding.tilKeyword.error = null

                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            if (keyword == null) {
                                db.keywordDao().insert(
                                    Keyword(
                                        keyword = value,
                                        createdAt = TimeUtils.nowBeijingString()
                                    )
                                )
                            } else {
                                db.keywordDao().update(keyword.copy(keyword = value))
                            }
                        }
                        dialog.dismiss()
                    } catch (e: Exception) {
                        context?.toast(
                            if (isUniqueConstraint(e)) {
                                "该关键词已存在"
                            } else {
                                "保存失败: ${e.message?.take(80)}"
                            }
                        )
                    }
                }
            }
        }
        dialog.show()
    }

    private fun confirmDeleteOne(keyword: Keyword) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("删除关键词")
            .setMessage("确定删除关键词“${keyword.keyword}”？")
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    withContext(Dispatchers.IO) { db.keywordDao().deleteById(keyword.id) }
                    context?.toast("已删除关键词")
                }
            }
            .show()
    }

    private fun confirmDeleteSelected() {
        val ids = adapter.selectedIds().toList()
        if (ids.isEmpty()) return

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("批量删除关键词")
            .setMessage("确定删除选中的 ${ids.size} 个关键词？")
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    withContext(Dispatchers.IO) { db.keywordDao().deleteByIds(ids) }
                    adapter.clearSelection()
                    updateSelectionUi()
                    context?.toast("已删除 ${ids.size} 个关键词")
                }
            }
            .show()
    }

    private fun isUniqueConstraint(exception: Exception): Boolean {
        val message = exception.message ?: return false
        return message.contains("UNIQUE", ignoreCase = true) ||
            message.contains("constraint", ignoreCase = true)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
