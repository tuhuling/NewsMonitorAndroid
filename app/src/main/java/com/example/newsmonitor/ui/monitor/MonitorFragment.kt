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
import androidx.recyclerview.widget.RecyclerView
import com.example.newsmonitor.R
import com.example.newsmonitor.data.AppDatabase
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

    /** 用户手动上滑查看历史日志时为 true，滑回列表底部后恢复自动滚动。 */
    private var userPausedAutoScroll = false

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
        binding.rvLogs.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                when (newState) {
                    RecyclerView.SCROLL_STATE_DRAGGING -> userPausedAutoScroll = true
                    RecyclerView.SCROLL_STATE_IDLE -> {
                        if (!recyclerView.canScrollVertically(1)) {
                            userPausedAutoScroll = false
                        }
                    }
                }
            }

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (!recyclerView.canScrollVertically(1)) {
                    userPausedAutoScroll = false
                }
            }
        })

        binding.btnStart.setOnClickListener { startMonitoring() }
        binding.btnStop.setOnClickListener { stopMonitoring() }
        binding.btnClearLogs.setOnClickListener { confirmClearLogs() }
        binding.btnExportLogs.setOnClickListener { exportLogs() }

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
                // 日志不再跟随数据库 Flow 每秒刷新，改为每 15 秒主动读取一次。
                launch {
                    while (isActive) {
                        refreshLogs()
                        delay(LOG_REFRESH_INTERVAL_MS)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 从「设置」页修改轮询间隔后返回本页时，重新读取并刷新文本。
        if (_binding != null) {
            refreshIntervalText()
        }
    }

    private fun refreshIntervalText() {
        viewLifecycleOwner.lifecycleScope.launch {
            val minutes = withContext(Dispatchers.IO) {
                ConfigRepository(db.configDao()).getPollInterval()
            }
            if (_binding == null) return@launch
            binding.tvInterval.text = getString(R.string.monitor_interval_info, minutes)
        }
    }

    private suspend fun refreshLogs() {
        val logs = withContext(Dispatchers.IO) { db.logDao().getAllAscending() }
        if (_binding == null) return
        binding.tvNoLogs.isVisible = logs.isEmpty()
        logAdapter.submitList(logs) {
            if (_binding == null || userPausedAutoScroll || logs.isEmpty()) return@submitList
            binding.rvLogs.post {
                if (_binding != null) {
                    binding.rvLogs.smoothScrollToPosition(logs.lastIndex)
                }
            }
        }
    }

    private fun startMonitoring() {
        viewLifecycleOwner.lifecycleScope.launch {
            val keywordCount = withContext(Dispatchers.IO) { db.keywordDao().count() }
            if (keywordCount == 0) {
                context?.toast("请先导入关键词")
                return@launch
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
                    refreshLogs()
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

    companion object {
        private const val LOG_REFRESH_INTERVAL_MS = 15_000L
    }
}
