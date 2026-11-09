package com.example.newsmonitor.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object MonitorState {

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _checkedSites = MutableStateFlow(0)
    val checkedSites: StateFlow<Int> = _checkedSites.asStateFlow()

    private val _totalSites = MutableStateFlow(0)
    val totalSites: StateFlow<Int> = _totalSites.asStateFlow()

    private val _startedAt = MutableStateFlow<Long?>(null)
    val startedAt: StateFlow<Long?> = _startedAt.asStateFlow()

    private val _lastDurationMs = MutableStateFlow(0L)
    val lastDurationMs: StateFlow<Long> = _lastDurationMs.asStateFlow()

    fun onStarted() {
        _startedAt.value = System.currentTimeMillis()
        _running.value = true
        _checkedSites.value = 0
        _totalSites.value = 0
        _lastDurationMs.value = 0L
    }

    fun setProgress(checked: Int, total: Int) {
        _checkedSites.value = checked
        _totalSites.value = total
    }

    fun onStopped() {
        _startedAt.value?.let { start ->
            _lastDurationMs.value = (System.currentTimeMillis() - start).coerceAtLeast(0L)
        }
        _startedAt.value = null
        _running.value = false
    }
}
