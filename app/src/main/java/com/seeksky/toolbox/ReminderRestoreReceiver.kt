package com.seeksky.toolbox

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ReminderRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> ReminderScheduler.restoreAll(context)
        }
    }
}
