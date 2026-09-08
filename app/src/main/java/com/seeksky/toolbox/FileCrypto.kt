package com.seeksky.toolbox

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.io.SequenceInputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

enum class FileCryptoAlgorithm(val id: Int, val label: String, val keyBytes: Int, val ivBytes: Int) {
    AES_256_GCM(1, "AES-256-GCM（推荐）", 32, 12),
    AES_128_GCM(2, "AES-128-GCM", 16, 12),
    AES_256_CBC_HMAC(3, "AES-256-CBC + HMAC-SHA256", 32, 16),
    CHACHA20_POLY1305(4, "ChaCha20-Poly1305", 32, 12);

    fun newCipher(): Cipher = when (this) {
        AES_256_GCM, AES_128_GCM -> Cipher.getInstance("AES/GCM/NoPadding")
        AES_256_CBC_HMAC -> Cipher.getInstance("AES/CBC/PKCS5Padding")
        CHACHA20_POLY1305 -> try {
            Cipher.getInstance("ChaCha20/Poly1305/NoPadding")
        } catch (_: GeneralSecurityException) {
            Cipher.getInstance("ChaCha20-Poly1305")
        }
    }

    fun isAvailable(): Boolean = try {
        val cipher = newCipher()
        initialize(cipher, Cipher.ENCRYPT_MODE, ByteArray(keyBytes), ByteArray(ivBytes))
        cipher.doFinal(byteArrayOf(1))
        true
    } catch (_: GeneralSecurityException) {
        false
    }

    internal fun initialize(cipher: Cipher, mode: Int, key: ByteArray, iv: ByteArray) {
        val keySpec = SecretKeySpec(key, if (this == CHACHA20_POLY1305) "ChaCha20" else "AES")
        val parameters: java.security.spec.AlgorithmParameterSpec = when (this) {
            AES_256_GCM, AES_128_GCM -> GCMParameterSpec(128, iv)
            else -> IvParameterSpec(iv)
        }
        cipher.init(mode, keySpec, parameters)
    }

    internal fun ciphertextSize(plaintextSize: Int): Int = when (this) {
        AES_256_CBC_HMAC -> (plaintextSize / 16 + 1) * 16
        else -> plaintextSize + 16
    }
}

data class FileCryptoMetadata(
    val algorithm: FileCryptoAlgorithm,
    val originalName: String,
    val mimeType: String,
    val originalSize: Long,
    val preservedHeaderBytes: Int,
    val frameCount: Long,
    val salt: ByteArray
)

class FileCryptoException(message: String, cause: Throwable? = null) : Exception(message, cause)

object FileCrypto {
    const val CHUNK_BYTES = 1024 * 1024
    const val MAX_HEADER_BYTES = 4096
    const val KDF_ITERATIONS = 600_000
    private const val MAX_METADATA_BYTES = 4096
    private const val MAX_FRAMES = 0xffff_ffffL
    private const val TAG_BYTES = 32
    private val MAGIC = "TBXCRYPT".toByteArray(Charsets.US_ASCII)
    private val random = SecureRandom()

