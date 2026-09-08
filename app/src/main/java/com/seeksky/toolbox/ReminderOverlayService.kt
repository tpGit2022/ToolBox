package com.seeksky.toolbox

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.seeksky.toolbox.ui.theme.ThemeMode
import com.seeksky.toolbox.ui.theme.ThemePreferences
import kotlin.math.roundToInt

class ReminderOverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private var overlay: View? = null
    private var currentContent: String? = null
    private var currentNotificationId = 0

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val content = intent?.getStringExtra(ReminderReceiver.EXTRA_CONTENT).orEmpty()
            .ifBlank { "提醒时间到了" }
        val notificationId = intent?.getIntExtra(ReminderReceiver.EXTRA_NOTIFICATION_ID, 0) ?: 0
        currentContent = content
        currentNotificationId = notificationId
        showOverlay(content, notificationId)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        removeOverlay()
        currentContent = null
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (ThemePreferences.load(this) == ThemeMode.SYSTEM) {
            currentContent?.let { showOverlay(it, currentNotificationId) }
        }
    }

    private fun showOverlay(content: String, notificationId: Int) {
        removeOverlay()
        val darkTheme = ThemePreferences.isDark(this)
        val cardColor = if (darkTheme) Color.rgb(32, 31, 36) else Color.WHITE
        val titleColor = if (darkTheme) Color.rgb(242, 239, 245) else Color.rgb(25, 25, 28)
        val contentColor = if (darkTheme) Color.rgb(218, 214, 221) else Color.rgb(45, 45, 50)
        val buttonColor = if (darkTheme) Color.rgb(208, 188, 255) else Color.rgb(103, 80, 164)
        val buttonTextColor = if (darkTheme) Color.rgb(56, 30, 114) else Color.WHITE
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            setBackgroundColor(Color.argb(150, 0, 0, 0))
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(20).toFloat()
                setColor(cardColor)
            }
        }
        card.addView(TextView(this).apply {
            text = "周期提醒"
            textSize = 26f
            setTextColor(titleColor)
        }, matchWidthWrapHeight())
        card.addView(TextView(this).apply {
            text = content
            textSize = 18f
            setTextColor(contentColor)
            setPadding(0, dp(20), 0, dp(20))
        }, matchWidthWrapHeight())
        card.addView(Button(this).apply {
            text = "知道了"
            backgroundTintList = ColorStateList.valueOf(buttonColor)
            setTextColor(buttonTextColor)
            setOnClickListener {
                getSystemService(NotificationManager::class.java).cancel(notificationId)
                stopSelf()
            }
        }, matchWidthWrapHeight())
        root.addView(card, LinearLayout.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        ))

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }
        windowManager.addView(root, params)
        overlay = root
    }

    private fun removeOverlay() {
        overlay?.let { runCatching { windowManager.removeView(it) } }
        overlay = null
    }

    private fun matchWidthWrapHeight() = LinearLayout.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.WRAP_CONTENT
    )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
}
