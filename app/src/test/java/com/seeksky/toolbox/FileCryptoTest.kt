package com.seeksky.toolbox

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.concurrent.CancellationException

class FileCryptoTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun everyAlgorithm_roundTripsMultipleChunksAndPreservesMagic() {
        val source = ByteArray(FileCrypto.CHUNK_BYTES + 73) { (it * 31).toByte() }
        FileCryptoAlgorithm.entries.forEach { algorithm ->
            assertTrue("Algorithm unavailable: $algorithm", algorithm.isAvailable())
            val encrypted = encrypt(source, algorithm, 32)
            assertArrayEquals(source.copyOf(32), encrypted.copyOf(32))
            val restored = ByteArrayOutputStream()
            val metadata = decrypt(encrypted, restored)
            assertArrayEquals(source, restored.toByteArray())
            assertEquals(algorithm, metadata.algorithm)
            assertEquals("照片.jpg", metadata.originalName)
            assertEquals("image/jpeg", metadata.mimeType)
            assertEquals(source.size.toLong(), metadata.originalSize)
            assertEquals(32, metadata.preservedHeaderBytes)
            assertEquals(2L, metadata.frameCount)
        }
    }

    @Test
    fun emptyTinyBlockAlignedAndExactChunkFiles_roundTrip() {
        listOf(0, 1, 16, FileCrypto.CHUNK_BYTES).forEach { size ->
            val source = ByteArray(size) { it.toByte() }
            val encrypted = encrypt(source, FileCryptoAlgorithm.AES_256_CBC_HMAC, 4096)
            val restored = ByteArrayOutputStream()
            val metadata = decrypt(encrypted, restored)
            assertArrayEquals(source, restored.toByteArray())
            assertEquals(minOf(size, 4096), metadata.preservedHeaderBytes)
            assertEquals(if (size == 0) 0L else 1L, metadata.frameCount)
        }
    }

    @Test
    fun zeroPrefix_andFreshRandomness() {
        val source = ByteArray(128) { it.toByte() }
        val first = encrypt(source, headerBytes = 0)
        val second = encrypt(source, headerBytes = 0)
        assertFalse(first.contentEquals(second))
        val restored = ByteArrayOutputStream()
        assertEquals(0, decrypt(first, restored).preservedHeaderBytes)
        assertArrayEquals(source, restored.toByteArray())
    }

    @Test
    fun wrongPassword_writesNoPlaintext() {
        val encrypted = encrypt(ByteArray(256))
        assertRejectedWithoutOutput(encrypted, "wrong-password".toCharArray())
    }

    @Test
    fun tamperingWithAnyRegion_isRejectedBeforeOutput() {
        val encrypted = encrypt(ByteArray(256) { it.toByte() })
        val metadataStart = metadataStart(encrypted)
        val tagStart = encrypted.size - 44
        listOf(0, 16, 24, 36, metadataStart + 44, metadataStart + 64, tagStart, encrypted.size - 1).forEach { offset ->
            val changed = encrypted.copyOf()
            changed[offset] = (changed[offset].toInt() xor 1).toByte()
            assertRejectedWithoutOutput(changed)
        }
    }

    @Test
    fun truncatedAppendedAndPlainFiles_areRejected() {
        val encrypted = encrypt(ByteArray(256))
        listOf(
            encrypted.copyOf(encrypted.size - 1),
            encrypted.copyOf(20),
            encrypted + byteArrayOf(0),
            ByteArray(128)
        ).forEach { assertRejectedWithoutOutput(it) }
    }

    @Test
    fun unknownVersionsAlgorithmsRulesAndUnsafeLengths_areRejected() {
        val encrypted = encrypt(ByteArray(256))
        val start = metadataStart(encrypted)
        listOf(0 to 2, 4 to 999, 8 to 3, 12 to 2, 16 to Int.MAX_VALUE, 20 to Int.MAX_VALUE, 24 to -1).forEach { (offset, value) ->
            val changed = encrypted.copyOf()
            ByteBuffer.wrap(changed).putInt(start + offset, value)
            assertRejectedWithoutOutput(changed)
        }
        val oversized = encrypted.copyOf()
        ByteBuffer.wrap(oversized).putInt(oversized.size - 12, Int.MAX_VALUE)
        assertRejectedWithoutOutput(oversized)
        val negativeSize = encrypted.copyOf()
        ByteBuffer.wrap(negativeSize).putLong(start + 28, -1L)
        assertRejectedWithoutOutput(negativeSize)
        val negativeFrames = encrypted.copyOf()
        ByteBuffer.wrap(negativeFrames).putLong(start + 36, -1L)
        assertRejectedWithoutOutput(negativeFrames)
    }

    @Test
    fun footerLayout_isVersionedAndDeterministicallyLocated() {
        val encrypted = encrypt(byteArrayOf(1, 2, 3))
        assertEquals("TBXCRYPT", String(encrypted.takeLast(8).toByteArray(), Charsets.US_ASCII))
        val start = metadataStart(encrypted)
        val fields = ByteBuffer.wrap(encrypted)
        assertEquals(1, fields.getInt(start))
        assertEquals(1, fields.getInt(start + 4))
        assertEquals(1, fields.getInt(start + 8))
        assertEquals(1, fields.getInt(start + 12))
        assertEquals(600_000, fields.getInt(start + 16))
        assertEquals(1024 * 1024, fields.getInt(start + 20))
        assertEquals(3, fields.getInt(start + 24))
        assertEquals(3L, fields.getLong(start + 28))
        assertEquals(1L, fields.getLong(start + 36))
    }

    @Test
    fun shortAndZeroLengthReads_doNotLoseBytes() {
        val source = ByteArray(300) { it.toByte() }
        val input = object : InputStream() {
            val delegate = ByteArrayInputStream(source)
            var returnZero = true
            override fun read(): Int = delegate.read()
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                returnZero = !returnZero
                return if (returnZero) 0 else delegate.read(buffer, offset, minOf(7, length))
            }
        }
        val output = ByteArrayOutputStream()
        FileCrypto.encrypt(input, output, password(), FileCryptoAlgorithm.AES_256_GCM, "照片.jpg", "image/jpeg")
        val restored = ByteArrayOutputStream()
        decrypt(output.toByteArray(), restored)
        assertArrayEquals(source, restored.toByteArray())
    }

    @Test
    fun cancellation_isPropagatedBeforeWriting() {
        val encrypted = encrypt(ByteArray(128))
        val input = temporaryFolder.newFile().apply { writeBytes(encrypted) }
        val output = ByteArrayOutputStream()
        try {
            FileCrypto.decrypt(input, output, password(), checkCancelled = { throw CancellationException() })
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertEquals(0, output.size())
        }
        try {
            FileCrypto.encrypt(
                ByteArrayInputStream(ByteArray(128)), output, password(), FileCryptoAlgorithm.AES_256_GCM,
                "file", "application/octet-stream", checkCancelled = { throw CancellationException() }
            )
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertEquals(0, output.size())
        }
    }

    @Test
    fun pbkdf2Fallback_matchesKnownAnswersAndPlatformForUnicode() {
        val expected = mapOf(
            1 to "120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b",
            2 to "ae4d0c95af6b46d32d0adff928f06dd02a303f8ef3c251dfd6e2d85a95474c43"
        )
        expected.forEach { (iterations, hex) ->
            val derived = FileCrypto.deriveMasterKeyFallback("password".toCharArray(), "salt".toByteArray(), iterations)
            assertArrayEquals(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray(), derived)
        }
        val unicode = "长密码🔑é\u0000suffix".toCharArray()
        val salt = ByteArray(16) { it.toByte() }
        assertArrayEquals(
            FileCrypto.deriveMasterKey(unicode, salt, 1000),
            FileCrypto.deriveMasterKeyFallback(unicode, salt, 1000)
        )
    }

    private fun encrypt(
        source: ByteArray,
        algorithm: FileCryptoAlgorithm = FileCryptoAlgorithm.AES_256_GCM,
        headerBytes: Int = 16
    ): ByteArray = ByteArrayOutputStream().apply {
        FileCrypto.encrypt(ByteArrayInputStream(source), this, password(), algorithm, "照片.jpg", "image/jpeg", headerBytes)
    }.toByteArray()

    private fun decrypt(encrypted: ByteArray, output: ByteArrayOutputStream): FileCryptoMetadata {
        val file = temporaryFolder.newFile().apply { writeBytes(encrypted) }
        return FileCrypto.decrypt(file, output, password())
    }

    private fun assertRejectedWithoutOutput(encrypted: ByteArray, password: CharArray = password()) {
        val file = temporaryFolder.newFile().apply { writeBytes(encrypted) }
        val output = ByteArrayOutputStream()
        try {
            FileCrypto.decrypt(file, output, password)
            fail("Invalid input must be rejected")
        } catch (_: FileCryptoException) {
            assertEquals(0, output.size())
        }
    }

    private fun metadataStart(encrypted: ByteArray): Int =
        encrypted.size - 44 - ByteBuffer.wrap(encrypted).getInt(encrypted.size - 12)

    private fun password(): CharArray = "test-password-密码".toCharArray()
}
