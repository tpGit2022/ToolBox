package com.seeksky.toolbox

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.cardemulation.CardEmulation
import android.nfc.tech.IsoDep
import android.nfc.tech.MifareClassic
import android.nfc.tech.MifareUltralight
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.nfc.tech.NfcA
import android.nfc.tech.NfcB
import android.nfc.tech.NfcBarcode
import android.nfc.tech.NfcF
import android.nfc.tech.NfcV
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class NfcViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext
    private val adapter: NfcAdapter? get() = NfcAdapter.getDefaultAdapter(context)
    private val processing = AtomicBoolean(false)
    private var pendingOperation: PendingOperation? = null

    var latestTag by mutableStateOf<NfcTagSnapshot?>(null)
        private set
    var status by mutableStateOf("将 NFC 标签贴近手机背部以读取。")
        private set
    var processingTag by mutableStateOf(false)
        private set
    var pendingOperationDescription by mutableStateOf<String?>(null)
        private set

    fun controllerInfo(): NfcControllerInfo {
        val currentAdapter = adapter
        val packageManager = context.packageManager
        if (currentAdapter == null || !packageManager.hasSystemFeature(PackageManager.FEATURE_NFC)) {
            return NfcControllerInfo(
                supported = false,
                enabled = false,
                hceSupported = false,
                hceFSupported = false,
                secureNfcSupported = null,
                secureNfcEnabled = null,
                observeModeSupported = null,
                observeModeEnabled = null,
                readerOptionSupported = null,
                readerOptionEnabled = null,
                powerSavingSupported = null,
                powerSavingEnabled = null,
                exitFramesSupported = null,
                readerModeAnnotationSupported = null,
                tagIntentPreferenceSupported = null,
                tagIntentAllowed = null,
                gestureExchangeAid = null,
                antennaDescription = null,
            )
        }
        return NfcControllerInfo(
            supported = true,
            enabled = runCatching { currentAdapter.isEnabled }.getOrDefault(false),
            hceSupported = packageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION),
            hceFSupported = packageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION_NFCF),
            secureNfcSupported = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                runCatching { currentAdapter.isSecureNfcSupported }.getOrNull()
            } else null,
            secureNfcEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                runCatching { currentAdapter.isSecureNfcEnabled }.getOrNull()
            } else null,
            observeModeSupported = invokeBoolean(currentAdapter, "isObserveModeSupported"),
            observeModeEnabled = invokeBoolean(currentAdapter, "isObserveModeEnabled"),
            readerOptionSupported = invokeBoolean(currentAdapter, "isReaderOptionSupported"),
            readerOptionEnabled = invokeBoolean(currentAdapter, "isReaderOptionEnabled"),
            powerSavingSupported = invokeBoolean(currentAdapter, "isPowerSavingModeSupported"),
            powerSavingEnabled = invokeBoolean(currentAdapter, "isPowerSavingModeEnabled"),
            exitFramesSupported = invokeBoolean(currentAdapter, "isExitFramesSupported"),
            readerModeAnnotationSupported = invokeBoolean(currentAdapter, "isReaderModeAnnotationSupported"),
            tagIntentPreferenceSupported = invokeBoolean(currentAdapter, "isTagIntentAppPreferenceSupported"),
            tagIntentAllowed = invokeBoolean(currentAdapter, "isTagIntentAllowed"),
            gestureExchangeAid = invokeString(currentAdapter, "getGestureExchangeAid"),
            antennaDescription = readAntennaDescription(currentAdapter),
        )
    }

    fun hceRegistrationStatus(): String {
        val currentAdapter = adapter ?: return "当前设备没有 NFC 控制器"
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION)) {
            return "当前设备不支持 HCE"
        }
        return runCatching {
            val component = ComponentName(context, NfcHostApduService::class.java)
            val selected = CardEmulation.getInstance(currentAdapter)
                .isDefaultServiceForAid(component, NfcHostApduService.NDEF_APPLICATION_AID)
            if (selected) "NDEF AID 已由本应用处理" else "HCE 服务已注册；如有 AID 冲突，系统会要求选择应用"
        }.getOrElse { "HCE 服务已注册，系统未提供默认状态" }
    }

    fun savedHceKind(): NfcWriteKind {
        val raw = context.getSharedPreferences(NfcHcePreferences.FILE_NAME, Context.MODE_PRIVATE)
            .getString(NfcHcePreferences.KEY_KIND, NfcWriteKind.TEXT.name)
        return runCatching { NfcWriteKind.valueOf(raw.orEmpty()) }.getOrDefault(NfcWriteKind.TEXT)
    }

    fun savedHceValue(): String = context.getSharedPreferences(
        NfcHcePreferences.FILE_NAME,
        Context.MODE_PRIVATE,
    ).getString(NfcHcePreferences.KEY_VALUE, "百宝匣 NFC 卡模拟").orEmpty()

    fun saveHce(kind: NfcWriteKind, value: String) {
        require(kind == NfcWriteKind.TEXT || kind == NfcWriteKind.URI)
        val trimmed = value.trim().take(600)
        if (trimmed.isEmpty()) {
            status = "卡模拟内容不能为空。"
            return
        }
        val clean = if (
            kind == NfcWriteKind.URI && "://" !in trimmed &&
            !trimmed.startsWith("mailto:") && !trimmed.startsWith("tel:")
        ) {
            "https://$trimmed"
        } else {
            trimmed
        }
        context.getSharedPreferences(NfcHcePreferences.FILE_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(NfcHcePreferences.KEY_KIND, kind.name)
            .putString(NfcHcePreferences.KEY_VALUE, clean)
            .apply()
        status = "HCE 内容已保存。保持屏幕开启并将本机贴近另一台 NFC 设备即可读取。"
    }

    fun prepareWrite(kind: NfcWriteKind, value: String, type: String = "") {
        val cleanValue = value.trim()
        val operation = when (kind) {
            NfcWriteKind.TEXT -> {
                if (cleanValue.isEmpty()) return report("写入文本不能为空。")
                PendingOperation.Write(NdefMessage(NdefRecord.createTextRecord("zh", cleanValue)), "写入文本")
            }

            NfcWriteKind.URI -> {
                if (cleanValue.isEmpty()) return report("网址不能为空。")
                val normalized = if ("://" in cleanValue || cleanValue.startsWith("mailto:") || cleanValue.startsWith("tel:")) {
                    cleanValue
                } else {
                    "https://$cleanValue"
                }
                PendingOperation.Write(NdefMessage(NdefRecord.createUri(Uri.parse(normalized))), "写入网址")
            }

            NfcWriteKind.MIME -> {
                val mime = type.trim().lowercase()
                if (!mime.matches(Regex("[^/\\s]+/[^/\\s]+"))) return report("请输入有效 MIME 类型，例如 text/plain。")
                PendingOperation.Write(
                    NdefMessage(NdefRecord.createMime(mime, cleanValue.toByteArray(Charsets.UTF_8))),
                    "写入 MIME 数据",
                )
            }

            NfcWriteKind.EXTERNAL -> {
                val parts = type.trim().lowercase().split(':', limit = 2)
                if (parts.size != 2 || parts.any { it.isBlank() }) {
                    return report("外部类型格式应为 domain:type，例如 example.com:toolbox。")
                }
                PendingOperation.Write(
                    NdefMessage(NdefRecord.createExternal(parts[0], parts[1], cleanValue.toByteArray(Charsets.UTF_8))),
                    "写入外部类型数据",
                )
            }
        }
        arm(operation)
    }

    fun prepareEraseOrFormat() {
        arm(PendingOperation.EraseOrFormat)
    }

    fun prepareMakeReadOnly() {
        arm(PendingOperation.MakeReadOnly)
    }

    fun cancelPendingOperation() {
        pendingOperation = null
        pendingOperationDescription = null
        status = "已取消写入操作，恢复只读扫描。"
    }

    fun report(message: String) {
        status = message
    }

    /** Called by NfcAdapter.ReaderCallback; work is moved back to Main and then to Dispatchers.IO. */
    fun onTagDiscovered(tag: Tag) {
        if (!processing.compareAndSet(false, true)) return
        viewModelScope.launch {
            processingTag = true
            val operation = pendingOperation
            pendingOperation = null
            pendingOperationDescription = null
            status = operation?.let { "已发现标签，正在${it.description}…" } ?: "已发现标签，正在读取…"
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    operation?.let { executeOperation(tag, it) }
                    runCatching { readTag(tag) }.getOrElse { readError ->
                        if (operation == null) throw readError else null
                    }
                }
            }
            result.onSuccess { snapshot ->
                if (snapshot != null) latestTag = snapshot
                status = operation?.let {
                    if (snapshot != null) "${it.description}成功，并已重新读取标签。"
                    else "${it.description}成功；标签已移开，未能重新读取。"
                } ?: "标签读取完成。"
            }.onFailure { error ->
                status = "NFC 操作失败：${nfcErrorMessage(error)}"
            }
            processingTag = false
            processing.set(false)
        }
    }

    private fun arm(operation: PendingOperation) {
        pendingOperation = operation
        pendingOperationDescription = operation.description
        status = "已准备${operation.description}，请将目标标签贴近手机并保持不动。"
    }

    private fun executeOperation(tag: Tag, operation: PendingOperation) {
        when (operation) {
            is PendingOperation.Write -> writeNdef(tag, operation.message)
            PendingOperation.EraseOrFormat -> writeNdef(tag, emptyNdefMessage())
            PendingOperation.MakeReadOnly -> makeReadOnly(tag)
        }
    }

    private fun writeNdef(tag: Tag, message: NdefMessage) {
        val bytes = message.toByteArray().size
        val ndef = Ndef.get(tag)
        if (ndef != null) {
            try {
                ndef.connect()
                check(ndef.isWritable) { "标签是只读的" }
                check(bytes <= ndef.maxSize) { "数据需要 $bytes 字节，但标签最多可写 ${ndef.maxSize} 字节" }
                ndef.writeNdefMessage(message)
            } finally {
                runCatching { ndef.close() }
            }
            return
        }
        val formatable = NdefFormatable.get(tag) ?: error("该标签不支持 NDEF 写入或格式化")
        try {
            formatable.connect()
            formatable.format(message)
        } finally {
            runCatching { formatable.close() }
        }
    }

    private fun makeReadOnly(tag: Tag) {
        val ndef = Ndef.get(tag) ?: error("该标签不是已格式化的 NDEF 标签")
        try {
            ndef.connect()
            check(ndef.canMakeReadOnly()) { "该标签不支持通过 Android 设为只读" }
            check(ndef.makeReadOnly()) { "系统未能把标签设为只读" }
        } finally {
            runCatching { ndef.close() }
        }
    }

    private fun readTag(tag: Tag): NfcTagSnapshot {
        val technologyDetails = readTechnologyDetails(tag)
        val ndef = Ndef.get(tag)
        var ndefType: String? = null
        var writable: Boolean? = null
        var canMakeReadOnly: Boolean? = null
        var maxSize: Int? = null
        var message: NdefMessage? = null
        if (ndef != null) {
            try {
                ndef.connect()
                ndefType = ndefTypeName(ndef.type)
                writable = ndef.isWritable
                canMakeReadOnly = ndef.canMakeReadOnly()
                maxSize = ndef.maxSize
                message = ndef.ndefMessage ?: ndef.cachedNdefMessage
            } finally {
                runCatching { ndef.close() }
            }
        }
        val messageBytes = message?.toByteArray()
        return NfcTagSnapshot(
            idHex = nfcBytesToHex(tag.id ?: byteArrayOf()).ifEmpty { "系统未提供" },
            technologies = tag.techList.map { it.substringAfterLast('.') }.sorted(),
            technologyDetails = technologyDetails,
            ndefType = ndefType,
            ndefWritable = writable,
            ndefCanMakeReadOnly = canMakeReadOnly,
            ndefMaxSizeBytes = maxSize,
            ndefSizeBytes = messageBytes?.size,
            ndefRecords = message?.records.orEmpty().mapIndexed(::decodeNdefRecord),
            rawNdefHex = messageBytes?.let(::nfcBytesToHex),
            scannedAtElapsedMillis = SystemClock.elapsedRealtime(),
        )
    }

    private fun readTechnologyDetails(tag: Tag): List<Pair<String, String>> = buildList {
        NfcA.get(tag)?.let { tech ->
            add("NfcA · ATQA" to nfcBytesToHex(tech.atqa))
            add("NfcA · SAK" to "0x${tech.sak.toInt().toString(16).uppercase()}")
            add("NfcA · 最大收发" to "${tech.maxTransceiveLength} 字节")
            add("NfcA · 超时" to "${tech.timeout} ms")
        }
        NfcB.get(tag)?.let { tech ->
            add("NfcB · Application Data" to nfcBytesToHex(tech.applicationData))
            add("NfcB · Protocol Info" to nfcBytesToHex(tech.protocolInfo))
            add("NfcB · 最大收发" to "${tech.maxTransceiveLength} 字节")
        }
        NfcF.get(tag)?.let { tech ->
            add("NfcF · Manufacturer" to nfcBytesToHex(tech.manufacturer))
            add("NfcF · System Code" to nfcBytesToHex(tech.systemCode))
            add("NfcF · 最大收发" to "${tech.maxTransceiveLength} 字节")
            add("NfcF · 超时" to "${tech.timeout} ms")
        }
        NfcV.get(tag)?.let { tech ->
            add("NfcV · DSF ID" to "0x${(tech.dsfId.toInt() and 0xFF).toString(16).uppercase()}")
            add("NfcV · Response Flags" to "0x${(tech.responseFlags.toInt() and 0xFF).toString(16).uppercase()}")
            add("NfcV · 最大收发" to "${tech.maxTransceiveLength} 字节")
        }
        IsoDep.get(tag)?.let { tech ->
            tech.historicalBytes?.let { add("IsoDep · Historical Bytes" to nfcBytesToHex(it)) }
            tech.hiLayerResponse?.let { add("IsoDep · HiLayer Response" to nfcBytesToHex(it)) }
            add("IsoDep · 最大收发" to "${tech.maxTransceiveLength} 字节")
            add("IsoDep · 扩展 APDU" to nfcBoolean(tech.isExtendedLengthApduSupported))
            add("IsoDep · 超时" to "${tech.timeout} ms")
        }
        runCatching { MifareClassic.get(tag) }.getOrNull()?.let { tech ->
            add("MIFARE Classic · 类型" to mifareClassicType(tech.type))
            add("MIFARE Classic · 容量" to "${tech.size} 字节")
            add("MIFARE Classic · 扇区/块" to "${tech.sectorCount} / ${tech.blockCount}")
            add("MIFARE Classic · 最大收发" to "${tech.maxTransceiveLength} 字节")
            add("MIFARE Classic · 超时" to "${tech.timeout} ms")
        }
        MifareUltralight.get(tag)?.let { tech ->
            add("MIFARE Ultralight · 类型" to mifareUltralightType(tech.type))
            add("MIFARE Ultralight · 最大收发" to "${tech.maxTransceiveLength} 字节")
            add("MIFARE Ultralight · 超时" to "${tech.timeout} ms")
        }
        NfcBarcode.get(tag)?.let { tech ->
            add("NFC Barcode · 类型" to if (tech.type == NfcBarcode.TYPE_KOVIO) "Kovio" else "未知（${tech.type}）")
            add("NFC Barcode · 数据" to nfcBytesToHex(tech.barcode))
        }
        if (NdefFormatable.get(tag) != null) add("NdefFormatable" to "可尝试格式化为 NDEF")
    }

    private sealed interface PendingOperation {
        val description: String

        data class Write(val message: NdefMessage, override val description: String) : PendingOperation

        data object EraseOrFormat : PendingOperation {
            override val description = "清空/格式化 NDEF"
        }

        data object MakeReadOnly : PendingOperation {
            override val description = "永久设为只读"
        }
    }
}

