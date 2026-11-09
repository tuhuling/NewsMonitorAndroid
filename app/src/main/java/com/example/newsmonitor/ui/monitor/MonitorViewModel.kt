package com.example.newsmonitor.ui.monitor

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.example.newsmonitor.service.MonitorState
import kotlinx.coroutines.flow.StateFlow

class MonitorViewModel(application: Application) : AndroidViewModel(application) {

    val running: StateFlow<Boolean> = MonitorState.running
    val checkedSites: StateFlow<Int> = MonitorState.checkedSites
    val totalSites: StateFlow<Int> = MonitorState.totalSites
    val startedAt: StateFlow<Long?> = MonitorState.startedAt
    val lastDurationMs: StateFlow<Long> = MonitorState.lastDurationMs
}
