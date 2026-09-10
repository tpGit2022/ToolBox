package com.seeksky.toolbox

import android.Manifest
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reminderId = intent.getLongExtra(ReminderScheduler.EXTRA_REMINDER_ID, -1L)
        if (reminderId < 0) return
        val repository = ReminderRepository(context)
        val reminder = repository.find(reminderId) ?: return
        if (!reminder.enabled) return

        // Persist and arm the following occurrence before alerting the user.
        val next = reminder.copy(
            nextTriggerAt = reminder.period.nextAfter(
                reminder.nextTriggerAt,
                reminder.anchorDayOfMonth
            )
        )
            .withNextTrigger(System.currentTimeMillis())
        repository.upsert(next)
        ReminderScheduler.schedule(context, next)

        val showPopupAsOverlay = reminder.popup && canShowOverlayNow(context) &&
            showPopupOverlay(context, reminder)

        val needsFullScreenFallback = reminder.popup && !showPopupAsOverlay
        val needsNotification = reminder.notification || needsFullScreenFallback
        val notificationPosted = needsNotification && postNotification(
            context,
            reminder,
            fullScreen = needsFullScreenFallback
        )
        if (!notificationPosted) {
            if (reminder.sound) playSound(context)
            if (reminder.vibration) vibrate(context)
        }
    }

    private fun postNotification(
        context: Context,
        reminder: ReminderSpec,
        fullScreen: Boolean
    ): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return false
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false

        val manager = context.getSystemService(NotificationManager::class.java)
        val channelId = createChannel(manager, reminder.sound, reminder.vibration)
        val contentIntent = PendingIntent.getActivity(
            context,
            reminder.id.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notificationId = reminder.id.hashCode()
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("周期提醒")
            .setContentText(reminder.content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reminder.content))
            .setContentIntent(contentIntent)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            if (reminder.sound) builder.setSound(alarmSoundUri())
            if (reminder.vibration) builder.setVibrate(longArrayOf(0, 400, 250, 400))
        }

        if (fullScreen) {
            val popupIntent = Intent(context, ReminderPopupActivity::class.java).apply {
                data = Uri.parse("toolbox://reminder/${reminder.id}/${System.currentTimeMillis()}")
                putExtra(ReminderScheduler.EXTRA_REMINDER_ID, reminder.id)
                putExtra(EXTRA_CONTENT, reminder.content)
                putExtra(EXTRA_NOTIFICATION_ID, notificationId)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val popupPendingIntent = PendingIntent.getActivity(
                context,
                reminder.id.hashCode(),
                popupIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.setFullScreenIntent(popupPendingIntent, true)
        }

        manager.notify(notificationId, builder.build())
        return true
    }

    private fun canShowOverlayNow(context: Context): Boolean {
        if (!Settings.canDrawOverlays(context)) return false
        return !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
    }

    private fun showPopupOverlay(context: Context, reminder: ReminderSpec): Boolean =
        runCatching {
            context.startService(Intent(context, ReminderOverlayService::class.java).apply {
                putExtra(EXTRA_CONTENT, reminder.content)
                putExtra(EXTRA_NOTIFICATION_ID, reminder.id.hashCode())
            })
        }.isSuccess

    private fun createChannel(
        manager: NotificationManager,
        sound: Boolean,
        vibration: Boolean
    ): String {
        val channelId = "reminder_${if (sound) "sound" else "silent"}_${if (vibration) "vibrate" else "still"}"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager.getNotificationChannel(channelId) == null) {
            val channel = NotificationChannel(channelId, "周期提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "百宝匣周期提醒"
                enableVibration(vibration)
                vibrationPattern = if (vibration) longArrayOf(0, 400, 250, 400) else null
                if (sound) {
                    setSound(alarmSoundUri(), AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                } else {
                    setSound(null, null)
                }
            }
            manager.createNotificationChannel(channel)
        }
        return channelId
    }

    private fun playSound(context: Context) {
        runCatching { RingtoneManager.getRingtone(context, alarmSoundUri())?.play() }
    }

    private fun vibrate(context: Context) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(Vibrator::class.java)
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        val pattern = longArrayOf(0, 400, 250, 400)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, -1)
        }
    }

    private fun alarmSoundUri(): Uri =
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

    companion object {
        const val EXTRA_CONTENT = "reminder_content"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
    }
}
