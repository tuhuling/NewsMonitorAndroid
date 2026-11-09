package com.example.newsmonitor.ui.website

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.newsmonitor.R
import com.example.newsmonitor.data.AppDatabase
import com.example.newsmonitor.data.Website
import com.example.newsmonitor.databinding.DialogPromptBinding
import com.example.newsmonitor.databinding.DialogWebsiteEditBinding
import com.example.newsmonitor.databinding.FragmentWebsiteBinding
import com.example.newsmonitor.network.RecommendedSite
import com.example.newsmonitor.util.TimeUtils
import com.example.newsmonitor.util.copyPlainText
import com.example.newsmonitor.util.toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

class WebsiteFragment : Fragment() {

    private var _binding: FragmentWebsiteBinding? = null
    private val binding get() = _binding!!
    private val db by lazy { AppDatabase.get(requireContext().applicationContext) }
    private lateinit var adapter: WebsiteAdapter

    private var importing = false

    private val openDocument =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { importFile(it) }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentWebsiteBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = WebsiteAdapter(
            onEdit = { showEditDialog(it) },
            onDelete = { confirmDeleteOne(it) },
            onSelectionChanged = { updateSelectionUi() }
        )
        binding.rvWebsites.layoutManager = LinearLayoutManager(requireContext())
        binding.rvWebsites.adapter = adapter

        binding.btnAdd.setOnClickListener { showEditDialog(null) }
        binding.btnImport.setOnClickListener { openDocument.launch(arrayOf("*/*")) }
        binding.btnAi.setOnClickListener {
            AiRecommendDialogFragment().show(childFragmentManager, "ai_recommend")
        }
        binding.btnSourcePrompt.setOnClickListener { showSourcePrompt() }
        binding.btnBatchDelete.setOnClickListener { confirmDeleteSelected() }
        updateSelectionUi()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                db.websiteDao().observeAll().collect { list ->
                    adapter.submitList(list)
                    binding.tvEmpty.isVisible = list.isEmpty()
                    binding.tvCount.text = "共 ${list.size} 个网站源"
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

    private fun showEditDialog(website: Website?) {
        val dialogBinding = DialogWebsiteEditBinding.inflate(layoutInflater)
        website?.let {
            dialogBinding.etName.setText(it.name)
            dialogBinding.etUrl.setText(it.url)
            dialogBinding.etRemark.setText(it.remark.orEmpty())
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (website == null) R.string.add_website else R.string.edit_website)
            .setView(dialogBinding.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = dialogBinding.etName.text?.toString()?.trim().orEmpty()
                val url = dialogBinding.etUrl.text?.toString()?.trim().orEmpty()
                val remark = dialogBinding.etRemark.text?.toString()?.trim()
                    ?.takeIf { it.isNotEmpty() }

                var valid = true
                if (name.isEmpty()) {
                    dialogBinding.tilName.error = "请输入名称"
                    valid = false
                } else {
                    dialogBinding.tilName.error = null
                }
                if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
                    dialogBinding.tilUrl.error = "URL 必须以 http:// 或 https:// 开头"
                    valid = false
                } else {
                    dialogBinding.tilUrl.error = null
                }
                if (!valid) return@setOnClickListener

                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            if (website == null) {
                                db.websiteDao().insert(
                                    Website(
                                        name = name,
                                        url = url,
                                        remark = remark,
                                        createdAt = TimeUtils.nowBeijingString()
                                    )
                                )
                            } else {
                                db.websiteDao().update(
                                    website.copy(name = name, url = url, remark = remark)
                                )
                            }
                        }
                        dialog.dismiss()
                    } catch (e: Exception) {
                        context?.toast(
                            if (isUniqueConstraint(e)) {
                                "该 URL 已存在"
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

    private fun confirmDeleteOne(website: Website) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("删除网站源")
            .setMessage("确定删除网站源“${website.name}”？新闻不会被删除。")
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    withContext(Dispatchers.IO) { db.websiteDao().deleteById(website.id) }
                    context?.toast("已删除网站源，新闻保留")
                }
            }
            .show()
    }

