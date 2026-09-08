package com.seeksky.toolbox

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
fun Mp3MetadataScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedUriText by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedUri = selectedUriText?.let(Uri::parse)
    var document by remember { mutableStateOf<Mp3DocumentInfo?>(null) }
    var fields by remember { mutableStateOf(Mp3EditableFields()) }
    var displayedArtwork by remember { mutableStateOf<ByteArray?>(null) }
    var artworkUpdate by remember { mutableStateOf<ArtworkUpdate>(ArtworkUpdate.Keep) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }

    fun load(uri: Uri) {
        selectedUriText = uri.toString()
        document = null
        busy = true
        message = null
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { Mp3MetadataRepository.read(context, uri) } }
                .onSuccess { loaded ->
                    document = loaded
                    fields = loaded.fields
                    displayedArtwork = loaded.artwork
                    artworkUpdate = ArtworkUpdate.Keep
                    isError = false
                }
                .onFailure { error ->
                    selectedUriText = null
                    isError = true
                    message = error.userMessage("读取 MP3 文件失败")
                }
            busy = false
        }
    }

    LaunchedEffect(Unit) {
        selectedUri?.let(::load)
    }

    val mp3Picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }.recoverCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            load(uri)
        }
    }
    val artworkPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            busy = true
            message = null
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        Mp3MetadataRepository.readArtwork(context, uri).also { (bytes) ->
                            require(decodeArtworkPreview(bytes) != null) { "无法解码所选图片" }
                        }
                    }
                }
                    .onSuccess { (bytes, mimeType) ->
                        displayedArtwork = bytes
                        artworkUpdate = ArtworkUpdate.Replace(bytes, mimeType)
                        isError = false
                    }
                    .onFailure { error ->
                        isError = true
                        message = error.userMessage("读取封面图片失败")
                    }
                busy = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("MP3 文件信息", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            "查看音频参数并编辑 ID3 标签。文件通过系统选择器访问，不需要授予整个存储空间的权限。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
            onClick = { mp3Picker.launch(arrayOf("audio/mpeg", "audio/mp3", "audio/*")) }
        ) {
            Text(if (document == null) "选择 MP3 文件" else "重新选择 MP3 文件")
        }

        if (busy) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(12.dp))
                Text("正在处理…")
            }
        }

        message?.let {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (isError) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.primaryContainer
                    }
                )
            ) {
                Text(it, modifier = Modifier.padding(14.dp))
            }
        }

        if (selectedUri == null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "请选择可写入的 MP3 文件。是否能够保存取决于文件来源是否授予写入权限。",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        document?.let { info ->
            FileSummaryCard(info)

            Text("封面", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            ArtworkEditor(
                artwork = displayedArtwork,
                enabled = !busy,
                onChoose = {
                    artworkPicker.launch(arrayOf("image/jpeg", "image/png", "image/gif", "image/webp"))
                },
                onRemove = {
                    displayedArtwork = null
                    artworkUpdate = ArtworkUpdate.Remove
                }
            )

            Text("ID3 标签", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            MetadataTextField("标题", fields.title, !busy) { fields = fields.copy(title = it) }
            MetadataTextField("艺术家", fields.artist, !busy) { fields = fields.copy(artist = it) }
            MetadataTextField("专辑", fields.album, !busy) { fields = fields.copy(album = it) }
            MetadataTextField("专辑艺术家", fields.albumArtist, !busy) { fields = fields.copy(albumArtist = it) }
            MetadataTextField("作曲者", fields.composer, !busy) { fields = fields.copy(composer = it) }
            MetadataTextField("流派", fields.genre, !busy) { fields = fields.copy(genre = it) }
            MetadataTextField("年份", fields.year, !busy) { fields = fields.copy(year = it) }
            NumberPair(
                enabled = !busy,
                firstLabel = "音轨号",
                firstValue = fields.track,
                onFirstChange = { fields = fields.copy(track = it.filter(Char::isDigit)) },
                secondLabel = "音轨总数",
                secondValue = fields.trackTotal,
                onSecondChange = { fields = fields.copy(trackTotal = it.filter(Char::isDigit)) }
            )
            NumberPair(
                enabled = !busy,
                firstLabel = "碟号",
                firstValue = fields.disc,
                onFirstChange = { fields = fields.copy(disc = it.filter(Char::isDigit)) },
                secondLabel = "碟片总数",
                secondValue = fields.discTotal,
                onSecondChange = { fields = fields.copy(discTotal = it.filter(Char::isDigit)) }
            )
            MetadataTextField("备注", fields.comment, !busy, singleLine = false) {
                fields = fields.copy(comment = it)
            }
            MetadataTextField("歌词", fields.lyrics, !busy, singleLine = false, minLines = 4) {
                fields = fields.copy(lyrics = it)
            }

            AudioPropertiesCard(info.audio)

            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
                onClick = {
                    val uri = selectedUri ?: return@Button
                    busy = true
                    message = null
                    scope.launch {
                        val saveResult = withContext(Dispatchers.IO) {
                            runCatching {
                                Mp3MetadataRepository.write(context, uri, fields, artworkUpdate)
                            }
                        }
                        if (saveResult.isFailure) {
                            isError = true
                            message = saveResult.exceptionOrNull()
                                ?.userMessage("保存 MP3 文件失败")
                                ?: "保存 MP3 文件失败"
                            busy = false
                            return@launch
                        }

                        val reloadResult = withContext(Dispatchers.IO) {
                            runCatching { Mp3MetadataRepository.read(context, uri) }
                        }
                        reloadResult.onSuccess { reloaded ->
                            document = reloaded
                            fields = reloaded.fields
                            displayedArtwork = reloaded.artwork
                            artworkUpdate = ArtworkUpdate.Keep
                            isError = false
                            message = "MP3 文件信息已保存"
                        }.onFailure {
                            artworkUpdate = ArtworkUpdate.Keep
                            isError = true
                            message = "修改已保存，但重新读取 MP3 文件失败"
                        }
                        busy = false
                    }
                }
            ) {
                Text(if (busy) "保存中…" else "保存修改")
            }
            Text(
                "将文本字段留空并保存可移除对应标签。保存会修改原文件，建议先为重要音频保留备份。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun FileSummaryCard(info: Mp3DocumentInfo) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(info.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("文件大小：${formatMp3FileSize(info.fileSize)}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ArtworkEditor(
    artwork: ByteArray?,
    enabled: Boolean,
    onChoose: () -> Unit,
    onRemove: () -> Unit
) {
    val bitmap = remember(artwork) { artwork?.let(::decodeArtworkPreview)?.asImageBitmap() }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = "专辑封面",
                modifier = Modifier.size(180.dp).clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Crop
            )
        } else {
            Card(
                modifier = Modifier.size(180.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("无封面", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(enabled = enabled, onClick = onChoose) { Text("更换封面") }
            if (artwork != null) {
                OutlinedButton(enabled = enabled, onClick = onRemove) { Text("移除封面") }
            }
        }
    }
}

@Composable
private fun MetadataTextField(
    label: String,
    value: String,
    enabled: Boolean,
    singleLine: Boolean = true,
    minLines: Int = if (singleLine) 1 else 2,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth(),
        value = value,
        enabled = enabled,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = singleLine,
        minLines = minLines,
        maxLines = if (singleLine) 1 else 8
    )
}

@Composable
private fun NumberPair(
    enabled: Boolean,
    firstLabel: String,
    firstValue: String,
    onFirstChange: (String) -> Unit,
    secondLabel: String,
    secondValue: String,
    onSecondChange: (String) -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            modifier = Modifier.weight(1f),
            value = firstValue,
            enabled = enabled,
            onValueChange = onFirstChange,
            label = { Text(firstLabel) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
        OutlinedTextField(
            modifier = Modifier.weight(1f),
            value = secondValue,
            enabled = enabled,
            onValueChange = onSecondChange,
            label = { Text(secondLabel) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
    }
}

@Composable
private fun AudioPropertiesCard(audio: Mp3AudioProperties) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("音频参数（只读）", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            HorizontalDivider()
            PropertyRow("时长", formatMp3Duration(audio.durationSeconds))
            PropertyRow("码率", audio.bitRate.withUnit("kbps"))
            PropertyRow("采样率", audio.sampleRate.withUnit("Hz"))
            PropertyRow("声道", audio.channels.ifBlank { "未知" })
            PropertyRow("码率模式", if (audio.variableBitRate) "VBR（可变码率）" else "CBR（固定码率）")
            PropertyRow("编码", audio.encodingType.ifBlank { "未知" })
            PropertyRow("格式", audio.format.ifBlank { "MP3" })
        }
    }
}

@Composable
private fun PropertyRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, modifier = Modifier.padding(start = 16.dp), fontWeight = FontWeight.Medium)
    }
}