private fun emptyNdefMessage(): NdefMessage = NdefMessage(
    NdefRecord(NdefRecord.TNF_EMPTY, byteArrayOf(), byteArrayOf(), byteArrayOf())
)

private fun decodeNdefRecord(index: Int, record: NdefRecord): NfcRecordInfo {
    val type = record.type ?: byteArrayOf()
    val id = record.id ?: byteArrayOf()
    val payload = record.payload ?: byteArrayOf()
    val decoded = when {
        record.tnf == NdefRecord.TNF_WELL_KNOWN && type.contentEquals(NdefRecord.RTD_TEXT) -> {
            "文本" to decodeTextPayload(payload)
        }
        record.tnf == NdefRecord.TNF_WELL_KNOWN && type.contentEquals(NdefRecord.RTD_URI) -> {
            "URI" to decodeUriPayload(payload)
        }
        record.tnf == NdefRecord.TNF_WELL_KNOWN && type.contentEquals(NdefRecord.RTD_SMART_POSTER) -> {
            val nested = runCatching { NdefMessage(payload).records.size }.getOrNull()
            "Smart Poster" to nested?.let { "包含 $it 条嵌套记录" }
        }
        record.tnf == NdefRecord.TNF_MIME_MEDIA -> {
            "MIME · ${type.toString(Charsets.US_ASCII)}" to payloadTextPreview(payload)
        }
        record.tnf == NdefRecord.TNF_EXTERNAL_TYPE -> {
            "外部类型 · ${type.toString(Charsets.US_ASCII)}" to payloadTextPreview(payload)
        }
        record.tnf == NdefRecord.TNF_ABSOLUTE_URI -> {
            "绝对 URI" to type.toString(Charsets.UTF_8)
        }
        record.tnf == NdefRecord.TNF_EMPTY -> "空记录" to null
        else -> "${ndefTnfName(record.tnf)} · ${type.toString(Charsets.US_ASCII).ifEmpty { "未知类型" }}" to payloadTextPreview(payload)
    }
    return NfcRecordInfo(
        index = index,
        tnf = ndefTnfName(record.tnf),
        kind = decoded.first,
        value = decoded.second,
        typeHex = nfcBytesToHex(type),
        idHex = nfcBytesToHex(id),
        payloadHex = nfcBytesToHex(payload),
    )
}

