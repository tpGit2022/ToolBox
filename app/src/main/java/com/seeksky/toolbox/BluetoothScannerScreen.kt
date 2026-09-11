package com.seeksky.toolbox

import android.content.Intent
import android.net.Uri
import android.os.Build
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
fun BluetoothScannerScreen(viewModel: BluetoothScannerViewModel) {
    val context = LocalContext.current
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    var elapsedMillis by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            elapsedMillis = SystemClock.elapsedRealtime()
            delay(1_000L)
        }
    }
    DisposableEffect(viewModel) {
        onDispose { viewModel.stopScan() }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (viewModel.hasRequiredPermissions()) {
            viewModel.scan()
        } else {
            viewModel.reportStatus(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    "蓝牙扫描需要“附近的设备”权限。请重新授权或打开应用权限设置。"
                } else {
                    "Android 11 及以下的蓝牙扫描需要精确位置权限。请重新授权或打开应用权限设置。"
                }
            )
        }
    }

    fun openSettings(action: String) {
        try {
            context.startActivity(Intent(action).apply {
                if (action == Settings.ACTION_APPLICATION_DETAILS_SETTINGS) {
                    data = Uri.fromParts("package", context.packageName, null)
                }
            })
        } catch (_: RuntimeException) {
            viewModel.reportStatus("无法打开设置，请手动检查蓝牙、定位和应用权限。")
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("蓝牙信号扫描", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }
        item {
            Text("同时扫描 BLE 广播和经典蓝牙设备，按最近一次原始 RSSI（dBm）从强到弱排列。点开详情可查看系统返回的广播、PHY、设备类别、UUID 和原始数据。")
        }
        item {
            Text(
                "只被动扫描，不配对、不连接、不上传。蓝牙工作在 2.4 GHz 并会跳频，但 Android 扫描 API 不报告收到数据时的实际信道或频率，因此无法可靠展示信道号。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    if (viewModel.scanning) {
                        viewModel.stopScan()
                    } else if (viewModel.hasRequiredPermissions()) {
                        viewModel.scan()
                    } else {
                        permissionLauncher.launch(viewModel.requiredPermissions())
                    }
                },
            ) {
                Text(if (viewModel.scanning) "停止扫描" else "开始扫描 / 刷新")
            }
        }
        if (viewModel.scanning) {
            item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
        }
        item {
            Text(viewModel.status, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(onClick = { openSettings(Settings.ACTION_BLUETOOTH_SETTINGS) }) {
                    Text("蓝牙设置")
                }
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) {
                    OutlinedButton(onClick = { openSettings(Settings.ACTION_LOCATION_SOURCE_SETTINGS) }) {
                        Text("定位设置")
                    }
                }
                OutlinedButton(onClick = { openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS) }) {
                    Text("应用权限设置")
                }
            }
        }
        item {
            val bleCount = viewModel.devices.count { BluetoothScanSource.BLE in it.sources }
            val classicCount = viewModel.devices.count { BluetoothScanSource.CLASSIC in it.sources }
            Text("扫描结果：${viewModel.devices.size} 个设备（BLE $bleCount，经典蓝牙 $classicCount）")
        }
        items(viewModel.devices, key = { it.address }) { device ->
            BluetoothDeviceCard(
                device = device,
                elapsedMillis = elapsedMillis,
                expanded = expanded[device.address] == true,
                onToggleExpanded = { expanded[device.address] = expanded[device.address] != true },
            )
        }
    }
}