    fun encrypt(
        input: InputStream,
        output: OutputStream,
        password: CharArray,
        algorithm: FileCryptoAlgorithm,
        originalName: String,
        mimeType: String,
        headerBytes: Int = 16,
        onProgress: (Long) -> Unit = {},
        checkCancelled: () -> Unit = {}
    ): FileCryptoMetadata {
        require(password.isNotEmpty()) { "请输入密码" }
        require(headerBytes in 0..MAX_HEADER_BYTES) { "文件头保留长度必须在 0～4096 字节之间" }
        checkCancelled()
        val salt = ByteArray(16).also(random::nextBytes)
        val master = deriveMasterKey(password, salt, KDF_ITERATIONS, checkCancelled)
        val encryptionKey = subkey(master, "encryption").copyOf(algorithm.keyBytes)
        val authenticationKey = subkey(master, "authentication")
        master.fill(0)
        val plaintext = ByteArray(CHUNK_BYTES)
        try {
            val mac = newMac(authenticationKey)
            val prefixBuffer = ByteArray(headerBytes)
            val prefix = prefixBuffer.copyOf(readChunk(input, prefixBuffer))
            writeAuthenticated(output, mac, prefix)
            val fullInput = SequenceInputStream(ByteArrayInputStream(prefix), input)
            var originalSize = 0L
            var frames = 0L
            while (true) {
                checkCancelled()
                val count = readChunk(fullInput, plaintext)
                if (count == 0) break
                require(frames < MAX_FRAMES) { "文件超出此版本支持的大小" }
                val iv = if (algorithm == FileCryptoAlgorithm.AES_256_CBC_HMAC) {
                    ByteArray(16).also(random::nextBytes)
                } else {
                    ByteBuffer.allocate(12).put(salt, 0, 8).putInt(frames.toInt()).array()
                }
                val cipher = algorithm.newCipher()
                algorithm.initialize(cipher, Cipher.ENCRYPT_MODE, encryptionKey, iv)
                val ciphertext = cipher.doFinal(plaintext, 0, count)
                writeAuthenticated(output, mac, ByteBuffer.allocate(8).putInt(count).putInt(ciphertext.size).array())
                writeAuthenticated(output, mac, iv)
                writeAuthenticated(output, mac, ciphertext)
                originalSize += count
                frames++
                onProgress(originalSize)
            }
            val metadata = FileCryptoMetadata(algorithm, originalName, mimeType, originalSize, prefix.size, frames, salt)
            val encoded = encodeMetadata(metadata)
            val locator = ByteBuffer.allocate(12).putInt(encoded.size).put(MAGIC).array()
            writeAuthenticated(output, mac, encoded)
            mac.update(locator)
            output.write(mac.doFinal())
            output.write(locator)
            output.flush()
            return metadata
        } finally {
            plaintext.fill(0)
            encryptionKey.fill(0)
            authenticationKey.fill(0)
        }
    }

    fun decrypt(
        inputFile: File,
        output: OutputStream,
        password: CharArray,
        onProgress: (String, Long, Long) -> Unit = { _, _, _ -> },
        checkCancelled: () -> Unit = {}
    ): FileCryptoMetadata = RandomAccessFile(inputFile, "r").use { input ->
        require(password.isNotEmpty()) { "请输入密码" }
        checkCancelled()
        val length = input.length()
        if (length < TAG_BYTES + 12L) invalidFormat()
        input.seek(length - 12)
        val locator = ByteArray(12).also(input::readFully)
        val locatorBuffer = ByteBuffer.wrap(locator)
        val metadataSize = locatorBuffer.int
        val magic = ByteArray(8).also(locatorBuffer::get)
        if (!magic.contentEquals(MAGIC)) {
            throw FileCryptoException("不是 ToolBox 加密文件，或文件尾已损坏；不支持 TheBook 旧格式")
        }
        if (metadataSize !in 1..MAX_METADATA_BYTES || metadataSize > length - TAG_BYTES - 12) invalidFormat()
        val metadataStart = length - 12 - TAG_BYTES - metadataSize
        input.seek(metadataStart)
        val encoded = ByteArray(metadataSize).also(input::readFully)
        val metadata = decodeMetadata(encoded)
        validateGeometry(metadata, metadataStart)
        val expectedTag = ByteArray(TAG_BYTES).also(input::readFully)
        onProgress("派生密钥", 0, 0)
        val master = deriveMasterKey(password, metadata.salt, KDF_ITERATIONS, checkCancelled)
        val encryptionKey = subkey(master, "encryption").copyOf(metadata.algorithm.keyBytes)
        val authenticationKey = subkey(master, "authentication")
        master.fill(0)
        try {
            val mac = newMac(authenticationKey)
            input.seek(0)
            val buffer = ByteArray(CHUNK_BYTES)
            val authenticatedSize = metadataStart + metadataSize
            var authenticated = 0L
            while (authenticated < authenticatedSize) {
                checkCancelled()
                val count = minOf(buffer.size.toLong(), authenticatedSize - authenticated).toInt()
                input.readFully(buffer, 0, count)
                mac.update(buffer, 0, count)
                authenticated += count
                onProgress("校验密码与完整性", authenticated, authenticatedSize)
            }
            mac.update(locator)
            if (!MessageDigest.isEqual(expectedTag, mac.doFinal())) {
                throw FileCryptoException("密码错误或文件已被篡改／损坏，未导出解密数据")
            }
            input.seek(metadata.preservedHeaderBytes.toLong())
            var completed = 0L
            var frame = 0L
            while (frame < metadata.frameCount) {
                checkCancelled()
                val expectedSize = minOf(CHUNK_BYTES.toLong(), metadata.originalSize - completed).toInt()
                val plaintextSize = input.readInt()
                val ciphertextSize = input.readInt()
                if (plaintextSize != expectedSize || ciphertextSize != metadata.algorithm.ciphertextSize(expectedSize)) {
                    invalidFormat()
                }
                val iv = ByteArray(metadata.algorithm.ivBytes).also(input::readFully)
                val ciphertext = ByteArray(ciphertextSize).also(input::readFully)
                val cipher = metadata.algorithm.newCipher()
                metadata.algorithm.initialize(cipher, Cipher.DECRYPT_MODE, encryptionKey, iv)
                val plaintext = try {
                    cipher.doFinal(ciphertext)
                } catch (error: GeneralSecurityException) {
                    throw FileCryptoException("解密认证失败，文件已损坏或算法不可用", error)
                }
                try {
                    if (plaintext.size != plaintextSize) invalidFormat()
                    output.write(plaintext)
                } finally {
                    plaintext.fill(0)
                }
                completed += plaintextSize
                frame++
                onProgress("解密", completed, metadata.originalSize)
            }
            if (input.filePointer != metadataStart || completed != metadata.originalSize) invalidFormat()
            output.flush()
            metadata
        } finally {
            encryptionKey.fill(0)
            authenticationKey.fill(0)
        }
    }

