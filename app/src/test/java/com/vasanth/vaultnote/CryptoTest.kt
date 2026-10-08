package com.vasanth.vaultnote

import com.vasanth.vaultnote.crypto.AesGcm
import com.vasanth.vaultnote.crypto.Base32
import com.vasanth.vaultnote.crypto.Kdf
import com.vasanth.vaultnote.crypto.RecoveryKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.spec.SecretKeySpec

class CryptoTest {

    private fun key(seed: Int = 1) = SecretKeySpec(ByteArray(32) { (it + seed).toByte() }, "AES")
    private val aad = "note-123".toByteArray()
    private val plain = """{"title":"Hello"}""".toByteArray()

    @Test fun roundTrip() {
        val blob = AesGcm.encrypt(key(), aad, plain)
        assertArrayEquals(plain, AesGcm.decrypt(key(), aad, blob))
    }

    @Test fun tamperedBlobFails() {
        val blob = AesGcm.encrypt(key(), aad, plain)
        blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 1).toByte()
        assertThrows(GeneralSecurityException::class.java) { AesGcm.decrypt(key(), aad, blob) }
    }

    @Test fun wrongKeyFails() {
        val blob = AesGcm.encrypt(key(1), aad, plain)
        assertThrows(GeneralSecurityException::class.java) { AesGcm.decrypt(key(2), aad, blob) }
    }

    @Test fun wrongAadFails() {
        val blob = AesGcm.encrypt(key(), aad, plain)
        assertThrows(GeneralSecurityException::class.java) {
            AesGcm.decrypt(key(), "note-999".toByteArray(), blob)
        }
    }

    @Test fun ivIsNewEveryTime() {
        val a = AesGcm.encrypt(key(), aad, plain)
        val b = AesGcm.encrypt(key(), aad, plain)
        assertEquals(false, a.contentEquals(b))
    }

    @Test fun pbkdf2KnownVector() {
        // PBKDF2-HMAC-SHA256, password="password", salt="salt", c=1, dkLen=32
        val k = Kdf.deriveKey("password".toCharArray(), "salt".toByteArray(), 1)
        val hex = k.encoded.joinToString("") { "%02x".format(it) }
        assertEquals("120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b", hex)
    }

    @Test fun recoveryKeyRoundTrip() {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val text = RecoveryKey.format(bytes)
        assertArrayEquals(bytes, RecoveryKey.parse(text))
        assertArrayEquals(bytes, RecoveryKey.parse(text.lowercase().replace("-", " ")))
    }

    @Test fun invalidRecoveryKeyRejected() {
        assertNull(RecoveryKey.parse("not a key!"))
        assertNull(RecoveryKey.parse("AAAA-BBBB"))
    }

    @Test fun base32Basics() {
        assertEquals("MZXW6===".trimEnd('='), Base32.encode("foo".toByteArray()))
        assertNotNull(Base32.decode("MZXW6"))
    }

    /** Run this test and copy the printed line. The web client must decrypt the same value. */
    @Test fun printCrossPlatformTestVector() {
        val k = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
        val iv = ByteArray(12) { (it + 100).toByte() }
        val blob = AesGcm.encryptWithIv(k, iv, "note-1".toByteArray(), """{"v":1,"title":"Test"}""".toByteArray())
        println("TEST VECTOR (key=00..1f, iv=64..6f, aad=note-1): " + Base64.getEncoder().encodeToString(blob))
        assertArrayEquals("""{"v":1,"title":"Test"}""".toByteArray(), AesGcm.decrypt(k, "note-1".toByteArray(), blob))
    }
}