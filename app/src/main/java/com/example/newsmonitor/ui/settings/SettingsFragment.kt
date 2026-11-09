package com.example.newsmonitor.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.newsmonitor.data.AppDatabase
import com.example.newsmonitor.data.ConfigEntry
import com.example.newsmonitor.data.ConfigKeys
import com.example.newsmonitor.databinding.FragmentSettingsBinding
import com.example.newsmonitor.util.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val db by lazy { AppDatabase.get(requireContext().applicationContext) }

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
    }

    private fun loadConfig() {
        viewLifecycleOwner.lifecycleScope.launch {
            val values = withContext(Dispatchers.IO) {
                db.configDao().getAll().associate { it.k to (it.v ?: "") }
            }
            if (_binding == null) return@launch

            binding.etBaiduAppId.setText(values[ConfigKeys.BAIDU_OCR_APP_ID].orEmpty())
            binding.etBaiduApiKey.setText(values[ConfigKeys.BAIDU_OCR_API_KEY].orEmpty())
            binding.etBaiduSecretKey.setText(values[ConfigKeys.BAIDU_OCR_SECRET_KEY].orEmpty())
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
            ConfigKeys.BAIDU_OCR_APP_ID to binding.etBaiduAppId.text?.toString()?.trim().orEmpty(),
            ConfigKeys.BAIDU_OCR_API_KEY to binding.etBaiduApiKey.text?.toString()?.trim().orEmpty(),
            ConfigKeys.BAIDU_OCR_SECRET_KEY to binding.etBaiduSecretKey.text?.toString()?.trim()
                .orEmpty(),
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
