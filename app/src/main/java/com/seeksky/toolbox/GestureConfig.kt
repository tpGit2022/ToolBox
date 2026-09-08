package com.seeksky.toolbox

import android.content.Context

data class GestureConfig(
    val clickCount: Int,
    val clickIntervalMs: Long,
    val swipeCount: Int,
    val swipeIntervalMs: Long
) {
    companion object {
        const val MAX_COUNT = 10_000
        const val MAX_INTERVAL_MS = 3_600_000L

        fun parse(
            clickCount: String,
            clickIntervalMs: String,
            swipeCount: String,
            swipeIntervalMs: String
        ): Result<GestureConfig> = runCatching {
            val parsedClickCount = clickCount.toIntOrNull()
                ?: throw IllegalArgumentException("请输入有效的点击次数")
            val parsedClickInterval = clickIntervalMs.toLongOrNull()
                ?: throw IllegalArgumentException("请输入有效的点击间隔")
            val parsedSwipeCount = swipeCount.toIntOrNull()
                ?: throw IllegalArgumentException("请输入有效的滑动次数")
            val parsedSwipeInterval = swipeIntervalMs.toLongOrNull()
                ?: throw IllegalArgumentException("请输入有效的滑动间隔")

            require(parsedClickCount in 1..MAX_COUNT) { "点击次数须为 1～$MAX_COUNT" }
            require(parsedSwipeCount in 1..MAX_COUNT) { "滑动次数须为 1～$MAX_COUNT" }
            require(parsedClickInterval in 0..MAX_INTERVAL_MS) {
                "点击间隔须为 0～$MAX_INTERVAL_MS 毫秒"
            }
            require(parsedSwipeInterval in 0..MAX_INTERVAL_MS) {
                "滑动间隔须为 0～$MAX_INTERVAL_MS 毫秒"
            }

            GestureConfig(
                clickCount = parsedClickCount,
                clickIntervalMs = parsedClickInterval,
                swipeCount = parsedSwipeCount,
                swipeIntervalMs = parsedSwipeInterval
            )
        }
    }
}

object GesturePreferences {
    private const val NAME = "gesture_config"

    fun load(context: Context): GestureConfig {
        val preferences = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        return GestureConfig(
            clickCount = preferences.getInt("click_count", 10),
            clickIntervalMs = preferences.getLong("click_interval_ms", 500L),
            swipeCount = preferences.getInt("swipe_count", 5),
            swipeIntervalMs = preferences.getLong("swipe_interval_ms", 800L)
        )
    }

    fun save(context: Context, config: GestureConfig) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt("click_count", config.clickCount)
            .putLong("click_interval_ms", config.clickIntervalMs)
            .putInt("swipe_count", config.swipeCount)
            .putLong("swipe_interval_ms", config.swipeIntervalMs)
            .apply()
    }
}
