package com.seeksky.toolbox

import android.Manifest
import android.app.AlarmManager
import android.app.DatePickerDialog
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun ReminderScreen() {
    val context = LocalContext.current
    val repository = remember { ReminderRepository(context) }
    var reminders by remember { mutableStateOf(repository.all()) }
    var content by remember { mutableStateOf("") }
    var triggerAt by remember { mutableLongStateOf(System.currentTimeMillis() + 5 * 60_000L) }
    var period by remember { mutableStateOf(ReminderPeriod.DAILY) }
    var periodMenuExpanded by remember { mutableStateOf(false) }
    var sound by remember { mutableStateOf(true) }
    var vibration by remember { mutableStateOf(true) }
    var popup by remember { mutableStateOf(true) }
    var notification by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Toast.makeText(
            context,
            if (granted) "通知权限已开启" else "未开启通知权限，通知和后台弹窗将不可用",
            Toast.LENGTH_LONG
        ).show()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("周期提醒", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            "到达设定时间后按周期重复提醒。铃声、震动、弹窗和通知可以自由组合。",
            style = MaterialTheme.typography.bodyMedium
        )

        ReminderPermissionCard(
            onRequestNotifications = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
            onRequestExactAlarm = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    runCatching {
                        context.startActivity(Intent(
                            Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            Uri.parse("package:${context.packageName}")
                        ))
                    }
                }
            },
            onRequestOverlay = {
                runCatching {
                    context.startActivity(Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    ))
                }
            },
            onRequestFullScreen = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    runCatching {
                        context.startActivity(Intent(
                            Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                            Uri.parse("package:${context.packageName}")
                        ))
                    }
                }
            }
        )

        Text("新建提醒", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = content,
            onValueChange = { if (it.length <= 500) content = it },
            label = { Text("提醒内容") },
            placeholder = { Text("请输入弹窗和通知中显示的内容") },
            minLines = 3,
            maxLines = 6,
            supportingText = { Text("${content.length}/500") }
        )

        Text("首次提醒时间", style = MaterialTheme.typography.titleMedium)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                modifier = Modifier.weight(1f),
                onClick = {
                    val calendar = Calendar.getInstance().apply { timeInMillis = triggerAt }
                    DatePickerDialog(
                        context,
                        { _, year, month, day ->
                            triggerAt = Calendar.getInstance().apply {
                                timeInMillis = triggerAt
                                set(Calendar.YEAR, year)
                                set(Calendar.MONTH, month)
                                set(Calendar.DAY_OF_MONTH, day)
                            }.timeInMillis
                        },
                        calendar.get(Calendar.YEAR),
                        calendar.get(Calendar.MONTH),
                        calendar.get(Calendar.DAY_OF_MONTH)
                    ).apply { datePicker.minDate = System.currentTimeMillis() - 1_000L }.show()
                }
            ) { Text(formatDate(triggerAt)) }
            OutlinedButton(
                modifier = Modifier.weight(1f),
                onClick = {
                    val calendar = Calendar.getInstance().apply { timeInMillis = triggerAt }
                    TimePickerDialog(
                        context,
                        { _, hour, minute ->
                            triggerAt = Calendar.getInstance().apply {
                                timeInMillis = triggerAt
                                set(Calendar.HOUR_OF_DAY, hour)
                                set(Calendar.MINUTE, minute)
                                set(Calendar.SECOND, 0)
                                set(Calendar.MILLISECOND, 0)
                            }.timeInMillis
                        },
                        calendar.get(Calendar.HOUR_OF_DAY),
                        calendar.get(Calendar.MINUTE),
                        true
                    ).show()
                }
            ) { Text(formatTime(triggerAt)) }
        }

        Text("重复周期", style = MaterialTheme.typography.titleMedium)
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = { periodMenuExpanded = true }
            ) { Text(period.label) }
            DropdownMenu(
                expanded = periodMenuExpanded,
                onDismissRequest = { periodMenuExpanded = false }
            ) {
                ReminderPeriod.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label) },
                        onClick = {
                            period = option
                            periodMenuExpanded = false
                        }
                    )
                }
            }
        }

        Text("提醒方式（可多选）", style = MaterialTheme.typography.titleMedium)
        Row(modifier = Modifier.fillMaxWidth()) {
            ReminderMethodOption("铃声", sound, { sound = it }, Modifier.weight(1f))
            ReminderMethodOption("震动", vibration, { vibration = it }, Modifier.weight(1f))
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            ReminderMethodOption("后台弹窗", popup, { popup = it }, Modifier.weight(1f))
            ReminderMethodOption("通知", notification, { notification = it }, Modifier.weight(1f))
        }
        Text(
            "后台弹窗在屏幕解锁时使用悬浮窗，锁屏时使用系统全屏提醒通知；未授权悬浮窗时会自动退回全屏通知方案。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                error = when {
                    content.isBlank() -> "请输入提醒内容"
                    triggerAt <= System.currentTimeMillis() -> "首次提醒时间必须晚于当前时间"
                    !sound && !vibration && !popup && !notification -> "请至少选择一种提醒方式"
                    else -> null
                }
                if (error == null) {
                    val reminder = ReminderSpec(
                        id = System.currentTimeMillis(),
                        content = content.trim(),
                        nextTriggerAt = triggerAt,
                        period = period,
                        sound = sound,
                        vibration = vibration,
                        popup = popup,
                        notification = notification,
                        anchorDayOfMonth = ReminderPeriod.dayOfMonth(triggerAt)
                    )
                    repository.upsert(reminder)
                    ReminderScheduler.schedule(context, reminder)
                    reminders = repository.all()
                    content = ""
                    Toast.makeText(context, "提醒已创建", Toast.LENGTH_SHORT).show()
                }
            }
        ) { Text("创建周期提醒") }

        Text("已创建的提醒", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        if (reminders.isEmpty()) {
            Text("暂无提醒", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            reminders.forEach { reminder ->
                ReminderCard(
                    reminder = reminder,
                    onEnabledChange = { enabled ->
                        val changed = reminder.copy(enabled = enabled)
                        repository.upsert(changed)
                        if (enabled) ReminderScheduler.schedule(context, changed)
                        else ReminderScheduler.cancel(context, reminder.id)
                        reminders = repository.all()
                    },
                    onDelete = {
                        ReminderScheduler.cancel(context, reminder.id)
                        repository.delete(reminder.id)
                        reminders = repository.all()
                    }
                )
            }
        }
    }
}

