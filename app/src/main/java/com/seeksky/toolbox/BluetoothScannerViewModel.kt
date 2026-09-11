package com.seeksky.toolbox

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.AndroidViewModel

class BluetoothScannerViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext
    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter
    private val handler = Handler(Looper.getMainLooper())
    private val seen = linkedMapOf<String, NearbyBluetoothDevice>()
    private var receiverRegistered = false
    private var bleStarted = false
    private var classicStarted = false

    internal var devices by mutableStateOf<List<NearbyBluetoothDevice>>(emptyList())
        private set
    var scanning by mutableStateOf(false)
        private set
    var status by mutableStateOf("点击“开始扫描”查看附近蓝牙设备。")
        private set

    private val timeout = Runnable {
        finishScan("扫描完成。结果按最近一次 RSSI 从强到弱排列。")
    }

    private val classicReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device = intent.bluetoothDeviceExtra() ?: return
                    val rawRssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE)
                    val rssi = rawRssi.takeUnless { it == Short.MIN_VALUE }?.toInt()
                    record(classicSnapshot(device, intent.getStringExtra(BluetoothDevice.EXTRA_NAME), rssi))
                }

                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    classicStarted = false
                    if (scanning && !bleStarted) {
                        finishScan("经典蓝牙发现已完成。")
                    } else if (scanning) {
                        status = "经典蓝牙发现已完成，仍在接收 BLE 广播…"
                    }
                }

                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    if (scanning && (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF)) {
                        finishScan("蓝牙已关闭，扫描停止。")
                    }
                }
            }
        }
    }

    private val bleCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (scanning) record(bleSnapshot(result))
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            if (scanning) results.forEach { record(bleSnapshot(it)) }
        }

        override fun onScanFailed(errorCode: Int) {
            bleStarted = false
            val reason = bluetoothScanFailure(errorCode)
            if (scanning && classicStarted) {
                status = "BLE 扫描失败：$reason；经典蓝牙发现仍在继续。"
            } else if (scanning) {
                finishScan("蓝牙扫描失败：$reason")
            }
        }
    }

    fun requiredPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
    }

    fun hasRequiredPermissions(): Boolean = requiredPermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    fun reportStatus(message: String) {
        status = message
    }

    fun scan() {
        if (scanning) return
        val hasClassicBluetooth = context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)
        val hasBluetoothLe = context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
        if ((!hasClassicBluetooth && !hasBluetoothLe) || adapter == null) {
            status = "当前设备不支持蓝牙。"
            return
        }
        if (!hasRequiredPermissions()) {
            devices = emptyList()
            status = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                "需要“附近的设备”权限才能扫描并读取蓝牙设备信息。"
            } else {
                "Android 11 及以下需要精确位置权限才能扫描蓝牙设备。"
            }
            return
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R && !isLocationEnabled()) {
            status = "Android 11 及以下还需要开启系统定位开关，请开启后重试。"
            return
        }

        try {
            val currentAdapter = adapter ?: return
            if (!currentAdapter.isEnabled) {
                status = "蓝牙尚未开启，请先进入蓝牙设置开启。"
                return
            }

            cleanupActiveScan()
            seen.clear()
            devices = emptyList()
            registerClassicReceiver()
            scanning = true
            status = "正在启动 BLE 广播扫描和经典蓝牙发现…"

            bleStarted = try {
                startBleScan(currentAdapter)
            } catch (_: RuntimeException) {
                false
            }
            classicStarted = try {
                startClassicDiscovery(currentAdapter)
            } catch (_: RuntimeException) {
                false
            }
            if (!bleStarted && !classicStarted) {
                finishScan("系统未接受扫描请求，请确认蓝牙已开启并稍后重试。")
                return
            }

            status = when {
                bleStarted && classicStarted -> "正在扫描 BLE 广播和经典蓝牙设备…"
                bleStarted -> "正在扫描 BLE 广播；当前设备未启动经典蓝牙发现。"
                else -> "正在发现经典蓝牙设备；当前设备未启动 BLE 扫描。"
            }
            handler.postDelayed(timeout, SCAN_DURATION_MILLIS)
        } catch (_: SecurityException) {
            finishScan("蓝牙权限不可用，请检查“附近的设备”和位置权限。")
        } catch (_: RuntimeException) {
            finishScan("蓝牙扫描暂不可用，请稍后重试。")
        }
    }

    fun stopScan() {
        if (scanning) finishScan("扫描已停止，保留当前结果。")
    }

    private fun startBleScan(currentAdapter: BluetoothAdapter): Boolean {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) return false
        val scanner = currentAdapter.bluetoothLeScanner ?: return false
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0L)
            .build()
        scanner.startScan(null, settings, bleCallback)
        return true
    }

    @Suppress("DEPRECATION")
    private fun startClassicDiscovery(currentAdapter: BluetoothAdapter): Boolean {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)) return false
        if (currentAdapter.isDiscovering) currentAdapter.cancelDiscovery()
        return currentAdapter.startDiscovery()
    }

    private fun registerClassicReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        ContextCompat.registerReceiver(
            context,
            classicReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED,
        )
        receiverRegistered = true
    }

    private fun record(update: NearbyBluetoothDevice) {
        seen[update.address] = mergeBluetoothDevice(seen[update.address], update)
        devices = seen.values.sortedWith(
            compareByDescending<NearbyBluetoothDevice> { it.rssiDbm ?: Int.MIN_VALUE }
                .thenBy { it.displayName }
                .thenBy { it.address }
        )
    }

    private fun bleSnapshot(result: ScanResult): NearbyBluetoothDevice {
        val now = result.timestampNanos.takeIf { it > 0L }
            ?.div(1_000_000L)
            ?: SystemClock.elapsedRealtime()
        val record = result.scanRecord
        val device = result.device
        val deviceDetails = readDeviceDetails(device)
        val recordTxPower = record?.txPowerLevel?.takeUnless { it == Int.MIN_VALUE }
        val resultTxPower = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            result.txPower.takeUnless { it == ScanResult.TX_POWER_NOT_PRESENT }
        } else null

        val manufacturerData = buildList {
            val values = record?.manufacturerSpecificData ?: return@buildList
            for (index in 0 until values.size()) {
                val id = values.keyAt(index)
                val bytes = values.valueAt(index) ?: continue
                add(BluetoothDataEntry("$id (0x${id.toString(16).uppercase().padStart(4, '0')})", bluetoothBytesToHex(bytes)))
            }
        }
        val serviceData = record?.serviceData.orEmpty().map { (uuid, bytes) ->
            BluetoothDataEntry(uuid.toString(), bluetoothBytesToHex(bytes))
        }.sortedBy { it.key }

        val primaryPhy: String?
        val secondaryPhy: String?
        val advertisingSid: Int?
        val intervalMillis: Double?
        val connectable: Boolean?
        val legacy: Boolean?
        val dataStatus: String?
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            primaryPhy = bluetoothPhy(result.primaryPhy)
            secondaryPhy = bluetoothPhy(result.secondaryPhy).takeUnless { result.secondaryPhy == ScanResult.PHY_UNUSED }
            advertisingSid = result.advertisingSid.takeUnless { it == ScanResult.SID_NOT_PRESENT }
            intervalMillis = result.periodicAdvertisingInterval
                .takeUnless { it == ScanResult.PERIODIC_INTERVAL_NOT_PRESENT }
                ?.times(1.25)
            connectable = result.isConnectable
            legacy = result.isLegacy
            dataStatus = when (result.dataStatus) {
                ScanResult.DATA_COMPLETE -> "完整"
                ScanResult.DATA_TRUNCATED -> "已截断"
                else -> "未知（${result.dataStatus}）"
            }
        } else {
            primaryPhy = null
            secondaryPhy = null
            advertisingSid = null
            intervalMillis = null
            connectable = null
            legacy = null
            dataStatus = null
        }

        return NearbyBluetoothDevice(
            address = deviceDetails.address,
            systemName = deviceDetails.name,
            scanReportedName = record?.deviceName,
            alias = deviceDetails.alias,
            sources = setOf(BluetoothScanSource.BLE),
            rssiDbm = result.rssi,
            firstSeenElapsedMillis = now,
            lastSeenElapsedMillis = now,
            bondState = deviceDetails.bondState,
            deviceType = deviceDetails.deviceType,
            majorDeviceClass = deviceDetails.majorClass,
            deviceClassCode = deviceDetails.deviceClassCode,
            classServices = deviceDetails.classServices,
            serviceUuids = (
                deviceDetails.serviceUuids + record?.serviceUuids.orEmpty().map { it.toString() }
            ).distinct(),
            solicitationUuids = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                record?.serviceSolicitationUuids.orEmpty().map { it.toString() }
            } else emptyList(),
            manufacturerData = manufacturerData,
            serviceData = serviceData,
            advertisingFlags = record?.advertiseFlags?.takeUnless { it < 0 },
            txPowerDbm = resultTxPower ?: recordTxPower,
            primaryPhy = primaryPhy,
            secondaryPhy = secondaryPhy,
            advertisingSid = advertisingSid,
            periodicAdvertisingIntervalMillis = intervalMillis,
            connectable = connectable,
            legacyAdvertisement = legacy,
            advertisingDataStatus = dataStatus,
            rawAdvertisingDataHex = record?.bytes?.takeIf { it.isNotEmpty() }?.let(::bluetoothBytesToHex),
        )
    }

    private fun classicSnapshot(device: BluetoothDevice, reportedName: String?, rssi: Int?): NearbyBluetoothDevice {
        val now = SystemClock.elapsedRealtime()
        val details = readDeviceDetails(device)
        return NearbyBluetoothDevice(
            address = details.address,
            systemName = details.name,
            scanReportedName = reportedName,
            alias = details.alias,
            sources = setOf(BluetoothScanSource.CLASSIC),
            rssiDbm = rssi,
            firstSeenElapsedMillis = now,
            lastSeenElapsedMillis = now,
            bondState = details.bondState,
            deviceType = details.deviceType,
            majorDeviceClass = details.majorClass,
            deviceClassCode = details.deviceClassCode,
            classServices = details.classServices,
            serviceUuids = details.serviceUuids,
            solicitationUuids = emptyList(),
            manufacturerData = emptyList(),
            serviceData = emptyList(),
            advertisingFlags = null,
            txPowerDbm = null,
            primaryPhy = null,
            secondaryPhy = null,
            advertisingSid = null,
            periodicAdvertisingIntervalMillis = null,
            connectable = null,
            legacyAdvertisement = null,
            advertisingDataStatus = null,
            rawAdvertisingDataHex = null,
        )
    }

    private fun readDeviceDetails(device: BluetoothDevice): DeviceDetails {
        val address = runCatching { device.address }.getOrNull().orEmpty().ifBlank { "未知地址" }
        val name = runCatching { device.name }.getOrNull()
        val alias = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { device.alias }.getOrNull()
        } else null
        val bondState = runCatching {
            when (device.bondState) {
                BluetoothDevice.BOND_BONDED -> "已配对"
                BluetoothDevice.BOND_BONDING -> "正在配对"
                BluetoothDevice.BOND_NONE -> "未配对"
                else -> "未知"
            }
        }.getOrDefault("权限不足，无法读取")
        val deviceType = runCatching {
            when (device.type) {
                BluetoothDevice.DEVICE_TYPE_CLASSIC -> "经典蓝牙"
                BluetoothDevice.DEVICE_TYPE_LE -> "低功耗蓝牙（BLE）"
                BluetoothDevice.DEVICE_TYPE_DUAL -> "双模（经典 + BLE）"
                BluetoothDevice.DEVICE_TYPE_UNKNOWN -> "未知"
                else -> "未知（${device.type}）"
            }
        }.getOrDefault("权限不足，无法读取")
        val bluetoothClass = runCatching { device.bluetoothClass }.getOrNull()
        return DeviceDetails(
            address = address,
            name = name,
            alias = alias,
            bondState = bondState,
            deviceType = deviceType,
            majorClass = bluetoothClass?.let { bluetoothMajorClass(it.majorDeviceClass) },
            deviceClassCode = bluetoothClass?.deviceClass,
            classServices = bluetoothClassServices(bluetoothClass),
            serviceUuids = runCatching { device.uuids.orEmpty().map { it.toString() } }.getOrDefault(emptyList()),
        )
    }

    private fun finishScan(message: String) {
        cleanupActiveScan()
        scanning = false
        status = if (devices.isEmpty()) "$message\n未发现附近蓝牙设备。" else message
    }

    @Suppress("DEPRECATION")
    private fun cleanupActiveScan() {
        handler.removeCallbacks(timeout)
        if (hasRequiredPermissions()) {
            runCatching {
                if (bleStarted) adapter?.bluetoothLeScanner?.stopScan(bleCallback)
            }
            runCatching {
                if (adapter?.isDiscovering == true) adapter?.cancelDiscovery()
            }
        }
        bleStarted = false
        classicStarted = false
        if (receiverRegistered) {
            runCatching { context.unregisterReceiver(classicReceiver) }
            receiverRegistered = false
        }
    }

    private fun isLocationEnabled(): Boolean {
        val manager = context.getSystemService(LocationManager::class.java) ?: return false
        return LocationManagerCompat.isLocationEnabled(manager)
    }

    override fun onCleared() {
        cleanupActiveScan()
        super.onCleared()
    }

    private data class DeviceDetails(
        val address: String,
        val name: String?,
        val alias: String?,
        val bondState: String,
        val deviceType: String,
        val majorClass: String?,
        val deviceClassCode: Int?,
        val classServices: List<String>,
        val serviceUuids: List<String>,
    )

    companion object {
        private const val SCAN_DURATION_MILLIS = 18_000L
    }
}