    private fun encodeMetadata(metadata: FileCryptoMetadata): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(1)
            output.writeInt(metadata.algorithm.id)
            output.writeInt(1)
            output.writeInt(1)
            output.writeInt(KDF_ITERATIONS)
            output.writeInt(CHUNK_BYTES)
            output.writeInt(metadata.preservedHeaderBytes)
            output.writeLong(metadata.originalSize)
            output.writeLong(metadata.frameCount)
            output.write(metadata.salt)
            writeText(output, metadata.originalName, 1024)
            writeText(output, metadata.mimeType, 255)
        }
        bytes.toByteArray()
    }

    private fun decodeMetadata(encoded: ByteArray): FileCryptoMetadata = try {
        DataInputStream(ByteArrayInputStream(encoded)).use { input ->
            val version = input.readInt()
            if (version != 1) throw FileCryptoException("不支持的加密格式版本 $version，请升级 ToolBox")
            val algorithmId = input.readInt()
            val algorithm = FileCryptoAlgorithm.entries.firstOrNull { it.id == algorithmId }
                ?: throw FileCryptoException("不支持的算法编号 $algorithmId，请升级 ToolBox")
            val flags = input.readInt()
            val kdf = input.readInt()
            val iterations = input.readInt()
            val chunkBytes = input.readInt()
            if (flags != 1 || kdf != 1 || iterations != KDF_ITERATIONS || chunkBytes != CHUNK_BYTES) {
                throw FileCryptoException("不支持的加密规则或密钥派生参数，请升级 ToolBox")
            }
            val prefixSize = input.readInt()
            val originalSize = input.readLong()
            val frameCount = input.readLong()
            val salt = ByteArray(16).also(input::readFully)
            val name = readText(input, 1024)
            val mime = readText(input, 255)
            if (input.available() != 0) invalidFormat()
            FileCryptoMetadata(algorithm, name, mime, originalSize, prefixSize, frameCount, salt)
        }
    } catch (error: FileCryptoException) {
        throw error
    } catch (error: Exception) {
        throw FileCryptoException("加密文件尾部信息损坏", error)
    }

    private fun validateGeometry(metadata: FileCryptoMetadata, metadataStart: Long) {
        if (metadata.originalSize < 0 || metadata.preservedHeaderBytes !in 0..MAX_HEADER_BYTES ||
            metadata.preservedHeaderBytes > metadata.originalSize || metadata.frameCount !in 0..MAX_FRAMES
        ) invalidFormat()
        val fullFrames = metadata.originalSize / CHUNK_BYTES
        val remainder = (metadata.originalSize % CHUNK_BYTES).toInt()
        val expectedFrames = fullFrames + if (remainder > 0) 1 else 0
        if (metadata.frameCount != expectedFrames) invalidFormat()
        val frameOverhead = 8 + metadata.algorithm.ivBytes
        val expectedLength = metadata.preservedHeaderBytes +
            fullFrames * (frameOverhead + metadata.algorithm.ciphertextSize(CHUNK_BYTES)).toLong() +
            (if (remainder > 0) frameOverhead + metadata.algorithm.ciphertextSize(remainder).toLong() else 0L)
        if (metadataStart != expectedLength) invalidFormat()
    }

    internal fun deriveMasterKey(
        password: CharArray,
        salt: ByteArray,
        iterations: Int,
        checkCancelled: () -> Unit = {}
    ): ByteArray {
        val factory = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        } catch (_: NoSuchAlgorithmException) {
            return deriveMasterKeyFallback(password, salt, iterations, checkCancelled)
        }
        val spec = PBEKeySpec(password, salt, iterations, 256)
        return try {
            checkCancelled()
            val key = factory.generateSecret(spec).encoded
            try {
                checkCancelled()
                key
            } catch (error: Throwable) {
                key.fill(0)
                throw error
            }
        } finally {
            spec.clearPassword()
        }
    }

    internal fun deriveMasterKeyFallback(
        password: CharArray,
        salt: ByteArray,
        iterations: Int,
        checkCancelled: () -> Unit = {}
    ): ByteArray {
        require(password.isNotEmpty() && iterations > 0)
        val encoded = Charsets.UTF_8.encode(java.nio.CharBuffer.wrap(password))
        val passwordBytes = ByteArray(encoded.remaining()).also(encoded::get)
        if (encoded.hasArray()) encoded.array().fill(0)
        var block = ByteArray(0)
        try {
            val mac = newMac(passwordBytes)
            block = mac.doFinal(salt + byteArrayOf(0, 0, 0, 1))
            val result = block.copyOf()
            try {
                repeat(iterations - 1) { iteration ->
                    if (iteration % 1024 == 0) checkCancelled()
                    val next = mac.doFinal(block)
                    block.fill(0)
                    block = next
                    for (index in result.indices) {
                        result[index] = (result[index].toInt() xor block[index].toInt()).toByte()
                    }
                }
                checkCancelled()
                return result
            } catch (error: Throwable) {
                result.fill(0)
                throw error
            }
        } finally {
            passwordBytes.fill(0)
            block.fill(0)
        }
    }

    private fun subkey(master: ByteArray, purpose: String): ByteArray =
        newMac(master).doFinal("ToolBox/v1/$purpose".toByteArray(Charsets.US_ASCII))

    private fun newMac(key: ByteArray): Mac = Mac.getInstance("HmacSHA256").apply {
        init(SecretKeySpec(key, "HmacSHA256"))
    }

    private fun writeAuthenticated(output: OutputStream, mac: Mac, bytes: ByteArray) {
        output.write(bytes)
        mac.update(bytes)
    }

    private fun readChunk(input: InputStream, buffer: ByteArray): Int {
        var count = 0
        while (count < buffer.size) {
            val read = input.read(buffer, count, buffer.size - count)
            if (read < 0) break
            if (read == 0) {
                val singleByte = input.read()
                if (singleByte < 0) break
                buffer[count++] = singleByte.toByte()
            } else {
                count += read
            }
        }
        return count
    }

    private fun writeText(output: DataOutputStream, text: String, maximum: Int) {
        val encoded = text.toByteArray(Charsets.UTF_8)
        require(encoded.size in 1..maximum) { "文件名或 MIME 类型过长／为空" }
        output.writeInt(encoded.size)
        output.write(encoded)
    }

    private fun readText(input: DataInputStream, maximum: Int): String {
        val length = input.readInt()
        if (length !in 1..maximum || length > input.available()) invalidFormat()
        val bytes = ByteArray(length).also(input::readFully)
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    }

    private fun invalidFormat(): Nothing = throw FileCryptoException("加密文件结构无效、被截断或尾部信息已损坏")
}
