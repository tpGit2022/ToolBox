package com.seeksky.toolbox

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import kotlin.math.min

enum class ReminderPeriod(val label: String) {
    DAILY("每天"),
    THREE_DAYS("每三天"),
    FIVE_DAYS("每五天"),
    WEEKLY("每周"),
    MONTHLY("每月"),
    QUARTERLY("每季度");

    fun nextAfter(timeMillis: Long, anchorDayOfMonth: Int = dayOfMonth(timeMillis)): Long =
        Calendar.getInstance().run {
            timeInMillis = timeMillis
            when (this@ReminderPeriod) {
                DAILY -> add(Calendar.DAY_OF_YEAR, 1)
                THREE_DAYS -> add(Calendar.DAY_OF_YEAR, 3)
                FIVE_DAYS -> add(Calendar.DAY_OF_YEAR, 5)
                WEEKLY -> add(Calendar.WEEK_OF_YEAR, 1)
                MONTHLY -> addCalendarMonths(1, anchorDayOfMonth)
                QUARTERLY -> addCalendarMonths(3, anchorDayOfMonth)
            }
            timeInMillis
        }

    fun firstAfter(timeMillis: Long, nowMillis: Long, anchorDayOfMonth: Int): Long {
        var result = timeMillis
        while (result <= nowMillis) result = nextAfter(result, anchorDayOfMonth)
        return result
    }

    private fun Calendar.addCalendarMonths(months: Int, anchorDayOfMonth: Int) {
        set(Calendar.DAY_OF_MONTH, 1)
        add(Calendar.MONTH, months)
        set(Calendar.DAY_OF_MONTH, min(anchorDayOfMonth, getActualMaximum(Calendar.DAY_OF_MONTH)))
    }

    companion object {
        fun dayOfMonth(timeMillis: Long): Int = Calendar.getInstance().run {
            this.timeInMillis = timeMillis
            get(Calendar.DAY_OF_MONTH)
        }
    }
}

data class ReminderSpec(
    val id: Long,
    val content: String,
    val nextTriggerAt: Long,
    val period: ReminderPeriod,
    val sound: Boolean,
    val vibration: Boolean,
    val popup: Boolean,
    val notification: Boolean,
    val anchorDayOfMonth: Int,
    val enabled: Boolean = true
) {
    fun withNextTrigger(nowMillis: Long): ReminderSpec = copy(
        nextTriggerAt = period.firstAfter(nextTriggerAt, nowMillis, anchorDayOfMonth)
    )
}

class ReminderRepository(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun all(): List<ReminderSpec> {
        val array = runCatching {
            JSONArray(preferences.getString(KEY_REMINDERS, "[]"))
        }.getOrElse { JSONArray() }
        return buildList {
            for (index in 0 until array.length()) {
                runCatching { fromJson(array.getJSONObject(index)) }.getOrNull()?.let(::add)
            }
        }.sortedBy { it.nextTriggerAt }
    }

    fun find(id: Long): ReminderSpec? = all().firstOrNull { it.id == id }

    fun upsert(reminder: ReminderSpec) {
        val reminders = all().toMutableList()
        val index = reminders.indexOfFirst { it.id == reminder.id }
        if (index >= 0) reminders[index] = reminder else reminders += reminder
        write(reminders)
    }

    fun delete(id: Long) {
        write(all().filterNot { it.id == id })
    }

    private fun write(reminders: List<ReminderSpec>) {
        val array = JSONArray()
        reminders.forEach { array.put(toJson(it)) }
        preferences.edit().putString(KEY_REMINDERS, array.toString()).apply()
    }

    private fun toJson(reminder: ReminderSpec) = JSONObject().apply {
        put("id", reminder.id)
        put("content", reminder.content)
        put("nextTriggerAt", reminder.nextTriggerAt)
        put("period", reminder.period.name)
        put("sound", reminder.sound)
        put("vibration", reminder.vibration)
        put("popup", reminder.popup)
        put("notification", reminder.notification)
        put("anchorDayOfMonth", reminder.anchorDayOfMonth)
        put("enabled", reminder.enabled)
    }

    private fun fromJson(json: JSONObject) = ReminderSpec(
        id = json.getLong("id"),
        content = json.getString("content"),
        nextTriggerAt = json.getLong("nextTriggerAt"),
        period = ReminderPeriod.valueOf(json.getString("period")),
        sound = json.optBoolean("sound"),
        vibration = json.optBoolean("vibration"),
        popup = json.optBoolean("popup"),
        notification = json.optBoolean("notification"),
        anchorDayOfMonth = json.optInt(
            "anchorDayOfMonth",
            ReminderPeriod.dayOfMonth(json.getLong("nextTriggerAt"))
        ),
        enabled = json.optBoolean("enabled", true)
    )

    companion object {
        private const val PREFERENCES_NAME = "periodic_reminders"
        private const val KEY_REMINDERS = "reminders"
    }
}
