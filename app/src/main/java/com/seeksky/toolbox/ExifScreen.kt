package com.seeksky.toolbox

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Locale

@Composable
fun ExifScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedUriText by rememberSaveable { mutableStateOf<String?>(null) }
    var selectionVersion by rememberSaveable { mutableIntStateOf(0) }
    var image by remember { mutableStateOf<ExifImage?>(null) }
    var editor by remember { mutableStateOf(ExifEditable()) }
    var editorExpanded by rememberSaveable { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<ExifMessage?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            selectedUriText = uri.toString()
            selectionVersion++
            editorExpanded = false
        }
    }

    LaunchedEffect(selectedUriText, selectionVersion) {
        val uri = selectedUriText?.let(Uri::parse) ?: return@LaunchedEffect
        loading = true
        message = null
        image = null
        val result = withContext(Dispatchers.IO) { ExifMetadata.read(context, uri) }
        result.fold(
            onSuccess = {
                image = it
                editor = it.editable
            },
            onFailure = { message = ExifMessage(friendlyExifError(it), true) }
        )
        loading = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "图片 EXIF",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "查看图片中的拍摄设备、参数、时间和位置，也可修改常用元数据。图片只在本机处理。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            enabled = !loading && !saving,
            onClick = { picker.launch(arrayOf("image/*")) }
        ) {
            Text(if (selectedUriText == null) "选择图片" else "重新选择图片")
        }

        if (loading) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator()
            }
        }

        message?.let { status ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (status.isError) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.primaryContainer
                    }
                )
            ) {
                Text(status.text, modifier = Modifier.padding(14.dp))
            }
        }

        if (selectedUriText == null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "请选择 JPEG、PNG、WebP 或 HEIF 图片。能否保存修改取决于图片格式及其来源是否允许写入。",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        image?.let { selectedImage ->
            selectedImage.preview?.let { bitmap ->
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "所选图片预览",
                    modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp),
                    contentScale = ContentScale.Fit
                )
            }

            ImageInfoCard(selectedImage)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("编辑常用信息", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = { editorExpanded = !editorExpanded }) {
                    Text(if (editorExpanded) "收起" else "编辑")
                }
            }

            if (editorExpanded) {
                ExifEditor(
                    value = editor,
                    enabled = !saving,
                    onValueChange = {
                        editor = it
                        message = null
                    }
                )
                Text(
                    text = "将字段留空并保存，可移除该字段；经纬度需同时填写或同时留空。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        enabled = !saving,
                        onClick = {
                            editor = selectedImage.editable
                            message = null
                        }
                    ) { Text("还原") }
                    Button(
                        modifier = Modifier.weight(1f),
                        enabled = !saving,
                        onClick = {
                            val uri = selectedUriText?.let(Uri::parse) ?: return@Button
                            ExifMetadata.validate(editor)?.let {
                                message = ExifMessage(it, true)
                                return@Button
                            }
                            scope.launch {
                                saving = true
                                message = null
                                val saveResult = withContext(Dispatchers.IO) {
                                    ExifMetadata.save(context, uri, editor)
                                }
                                if (saveResult.isFailure) {
                                    message = ExifMessage(
                                        friendlyExifError(saveResult.exceptionOrNull()!!),
                                        true
                                    )
                                    saving = false
                                    return@launch
                                }

                                val refreshed = withContext(Dispatchers.IO) {
                                    ExifMetadata.read(context, uri)
                                }
                                refreshed.onSuccess {
                                    image = it
                                    editor = it.editable
                                }
                                message = if (refreshed.isSuccess) {
                                    ExifMessage("EXIF 信息已保存", false)
                                } else {
                                    ExifMessage("修改已保存，但重新读取图片失败", true)
                                }
                                saving = false
                            }
                        }
                    ) {
                        Text(if (saving) "保存中…" else "保存修改")
                    }
                }
            }

            Text("EXIF 信息", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            if (selectedImage.items.isEmpty()) {
                Text(
                    text = "这张图片没有可读取的常用 EXIF 信息。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        selectedImage.items.forEachIndexed { index, item ->
                            ExifItemRow(item)
                            if (index != selectedImage.items.lastIndex) HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ImageInfoCard(image: ExifImage) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(image.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("类型：${image.mimeType}", style = MaterialTheme.typography.bodySmall)
            image.byteSize?.let {
                Text("大小：${formatBytes(it)}", style = MaterialTheme.typography.bodySmall)
            }
            if (image.width != null && image.height != null) {
                Text("尺寸：${image.width} × ${image.height} px", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ExifEditor(
    value: ExifEditable,
    enabled: Boolean,
    onValueChange: (ExifEditable) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ExifTextField("图片描述", value.description, enabled, false) {
            onValueChange(value.copy(description = it))
        }
        ExifTextField("用户备注", value.userComment, enabled, false) {
            onValueChange(value.copy(userComment = it))
        }
        ExifTextField("作者", value.artist, enabled) { onValueChange(value.copy(artist = it)) }
        ExifTextField("版权", value.copyright, enabled) { onValueChange(value.copy(copyright = it)) }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ExifTextField("相机制造商", value.make, enabled, modifier = Modifier.weight(1f)) {
                onValueChange(value.copy(make = it))
            }
            ExifTextField("相机型号", value.model, enabled, modifier = Modifier.weight(1f)) {
                onValueChange(value.copy(model = it))
            }
        }
        ExifTextField("处理软件", value.software, enabled) { onValueChange(value.copy(software = it)) }
        ExifTextField(
            label = "拍摄时间",
            value = value.dateTimeOriginal,
            enabled = enabled,
            placeholder = "yyyy:MM:dd HH:mm:ss"
        ) { onValueChange(value.copy(dateTimeOriginal = it)) }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ExifTextField(
                label = "纬度",
                value = value.latitude,
                enabled = enabled,
                modifier = Modifier.weight(1f),
                keyboardType = KeyboardType.Decimal
            ) { onValueChange(value.copy(latitude = it)) }
            ExifTextField(
                label = "经度",
                value = value.longitude,
                enabled = enabled,
                modifier = Modifier.weight(1f),
                keyboardType = KeyboardType.Decimal
            ) { onValueChange(value.copy(longitude = it)) }
        }
    }
}

@Composable
private fun ExifTextField(
    label: String,
    value: String,
    enabled: Boolean,
    singleLine: Boolean = true,
    placeholder: String? = null,
    modifier: Modifier = Modifier.fillMaxWidth(),
    keyboardType: KeyboardType = KeyboardType.Text,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        modifier = modifier,
        value = value,
        enabled = enabled,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 2,
        maxLines = if (singleLine) 1 else 4,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType)
    )
}

@Composable
private fun ExifItemRow(item: ExifItem) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(
            text = item.label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(text = item.value, style = MaterialTheme.typography.bodyMedium)
    }
}

private data class ExifMessage(val text: String, val isError: Boolean)

private fun friendlyExifError(error: Throwable): String = when (error) {
    is SecurityException -> "没有读取或写入这张图片的权限，请重新选择图片"
    is UnsupportedOperationException -> "此图片格式不支持保存 EXIF 信息"
    is IOException -> when {
        error.message?.contains("JPEG, PNG, and WebP", ignoreCase = true) == true ->
            "该格式仅支持查看；目前只能把 EXIF 修改写回 JPEG、PNG 和 WebP 图片"
        error.message?.contains("saving attributes", ignoreCase = true) == true ->
            "图片来源不支持写回 EXIF 信息，请选择本机可编辑的文件"
        else -> error.message?.takeIf(String::isNotBlank)
            ?: "读取或保存图片时发生错误"
    }
    else -> error.message?.takeIf(String::isNotBlank)
        ?: "无法处理这张图片，请确认格式受支持且文件未损坏"
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB")
    var value = bytes.toDouble()
    var unitIndex = -1
    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024
        unitIndex++
    }
    return String.format(Locale.getDefault(), "%.1f %s", value, units[unitIndex])
}