private fun decodeTextPayload(payload: ByteArray): String? {
    if (payload.isEmpty()) return null
    val status = payload[0].toInt() and 0xFF
    val languageLength = status and 0x3F
    if (payload.size < 1 + languageLength) return null
    val charset = if ((status and 0x80) != 0) Charsets.UTF_16 else Charsets.UTF_8
    val language = payload.copyOfRange(1, 1 + languageLength).toString(Charsets.US_ASCII)
    val text = payload.copyOfRange(1 + languageLength, payload.size).toString(charset)
    return if (language.isBlank()) text else "$text（语言：$language）"
}

private fun decodeUriPayload(payload: ByteArray): String? {
    if (payload.isEmpty()) return null
    val prefixIndex = payload[0].toInt() and 0xFF
    val prefix = NFC_URI_PREFIXES.getOrElse(prefixIndex) { "" }
    return prefix + payload.copyOfRange(1, payload.size).toString(Charsets.UTF_8)
}

private fun payloadTextPreview(payload: ByteArray): String? {
    if (payload.isEmpty()) return null
    val text = payload.toString(Charsets.UTF_8)
    return text.takeIf { value -> value.all { it == '\n' || it == '\r' || it == '\t' || !it.isISOControl() } }
}

private fun ndefTnfName(tnf: Short): String = when (tnf) {
    NdefRecord.TNF_EMPTY -> "EMPTY"
    NdefRecord.TNF_WELL_KNOWN -> "WELL_KNOWN"
    NdefRecord.TNF_MIME_MEDIA -> "MIME_MEDIA"
    NdefRecord.TNF_ABSOLUTE_URI -> "ABSOLUTE_URI"
    NdefRecord.TNF_EXTERNAL_TYPE -> "EXTERNAL_TYPE"
    NdefRecord.TNF_UNKNOWN -> "UNKNOWN"
    NdefRecord.TNF_UNCHANGED -> "UNCHANGED"
    else -> "保留值 $tnf"
}

