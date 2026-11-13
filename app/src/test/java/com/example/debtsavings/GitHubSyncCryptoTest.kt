package com.example.debtsavings

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubSyncCryptoTest {
    @Test
    fun encryptedBackupRoundTripsWithoutExposingPlaintext() {
        val plain = """{"appId":"com.example.debtsavings","credit":"private-credit-name","balance":12345.67}"""
            .toByteArray(Charsets.UTF_8)

        val encrypted = GitHubSyncCrypto.encrypt(plain, "correct horse battery")

        assertFalse(String(encrypted, Charsets.UTF_8).contains("private-credit-name"))
        assertFalse(String(encrypted, Charsets.UTF_8).contains("12345.67"))
        assertArrayEquals(plain, GitHubSyncCrypto.decrypt(encrypted, "correct horse battery"))
    }

    @Test
    fun wrongPasswordCannotDecryptBackup() {
        val encrypted = GitHubSyncCrypto.encrypt(
            """{"appId":"com.example.debtsavings","amount":9000}""".toByteArray(Charsets.UTF_8),
            "correct horse battery"
        )

        try {
            GitHubSyncCrypto.decrypt(encrypted, "incorrect password")
            throw AssertionError("Decryption should fail for a wrong password")
        } catch (_: IllegalArgumentException) {
            // Expected: AES-GCM authentication rejects the wrong key.
        }
    }

    @Test
    fun base64RoundTripsDifferentByteLengths() {
        val examples = listOf(
            byteArrayOf(),
            byteArrayOf(0),
            byteArrayOf(1, 2),
            byteArrayOf(0, 1, 2, 3, 4, 5),
            ByteArray(257) { (it * 31).toByte() }
        )

        examples.forEach { bytes ->
            assertArrayEquals(bytes, SyncBase64.decode(SyncBase64.encode(bytes)))
        }
    }

    @Test
    fun encryptedFormatRejectsUnknownVersion() {
        val malformed = """{"format":"DebtSavings-GitHub-Encrypted","version":99}"""
            .toByteArray(Charsets.UTF_8)
        val error = try {
            GitHubSyncCrypto.decrypt(malformed, "correct horse battery")
            null
        } catch (e: IllegalArgumentException) {
            e
        }
        assertTrue(error?.message?.contains("не является резервной копией") == true)
    }
}
