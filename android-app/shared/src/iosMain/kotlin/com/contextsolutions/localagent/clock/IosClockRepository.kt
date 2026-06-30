package com.contextsolutions.localagent.clock

import com.contextsolutions.localagent.platform.IosJsonStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * iOS [ClockRepository] (PR #41), the counterpart of desktop's
 * [DesktopClockRepository]. Timers and alarms are persisted as JSON-serialised
 * lists in an [IosJsonStore] file ([KEY_TIMERS] / [KEY_ALARMS]). State is seeded
 * from disk at construction; mutations update both the in-memory
 * [MutableStateFlow] and the file. A corrupt/incompatible blob is discarded on
 * load, matching the other platforms.
 */
class IosClockRepository(private val store: IosJsonStore) : ClockRepository {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }
    private val timerSerializer = ListSerializer(TimerEntry.serializer())
    private val alarmSerializer = ListSerializer(AlarmEntry.serializer())

    private val timersState = MutableStateFlow(loadTimers())
    private val alarmsState = MutableStateFlow(loadAlarms())

    override fun timers(): Flow<List<TimerEntry>> = timersState.asStateFlow()
    override fun alarms(): Flow<List<AlarmEntry>> = alarmsState.asStateFlow()

    override fun snapshotTimers(): List<TimerEntry> = timersState.value
    override fun snapshotAlarms(): List<AlarmEntry> = alarmsState.value

    override fun upsertTimer(timer: TimerEntry) {
        val updated = timersState.value.filterNot { it.id == timer.id } + timer
        timersState.value = updated
        persistTimers(updated)
    }

    override fun deleteTimer(id: String) {
        val updated = timersState.value.filterNot { it.id == id }
        if (updated.size == timersState.value.size) return
        timersState.value = updated
        persistTimers(updated)
    }

    override fun upsertAlarm(alarm: AlarmEntry) {
        val updated = alarmsState.value.filterNot { it.id == alarm.id } + alarm
        alarmsState.value = updated
        persistAlarms(updated)
    }

    override fun deleteAlarm(id: String) {
        val updated = alarmsState.value.filterNot { it.id == id }
        if (updated.size == alarmsState.value.size) return
        alarmsState.value = updated
        persistAlarms(updated)
    }

    private fun loadTimers(): List<TimerEntry> = try {
        store.getString(KEY_TIMERS)?.let { json.decodeFromString(timerSerializer, it) } ?: emptyList()
    } catch (_: Throwable) {
        emptyList()
    }

    private fun loadAlarms(): List<AlarmEntry> = try {
        store.getString(KEY_ALARMS)?.let { json.decodeFromString(alarmSerializer, it) } ?: emptyList()
    } catch (_: Throwable) {
        emptyList()
    }

    private fun persistTimers(list: List<TimerEntry>) {
        store.putString(KEY_TIMERS, json.encodeToString(timerSerializer, list))
    }

    private fun persistAlarms(list: List<AlarmEntry>) {
        store.putString(KEY_ALARMS, json.encodeToString(alarmSerializer, list))
    }

    private companion object {
        const val KEY_TIMERS = "timers_json"
        const val KEY_ALARMS = "alarms_json"
    }
}