@Suppress("DEPRECATION")
private fun Intent.bluetoothDeviceExtra(): BluetoothDevice? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
    } else {
        getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
    }

private fun bluetoothScanFailure(code: Int): String = when (code) {
    ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "扫描已经启动"
    ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "系统无法注册扫描器"
    ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "系统内部错误"
    ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "设备不支持该扫描方式"
    ScanCallback.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES -> "硬件扫描资源不足"
    ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY -> "扫描过于频繁，请稍后重试"
    else -> "错误码 $code"
}

private fun bluetoothPhy(value: Int): String = when (value) {
    0 -> "未使用"
    1 -> "LE 1M"
    2 -> "LE 2M"
    3 -> "LE Coded"
    else -> "未知（$value）"
}

private fun bluetoothMajorClass(value: Int): String = when (value) {
    BluetoothClass.Device.Major.MISC -> "杂项"
    BluetoothClass.Device.Major.COMPUTER -> "电脑"
    BluetoothClass.Device.Major.PHONE -> "手机"
    BluetoothClass.Device.Major.NETWORKING -> "网络设备"
    BluetoothClass.Device.Major.AUDIO_VIDEO -> "音频/视频设备"
    BluetoothClass.Device.Major.PERIPHERAL -> "外设"
    BluetoothClass.Device.Major.IMAGING -> "图像设备"
    BluetoothClass.Device.Major.WEARABLE -> "可穿戴设备"
    BluetoothClass.Device.Major.TOY -> "玩具"
    BluetoothClass.Device.Major.HEALTH -> "健康设备"
    BluetoothClass.Device.Major.UNCATEGORIZED -> "未分类"
    else -> "未知（0x${value.toString(16).uppercase()}）"
}

private fun bluetoothClassServices(bluetoothClass: BluetoothClass?): List<String> {
    bluetoothClass ?: return emptyList()
    return buildList {
        val candidates = listOf(
            BluetoothClass.Service.LIMITED_DISCOVERABILITY to "有限可发现",
            BluetoothClass.Service.POSITIONING to "定位",
            BluetoothClass.Service.NETWORKING to "网络",
            BluetoothClass.Service.RENDER to "渲染",
            BluetoothClass.Service.CAPTURE to "采集",
            BluetoothClass.Service.OBJECT_TRANSFER to "对象传输",
            BluetoothClass.Service.AUDIO to "音频",
            BluetoothClass.Service.TELEPHONY to "电话",
            BluetoothClass.Service.INFORMATION to "信息",
        )
        candidates.forEach { (flag, label) -> if (bluetoothClass.hasService(flag)) add(label) }
    }
}
