package com.example.newsmonitor.ui.monitor

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.newsmonitor.data.AppDatabase
import com.example.newsmonitor.data.LogEntry
import com.example.newsmonitor.service.MonitorState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class MonitorViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.get(application)

    val logs: StateFlow<List<LogEntry>> = db.logDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val running: StateFlow<Boolean> = MonitorState.running
    val checkedSites: StateFlow<Int> = MonitorState.checkedSites
    val totalSites: StateFlow<Int> = MonitorState.totalSites
    val startedAt: StateFlow<Long?> = MonitorState.startedAt
    val lastDurationMs: StateFlow<Long> = MonitorState.lastDurationMs
}
