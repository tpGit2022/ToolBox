package com.seeksky.toolbox

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.client.android.Intents
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

@Composable
fun BarcodeScannerScreen() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var contents by rememberSaveable { mutableStateOf<String?>(null) }
    var format by rememberSaveable { mutableStateOf<String?>(null) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    var showPermissionSettings by rememberSaveable { mutableStateOf(false) }
    var scanInProgress by rememberSaveable { mutableStateOf(false) }

    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        scanInProgress = false
        val missingPermission = result.originalIntent
            ?.getBooleanExtra(Intents.Scan.MISSING_CAMERA_PERMISSION, false) == true
        showPermissionSettings = missingPermission
        if (result.contents != null) {
            contents = result.contents
            format = result.formatName
            message = null
        } else {
            message = if (missingPermission) {
                "未获得相机权限，请在应用设置中允许使用相机后重试。"
            } else {
                "未获取到内容。可重新扫描，已有结果不会被清除。"
            }
        }
    }

    fun launchScanner() {
        val options = ScanOptions()
            .setDesiredBarcodeFormats(BarcodeFormat.values().map { it.name })
            .setPrompt("对准二维码或条形码，识别后自动返回；音量键可开关补光灯")
            .setOrientationLocked(false)
            .setBeepEnabled(false)
            .setBarcodeImageEnabled(false)
            .addExtra(Intents.Scan.SHOW_MISSING_CAMERA_PERMISSION_DIALOG, false)
        message = null
        showPermissionSettings = false
        scanInProgress = true
        try {
            scanner.launch(options)
        } catch (_: RuntimeException) {
            scanInProgress = false
            message = "无法启动扫码，请检查相机是否可用后重试。"
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            launchScanner()
        } else {
            scanInProgress = false
            showPermissionSettings = true
            message = "扫码需要相机权限。可再次点击扫描授权，或前往应用设置开启相机权限。"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "二维码 / 条形码扫描",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "使用相机离线识别二维码和常见条形码（EAN、UPC、Code 128、Code 39、ITF 等），查看并复制原始内容。",
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = "仅在点击扫描时使用相机，不保存相机图片，也不会自动打开链接或执行扫码内容。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = !scanInProgress,
            onClick = {
                when {
                    !context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) -> {
                        message = "当前设备没有可用相机，无法扫描。"
                    }
                    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                        PackageManager.PERMISSION_GRANTED -> launchScanner()
                    else -> {
                        scanInProgress = true
                        permissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                }
            }
        ) {
            Text(if (contents == null) "开始扫描" else "再次扫描")
        }
        message?.let { status ->
            Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (showPermissionSettings) {
            OutlinedButton(
                onClick = {
                    try {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                .setData(Uri.fromParts("package", context.packageName, null))
                        )
                    } catch (_: RuntimeException) {
                        message = "无法打开应用设置，请手动在系统设置中开启百宝匣的相机权限。"
                    }
                }
            ) {
                Text("打开应用设置")
            }
        }
        val scannedContents = contents
        if (scannedContents == null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text("暂无扫描结果，请点击“开始扫描”。", modifier = Modifier.padding(16.dp))
            }
        } else {
            BarcodeScanResultCard(
                contents = scannedContents,
                format = format,
                onCopy = {
                    clipboard.setText(AnnotatedString(scannedContents))
                    Toast.makeText(context, "扫描内容已复制", Toast.LENGTH_SHORT).show()
                },
                onClear = {
                    contents = null
                    format = null
                    message = null
                }
            )
        }
    }
}

@Composable
internal fun BarcodeScanResultCard(
    contents: String,
    format: String?,
    onCopy: () -> Unit,
    onClear: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("扫描结果", style = MaterialTheme.typography.titleMedium)
            Text("码制：${format ?: "未知"}", style = MaterialTheme.typography.labelLarge)
            SelectionContainer {
                Text(
                    text = contents.ifEmpty { "（内容为空）" },
                    style = MaterialTheme.typography.bodyLarge
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onCopy) {
                    Text("复制内容")
                }
                OutlinedButton(onClick = onClear) {
                    Text("清除结果")
                }
            }
        }
    }
}
