package com.vasanth.vaultnote.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DbKeyProvider @Inject constructor(
    @ApplicationContext private val ctx: Context
) {
    private val prefs = ctx.getSharedPreferences("vault_secure", Context.MODE_PRIVATE)

    fun getOrCreateDbKey(): ByteArray {
        prefs.getString(PREF_KEY, null)?.let { stored ->
            try {
                return unwrap(Base64.getDecoder().decode(stored))
            } catch (e: Exception) {
                // Keystore key lost: the old database can never be opened again.
                ctx.deleteDatabase(DB_NAME)
            }
        }
        val fresh = ByteArray(32).also { SecureRandom().nextBytes(it) }
        prefs.edit().putString(PREF_KEY, Base64.getEncoder().encodeToString(wrap(fresh))).commit()
        return fresh
    }

    private fun wrap(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, wrapKey())
        return c.iv + c.doFinal(plain)
    }

    private fun unwrap(blob: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, wrapKey(), GCMParameterSpec(128, blob.copyOfRange(0, 12)))
        return c.doFinal(blob, 12, blob.size - 12)
    }

    private fun wrapKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    companion object {
        const val DB_NAME = "vaultnote_enc.db"
        private const val ALIAS = "vaultnote_db_wrap"
        private const val PREF_KEY = "db_key_wrapped"
    }
}