package com.seeksky.toolbox

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(AndroidJUnit4::class)
class FileCryptoInstrumentedTest {
    @Test
    fun androidProviders_roundTripSupportedAlgorithms() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = ByteArray(FileCrypto.CHUNK_BYTES + 17) { (it * 19).toByte() }
        val password = "跨设备密码🔑-123456".toCharArray()
        FileCryptoAlgorithm.entries.forEach { algorithm ->
            if (algorithm == FileCryptoAlgorithm.CHACHA20_POLY1305 && Build.VERSION.SDK_INT < 28 && !algorithm.isAvailable()) {
                return@forEach
            }
            assertTrue("Device must support $algorithm", algorithm.isAvailable())
            val file = File.createTempFile("crypto-provider-", ".tmp", context.cacheDir)
            try {
                file.outputStream().use { output ->
                    FileCrypto.encrypt(ByteArrayInputStream(source), output, password, algorithm, "测试.bin", "application/octet-stream", 16)
                }
                val restored = ByteArrayOutputStream()
                FileCrypto.decrypt(file, restored, password)
                assertArrayEquals(source, restored.toByteArray())
            } finally {
                file.delete()
            }
        }
        password.fill('\u0000')
    }

    @Test
    fun androidPbkdf2_matchesFallbackWithUnicodePassword() {
        val password = "跨平台🔑é\u0000password".toCharArray()
        val salt = ByteArray(16) { it.toByte() }
        assertArrayEquals(
            FileCrypto.deriveMasterKeyFallback(password, salt, 1000),
            FileCrypto.deriveMasterKey(password, salt, 1000)
        )
    }
}