private fun ndefTypeName(type: String): String = when (type) {
    Ndef.NFC_FORUM_TYPE_1 -> "NFC Forum Type 1"
    Ndef.NFC_FORUM_TYPE_2 -> "NFC Forum Type 2"
    Ndef.NFC_FORUM_TYPE_3 -> "NFC Forum Type 3"
    Ndef.NFC_FORUM_TYPE_4 -> "NFC Forum Type 4"
    Ndef.MIFARE_CLASSIC -> "MIFARE Classic"
    else -> type
}

private fun mifareClassicType(type: Int): String = when (type) {
    MifareClassic.TYPE_CLASSIC -> "Classic"
    MifareClassic.TYPE_PLUS -> "Plus"
    MifareClassic.TYPE_PRO -> "Pro"
    else -> "未知（$type）"
}

private fun mifareUltralightType(type: Int): String = when (type) {
    MifareUltralight.TYPE_ULTRALIGHT -> "Ultralight"
    MifareUltralight.TYPE_ULTRALIGHT_C -> "Ultralight C"
    else -> "未知（$type）"
}

private fun nfcErrorMessage(error: Throwable): String = when (error) {
    is SecurityException -> "系统拒绝访问该标签"
    is java.io.IOException -> "标签已移开或通信中断，请贴紧后重试"
    else -> error.message ?: "未知错误"
}