    private fun confirmDeleteSelected() {
        val ids = adapter.selectedIds().toList()
        if (ids.isEmpty()) return

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("批量删除网站源")
            .setMessage("确定删除选中的 ${ids.size} 个网站源？新闻不会被删除。")
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    withContext(Dispatchers.IO) { db.websiteDao().deleteByIds(ids) }
                    adapter.clearSelection()
                    updateSelectionUi()
                    context?.toast("已删除 ${ids.size} 个网站源，新闻保留")
                }
            }
            .show()
    }

    private fun showSourcePrompt() {
        val dialogBinding = DialogPromptBinding.inflate(layoutInflater)
        val prompt = "请严格按照模板格式\"CNN , https://www.cnn.com , " +
            "美国24小时全天候政治经济新闻资讯平台\"，生成10个美国新闻网站，生成结果不要序号。"
        dialogBinding.tvPrompt.text = prompt

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.source_prompt_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.confirm, null)
            .create()

        dialogBinding.btnCopyPrompt.setOnClickListener {
            requireContext().copyPlainText("website_prompt", prompt)
            context?.toast("已复制")
        }
        dialog.show()
    }

    private fun importFile(uri: Uri) {
        if (importing) return
        importing = true
        context?.toast("正在导入，请稍候…")

        val appContext = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { readTextAuto(appContext, uri) }.getOrNull()
            }
            if (text == null) {
                importing = false
                context?.toast("读取文件失败")
                return@launch
            }

            val stats = withContext(Dispatchers.IO) { importLines(text) }
            importing = false

            MaterialAlertDialogBuilder(requireContext())
                .setTitle("导入完成")
                .setMessage("成功：${stats.success}\n跳过：${stats.skipped}\n失败：${stats.failed}")
                .setPositiveButton(R.string.confirm, null)
                .show()
        }
    }

    private suspend fun importLines(text: String): ImportStats {
        val stats = ImportStats()
        val seenUrls = HashSet<String>()
        var firstContentLine = true

        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim().trimStart('﻿')
            if (line.isEmpty()) continue

            if (firstContentLine) {
                firstContentLine = false
                if (line.replace(" ", "").startsWith("名称,URL", ignoreCase = true)) {
                    continue
                }
            }

            val parts = line.split(',', limit = 3)
            val name = parts.getOrNull(0)?.trim().orEmpty()
            val url = parts.getOrNull(1)?.trim().orEmpty()
            val remark = parts.getOrNull(2)?.trim()?.takeIf { it.isNotEmpty() }

            val urlValid = url.startsWith("http://", true) || url.startsWith("https://", true)
            if (name.isEmpty() || !urlValid) {
                stats.failed++
                continue
            }

            if (!seenUrls.add(url) || db.websiteDao().findByUrl(url) != null) {
                stats.skipped++
                continue
            }

            try {
                db.websiteDao().insert(
                    Website(
                        name = name,
                        url = url,
                        remark = remark,
                        createdAt = TimeUtils.nowBeijingString()
                    )
                )
                stats.success++
            } catch (e: Exception) {
                if (isUniqueConstraint(e)) stats.skipped++ else stats.failed++
            }
        }
        return stats
    }

    /**
     * AI 推荐导入：按需求不测试 URL、不做额外验证，勾选即导入。
     */
    fun importRecommendedSites(sites: List<RecommendedSite>) {
        if (sites.isEmpty()) return

        viewLifecycleOwner.lifecycleScope.launch {
            val stats = withContext(Dispatchers.IO) {
                val result = ImportStats()
                val seenUrls = HashSet<String>()
                for (site in sites) {
                    val name = site.name.trim()
                    val url = site.url.trim()
                    val remark = site.remark.trim().takeIf { it.isNotEmpty() }
                    if (name.isEmpty() || url.isEmpty()) {
                        result.failed++
                        continue
                    }
                    if (!seenUrls.add(url) || db.websiteDao().findByUrl(url) != null) {
                        result.skipped++
                        continue
                    }
                    try {
                        db.websiteDao().insert(
                            Website(
                                name = name,
                                url = url,
                                remark = remark,
                                createdAt = TimeUtils.nowBeijingString()
                            )
                        )
                        result.success++
                    } catch (e: Exception) {
                        if (isUniqueConstraint(e)) result.skipped++ else result.failed++
                    }
                }
                result
            }

            MaterialAlertDialogBuilder(requireContext())
                .setTitle("导入完成")
                .setMessage("成功：${stats.success}\n跳过：${stats.skipped}\n失败：${stats.failed}")
                .setPositiveButton(R.string.confirm, null)
                .show()
        }
    }

    private fun isUniqueConstraint(exception: Exception): Boolean {
        val message = exception.message ?: return false
        return message.contains("UNIQUE", ignoreCase = true) ||
            message.contains("constraint", ignoreCase = true)
    }

    private fun readTextAuto(context: Context, uri: Uri): String? {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return null
        if (bytes.isEmpty()) return ""

        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return Charset.forName("UTF-16LE")
                .decode(ByteBuffer.wrap(bytes, 2, bytes.size - 2)).toString()
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return Charset.forName("UTF-16BE")
                .decode(ByteBuffer.wrap(bytes, 2, bytes.size - 2)).toString()
        }

        var offset = 0
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            offset = 3
        }

        val slice = bytes.copyOfRange(offset, bytes.size)
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(slice))
                .toString()
        } catch (_: Exception) {
            try {
                Charset.forName("GB18030").decode(ByteBuffer.wrap(slice)).toString()
            } catch (_: Exception) {
                null
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private class ImportStats(
        var success: Int = 0,
        var skipped: Int = 0,
        var failed: Int = 0
    )
}