@Composable
internal fun BluetoothDeviceCard(
    device: NearbyBluetoothDevice,
    elapsedMillis: Long,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(device.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                device.rssiDbm?.let { "$it dBm · ${bluetoothRssiDescription(it)}" } ?: "RSSI：系统未提供",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text("来源：${device.sources.joinToString(" + ") { it.label }}")
            SelectionContainer {
                Text("地址：${device.address}", style = MaterialTheme.typography.bodySmall)
            }
            val ageSeconds = (elapsedMillis - device.lastSeenElapsedMillis).coerceAtLeast(0L) / 1_000L
            Text(
                "最近发现：$ageSeconds 秒前",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onToggleExpanded) {
                Text(if (expanded) "收起详情" else "查看全部详情")
            }

            if (expanded) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                BluetoothInfoLine("扫描报告名称", device.scanReportedName ?: "未提供")
                BluetoothInfoLine("系统缓存名称", device.systemName ?: "未提供")
                BluetoothInfoLine("用户别名", device.alias ?: "未设置或系统未提供")
                BluetoothInfoLine("设备类型", device.deviceType)
                BluetoothInfoLine("配对状态", device.bondState)
                BluetoothInfoLine("频段", "2.4 GHz ISM")
                BluetoothInfoLine("信道体系", bluetoothChannelPlan(device.sources))
                BluetoothInfoLine("射频信道/频率", "Android 扫描 API 未提供；蓝牙会跳频，不能由 RSSI 推算")
                BluetoothInfoLine("主设备类别", device.majorDeviceClass ?: "未提供")
                BluetoothInfoLine(
                    "设备类别原始值",
                    device.deviceClassCode?.let { "0x${it.toString(16).uppercase().padStart(4, '0')}" } ?: "未提供",
                )
                BluetoothInfoLine("类别服务能力", device.classServices.joinToString().ifEmpty { "未提供" })
                BluetoothInfoLine("首次发现", bluetoothAge(elapsedMillis, device.firstSeenElapsedMillis))
                BluetoothInfoLine("最近发现", bluetoothAge(elapsedMillis, device.lastSeenElapsedMillis))

                device.txPowerDbm?.let { txPower ->
                    BluetoothInfoLine("广播 Tx Power", "$txPower dBm")
                    device.rssiDbm?.let { rssi ->
                        BluetoothInfoLine("Tx Power 与 RSSI 差值", "${txPower - rssi} dB（不能直接当作距离）")
                    }
                }
                device.connectable?.let { BluetoothInfoLine("广播可连接", yesNo(it)) }
                device.legacyAdvertisement?.let { BluetoothInfoLine("传统 BLE 广播格式", yesNo(it)) }
                device.primaryPhy?.let { BluetoothInfoLine("主 PHY", it) }
                device.secondaryPhy?.let { BluetoothInfoLine("辅助 PHY", it) }
                device.advertisingSid?.let { BluetoothInfoLine("广播 SID", it.toString()) }
                device.periodicAdvertisingIntervalMillis?.let {
                    BluetoothInfoLine("周期广播间隔", String.format(Locale.ROOT, "%.2f ms", it))
                }
                device.advertisingDataStatus?.let { BluetoothInfoLine("广播数据状态", it) }
                device.advertisingFlags?.let {
                    BluetoothInfoLine("广播 Flags", "0x${it.toString(16).uppercase().padStart(2, '0')}")
                }

                BluetoothDetailList("服务 UUID", device.serviceUuids)
                BluetoothDetailList("Solicitation UUID", device.solicitationUuids)
                BluetoothDataList("厂商数据（厂商 ID）", device.manufacturerData)
                BluetoothDataList("Service Data（UUID）", device.serviceData)
                device.rawAdvertisingDataHex?.let {
                    Text("原始广播数据", fontWeight = FontWeight.SemiBold)
                    SelectionContainer {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text(
                    "提示：空字段表示设备没有广播该信息、当前发现方式不包含该信息，或系统因隐私策略未提供。随机地址也可能随时间变化。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun BluetoothInfoLine(label: String, value: String) {
    Text("$label：$value")
}

@Composable
private fun BluetoothDetailList(label: String, values: List<String>) {
    if (values.isEmpty()) return
    Text("$label：", fontWeight = FontWeight.SemiBold)
    SelectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            values.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun BluetoothDataList(label: String, values: List<BluetoothDataEntry>) {
    if (values.isEmpty()) return
    Text("$label：", fontWeight = FontWeight.SemiBold)
    SelectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            values.forEach { entry ->
                Text("${entry.key}：${entry.valueHex.ifEmpty { "（空）" }}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun bluetoothAge(nowElapsedMillis: Long, timestampElapsedMillis: Long): String {
    val seconds = (nowElapsedMillis - timestampElapsedMillis).coerceAtLeast(0L) / 1_000L
    return "$seconds 秒前"
}

private fun bluetoothChannelPlan(sources: Set<BluetoothScanSource>): String = buildList {
    if (BluetoothScanSource.BLE in sources) add("BLE 共 40 个 2 MHz 信道（37/38/39 为主广播信道）")
    if (BluetoothScanSource.CLASSIC in sources) add("经典蓝牙使用 79 个 1 MHz 跳频信道")
}.joinToString("；").ifEmpty { "未知" }

private fun yesNo(value: Boolean): String = if (value) "是" else "否"
