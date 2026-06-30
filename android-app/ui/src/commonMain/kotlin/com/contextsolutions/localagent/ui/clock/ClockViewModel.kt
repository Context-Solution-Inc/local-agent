package com.contextsolutions.localagent.ui.clock
import com.contextsolutions.localagent.platform.platformIoDispatcher

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.contextsolutions.localagent.clock.AlarmDay
import com.contextsolutions.localagent.clock.AlarmEntry
import com.contextsolutions.localagent.clock.ClockRepository
import com.contextsolutions.localagent.clock.ClockService
import com.contextsolutions.localagent.clock.TimerEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Surface state for the clock UI. The repository's StateFlows are the source
 * of truth; this view-model just exposes them on viewModelScope and forwards
 * user actions to [ClockService] (writes happen on [Dispatchers.IO] so the
 * SharedPreferences encode/commit doesn't touch the main thread).
 */
class ClockViewModel(
    private val clockService: ClockService,
    repository: ClockRepository,
) : ViewModel() {

    val timers: StateFlow<List<TimerEntry>> = repository.timers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), repository.snapshotTimers())

    // Always display earliest-first by time so toggling an alarm on/off never
    // reorders the list (the repositories append the upserted alarm to the end).
    val alarms: StateFlow<List<AlarmEntry>> = repository.alarms()
        .map { it.sortedWith(compareBy(AlarmEntry::hour, AlarmEntry::minute)) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            repository.snapshotAlarms().sortedWith(compareBy(AlarmEntry::hour, AlarmEntry::minute)),
        )

    fun createTimer(durationMs: Long, label: String?) {
        if (durationMs <= 0) return
        viewModelScope.launch(platformIoDispatcher) {
            clockService.createTimer(durationMs, label)
        }
    }

    fun extendTimer(id: String, extraMs: Long) {
        viewModelScope.launch(platformIoDispatcher) {
            clockService.extendTimer(id, extraMs)
        }
    }

    fun cancelTimer(id: String) {
        viewModelScope.launch(platformIoDispatcher) {
            clockService.cancelTimer(id)
        }
    }

    fun createAlarm(hour: Int, minute: Int, days: Set<AlarmDay>, label: String?) {
        viewModelScope.launch(platformIoDispatcher) {
            clockService.createAlarm(hour, minute, days, label)
        }
    }

    fun updateAlarm(alarm: AlarmEntry) {
        viewModelScope.launch(platformIoDispatcher) {
            clockService.updateAlarm(alarm)
        }
    }

    fun cancelAlarm(id: String) {
        viewModelScope.launch(platformIoDispatcher) {
            clockService.cancelAlarm(id)
        }
    }

    fun setAlarmEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch(platformIoDispatcher) {
            clockService.setAlarmEnabled(id, enabled)
        }
    }
}