@Composable
private fun ReminderPermissionCard(
    onRequestNotifications: () -> Unit,
    onRequestExactAlarm: () -> Unit,
    onRequestOverlay: () -> Unit,
    onRequestFullScreen: () -> Unit
) {
    val context = LocalContext.current
    val notificationsGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
    val exactAlarmGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    val overlayGranted = Settings.canDrawOverlays(context)
    val fullScreenGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
        context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("权限状态", fontWeight = FontWeight.SemiBold)
            PermissionRow("通知/锁屏弹窗", notificationsGranted, onRequestNotifications)
            PermissionRow("精确时间提醒", exactAlarmGranted, onRequestExactAlarm)
            PermissionRow("解锁状态后台弹窗", overlayGranted, onRequestOverlay)
            PermissionRow("全屏后台弹窗", fullScreenGranted, onRequestFullScreen)
        }
    }
}

@Composable
private fun PermissionRow(label: String, granted: Boolean, onRequest: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text("$label：${if (granted) "已允许" else "未允许"}")
        if (!granted) TextButton(onClick = onRequest) { Text("去开启") }
    }
}

@Composable
private fun ReminderMethodOption(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(label)
    }
}

@Composable
private fun ReminderCard(
    reminder: ReminderSpec,
    onEnabledChange: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    reminder.content,
                    modifier = Modifier.weight(1f),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 3
                )
                Switch(checked = reminder.enabled, onCheckedChange = onEnabledChange)
            }
            Text("下次：${formatDateTime(reminder.nextTriggerAt)} · ${reminder.period.label}")
            val methods = buildList {
                if (reminder.sound) add("铃声")
                if (reminder.vibration) add("震动")
                if (reminder.popup) add("弹窗")
                if (reminder.notification) add("通知")
            }.joinToString("、")
            Text("方式：$methods", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onDelete, modifier = Modifier.align(Alignment.End)) {
                Text("删除")
            }
        }
    }
}

private fun formatDate(timeMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(timeMillis))

private fun formatTime(timeMillis: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timeMillis))

private fun formatDateTime(timeMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timeMillis))
