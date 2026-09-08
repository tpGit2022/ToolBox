package com.seeksky.toolbox

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

data class CryptoDocument(val uri: Uri, val name: String, val mimeType: String, val size: Long)

data class CryptoPreparedFile(val file: File, val name: String, val mimeType: String)

class FileCryptoViewModel(application: Application) : AndroidViewModel(application) {
    var document by mutableStateOf<CryptoDocument?>(null)
        private set
    var prepared by mutableStateOf<CryptoPreparedFile?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var exportPickerOpen by mutableStateOf(false)
        private set
    var status by mutableStateOf("")
        private set
    var progress by mutableStateOf<Float?>(null)
        private set
    var isError by mutableStateOf(false)
        private set
    private var job: Job? = null
    private val resolver = application.contentResolver
    private val cacheRoot = File(application.cacheDir, "file-crypto")

    init {
        busy = true
        job = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (cacheRoot.exists() && !cacheRoot.deleteRecursively()) {
                        throw IOException("无法清理上次任务的临时文件")
                    }
                }
            } catch (error: IOException) {
                fail(error)
            } finally {
                busy = false
            }
        }
    }

    fun select(uri: Uri) {
        if (busy || prepared != null || exportPickerOpen) return
        busy = true
        isError = false
        status = "读取文件信息"
        job = viewModelScope.launch {
            try {
                document = withContext(Dispatchers.IO) {
                    var name = "未命名文件"
                    var size = -1L
                    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                        ?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                                val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                                if (nameColumn >= 0 && !cursor.isNull(nameColumn)) name = cursor.getString(nameColumn)
                                if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) size = cursor.getLong(sizeColumn)
                            }
                        }
                    CryptoDocument(uri, safeName(name), resolver.getType(uri) ?: "application/octet-stream", size)
                }
                status = "已选择文件"
            } catch (error: CancellationException) {
                status = "已取消"
                throw error
            } catch (error: Exception) {
                document = null
                fail(error)
            } finally {
                busy = false
            }
        }
    }

    fun prepare(encrypt: Boolean, password: CharArray, algorithm: FileCryptoAlgorithm, headerBytes: Int) {
        val selected = document
        if (busy || prepared != null || exportPickerOpen || selected == null) {
            password.fill('\u0000')
            return
        }
        busy = true
        isError = false
        progress = null
        status = "派生密钥，请稍候"
        val directory = File(cacheRoot, UUID.randomUUID().toString())
        job = viewModelScope.launch {
            try {
                prepared = withContext(Dispatchers.IO) {
                    if (!directory.mkdirs()) throw IOException("无法创建临时目录，请检查可用空间")
                    val processingContext = currentCoroutineContext()
                    val checkCancelled = { processingContext.ensureActive() }
                    var lastUpdate = 0L
                    val report: (String, Long, Long) -> Unit = { phase, completed, total ->
                        checkCancelled()
                        val now = System.nanoTime()
                        if (now - lastUpdate >= 150_000_000L || completed == total) {
                            lastUpdate = now
                            viewModelScope.launch {
                                if (processingContext[Job]?.isActive == true) {
                                    status = "$phase：${formatCryptoSize(completed)}" +
                                        if (total > 0) " / ${formatCryptoSize(total)}" else ""
                                    progress = if (total > 0) (completed.toDouble() / total).toFloat().coerceIn(0f, 1f) else null
                                }
                            }
                        }
                    }
                    val outputFile = File(directory, "result")
                    if (encrypt) {
                        resolver.openInputStream(selected.uri)?.use { input ->
                            outputFile.outputStream().buffered().use { output ->
                                FileCrypto.encrypt(
                                    input, output, password, algorithm, selected.name, selected.mimeType, headerBytes,
                                    onProgress = { completed -> report("加密", completed, selected.size) },
                                    checkCancelled = checkCancelled
                                )
                            }
                        } ?: throw IOException("无法读取源文件")
                        CryptoPreparedFile(outputFile, encryptedName(selected.name), selected.mimeType)
                    } else {
                        val stagedInput = File(directory, "encrypted-input")
                        try {
                            resolver.openInputStream(selected.uri)?.use { input ->
                                stagedInput.outputStream().buffered().use { output ->
                                    copy(input, output, checkCancelled) { report("读取加密文件", it, selected.size) }
                                }
                            } ?: throw IOException("无法读取源文件")
                            val metadata = outputFile.outputStream().buffered().use { output ->
                                FileCrypto.decrypt(stagedInput, output, password, report, checkCancelled)
                            }
                            CryptoPreparedFile(outputFile, safeName(metadata.originalName), metadata.mimeType)
                        } finally {
                            stagedInput.delete()
                        }
                    }
                }
                status = if (encrypt) "加密完成，请另存为新文件" else "完整性校验通过，解密完成，请另存为新文件"
                progress = null
            } catch (error: CancellationException) {
                status = "已取消，源文件未修改"
                throw error
            } catch (error: Exception) {
                fail(error)
            } finally {
                password.fill('\u0000')
                withContext(NonCancellable + Dispatchers.IO) {
                    if (prepared == null) directory.deleteRecursively()
                }
                busy = false
                progress = null
            }
        }
        job?.invokeOnCompletion { password.fill('\u0000') }
    }

    fun beginExport(): Boolean {
        if (busy || prepared == null || exportPickerOpen) return false
        exportPickerOpen = true
        return true
    }

    fun export(uri: Uri?) {
        exportPickerOpen = false
        val result = prepared ?: return
        if (uri == null) {
            status = "已取消保存，可重新另存为或丢弃临时结果"
            return
        }
        if (uri == document?.uri) {
            isError = true
            status = "不能覆盖源文件，请另选新文件"
            return
        }
        busy = true
        isError = false
        status = "正在保存"
        job = viewModelScope.launch {
            var exported = false
            try {
                withContext(Dispatchers.IO) {
                    val coroutineContext = currentCoroutineContext()
                    resolver.openOutputStream(uri, "wt")?.use { output ->
                        result.file.inputStream().buffered().use { input ->
                            copy(input, output, { coroutineContext.ensureActive() }) {}
                        }
                    } ?: throw IOException("无法写入目标文件")
                }
                exported = true
                prepared = null
                status = "保存成功，源文件未修改"
            } catch (error: CancellationException) {
                status = "保存已取消，可重试"
                throw error
            } catch (error: Exception) {
                fail(error)
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    if (exported) {
                        result.file.parentFile?.deleteRecursively()
                    } else {
                        val deleted = runCatching { DocumentsContract.deleteDocument(resolver, uri) }.getOrDefault(false)
                        if (!deleted) {
                            withContext(Dispatchers.Main) {
                                status += "；无法移除未完成的目标文件，请手动删除"
                            }
                        }
                    }
                }
                busy = false
            }
        }
    }

    fun discard() {
        if (busy || exportPickerOpen) return
        val result = prepared ?: return
        busy = true
        job = viewModelScope.launch {
            try {
                val deleted = withContext(Dispatchers.IO) { result.file.parentFile?.deleteRecursively() != false }
                if (!deleted) throw IOException("临时文件删除失败，请重试")
                prepared = null
                status = "已丢弃临时结果，源文件未修改"
                isError = false
            } catch (error: IOException) {
                fail(error)
            } finally {
                busy = false
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    override fun onCleared() {
        job?.cancel()
        prepared?.file?.parentFile?.deleteRecursively()
        super.onCleared()
    }

    private fun fail(error: Exception) {
        isError = true
        status = when (error) {
            is FileCryptoException, is IllegalArgumentException -> error.message ?: "加解密失败"
            is java.security.GeneralSecurityException -> "当前设备不支持所需算法，或密码／文件有误"
            is SecurityException -> "文件访问权限已失效，请重新选择文件"
            else -> "文件操作失败，请检查读写权限和可用空间：${error.localizedMessage ?: "未知错误"}"
        }
    }

    private fun copy(input: InputStream, output: OutputStream, checkCancelled: () -> Unit, report: (Long) -> Unit) {
        val buffer = ByteArray(256 * 1024)
        var completed = 0L
        try {
            while (true) {
                checkCancelled()
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                output.write(buffer, 0, count)
                completed += count
                report(completed)
            }
            output.flush()
        } finally {
            buffer.fill(0)
        }
    }
}

internal fun safeName(name: String): String = name.substringAfterLast('/').substringAfterLast('\\')
    .filter { it.code >= 32 && it.code != 127 }.take(240).takeUnless { it.isBlank() || it == "." || it == ".." }
    ?: "未命名文件"

internal fun encryptedName(name: String): String {
    val dot = name.lastIndexOf('.')
    return if (dot > 0 && dot < name.lastIndex) "${name.take(dot)}.encrypted${name.substring(dot)}" else "$name.encrypted"
}

internal fun formatCryptoSize(bytes: Long): String = when {
    bytes < 0 -> "大小未知"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(java.util.Locale.ROOT, "%.1f KiB", bytes / 1024.0)
    bytes < 1024 * 1024 * 1024 -> String.format(java.util.Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024))
    else -> String.format(java.util.Locale.ROOT, "%.2f GiB", bytes / (1024.0 * 1024 * 1024))
}
