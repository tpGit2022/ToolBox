package com.seeksky.toolbox

import android.app.NotificationManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.seeksky.toolbox.ui.theme.ToolBoxTheme
import com.seeksky.toolbox.ui.theme.ThemePreferences

class ReminderPopupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val themeMode = ThemePreferences.load(this)
        setTheme(ThemePreferences.activityTheme(themeMode))
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        val content = intent.getStringExtra(ReminderReceiver.EXTRA_CONTENT).orEmpty()
            .ifBlank { "提醒时间到了" }
        val notificationId = intent.getIntExtra(ReminderReceiver.EXTRA_NOTIFICATION_ID, 0)
        setContent {
            ToolBoxTheme(themeMode = themeMode) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(24.dp),
                                verticalArrangement = Arrangement.spacedBy(20.dp)
                            ) {
                                Text(
                                    text = "周期提醒",
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(content, style = MaterialTheme.typography.bodyLarge)
                                Button(
                                    modifier = Modifier.fillMaxWidth(),
                                    onClick = {
                                        getSystemService(NotificationManager::class.java).cancel(notificationId)
                                        finishAndRemoveTask()
                                    }
                                ) {
                                    Text("知道了")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
    }
}
