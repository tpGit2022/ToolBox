package com.seeksky.toolbox

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun FileCryptoScreen(model: FileCryptoViewModel) {
    var encrypt by rememberSaveable { mutableStateOf(true) }
    var algorithm by rememberSaveable { mutableStateOf(FileCryptoAlgorithm.AES_256_GCM) }
    var headerText by rememberSaveable { mutableStateOf("16") }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    val available = remember { FileCryptoAlgorithm.entries.associateWith { it.isAvailable() } }
    val editable = !model.busy && model.prepared == null && !model.exportPickerOpen
    val headerBytes = headerText.toIntOrNull()
    val passwordValid = password.isNotEmpty() && (!encrypt || (password.length >= 8 && password == confirmation))
    val parametersValid = !encrypt || (headerBytes != null && headerBytes in 0..FileCrypto.MAX_HEADER_BYTES && available[algorithm] == true)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.select(uri)
    }
    val exportContract = remember(model) {
        object : ActivityResultContracts.CreateDocument("application/octet-stream") {
            override fun createIntent(context: Context, input: String): Intent =
                super.createIntent(context, input).setType(model.prepared?.mimeType ?: "application/octet-stream")
        }
    }
    val exporter = rememberLauncherForActivityResult(exportContract, model::export)

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("文件加密 / 解密", style = MaterialTheme.typography.headlineSmall)
        Text("处理任意类型文件，分块读写，不覆盖源文件。解密时自动识别文件尾的算法与规则。")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(enabled = editable && !encrypt, onClick = { encrypt = true; confirmation = "" }) { Text("加密数据") }
            OutlinedButton(enabled = editable && encrypt, onClick = { encrypt = false; confirmation = "" }) { Text("解密数据") }
        }
        OutlinedButton(enabled = editable, onClick = { picker.launch(arrayOf("*/*")) }) {
            Text(if (model.document == null) "选择文件" else "更换文件")
        }
        model.document?.let { document ->
            Text(document.name, style = MaterialTheme.typography.titleMedium)
            Text(formatCryptoSize(document.size))
        }
        if (encrypt) {
            Text("加密算法", style = MaterialTheme.typography.titleMedium)
            FileCryptoAlgorithm.entries.forEach { option ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = algorithm == option,
                        enabled = editable && available[option] == true,
                        onClick = { algorithm = option }
                    )
                    Text(option.label + if (available[option] == true) "" else "（当前设备不可用）")
                }
            }
            OutlinedTextField(
                value = headerText,
                onValueChange = { headerText = it },
                enabled = editable,
                label = { Text("保留源文件头（字节）") },
                supportingText = { Text("默认 16；范围 0～4096。设为 0 可隐藏全部源文件内容。") },
                isError = headerBytes == null || headerBytes !in 0..FileCrypto.MAX_HEADER_BYTES,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            enabled = editable,
            label = { Text(if (encrypt) "加密密码（至少 8 个字符）" else "解密密码") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        if (encrypt) {
            OutlinedTextField(
                value = confirmation,
                onValueChange = { confirmation = it },
                enabled = editable,
                label = { Text("再次输入密码") },
                isError = confirmation.isNotEmpty() && confirmation != password,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("使用须知", style = MaterialTheme.typography.titleMedium)
                Text("保留的文件头是公开明文，可能包含隐私；文件小于保留长度时，全部内容都会公开。只做魔数检测的服务可能识别类型，但不能保证通过完整格式校验、上传或正常预览。")
                Text("文件尾会公开原文件名、类型、大小及加密参数，不包含密码。请使用独立长密码；忘记密码无法恢复。")
                Text("结果需另存为新文件。解密前先验证完整性；临时结果保存在应用私有缓存，请及时保存或丢弃，并预留约两倍文件大小的空间。")
                Text("支持 ToolBox v1 格式，不直接兼容 TheBook 的旧加密文件。切换页面或旋转屏幕不影响进行中的任务；退出应用或进程被终止后不自动恢复。")
            }
        }
        Button(
            enabled = editable && model.document != null && passwordValid && parametersValid,
            onClick = {
                model.prepare(encrypt, password.toCharArray(), algorithm, headerBytes ?: 0)
                password = ""
                confirmation = ""
            }
        ) { Text(if (encrypt) "开始加密" else "开始解密") }
        if (model.busy) {
            val fraction = model.progress
            if (fraction == null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            }
            TextButton(onClick = model::cancel) { Text("取消任务") }
        }
        model.prepared?.let { result ->
            Text("待保存：${result.name}")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(enabled = !model.busy && !model.exportPickerOpen, onClick = {
                    if (model.beginExport()) exporter.launch(result.name)
                }) { Text("另存为新文件") }
                OutlinedButton(enabled = !model.busy && !model.exportPickerOpen, onClick = model::discard) { Text("丢弃结果") }
            }
        }
        if (model.status.isNotEmpty()) {
            Text(model.status, color = if (model.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
