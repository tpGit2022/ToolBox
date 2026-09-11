package com.seeksky.toolbox

internal data class NfcRecordInfo(
    val index: Int,
    val tnf: String,
    val kind: String,
    val value: String?,
    val typeHex: String,
    val idHex: String,
    val payloadHex: String,
)

internal data class NfcTagSnapshot(
    val idHex: String,
    val technologies: List<String>,
    val technologyDetails: List<Pair<String, String>>,
    val ndefType: String?,
    val ndefWritable: Boolean?,
    val ndefCanMakeReadOnly: Boolean?,
    val ndefMaxSizeBytes: Int?,
    val ndefSizeBytes: Int?,
    val ndefRecords: List<NfcRecordInfo>,
    val rawNdefHex: String?,
    val scannedAtElapsedMillis: Long,
)

internal data class NfcControllerInfo(
    val supported: Boolean,
    val enabled: Boolean,
    val hceSupported: Boolean,
    val hceFSupported: Boolean,
    val secureNfcSupported: Boolean?,
    val secureNfcEnabled: Boolean?,
    val observeModeSupported: Boolean?,
    val observeModeEnabled: Boolean?,
    val readerOptionSupported: Boolean?,
    val readerOptionEnabled: Boolean?,
    val powerSavingSupported: Boolean?,
    val powerSavingEnabled: Boolean?,
    val exitFramesSupported: Boolean?,
    val readerModeAnnotationSupported: Boolean?,
    val tagIntentPreferenceSupported: Boolean?,
    val tagIntentAllowed: Boolean?,
    val gestureExchangeAid: String?,
    val antennaDescription: String?,
)

internal enum class NfcWriteKind(val label: String) {
    TEXT("文本"),
    URI("网址"),
    MIME("MIME"),
    EXTERNAL("外部类型"),
}

internal fun nfcBytesToHex(bytes: ByteArray): String = bytes.joinToString(" ") {
    (it.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0')
}

internal fun parseNfcHex(value: String): ByteArray? {
    val compact = value.filterNot(Char::isWhitespace)
        .removePrefix("0x")
        .removePrefix("0X")
    if (compact.isEmpty() || compact.length % 2 != 0 || compact.any { it.digitToIntOrNull(16) == null }) {
        return null
    }
    return ByteArray(compact.length / 2) { index ->
        compact.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}

internal fun nfcBoolean(value: Boolean): String = if (value) "是" else "否"