private fun formatMp3Duration(seconds: Double): String {
    if (!seconds.isFinite() || seconds < 0) return "未知"
    val totalSeconds = seconds.toLong()
    val hours = totalSeconds / 3600
    val minutes = totalSeconds % 3600 / 60
    val remainingSeconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, remainingSeconds)
    } else {
        String.format(Locale.ROOT, "%d:%02d", minutes, remainingSeconds)
    }
}

private fun formatMp3FileSize(bytes: Long): String {
    if (bytes < 0) return "未知"
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes / 1024.0
    var unitIndex = 0
    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024
        unitIndex++
    }
    return String.format(Locale.ROOT, "%.2f %s", value, units[unitIndex])
}

private fun String.withUnit(unit: String): String = ifBlank { "未知" }.let { value ->
    if (value == "未知") value else "$value $unit"
}

private fun decodeArtworkPreview(bytes: ByteArray): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sampleSize = 1
    while (bounds.outWidth / sampleSize > 1200 || bounds.outHeight / sampleSize > 1200) {
        sampleSize *= 2
    }
    return BitmapFactory.decodeByteArray(
        bytes,
        0,
        bytes.size,
        BitmapFactory.Options().apply { inSampleSize = sampleSize }
    )
}

private fun Throwable.userMessage(fallback: String): String =
    generateSequence(this) { it.cause }
        .mapNotNull { it.message?.takeIf(String::isNotBlank) }
        .firstOrNull { message -> message.any { it.code in 0x4E00..0x9FFF } }
        ?: fallback
