package com.seeksky.toolbox.ui.theme

import android.content.Context
import android.content.res.Configuration
import com.seeksky.toolbox.R

enum class ThemeMode(val storageValue: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    companion object {
        fun fromStorageValue(value: String?): ThemeMode =
            entries.firstOrNull { it.storageValue == value } ?: SYSTEM
    }
}

object ThemePreferences {
    private const val PREFERENCES_NAME = "appearance_preferences"
    private const val KEY_THEME_MODE = "theme_mode"

    fun load(context: Context): ThemeMode = ThemeMode.fromStorageValue(
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getString(KEY_THEME_MODE, null)
    )

    fun save(context: Context, mode: ThemeMode) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME_MODE, mode.storageValue)
            .apply()
    }

    fun activityTheme(mode: ThemeMode): Int = when (mode) {
        ThemeMode.SYSTEM -> R.style.Theme_ToolBox
        ThemeMode.LIGHT -> R.style.Theme_ToolBox_Light
        ThemeMode.DARK -> R.style.Theme_ToolBox_Dark
    }

    fun isDark(context: Context, mode: ThemeMode = load(context)): Boolean = when (mode) {
        ThemeMode.SYSTEM -> {
            val nightMode = context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK
            nightMode == Configuration.UI_MODE_NIGHT_YES
        }
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
}
