package com.vasanth.vaultnote.crypto

import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object AesGcm {
    private const val VERSION: Byte = 1
    private const val IV_LEN = 12
    private const val TAG_BITS = 128
    private val random = SecureRandom()

    fun encrypt(key: SecretKey, aad: ByteArray, plain: ByteArray): ByteArray {
        val iv = ByteArray(IV_LEN)
        random.nextBytes(iv)
        return encryptWithIv(key, iv, aad, plain)
    }

    /** Fixed IV: only for test vectors. Never reuse an IV with the same key. */
    fun encryptWithIv(key: SecretKey, iv: ByteArray, aad: ByteArray, plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        c.updateAAD(aad)
        return byteArrayOf(VERSION) + iv + c.doFinal(plain)
    }

    fun decrypt(key: SecretKey, aad: ByteArray, blob: ByteArray): ByteArray {
        if (blob.size < 1 + IV_LEN + 16 || blob[0] != VERSION) {
            throw GeneralSecurityException("Invalid blob")
        }
        val iv = blob.copyOfRange(1, 1 + IV_LEN)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        c.updateAAD(aad)
        return c.doFinal(blob, 1 + IV_LEN, blob.size - 1 - IV_LEN)
    }
}