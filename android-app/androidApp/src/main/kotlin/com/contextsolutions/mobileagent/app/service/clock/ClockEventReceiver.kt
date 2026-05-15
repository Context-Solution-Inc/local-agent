package com.contextsolutions.mobileagent.app.service.clock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import com.contextsolutions.mobileagent.clock.ClockService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Receives PendingIntent broadcasts when AlarmManager fires a scheduled
 * timer or alarm. Two responsibilities:
 *
 *  1. **Timer fire** → post the firing notification directly (timers are
 *     a single chime, no repeating service needed).
 *  2. **Alarm fire** → start [AlarmFiringService] which loops the alarm
 *     sound + re-posts every 10 s until the user snoozes or dismisses.
 *
 * In both cases we call into [ClockService] so the persistence layer
 * reflects the firing (one-shot rows get cleaned up, recurring alarms get
 * their next occurrence armed).
 *
 * `@AndroidEntryPoint` lets us field-inject Hilt dependencies into a
 * BroadcastReceiver — Hilt provides a generated `onReceive` that wires
 * dependencies before delegating to ours. Per the Hilt docs this requires
 * the manifest declaration `<receiver ... android:exported="false">` plus
 * the manifest also listing it (broadcast receivers don't auto-register).
 */
@AndroidEntryPoint
class ClockEventReceiver : BroadcastReceiver() {

    @Inject lateinit var clockService: ClockService
    @Inject lateinit var clockNotifications: ClockNotifications

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        when (intent.action) {
            ACTION_TIMER_FIRE -> onTimerFire(context, id)
            ACTION_ALARM_FIRE -> onAlarmFire(context, id)
            else -> Log.w(TAG, "unexpected action ${intent.action}")
        }
    }

    private fun onTimerFire(context: Context, id: String) {
        val timer = clockService.timersSnapshot().firstOrNull { it.id == id }
        clockService.onTimerFired(id)
        // Briefly hold a wake lock so the notification + sound have a chance
        // to start even if the device was deep-sleeping. setExactAndAllowWhileIdle
        // delivers in Doze but the post-delivery window is short.
        val wake = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mobileagent:timer-fire")
        wake.acquire(WAKELOCK_MS)
        try {
            clockNotifications.postTimerFiredNotification(id, timer?.label)
        } finally {
            if (wake.isHeld) wake.release()
        }
    }

    private fun onAlarmFire(context: Context, id: String) {
        // ClockService.onAlarmFired handles re-arm for recurring; one-shot
        // gets deleted. The firing service is what makes noise.
        val alarm = clockService.alarmsSnapshot().firstOrNull { it.id == id }
        clockService.onAlarmFired(id)
        val service = Intent(context, AlarmFiringService::class.java).apply {
            action = AlarmFiringService.ACTION_START
            putExtra(AlarmFiringService.EXTRA_ALARM_ID, id)
            putExtra(AlarmFiringService.EXTRA_ALARM_LABEL, alarm?.label)
        }
        // Foreground service from a BroadcastReceiver requires startForegroundService.
        context.startForegroundService(service)
    }

    companion object {
        private const val TAG = "ClockEventReceiver"
        private const val WAKELOCK_MS = 2_000L

        const val ACTION_TIMER_FIRE = "com.contextsolutions.mobileagent.action.TIMER_FIRE"
        const val ACTION_ALARM_FIRE = "com.contextsolutions.mobileagent.action.ALARM_FIRE"
        const val EXTRA_ID = "id"
    }
}
