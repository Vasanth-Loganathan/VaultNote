package com.vasanth.vaultnote.crypto

import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object Kdf {
    const val DEFAULT_ITERATIONS = 600_000

    fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKey {
        val spec = PBEKeySpec(passphrase, salt, iterations, 256)
        try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec).encoded
            return SecretKeySpec(bytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }
}