private fun invokeBoolean(adapter: NfcAdapter, methodName: String): Boolean? = runCatching {
    adapter.javaClass.getMethod(methodName).invoke(adapter) as? Boolean
}.getOrNull()

private fun invokeString(adapter: NfcAdapter, methodName: String): String? = runCatching {
    adapter.javaClass.getMethod(methodName).invoke(adapter) as? String
}.getOrNull()

private fun readAntennaDescription(adapter: NfcAdapter): String? = runCatching {
    val info = adapter.javaClass.getMethod("getNfcAntennaInfo").invoke(adapter) ?: return@runCatching null
    val infoClass = info.javaClass
    val width = infoClass.getMethod("getDeviceWidth").invoke(info)
    val height = infoClass.getMethod("getDeviceHeight").invoke(info)
    val foldable = infoClass.getMethod("isDeviceFoldable").invoke(info)
    val antennas = infoClass.getMethod("getAvailableNfcAntennas").invoke(info) as? List<*>
    val positions = antennas.orEmpty().joinToString { antenna ->
        if (antenna == null) return@joinToString "未知"
        val antennaClass = antenna.javaClass
        val x = antennaClass.getMethod("getLocationX").invoke(antenna)
        val y = antennaClass.getMethod("getLocationY").invoke(antenna)
        "($x, $y)"
    }
    "设备 ${width}×${height} mm，折叠屏：${if (foldable == true) "是" else "否"}，天线坐标：${positions.ifEmpty { "未提供" }}"
}.getOrNull()

private val NFC_URI_PREFIXES = listOf(
    "", "http://www.", "https://www.", "http://", "https://", "tel:", "mailto:",
    "ftp://anonymous:anonymous@", "ftp://ftp.", "ftps://", "sftp://", "smb://", "nfs://",
    "ftp://", "dav://", "news:", "telnet://", "imap:", "rtsp://", "urn:", "pop:",
    "sip:", "sips:", "tftp:", "btspp://", "btl2cap://", "btgoep://", "tcpobex://",
    "irdaobex://", "file://", "urn:epc:id:", "urn:epc:tag:", "urn:epc:pat:",
    "urn:epc:raw:", "urn:epc:", "urn:nfc:",
)
