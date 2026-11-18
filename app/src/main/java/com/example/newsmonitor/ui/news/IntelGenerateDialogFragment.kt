package com.example.newsmonitor.ui.news

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.newsmonitor.R
import com.example.newsmonitor.data.AppDatabase
import com.example.newsmonitor.data.ConfigRepository
import com.example.newsmonitor.data.IntelTemplate
import com.example.newsmonitor.databinding.DialogIntelGenerateBinding
import com.example.newsmonitor.databinding.ItemIntelTemplateBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class IntelGenerateDialogFragment : DialogFragment() {

    private var _binding: DialogIntelGenerateBinding? = null
    private val binding get() = _binding!!

    private lateinit var db: AppDatabase
    private var templates: List<IntelTemplate> = emptyList()
    private var selectedTemplateId = -1L
    private var starting = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogIntelGenerateBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        db = AppDatabase.get(requireContext().applicationContext)

        val count = requireArguments().getInt(ARG_COUNT, 0)
        binding.tvIntelSubtitle.text =
            getString(R.string.generate_intel_selected_count, count)

        binding.btnIntelCancel.setOnClickListener { dismiss() }
        binding.btnIntelStart.setOnClickListener { startGenerate() }
        binding.btnViewSelectedTemplate.setOnClickListener { viewSelectedTemplate() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                db.intelTemplateDao().observeAll().collect { list ->
                    renderTemplates(list)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val display = resources.displayMetrics
        dialog?.window?.setLayout(
            (display.widthPixels * 0.92f).toInt(),
            (display.heightPixels * 0.85f).toInt()
        )
        dialog?.window?.setBackgroundDrawableResource(R.drawable.bg_dialog)
    }

    private fun renderTemplates(list: List<IntelTemplate>) {
        if (_binding == null) return
        checkedTemplateId().takeIf { it > 0 }?.let { selectedTemplateId = it }

        templates = list
        binding.rgTemplates.removeAllViews()
        binding.tvNoTemplates.isVisible = list.isEmpty()

        list.forEach { template ->
            val row = ItemIntelTemplateBinding.inflate(
                layoutInflater,
                binding.rgTemplates,
                false
            )
            row.rbTemplate.id = View.generateViewId()
            row.rbTemplate.text = template.name
            row.rbTemplate.tag = template.id
            row.rbTemplate.isChecked = template.id == selectedTemplateId
            binding.rgTemplates.addView(row.root)
        }
    }

    private fun checkedTemplateId(): Long {
        val checkedId = binding.rgTemplates.checkedRadioButtonId
        if (checkedId == View.NO_ID) return -1L
        val checked = binding.rgTemplates.findViewById<View>(checkedId) ?: return -1L
        return checked.tag as? Long ?: -1L
    }

    private fun checkedTemplate(): IntelTemplate? {
        val id = checkedTemplateId()
        if (id <= 0) return null
        return templates.firstOrNull { it.id == id }
    }

    private fun viewSelectedTemplate() {
        val template = checkedTemplate()
        if (template == null) {
            showError("请先选择一个模板")
            return
        }
        showTemplate(template)
    }

    private fun showTemplate(template: IntelTemplate) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(template.name)
            .setMessage(template.content)
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun startGenerate() {
        if (starting) return
        val requirement = binding.etIntelRequirement.text?.toString()?.trim().orEmpty()
        val template = checkedTemplate()
        if (requirement.isEmpty() && template == null) {
            showError("请输入生成要求或选择模板")
            return
        }
        val newsIds = requireArguments().getLongArray(ARG_NEWS_IDS) ?: longArrayOf()
        if (newsIds.isEmpty()) {
            showError("请先选择新闻")
            return
        }

        starting = true
        hideError()
        viewLifecycleOwner.lifecycleScope.launch {
            val apiKey = withContext(Dispatchers.IO) {
                ConfigRepository(db.configDao()).getDeepSeekApiKey()
            }
            if (_binding == null) return@launch
            if (apiKey.isNullOrBlank()) {
                starting = false
                showError("请先在设置中配置 DeepSeek API Key")
                return@launch
            }
            startActivity(
                IntelGenerateActivity.intent(
                    requireContext(),
                    newsIds,
                    requirement,
                    template?.id ?: -1L
                )
            )
            dismissAllowingStateLoss()
        }
    }

    private fun showError(message: String) {
        if (_binding == null) return
        binding.tvIntelError.text = message
        binding.tvIntelError.isVisible = true
    }

    private fun hideError() {
        if (_binding == null) return
        binding.tvIntelError.isVisible = false
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_NEWS_IDS = "arg_intel_news_ids"
        private const val ARG_COUNT = "arg_intel_count"

        fun newInstance(newsIds: LongArray): IntelGenerateDialogFragment {
            return IntelGenerateDialogFragment().apply {
                arguments = Bundle().apply {
                    putLongArray(ARG_NEWS_IDS, newsIds)
                    putInt(ARG_COUNT, newsIds.size)
                }
            }
        }
    }
}
