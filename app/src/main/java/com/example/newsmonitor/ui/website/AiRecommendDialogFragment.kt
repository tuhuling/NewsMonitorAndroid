package com.example.newsmonitor.ui.website

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.newsmonitor.R
import com.example.newsmonitor.data.AppDatabase
import com.example.newsmonitor.data.ConfigRepository
import com.example.newsmonitor.databinding.DialogAiRecommendBinding
import com.example.newsmonitor.network.DeepSeekApi
import com.example.newsmonitor.util.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AiRecommendDialogFragment : DialogFragment() {

    private var _binding: DialogAiRecommendBinding? = null
    private val binding get() = _binding!!

    private lateinit var db: AppDatabase
    private lateinit var adapter: AiSiteAdapter
    private var generating = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogAiRecommendBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        db = AppDatabase.get(requireContext().applicationContext)
        adapter = AiSiteAdapter()

        binding.rvRecommendations.layoutManager = LinearLayoutManager(requireContext())
        binding.rvRecommendations.adapter = adapter

        binding.btnGenerate.setOnClickListener { generate() }
        binding.btnCancel.setOnClickListener { dismiss() }
        binding.btnImport.setOnClickListener { importSelected() }
    }

    override fun onStart() {
        super.onStart()
        val width = (resources.displayMetrics.widthPixels * 0.92f).toInt()
        dialog?.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog?.window?.setBackgroundDrawableResource(R.drawable.bg_dialog)
    }

    private fun generate() {
        if (generating) return
        val requirement = binding.etPrompt.text?.toString()?.trim().orEmpty()
        if (requirement.isEmpty()) {
            showError("请输入需求")
            return
        }

        generating = true
        binding.progress.isVisible = true
        binding.btnGenerate.isEnabled = false
        binding.btnImport.isEnabled = false
        binding.tvError.isVisible = false
        binding.tvPickHint.isVisible = false

        viewLifecycleOwner.lifecycleScope.launch {
            val apiKey = withContext(Dispatchers.IO) {
                ConfigRepository(db.configDao()).getDeepSeekApiKey().orEmpty()
            }
            if (apiKey.isBlank()) {
                resetLoading()
                showError("请先在设置中配置 DeepSeek API Key")
                return@launch
            }

            val result = DeepSeekApi.recommend(apiKey, requirement)
            resetLoading()

            result.onSuccess { sites ->
                adapter.submitList(sites)
                binding.tvPickHint.isVisible = sites.isNotEmpty()
                binding.btnImport.isEnabled = sites.isNotEmpty()
                if (sites.isEmpty()) {
                    showError("AI 未返回可用网站")
                }
            }.onFailure { error ->
                binding.btnImport.isEnabled = false
                showError(error.message ?: "AI 返回格式异常，请重试")
            }
        }
    }

    private fun importSelected() {
        val selected = adapter.selectedSites()
        if (selected.isEmpty()) {
            showError("请先勾选网站")
            return
        }
        val parent = parentFragment as? WebsiteFragment
        if (parent == null) {
            dismiss()
            return
        }
        dismiss()
        parent.importRecommendedSites(selected)
    }

    private fun resetLoading() {
        generating = false
        if (_binding == null) return
        binding.progress.isVisible = false
        binding.btnGenerate.isEnabled = true
    }

    private fun showError(message: String) {
        if (_binding == null) return
        binding.tvError.text = message
        binding.tvError.isVisible = true
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
