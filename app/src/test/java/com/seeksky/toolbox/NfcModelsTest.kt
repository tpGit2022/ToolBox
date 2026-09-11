package com.seeksky.toolbox

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NfcModelsTest {
    @Test
    fun hex_roundTripsAndAcceptsSpaces() {
        val bytes = byteArrayOf(0x00, 0x7F, 0x80.toByte(), 0xFF.toByte())
        assertEquals("00 7F 80 FF", nfcBytesToHex(bytes))
        assertArrayEquals(bytes, parseNfcHex("00 7f 80 ff"))
        assertArrayEquals(bytes, parseNfcHex("0x007F80FF"))
    }

    @Test
    fun hex_rejectsEmptyOddAndInvalidInput() {
        assertNull(parseNfcHex(""))
        assertNull(parseNfcHex("ABC"))
        assertNull(parseNfcHex("GG"))
    }
}
