package com.seeksky.toolbox

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import com.seeksky.toolbox.ui.theme.ToolBoxTheme
import com.seeksky.toolbox.ui.theme.ThemeMode
import com.seeksky.toolbox.ui.theme.ThemePreferences

class MainActivity : ComponentActivity() {
    private var statusVersion by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        val initialThemeMode = ThemePreferences.load(this)
        setTheme(ThemePreferences.activityTheme(initialThemeMode))
        super.onCreate(savedInstanceState)
        val fileCryptoViewModel = ViewModelProvider(this)[FileCryptoViewModel::class.java]
        enableEdgeToEdge()
        setContent {
            var themeMode by remember { mutableStateOf(initialThemeMode) }
            ToolBoxTheme(themeMode = themeMode) {
                statusVersion // Recompose after returning from accessibility settings.
                ToolBoxApp(
                    fileCryptoViewModel = fileCryptoViewModel,
                    themeMode = themeMode,
                    onThemeModeChange = { newMode ->
                        if (newMode != themeMode) {
                            ThemePreferences.save(this, newMode)
                            themeMode = newMode
                            recreate()
                        }
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        statusVersion++
    }
}

@Composable
private fun ToolBoxApp(
    fileCryptoViewModel: FileCryptoViewModel,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            PrimaryScrollableTabRow(selectedTabIndex = selectedTab, edgePadding = 0.dp) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("手势") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("提醒") }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text("MP3") }
                )
                Tab(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    text = { Text("外观") }
                )
                Tab(
                    selected = selectedTab == 4,
                    onClick = { selectedTab = 4 },
                    text = { Text("EXIF") }
                )
                Tab(
                    selected = selectedTab == 5,
                    onClick = { selectedTab = 5 },
                    text = { Text("加密") }
                )
            }
            Box(modifier = Modifier.weight(1f)) {
                when (selectedTab) {
                    0 -> GestureToolScreen()
                    1 -> ReminderScreen()
                    2 -> Mp3MetadataScreen()
                    3 -> AppearanceScreen(themeMode, onThemeModeChange)
                    4 -> ExifScreen()
                    else -> FileCryptoScreen(fileCryptoViewModel)
                }
            }
        }
    }
}

@Composable
private fun GestureToolScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val saved = remember { GesturePreferences.load(context) }
    var clickCount by remember { mutableStateOf(saved.clickCount.toString()) }
    var clickInterval by remember { mutableStateOf(saved.clickIntervalMs.toString()) }
    var swipeCount by remember { mutableStateOf(saved.swipeCount.toString()) }
    var swipeInterval by remember { mutableStateOf(saved.swipeIntervalMs.toString()) }
    var validationMessage by remember { mutableStateOf<String?>(null) }
    val serviceEnabled = isGestureServiceEnabled(context)

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "自动手势工具",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "配置后启动悬浮控制器，可在任意应用中模拟点击、向上滑动和向下滑动。",
                style = MaterialTheme.typography.bodyMedium
            )

            ServiceStatusCard(
                enabled = serviceEnabled,
                onOpenSettings = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            )

            Text("点击设置", style = MaterialTheme.typography.titleMedium)
            NumberFieldRow(
                firstValue = clickCount,
                onFirstChange = { clickCount = it.filter(Char::isDigit) },
                firstLabel = "点击次数",
                secondValue = clickInterval,
                onSecondChange = { clickInterval = it.filter(Char::isDigit) },
                secondLabel = "点击间隔（毫秒）"
            )

            Text("滑动设置", style = MaterialTheme.typography.titleMedium)
            NumberFieldRow(
                firstValue = swipeCount,
                onFirstChange = { swipeCount = it.filter(Char::isDigit) },
                firstLabel = "滑动次数",
                secondValue = swipeInterval,
                onSecondChange = { swipeInterval = it.filter(Char::isDigit) },
                secondLabel = "滑动间隔（毫秒）"
            )
            Text(
                text = "间隔指一次手势完成后，到下一次手势开始前的等待时间；可填写 0。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            validationMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }

            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val result = GestureConfig.parse(
                        clickCount = clickCount,
                        clickIntervalMs = clickInterval,
                        swipeCount = swipeCount,
                        swipeIntervalMs = swipeInterval
                    )
                    if (result.isFailure) {
                        validationMessage = result.exceptionOrNull()?.message
                        return@Button
                    }
                    validationMessage = null
                    val config = result.getOrThrow()
                    GesturePreferences.save(context, config)
                    when {
                        !isGestureServiceEnabled(context) -> {
                            Toast.makeText(context, "请先开启 ToolBox 无障碍服务", Toast.LENGTH_LONG).show()
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }
                        !AutoGestureAccessibilityService.showController(config) -> {
                            Toast.makeText(context, "服务正在连接，请稍后再试", Toast.LENGTH_SHORT).show()
                        }
                        else -> Toast.makeText(context, "悬浮控制器已启动", Toast.LENGTH_SHORT).show()
                    }
                }
            ) {
                Text("保存并启动悬浮控制器")
            }

            Text(
                text = "使用方法：拖动蓝色“＋”到点击位置；悬浮控制器中的“点击 / 上滑 / 下滑”会按配置执行，“停止”可中止后续动作。",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ServiceStatusCard(enabled: Boolean, onOpenSettings: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.errorContainer
            }
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (enabled) "无障碍服务已开启" else "需要开启无障碍服务",
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "系统仅允许无障碍服务在其他应用中执行手势",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(Modifier.width(12.dp))
            OutlinedButton(onClick = onOpenSettings) {
                Text(if (enabled) "查看" else "去开启")
            }
        }
    }
}

@Composable
private fun NumberFieldRow(
    firstValue: String,
    onFirstChange: (String) -> Unit,
    firstLabel: String,
    secondValue: String,
    onSecondChange: (String) -> Unit,
    secondLabel: String
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            modifier = Modifier.weight(1f),
            value = firstValue,
            onValueChange = onFirstChange,
            label = { Text(firstLabel) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
        Spacer(Modifier.width(12.dp))
        OutlinedTextField(
            modifier = Modifier.weight(1.35f),
            value = secondValue,
            onValueChange = onSecondChange,
            label = { Text(secondLabel) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
    }
}

private fun isGestureServiceEnabled(context: Context): Boolean {
    val expected = "${context.packageName}/${AutoGestureAccessibilityService::class.java.name}"
    val shortExpected = "${context.packageName}/.${AutoGestureAccessibilityService::class.java.simpleName}"
    val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ).orEmpty()
    return enabledServices.split(':').any {
        it.equals(expected, ignoreCase = true) || it.equals(shortExpected, ignoreCase = true)
    }
}
