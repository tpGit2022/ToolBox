package com.seeksky.toolbox

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

@Composable
fun WifiScannerScreen(viewModel: WifiScannerViewModel, lifecycle: Lifecycle) {
    val context = LocalContext.current
    var elapsedMillis by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                elapsedMillis = SystemClock.elapsedRealtime()
                delay(1_000L)
            }
        }
    }
    DisposableEffect(viewModel, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.startObserving()
                Lifecycle.Event.ON_STOP -> viewModel.stopObserving()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) viewModel.startObserving()
        onDispose {
            lifecycle.removeObserver(observer)
            viewModel.stopObserving()
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (viewModel.hasLocationPermission()) viewModel.scan()
        else viewModel.reportStatus("Wi-Fi 扫描需要精确位置权限，仅允许大致位置不够。请重试授权或打开应用权限设置。")
    }
    fun openSettings(action: String) {
        try {
            context.startActivity(Intent(action).apply {
                if (action == Settings.ACTION_APPLICATION_DETAILS_SETTINGS) {
                    data = Uri.fromParts("package", context.packageName, null)
                }
            })
        } catch (_: RuntimeException) {
            viewModel.reportStatus("无法打开设置，请手动进入系统设置检查 Wi-Fi、定位和应用权限。")
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Wi-Fi 信号扫描", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }
        item {
            Text("显示频段、协议、安全类型、主信道及系统报告的原始 RSSI（dBm），按信号从强到弱排序。-40 dBm 比 -80 dBm 更强，不使用百分比或信号格替代实际数值。")
        }
        item {
            Text(
                "扫描需要精确位置权限及系统定位开关。仅在此页面监听结果，不连接网络、不上传扫描信息。系统可能限制扫描频率，结果不是连续实时测量。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        item {
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = !viewModel.scanning,
                onClick = {
                    if (viewModel.hasLocationPermission()) viewModel.scan()
                    else permissionLauncher.launch(arrayOf(
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ))
                }
            ) {
                Text(if (viewModel.scanning) "正在扫描…" else "开始扫描 / 刷新")
            }
        }
        item { Text(viewModel.status, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(onClick = { openSettings(Settings.ACTION_WIFI_SETTINGS) }) { Text("Wi-Fi 设置") }
                OutlinedButton(onClick = { openSettings(Settings.ACTION_LOCATION_SOURCE_SETTINGS) }) { Text("定位设置") }
                OutlinedButton(onClick = { openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS) }) { Text("应用权限设置") }
            }
        }
        item { Text("扫描结果：${viewModel.networks.size} 个接入点（同名网络按 BSSID 区分）") }
        items(viewModel.networks) { network ->
            WifiNetworkCard(network, elapsedMillis)
        }
    }
}

@Composable
internal fun WifiNetworkCard(network: WifiNetwork, elapsedMillis: Long) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(network.ssid, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "${network.signalDbm} dBm",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Text("频段：${wifiBand(network.frequencyMhz)} · ${network.frequencyMhz} MHz")
            Text("协议：${network.standard}")
            Text("安全类型：${network.security}")
            Text("主信道：${wifiChannel(network.frequencyMhz)?.toString() ?: "未知"}")
            Text("BSSID：${network.bssid.ifEmpty { "未知" }}", style = MaterialTheme.typography.bodySmall)
            val ageSeconds = (elapsedMillis - network.timestampMicros / 1_000L).coerceAtLeast(0L) / 1_000L
            Text(
                if (network.timestampMicros > 0) "信号采样：$ageSeconds 秒前" else "信号采样时间：未知",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
