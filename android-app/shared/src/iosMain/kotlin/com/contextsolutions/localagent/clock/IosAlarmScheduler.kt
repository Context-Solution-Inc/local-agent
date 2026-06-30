package com.contextsolutions.localagent.clock

/**
 * iOS no-op [AlarmScheduler] (PR #41). Alarms/timers are mobile-clock features
 * deferred on iOS this milestone (no `UNUserNotificationCenter`/`BGTaskScheduler`
 * wiring yet); the clock UI still works for viewing, but nothing is armed with the
 * OS. A real scheduler is a follow-up.
 */
class IosAlarmScheduler : AlarmScheduler {
    override fun scheduleTimer(timerId: String, fireAtEpochMs: Long) {}
    override fun cancelTimer(timerId: String) {}
    override fun scheduleAlarm(alarmId: String, fireAtEpochMs: Long) {}
    override fun cancelAlarm(alarmId: String) {}
    override fun stopFiringAlarm(alarmId: String) {}
}
