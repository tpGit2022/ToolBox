package com.seeksky.toolbox

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.cardemulation.CardEmulation
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay

@Composable
internal fun NfcScreen(viewModel: NfcViewModel, lifecycle: Lifecycle) {
    val context = LocalContext.current
    val activity = context.findActivity()
    val adapter = remember(context) { NfcAdapter.getDefaultAdapter(context) }
    val controller = viewModel.controllerInfo()
    var elapsedMillis by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var writeKind by rememberSaveable { mutableStateOf(NfcWriteKind.TEXT) }
    var writeValue by rememberSaveable { mutableStateOf("") }
    var writeType by rememberSaveable { mutableStateOf("text/plain") }
    var confirmReadOnly by remember { mutableStateOf(false) }
    var hceKind by rememberSaveable { mutableStateOf(viewModel.savedHceKind()) }
    var hceValue by rememberSaveable { mutableStateOf(viewModel.savedHceValue()) }
    var toolMode by rememberSaveable { mutableStateOf(NfcToolMode.READER) }

    LaunchedEffect(Unit) {
        while (true) {
            elapsedMillis = SystemClock.elapsedRealtime()
            delay(1_000L)
        }
    }

    DisposableEffect(viewModel) {
        onDispose {
            if (viewModel.pendingOperationDescription != null) viewModel.cancelPendingOperation()
        }
    }

    DisposableEffect(adapter, activity, lifecycle, controller.enabled, controller.hceSupported, toolMode) {
        var hcePreferred = false

        fun activateNfcMode() {
            if (adapter == null || activity == null || !controller.enabled) return
            if (toolMode == NfcToolMode.READER) {
                val flags = NfcAdapter.FLAG_READER_NFC_A or
                    NfcAdapter.FLAG_READER_NFC_B or
                    NfcAdapter.FLAG_READER_NFC_F or
                    NfcAdapter.FLAG_READER_NFC_V or
                    NfcAdapter.FLAG_READER_NFC_BARCODE or
                    NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS
                val options = Bundle().apply {
                    putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250)
                }
                runCatching {
                    adapter.enableReaderMode(activity, viewModel::onTagDiscovered, flags, options)
                }.onFailure {
                    viewModel.report("无法开启 NFC 前台读卡模式：${it.message ?: "系统拒绝请求"}")
                }
            } else if (controller.hceSupported) {
                hcePreferred = runCatching {
                    CardEmulation.getInstance(adapter).setPreferredService(
                        activity,
                        ComponentName(context, NfcHostApduService::class.java),
                    )
                }.getOrDefault(false)
                if (!hcePreferred) {
                    viewModel.report("系统未将本应用设为前台 HCE 服务；如有 AID 冲突，可能需要手动选择。")
                }
            }
        }

        fun deactivateNfcMode() {
            if (adapter == null || activity == null) return
            runCatching { adapter.disableReaderMode(activity) }
            if (hcePreferred) {
                runCatching { CardEmulation.getInstance(adapter).unsetPreferredService(activity) }
                hcePreferred = false
            }
        }

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> activateNfcMode()
                Lifecycle.Event.ON_PAUSE -> deactivateNfcMode()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) activateNfcMode()
        onDispose {
            lifecycle.removeObserver(observer)
            deactivateNfcMode()
        }
    }

    fun openNfcSettings() {
        runCatching { context.startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) }
            .recoverCatching { context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }
            .onFailure { viewModel.report("无法打开 NFC 设置，请手动进入系统设置。") }
    }

    if (confirmReadOnly) {
        AlertDialog(
            onDismissRequest = { confirmReadOnly = false },
            title = { Text("永久设为只读？") },
            text = { Text("这个操作通常不可撤销。设为只读后，该标签将无法再次写入或清空。确认后还需要重新贴近目标标签。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmReadOnly = false
                    viewModel.prepareMakeReadOnly()
                }) { Text("确认，准备设为只读") }
            },
            dismissButton = {
                TextButton(onClick = { confirmReadOnly = false }) { Text("取消") }
            },
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("NFC 工具", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }
        item {
            Text("读取和解析 NFC 标签、写入 NDEF 内容，并支持把本机模拟为可读取的 Type 4 NDEF 卡。所有处理均在设备本地完成。")
        }
        item {
            NfcControllerCard(controller, viewModel.hceRegistrationStatus(), ::openNfcSettings)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = toolMode == NfcToolMode.READER,
                    onClick = { toolMode = NfcToolMode.READER },
                    label = { Text("读写标签") },
                )
                FilterChip(
                    selected = toolMode == NfcToolMode.HCE,
                    onClick = {
                        if (viewModel.pendingOperationDescription != null) viewModel.cancelPendingOperation()
                        toolMode = NfcToolMode.HCE
                    },
                    enabled = controller.hceSupported,
                    label = { Text("卡模拟 HCE") },
                )
            }
        }

        if (toolMode == NfcToolMode.READER) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (viewModel.pendingOperationDescription != null) {
                            MaterialTheme.colorScheme.tertiaryContainer
                        } else {
                            MaterialTheme.colorScheme.primaryContainer
                        }
                    )
                ) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            viewModel.pendingOperationDescription?.let { "等待标签：$it" }
                                ?: when {
                                    viewModel.processingTag -> "正在处理标签…"
                                    !controller.enabled -> "读卡器未启用"
                                    else -> "读卡器已就绪"
                                },
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(viewModel.status, style = MaterialTheme.typography.bodySmall)
                        if (viewModel.pendingOperationDescription != null) {
                            OutlinedButton(onClick = viewModel::cancelPendingOperation) { Text("取消待执行操作") }
                        }
                    }
                }
            }

            viewModel.latestTag?.let { tag ->
                item { NfcTagCard(tag, elapsedMillis) }
            }

            item {
                Text("写入 NDEF 标签", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            item {
                Card {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("选择记录类型；点击准备写入后，再贴近目标标签。未格式化但支持 NDEF 的标签会自动格式化。")
                        NfcWriteKind.entries.chunked(2).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { kind ->
                                    FilterChip(
                                        selected = writeKind == kind,
                                        onClick = {
                                            writeKind = kind
                                            writeType = when (kind) {
                                                NfcWriteKind.MIME -> "text/plain"
                                                NfcWriteKind.EXTERNAL -> "example.com:toolbox"
                                                else -> ""
                                            }
                                        },
                                        label = { Text(kind.label) },
                                    )
                                }
                            }
                        }
                        OutlinedTextField(
                            value = writeValue,
                            onValueChange = { writeValue = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = {
                                Text(
                                    when (writeKind) {
                                        NfcWriteKind.TEXT -> "文本内容"
                                        NfcWriteKind.URI -> "网址、电话或邮件 URI"
                                        NfcWriteKind.MIME -> "MIME 数据（UTF-8）"
                                        NfcWriteKind.EXTERNAL -> "外部类型数据（UTF-8）"
                                    }
                                )
                            },
                            minLines = 2,
                            maxLines = 5,
                        )
                        if (writeKind == NfcWriteKind.MIME || writeKind == NfcWriteKind.EXTERNAL) {
                            OutlinedTextField(
                                value = writeType,
                                onValueChange = { writeType = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = {
                                    Text(if (writeKind == NfcWriteKind.MIME) "MIME 类型" else "外部类型 domain:type")
                                },
                                singleLine = true,
                            )
                        }
                        Button(
                            onClick = { viewModel.prepareWrite(writeKind, writeValue, writeType) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = controller.enabled && !viewModel.processingTag,
                        ) { Text("准备写入，等待贴标签") }
                        OutlinedButton(
                            onClick = viewModel::prepareEraseOrFormat,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = controller.enabled && !viewModel.processingTag,
                        ) { Text("清空 / 格式化为空 NDEF") }
                        Button(
                            onClick = { confirmReadOnly = true },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = controller.enabled && !viewModel.processingTag,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        ) { Text("永久设为只读…") }
                    }
                }
            }
        } else if (controller.hceSupported) {
            item {
                Text("HCE 卡模拟", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            item {
                Card {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("将本机模拟为只读 NFC Forum Type 4 NDEF 标签。另一台设备可读取下方文本或网址；HCE 无法模拟实体标签 UID、门禁卡或支付卡。")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(NfcWriteKind.TEXT, NfcWriteKind.URI).forEach { kind ->
                                FilterChip(
                                    selected = hceKind == kind,
                                    onClick = { hceKind = kind },
                                    label = { Text(kind.label) },
                                )
                            }
                        }
                        OutlinedTextField(
                            value = hceValue,
                            onValueChange = { hceValue = it.take(600) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(if (hceKind == NfcWriteKind.URI) "模拟的网址" else "模拟的文本") },
                            minLines = 2,
                            maxLines = 5,
                        )
                        Button(
                            onClick = { viewModel.saveHce(hceKind, hceValue) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = controller.enabled,
                        ) { Text("保存卡模拟内容") }
                        Text(viewModel.status, style = MaterialTheme.typography.bodySmall)
                        SelectionContainer {
                            Text(
                                "NDEF 应用 AID：${NfcHostApduService.NDEF_APPLICATION_AID}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }

        item {
            Text(
                "系统限制：Android Beam/点对点推送已被移除；支付、公交和门禁凭据通常由安全元件、厂商服务或密钥保护，普通应用不能读取或复制。低层标签命令依芯片协议而异，本页不会自动发送可能改写或锁死标签的私有命令。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NfcControllerCard(
    info: NfcControllerInfo,
    hceStatus: String,
    onOpenSettings: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = when {
                !info.supported -> MaterialTheme.colorScheme.errorContainer
                info.enabled -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.tertiaryContainer
            }
        )
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                when {
                    !info.supported -> "当前设备不支持 NFC"
                    info.enabled -> "NFC 已开启"
                    else -> "NFC 尚未开启"
                },
                fontWeight = FontWeight.SemiBold,
            )
            if (info.supported) {
                Text("HCE (ISO-DEP)：${nfcBoolean(info.hceSupported)} · HCE-F：${nfcBoolean(info.hceFSupported)}")
                Text("HCE 状态：$hceStatus", style = MaterialTheme.typography.bodySmall)
                info.secureNfcSupported?.let {
                    Text("安全 NFC：支持 ${nfcBoolean(it)} · 启用 ${info.secureNfcEnabled?.let(::nfcBoolean) ?: "未知"}")
                }
                info.observeModeSupported?.let {
                    Text("观察模式：支持 ${nfcBoolean(it)} · 启用 ${info.observeModeEnabled?.let(::nfcBoolean) ?: "未知"}")
                }
                info.readerOptionSupported?.let {
                    Text("系统 Reader Option：支持 ${nfcBoolean(it)} · 启用 ${info.readerOptionEnabled?.let(::nfcBoolean) ?: "未知"}")
                }
                info.powerSavingSupported?.let {
                    Text("NFC 省电模式：支持 ${nfcBoolean(it)} · 启用 ${info.powerSavingEnabled?.let(::nfcBoolean) ?: "未知"}")
                }
                info.exitFramesSupported?.let { Text("Exit Frame：支持 ${nfcBoolean(it)}") }
                info.readerModeAnnotationSupported?.let { Text("Reader Mode Annotation：支持 ${nfcBoolean(it)}") }
                info.tagIntentPreferenceSupported?.let {
                    Text("标签 Intent 应用偏好：支持 ${nfcBoolean(it)} · 当前允许 ${info.tagIntentAllowed?.let(::nfcBoolean) ?: "未知"}")
                }
                info.gestureExchangeAid?.let { Text("Gesture Exchange AID：$it", style = MaterialTheme.typography.bodySmall) }
                info.antennaDescription?.let { Text("天线信息：$it", style = MaterialTheme.typography.bodySmall) }
                OutlinedButton(onClick = onOpenSettings) { Text("打开 NFC 设置") }
            }
        }
    }
}

@Composable
private fun NfcTagCard(tag: NfcTagSnapshot, elapsedMillis: Long) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Card {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("最近读取的标签", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                SelectionContainer { Text("UID：${tag.idHex}") }
                Text("技术栈：${tag.technologies.joinToString().ifEmpty { "系统未提供" }}")
                Text("读取时间：${((elapsedMillis - tag.scannedAtElapsedMillis).coerceAtLeast(0L) / 1_000L)} 秒前")
                HorizontalDivider()
                if (tag.technologyDetails.isEmpty()) {
                    Text("未提供更多芯片参数。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    tag.technologyDetails.forEach { (label, value) ->
                        SelectionContainer { Text("$label：$value") }
                    }
                }
                HorizontalDivider()
                if (tag.ndefType == null) {
                    Text("NDEF：未格式化或不支持")
                } else {
                    Text("NDEF 类型：${tag.ndefType}", fontWeight = FontWeight.SemiBold)
                    Text("可写：${tag.ndefWritable?.let(::nfcBoolean) ?: "未知"}")
                    Text("可永久设为只读：${tag.ndefCanMakeReadOnly?.let(::nfcBoolean) ?: "未知"}")
                    Text("容量：${tag.ndefSizeBytes ?: 0} / ${tag.ndefMaxSizeBytes ?: 0} 字节")
                    Text("NDEF 记录：${tag.ndefRecords.size} 条")
                }
            }
        }
        tag.ndefRecords.forEach { record -> NfcRecordCard(record) }
        tag.rawNdefHex?.let { raw ->
            Card {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("原始 NDEF 消息", fontWeight = FontWeight.SemiBold)
                    SelectionContainer { Text(raw, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

@Composable
private fun NfcRecordCard(record: NfcRecordInfo) {
    Card {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("记录 ${record.index + 1} · ${record.kind}", fontWeight = FontWeight.SemiBold)
            Text("TNF：${record.tnf}")
            record.value?.let { SelectionContainer { Text("内容：$it") } }
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Type：${record.typeHex.ifEmpty { "（空）" }}", style = MaterialTheme.typography.bodySmall)
                    Text("ID：${record.idHex.ifEmpty { "（空）" }}", style = MaterialTheme.typography.bodySmall)
                    Text("Payload：${record.payloadHex.ifEmpty { "（空）" }}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private enum class NfcToolMode { READER, HCE }
