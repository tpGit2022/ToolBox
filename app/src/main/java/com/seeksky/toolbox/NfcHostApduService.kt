package com.seeksky.toolbox

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.cardemulation.HostApduService
import android.net.Uri
import android.os.Bundle

/** Read-only NFC Forum Type 4 NDEF tag emulation over Android HCE. */
class NfcHostApduService : HostApduService() {
    private var selectedFile = SelectedFile.NONE

    override fun processCommandApdu(commandApdu: ByteArray, extras: Bundle?): ByteArray {
        return when {
            commandApdu.startsWithBytes(SELECT_NDEF_APPLICATION) -> {
                selectedFile = SelectedFile.NONE
                STATUS_OK
            }

            commandApdu.startsWithBytes(SELECT_CAPABILITY_CONTAINER) -> {
                selectedFile = SelectedFile.CAPABILITY_CONTAINER
                STATUS_OK
            }

            commandApdu.startsWithBytes(SELECT_NDEF_FILE) -> {
                selectedFile = SelectedFile.NDEF
                STATUS_OK
            }

            commandApdu.size >= 5 && commandApdu[0] == 0x00.toByte() && commandApdu[1] == 0xB0.toByte() -> {
                readBinary(commandApdu)
            }

            else -> STATUS_INSTRUCTION_NOT_SUPPORTED
        }
    }

    override fun onDeactivated(reason: Int) {
        selectedFile = SelectedFile.NONE
    }

    private fun readBinary(command: ByteArray): ByteArray {
        val source = when (selectedFile) {
            SelectedFile.CAPABILITY_CONTAINER -> CAPABILITY_CONTAINER
            SelectedFile.NDEF -> currentNdefFile()
            SelectedFile.NONE -> return STATUS_FILE_NOT_FOUND
        }
        val offset = ((command[2].toInt() and 0xFF) shl 8) or (command[3].toInt() and 0xFF)
        val requested = (command[4].toInt() and 0xFF).let { if (it == 0) 256 else it }
        if (offset > source.size) return STATUS_WRONG_PARAMETERS
        val end = minOf(offset + requested, source.size)
        return source.copyOfRange(offset, end) + STATUS_OK
    }

    private fun currentNdefFile(): ByteArray {
        val preferences = getSharedPreferences(NfcHcePreferences.FILE_NAME, MODE_PRIVATE)
        val kind = preferences.getString(NfcHcePreferences.KEY_KIND, NfcWriteKind.TEXT.name)
        val value = preferences.getString(NfcHcePreferences.KEY_VALUE, "百宝匣 NFC 卡模拟").orEmpty()
        var safeValue = value.take(600)
        var safeMessage = createMessage(kind, safeValue)
        while (safeMessage.size > MAX_NDEF_SIZE && safeValue.isNotEmpty()) {
            safeValue = safeValue.dropLast(maxOf(1, safeValue.length / 8))
            safeMessage = createMessage(kind, safeValue)
        }
        return byteArrayOf(
            ((safeMessage.size ushr 8) and 0xFF).toByte(),
            (safeMessage.size and 0xFF).toByte(),
        ) + safeMessage
    }

    private fun createMessage(kind: String?, value: String): ByteArray {
        val record = if (kind == NfcWriteKind.URI.name) {
            runCatching { NdefRecord.createUri(Uri.parse(value)) }
                .getOrElse { NdefRecord.createTextRecord("zh", value) }
        } else {
            NdefRecord.createTextRecord("zh", value)
        }
        return NdefMessage(arrayOf(record)).toByteArray()
    }

    private enum class SelectedFile { NONE, CAPABILITY_CONTAINER, NDEF }

    companion object {
        /** NFC Forum Type 4 Tag NDEF application AID. */
        const val NDEF_APPLICATION_AID = "D2760000850101"
        private const val MAX_NDEF_SIZE = 1024

        private val SELECT_NDEF_APPLICATION = nfcHex("00A4040007D2760000850101")
        private val SELECT_CAPABILITY_CONTAINER = nfcHex("00A4000C02E103")
        private val SELECT_NDEF_FILE = nfcHex("00A4000C02E104")
        private val STATUS_OK = nfcHex("9000")
        private val STATUS_FILE_NOT_FOUND = nfcHex("6A82")
        private val STATUS_WRONG_PARAMETERS = nfcHex("6B00")
        private val STATUS_INSTRUCTION_NOT_SUPPORTED = nfcHex("6D00")

        // CCLEN=15, Mapping v2.0, MLe=255, MLc=255, NDEF file E104, 1024 bytes, read-only.
        private val CAPABILITY_CONTAINER = nfcHex("000F2000FF00FF0406E104040000FF")
    }
}

internal object NfcHcePreferences {
    const val FILE_NAME = "nfc_hce"
    const val KEY_KIND = "kind"
    const val KEY_VALUE = "value"
}

private fun ByteArray.startsWithBytes(prefix: ByteArray): Boolean {
    if (size < prefix.size) return false
    return prefix.indices.all { this[it] == prefix[it] }
}

private fun nfcHex(value: String): ByteArray = ByteArray(value.length / 2) { index ->
    value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
}
