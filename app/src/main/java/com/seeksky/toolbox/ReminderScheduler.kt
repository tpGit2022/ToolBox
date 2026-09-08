package com.seeksky.toolbox

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

object ReminderScheduler {
    const val EXTRA_REMINDER_ID = "reminder_id"

    fun schedule(context: Context, reminder: ReminderSpec) {
        if (!reminder.enabled) return
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val pendingIntent = alarmPendingIntent(context, reminder.id)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                reminder.nextTriggerAt,
                pendingIntent
            )
        } else {
            // Keep the reminder functional if exact-alarm access is denied.
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                reminder.nextTriggerAt,
                pendingIntent
            )
        }
    }

    fun cancel(context: Context, reminderId: Long) {
        context.getSystemService(AlarmManager::class.java)
            .cancel(alarmPendingIntent(context, reminderId))
    }

    fun restoreAll(context: Context) {
        val repository = ReminderRepository(context)
        val now = System.currentTimeMillis()
        repository.all().filter { it.enabled }.forEach { saved ->
            val reminder = saved.withNextTrigger(now)
            if (reminder != saved) repository.upsert(reminder)
            schedule(context, reminder)
        }
    }

    private fun alarmPendingIntent(context: Context, reminderId: Long): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra(EXTRA_REMINDER_ID, reminderId)
        }
        return PendingIntent.getBroadcast(
            context,
            reminderId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
