package com.example.newsmonitor.ui.monitor

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.newsmonitor.R
import com.example.newsmonitor.data.AppDatabase
import com.example.newsmonitor.data.ConfigEntry
import com.example.newsmonitor.data.ConfigKeys
import com.example.newsmonitor.data.ConfigRepository
import com.example.newsmonitor.data.LogEntry
import com.example.newsmonitor.databinding.FragmentMonitorBinding
import com.example.newsmonitor.service.MonitorService
import com.example.newsmonitor.ui.common.ExportUtils
import com.example.newsmonitor.util.TimeUtils
import com.example.newsmonitor.util.toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MonitorFragment : Fragment() {

    private var _binding: FragmentMonitorBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MonitorViewModel by viewModels()
    private val db by lazy { AppDatabase.get(requireContext().applicationContext) }

    private lateinit var logAdapter: LogAdapter
    private var pendingStart = false

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                context?.toast("未授予通知权限，状态栏将不显示监测通知")
            }
            if (pendingStart) {
                pendingStart = false
                doStartMonitoring()
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMonitorBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        logAdapter = LogAdapter()
        binding.rvLogs.layoutManager = LinearLayoutManager(requireContext())
        binding.rvLogs.adapter = logAdapter

        binding.btnStart.setOnClickListener { startMonitoring() }
        binding.btnStop.setOnClickListener { stopMonitoring() }
        binding.btnClearLogs.setOnClickListener { confirmClearLogs() }
        binding.btnExportLogs.setOnClickListener { exportLogs() }

        viewLifecycleOwner.lifecycleScope.launch {
            val interval = withContext(Dispatchers.IO) {
                ConfigRepository(db.configDao()).getPollInterval()
            }
            binding.etInterval.setText(interval.toString())
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.running.collect { running -> updateRunningUi(running) }
                }
                launch {
                    combine(viewModel.checkedSites, viewModel.totalSites) { checked, total ->
                        checked to total
                    }.collect { (checked, total) ->
                        binding.tvProgress.text = "本次已监测 $checked / $total 站"
                    }
                }
                launch {
                    while (isActive) {
                        updateDuration()
                        delay(1000)
                    }
                }
                launch {
                    viewModel.logs.collect { logs ->
                        logAdapter.submitList(logs)
                        binding.tvNoLogs.isVisible = logs.isEmpty()
                    }
                }
            }
        }
    }

    private fun startMonitoring() {
        val minutes = binding.etInterval.text?.toString()?.trim()?.toIntOrNull()
        if (minutes == null ||
            minutes < ConfigKeys.MIN_POLL_INTERVAL ||
            minutes > ConfigKeys.MAX_POLL_INTERVAL
        ) {
            binding.tilInterval.error = "请输入 1~1440 的整数"
            return
        }
        binding.tilInterval.error = null

        viewLifecycleOwner.lifecycleScope.launch {
            val keywordCount = withContext(Dispatchers.IO) { db.keywordDao().count() }
            if (keywordCount == 0) {
                context?.toast("请先导入关键词")
                return@launch
            }

            withContext(Dispatchers.IO) {
                db.configDao().put(ConfigEntry(ConfigKeys.POLL_INTERVAL, minutes.toString()))
                db.logDao().insert(
                    LogEntry(
                        level = "INFO",
                        message = "监测已启动",
                        createdAt = TimeUtils.nowBeijingString()
                    )
                )
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    requireContext(),
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                pendingStart = true
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                doStartMonitoring()
            }
        }
    }

    private fun doStartMonitoring() {
        val context = context ?: return
        try {
            MonitorService.start(context)
        } catch (e: Exception) {
            context.toast("启动监测失败: ${e.message?.take(100)}")
        }
    }

    private fun stopMonitoring() {
        val appContext = context?.applicationContext ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                AppDatabase.get(appContext).logDao().insert(
                    LogEntry(
                        level = "INFO",
                        message = "监测已停止",
                        createdAt = TimeUtils.nowBeijingString()
                    )
                )
            }
        }
        MonitorService.stop(appContext)
    }

    private fun updateRunningUi(running: Boolean) {
        if (_binding == null) return
        binding.tvStatus.text = getString(if (running) R.string.running else R.string.stopped)
        binding.tvStatus.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (running) R.color.status_green else R.color.status_gray
            )
        )
        binding.btnStart.isEnabled = !running
        binding.btnStop.isEnabled = running
    }

    private fun updateDuration() {
        if (_binding == null) return
        val running = viewModel.running.value
        val durationMs = if (running) {
            val startedAt = viewModel.startedAt.value
            if (startedAt != null) System.currentTimeMillis() - startedAt else 0L
        } else {
            viewModel.lastDurationMs.value
        }
        binding.tvDuration.text = "本次运行时长 ${TimeUtils.formatDuration(durationMs)}"
    }

    private fun confirmClearLogs() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("清空日志")
            .setMessage("确定清空全部日志？")
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    withContext(Dispatchers.IO) { db.logDao().clear() }
                    context?.toast("日志已清空")
                }
            }
            .show()
    }

    private fun exportLogs() {
        val appContext = context?.applicationContext ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val logs = withContext(Dispatchers.IO) {
                AppDatabase.get(appContext).logDao().getAll()
            }
            if (logs.isEmpty()) {
                context?.toast("暂无日志")
                return@launch
            }
            val file = withContext(Dispatchers.IO) {
                ExportUtils.exportLogsTxt(appContext, logs)
            }
            context?.toast("已导出: ${file.absolutePath}")
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
