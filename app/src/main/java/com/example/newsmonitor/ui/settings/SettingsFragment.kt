package com.example.newsmonitor.ui.settings

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.newsmonitor.R
import com.example.newsmonitor.data.AppDatabase
import com.example.newsmonitor.data.ConfigEntry
import com.example.newsmonitor.data.ConfigKeys
import com.example.newsmonitor.data.IntelTemplate
import com.example.newsmonitor.databinding.FragmentSettingsBinding
import com.example.newsmonitor.databinding.ItemIntelTemplateSettingsBinding
import com.example.newsmonitor.util.TextFileReader
import com.example.newsmonitor.util.toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val db by lazy { AppDatabase.get(requireContext().applicationContext) }

    private val templatePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { importTemplate(it) }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadConfig()
        binding.btnSave.setOnClickListener { saveConfig() }
        binding.btnUploadTemplate.setOnClickListener {
            templatePicker.launch(arrayOf("*/*"))
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                db.intelTemplateDao().observeAll().collect { list ->
                    renderTemplates(list)
                }
            }
        }
    }

    private fun loadConfig() {
        viewLifecycleOwner.lifecycleScope.launch {
            val values = withContext(Dispatchers.IO) {
                db.configDao().getAll().associate { it.k to (it.v ?: "") }
            }
            if (_binding == null) return@launch

            binding.etBaiduApiKey.setText(values[ConfigKeys.BAIDU_OCR_API_KEY].orEmpty())
            binding.etBaiduSecretKey.setText(values[ConfigKeys.BAIDU_OCR_SECRET_KEY].orEmpty())
            binding.etBaiduTranslateAppId.setText(
                values[ConfigKeys.BAIDU_TRANSLATE_APP_ID].orEmpty()
            )
            binding.etBaiduTranslateSecret.setText(
                values[ConfigKeys.BAIDU_TRANSLATE_SECRET_KEY].orEmpty()
            )
            binding.etWebhook.setText(values[ConfigKeys.DINGTALK_WEBHOOK].orEmpty())
            binding.etDeepseekKey.setText(values[ConfigKeys.DEEPSEEK_API_KEY].orEmpty())
            binding.etInterval.setText(
                values[ConfigKeys.POLL_INTERVAL] ?: ConfigKeys.DEFAULT_POLL_INTERVAL.toString()
            )
        }
    }

    private fun saveConfig() {
        val interval = binding.etInterval.text?.toString()?.trim()?.toIntOrNull()
        if (interval == null ||
            interval < ConfigKeys.MIN_POLL_INTERVAL ||
            interval > ConfigKeys.MAX_POLL_INTERVAL
        ) {
            binding.tilInterval.error = "请输入 1~1440 的整数"
            return
        }
        binding.tilInterval.error = null

        val entries = listOf(
            ConfigKeys.BAIDU_OCR_API_KEY to binding.etBaiduApiKey.text?.toString()?.trim().orEmpty(),
            ConfigKeys.BAIDU_OCR_SECRET_KEY to binding.etBaiduSecretKey.text?.toString()?.trim()
                .orEmpty(),
            ConfigKeys.BAIDU_TRANSLATE_APP_ID to binding.etBaiduTranslateAppId.text?.toString()?.trim()
                .orEmpty(),
            ConfigKeys.BAIDU_TRANSLATE_SECRET_KEY to binding.etBaiduTranslateSecret.text?.toString()
                ?.trim().orEmpty(),
            ConfigKeys.DINGTALK_WEBHOOK to binding.etWebhook.text?.toString()?.trim().orEmpty(),
            ConfigKeys.DEEPSEEK_API_KEY to binding.etDeepseekKey.text?.toString()?.trim().orEmpty(),
            ConfigKeys.POLL_INTERVAL to interval.toString()
        )

        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                entries.forEach { (key, value) ->
                    db.configDao().put(ConfigEntry(key, value))
                }
            }
            context?.toast("保存成功")
        }
    }

    private fun renderTemplates(list: List<IntelTemplate>) {
        if (_binding == null) return
        binding.llIntelTemplates.removeAllViews()
        binding.tvNoTemplates.isVisible = list.isEmpty()

        list.forEach { template ->
            val row = ItemIntelTemplateSettingsBinding.inflate(
                layoutInflater,
                binding.llIntelTemplates,
                false
            )
            row.tvTemplateName.text = template.name
            row.tvTemplatePreview.text = template.content
                .replace(WHITESPACE, " ")
                .trim()
                .take(120)
            row.btnTemplateView.setOnClickListener { showTemplate(template) }
            row.btnTemplateDelete.setOnClickListener { confirmDeleteTemplate(template) }
            binding.llIntelTemplates.addView(row.root)
        }
    }

    private fun showTemplate(template: IntelTemplate) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(template.name)
            .setMessage(template.content)
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun confirmDeleteTemplate(template: IntelTemplate) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("删除模板")
            .setMessage("确定删除模板“${template.name}”？")
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        db.intelTemplateDao().deleteById(template.id)
                    }
                    context?.toast("已删除模板")
                }
            }
            .show()
    }

    private fun importTemplate(uri: Uri) {
        if (view == null) return
        val appContext = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                TextFileReader.readTextAuto(appContext, uri, MAX_TEMPLATE_BYTES)
            }
            if (_binding == null) return@launch
            if (text == null) {
                context?.toast("读取失败，或文件超过 200KB")
                return@launch
            }
            val content = text.trim()
            if (content.isEmpty()) {
                context?.toast("模板内容为空")
                return@launch
            }

            val name = resolveTemplateName(appContext, uri)
            withContext(Dispatchers.IO) {
                db.intelTemplateDao().insert(
                    IntelTemplate(
                        name = name,
                        content = content,
                        createdAt = System.currentTimeMillis()
                    )
                )
            }
            context?.toast(getString(R.string.template_uploaded))
        }
    }

    private fun resolveTemplateName(context: Context, uri: Uri): String {
        val displayName = runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()

        val raw = displayName?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')
        val name = raw?.let { it.substringBeforeLast('.').trim() }
        return name?.takeIf { it.isNotBlank() } ?: "未命名模板"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val MAX_TEMPLATE_BYTES = 200_000
        private val WHITESPACE = Regex("\\s+")
    }
}
