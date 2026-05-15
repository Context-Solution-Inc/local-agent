package com.contextsolutions.mobileagent.app.service.clock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.contextsolutions.mobileagent.clock.ClockService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Re-arms every persisted timer + alarm after a device reboot. Without this,
 * AlarmManager loses all scheduled fires across reboot (Android does not
 * persist them itself).
 *
 * Also fires on `ACTION_MY_PACKAGE_REPLACED` so an app upgrade doesn't drop
 * scheduled alarms either — Android drops them on package replacement just
 * like on reboot.
 */
@AndroidEntryPoint
class ClockBootReceiver : BroadcastReceiver() {

    @Inject lateinit var clockService: ClockService

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                clockService.rearmAll()
            }
        }
    }
}
