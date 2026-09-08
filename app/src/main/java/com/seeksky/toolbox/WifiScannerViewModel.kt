package com.seeksky.toolbox

import android.Manifest
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.AndroidViewModel

class WifiScannerViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext
    private val wifiManager = context.getSystemService(WifiManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var registered = false
    internal var networks by mutableStateOf<List<WifiNetwork>>(emptyList())
        private set
    var scanning by mutableStateOf(false)
        private set
    var status by mutableStateOf("点击“开始扫描”查看附近 Wi-Fi。")
        private set

    private val timeout = Runnable {
        finishScan()
        readResults("扫描超时；以下为系统缓存，可能不是最新结果。")
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) return
            finishScan()
            val updated = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
            readResults(
                if (updated) "已收到系统扫描结果；各信号的采样时间见下方。"
                else "本次扫描未更新；以下为系统缓存，可能不是最新结果。"
            )
        }
    }

    fun hasLocationPermission(): Boolean = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    fun reportStatus(message: String) {
        status = message
    }

    fun startObserving() {
        if (registered) return
        try {
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
                ContextCompat.RECEIVER_EXPORTED
            )
            registered = true
        } catch (_: RuntimeException) {
            status = "无法监听 Wi-Fi 扫描结果，请退出此页面后重试。"
        }
        if (!hasLocationPermission()) networks = emptyList()
    }

    fun stopObserving() {
        if (registered) {
            context.unregisterReceiver(receiver)
            registered = false
        }
        if (scanning) status = "已停止等待扫描结果，请重新扫描。"
        finishScan()
    }

    @Suppress("DEPRECATION")
    fun scan() {
        if (scanning) return
        if (wifiManager == null || !context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI)) {
            status = "当前设备不支持 Wi-Fi 扫描。"
            return
        }
        if (!hasLocationPermission()) {
            networks = emptyList()
            status = "需要精确位置权限才能扫描 Wi-Fi，请在应用设置中授权。"
            return
        }
        try {
            if (!wifiManager.isWifiEnabled) {
                status = "请先在系统设置中开启 Wi-Fi；已有结果不会实时更新。"
                return
            }
            val locationManager = context.getSystemService(LocationManager::class.java)
            if (locationManager == null || !LocationManagerCompat.isLocationEnabled(locationManager)) {
                status = "请先开启系统定位开关，再重新扫描；已有结果不会实时更新。"
                return
            }
            startObserving()
            if (!registered) return
            scanning = true
            status = "正在扫描，请稍候…"
            if (wifiManager.startScan()) {
                handler.postDelayed(timeout, 20_000L)
            } else {
                finishScan()
                readResults("扫描请求未被系统接受（可能扫描过于频繁）；以下为缓存，请稍后重试。")
            }
        } catch (_: SecurityException) {
            finishScan()
            networks = emptyList()
            status = "扫描权限不可用，请检查精确位置权限和系统定位开关。"
        } catch (_: RuntimeException) {
            finishScan()
            status = "Wi-Fi 扫描暂不可用，请稍后重试；已有结果不会实时更新。"
        }
    }

    @Suppress("DEPRECATION")
    private fun readResults(message: String) {
        if (!hasLocationPermission()) {
            networks = emptyList()
            status = "位置权限未授予，无法读取扫描结果。"
            return
        }
        try {
            networks = wifiManager?.scanResults.orEmpty().map { result ->
                WifiNetwork(
                    ssid = result.SSID.orEmpty().ifEmpty { "（隐藏网络）" },
                    bssid = result.BSSID.orEmpty(),
                    frequencyMhz = result.frequency,
                    signalDbm = result.level,
                    standard = wifiStandard(result),
                    security = wifiSecurity(result.capabilities.orEmpty()),
                    timestampMicros = result.timestamp
                )
            }.sortedWith(compareByDescending<WifiNetwork> { it.signalDbm }.thenBy { it.bssid })
            status = if (networks.isEmpty()) "$message\n未发现可用 Wi-Fi 信号。" else message
        } catch (_: SecurityException) {
            networks = emptyList()
            status = "无法读取结果，请检查精确位置权限与系统定位开关。"
        } catch (_: RuntimeException) {
            status = "读取扫描结果失败；已有结果不会实时更新，请稍后重试。"
        }
    }

    private fun finishScan() {
        scanning = false
        handler.removeCallbacks(timeout)
    }

    override fun onCleared() {
        stopObserving()
        super.onCleared()
    }
}

private fun wifiStandard(result: ScanResult): String {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return "未知（Android 11 以下不提供协议）"
    return when (result.wifiStandard) {
        ScanResult.WIFI_STANDARD_LEGACY -> "802.11a/b/g（传统 Wi-Fi）"
        ScanResult.WIFI_STANDARD_11N -> "Wi-Fi 4（802.11n）"
        ScanResult.WIFI_STANDARD_11AC -> "Wi-Fi 5（802.11ac）"
        ScanResult.WIFI_STANDARD_11AX -> if (wifiBand(result.frequency) == "6 GHz") {
            "Wi-Fi 6E（802.11ax）"
        } else {
            "Wi-Fi 6（802.11ax）"
        }
        ScanResult.WIFI_STANDARD_11AD -> "WiGig（802.11ad）"
        ScanResult.WIFI_STANDARD_11BE -> "Wi-Fi 7（802.11be）"
        else -> "未知（设备未提供）"
    }
}
