package com.vasanth.vaultnote.crypto

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/** Contents of keyring.bin (JSON). Step 5 uploads this file to Drive as-is. */
@Serializable
data class Keyring(
    val v: Int = 1,
    val iterations: Int,
    val salt: String,
    val dekPass: String,
    val dekRecovery: String,
    val kcv: String = "",      // key-check value: proves two keyrings share the same DEK
    val ts: Long = 0           // last change, used to pick the newer keyring
)

class PendingSetup(val keyring: Keyring, val dek: SecretKey, val recoveryKey: String)

@Singleton
class KeyringManager @Inject constructor(
    @ApplicationContext private val ctx: Context
) {
    private val file = File(ctx.filesDir, "keyring.bin")
    private val prefs = ctx.getSharedPreferences("vault_secure", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val enc = Base64.getEncoder()
    private val dec = Base64.getDecoder()

    /** The DEK lives only in memory while the app is unlocked. */
    @Volatile private var dek: SecretKey? = null

    fun isSetUp() = file.exists()
    fun currentDek(): SecretKey? = dek
    fun lock() { dek = null }

    // ---------------- setup ----------------
    suspend fun prepareSetup(passphrase: CharArray): PendingSetup = withContext(Dispatchers.Default) {
        val rnd = SecureRandom()
        val salt = ByteArray(16).also { rnd.nextBytes(it) }
        val dekBytes = ByteArray(32).also { rnd.nextBytes(it) }
        val recoveryBytes = ByteArray(32).also { rnd.nextBytes(it) }

        val kek = Kdf.deriveKey(passphrase, salt, Kdf.DEFAULT_ITERATIONS)
        val recovery = SecretKeySpec(recoveryBytes, "AES")
        val keyring = Keyring(
            iterations = Kdf.DEFAULT_ITERATIONS,
            salt = enc.encodeToString(salt),
            dekPass = enc.encodeToString(AesGcm.encrypt(kek, AAD_PASSPHRASE, dekBytes)),
            dekRecovery = enc.encodeToString(AesGcm.encrypt(recovery, AAD_RECOVERY, dekBytes)),
            kcv = enc.encodeToString(AesGcm.encrypt(SecretKeySpec(dekBytes, "AES"), AAD_KCV, KCV_TEXT)),
            ts = System.currentTimeMillis()
        )
        val pending = PendingSetup(keyring, SecretKeySpec(dekBytes, "AES"), RecoveryKey.format(recoveryBytes))
        dekBytes.fill(0); recoveryBytes.fill(0); passphrase.fill('0')
        pending
    }

    fun commitSetup(p: PendingSetup) {
        writeKeyring(p.keyring)
        dek = p.dek
    }

    // ---------------- unlock ----------------
    suspend fun unlockWithPassphrase(passphrase: CharArray): Boolean = withContext(Dispatchers.Default) {
        try {
            val k = readKeyring() ?: return@withContext false
            val kek = Kdf.deriveKey(passphrase, dec.decode(k.salt), k.iterations)
            val bytes = AesGcm.decrypt(kek, AAD_PASSPHRASE, dec.decode(k.dekPass))
            dek = SecretKeySpec(bytes, "AES")
            bytes.fill(0)
            true
        } catch (e: Exception) {
            false
        } finally {
            passphrase.fill('0')
        }
    }

    suspend fun unlockWithRecoveryKey(text: String): Boolean = withContext(Dispatchers.Default) {
        try {
            val rec = RecoveryKey.parse(text) ?: return@withContext false
            val k = readKeyring() ?: return@withContext false
            val bytes = AesGcm.decrypt(SecretKeySpec(rec, "AES"), AAD_RECOVERY, dec.decode(k.dekRecovery))
            dek = SecretKeySpec(bytes, "AES")
            bytes.fill(0)
            true
        } catch (e: Exception) {
            false
        }
    }

    // ---------------- change passphrase ----------------
    /** Verifies the old passphrase, then re-wraps the DEK. Notes are not touched. */
    suspend fun changePassphrase(old: CharArray, new: CharArray): Boolean = withContext(Dispatchers.Default) {
        try {
            val k = readKeyring() ?: return@withContext false
            val oldKek = Kdf.deriveKey(old, dec.decode(k.salt), k.iterations)
            val dekBytes = AesGcm.decrypt(oldKek, AAD_PASSPHRASE, dec.decode(k.dekPass))
            rewrap(k, dekBytes, new)
            dekBytes.fill(0)
            true
        } catch (e: Exception) {
            false
        } finally {
            old.fill('0'); new.fill('0')
        }
    }

    /** Used right after a recovery-key unlock (DEK is already in memory). */
    suspend fun resetPassphrase(new: CharArray): Boolean = withContext(Dispatchers.Default) {
        try {
            val k = readKeyring() ?: return@withContext false
            val d = dek ?: return@withContext false
            rewrap(k, d.encoded, new)
            true
        } catch (e: Exception) {
            false
        } finally {
            new.fill('0')
        }
    }

    private fun rewrap(k: Keyring, dekBytes: ByteArray, newPass: CharArray) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val iterations = maxOf(k.iterations, Kdf.DEFAULT_ITERATIONS)
        val kek = Kdf.deriveKey(newPass, salt, iterations)
        writeKeyring(
            k.copy(
                iterations = iterations,
                salt = enc.encodeToString(salt),
                dekPass = enc.encodeToString(AesGcm.encrypt(kek, AAD_PASSPHRASE, dekBytes)),
                ts = System.currentTimeMillis()
            )
        )
        setKeyringDirty(true)       // uploaded to Drive on the next sync
    }

    // ---------------- biometric cache of the DEK ----------------
    // The DEK is wrapped by a Keystore key that needs a fresh biometric / device-lock
    // authentication (valid for a few seconds). Call these right after BiometricPrompt succeeds.

    fun hasBiometricCache(): Boolean = try {
        prefs.contains(PREF_DEK_CACHE) && keyStore().containsAlias(CACHE_ALIAS)
    } catch (e: Exception) { false }

    fun enableBiometricCache(): Boolean {
        val d = dek ?: return false
        return try {
            disableBiometricCache()
            val key = createCacheKey()
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key)
            val ct = c.doFinal(d.encoded)
            prefs.edit().putString(PREF_DEK_CACHE, enc.encodeToString(c.iv + ct)).apply()
            true
        } catch (e: Exception) {
            disableBiometricCache()
            false
        }
    }

    fun unlockWithBiometricCache(): Boolean {
        val stored = prefs.getString(PREF_DEK_CACHE, null) ?: return false
        return try {
            val key = keyStore().getKey(CACHE_ALIAS, null) as? SecretKey ?: return false
            val blob = dec.decode(stored)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob.copyOfRange(0, 12)))
            val bytes = c.doFinal(blob, 12, blob.size - 12)
            dek = SecretKeySpec(bytes, "AES")
            bytes.fill(0)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun disableBiometricCache() {
        prefs.edit().remove(PREF_DEK_CACHE).apply()
        try { keyStore().deleteEntry(CACHE_ALIAS) } catch (_: Exception) {}
    }

    @Suppress("DEPRECATION")
    private fun createCacheKey(): SecretKey {
        val builder = KeyGenParameterSpec.Builder(
            CACHE_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(false)
        if (Build.VERSION.SDK_INT >= 30) {
            builder.setUserAuthenticationParameters(
                AUTH_WINDOW_SECONDS,
                KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL
            )
        } else {
            builder.setUserAuthenticationValidityDurationSeconds(AUTH_WINDOW_SECONDS)
        }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(builder.build())
        return gen.generateKey()
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    // ---------------- file helpers ----------------
    private fun readKeyring(): Keyring? = try {
        json.decodeFromString<Keyring>(file.readText())
    } catch (e: Exception) { null }

    private fun writeKeyring(k: Keyring) {
        val tmp = File(ctx.filesDir, "keyring.tmp")
        tmp.writeText(json.encodeToString(k))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    companion object {
        /** Associated data for the two DEK wraps. The web client must use the same strings. */
        val AAD_PASSPHRASE = "vaultnotes:dek:passphrase".toByteArray()
        val AAD_RECOVERY = "vaultnotes:dek:recovery".toByteArray()

        private const val CACHE_ALIAS = "vaultnote_dek_cache"
        private const val PREF_DEK_CACHE = "dek_cache"
        private const val AUTH_WINDOW_SECONDS = 10
        val AAD_KCV = "vaultnotes:kcv".toByteArray()
        private val KCV_TEXT = "vaultnotes-key-check".toByteArray()

    }

    // ---------------- sync helpers ----------------
    fun rawKeyring(): String? = if (file.exists()) file.readText() else null
    fun localKeyring(): Keyring? = readKeyring()

    fun parseKeyring(raw: String): Keyring? = try {
        json.decodeFromString<Keyring>(raw)
    } catch (e: Exception) { null }

    /** Stores a keyring downloaded from Drive. */
    fun installKeyring(raw: String): Boolean {
        val k = parseKeyring(raw) ?: return false
        writeKeyring(k)
        return true
    }

    /** Keyrings created in Step 4 have no key-check value. Adds one while the DEK is in memory. */
    fun ensureKcv() {
        val d = dek ?: return
        val k = readKeyring() ?: return
        if (k.kcv.isNotEmpty()) return
        writeKeyring(
            k.copy(
                kcv = enc.encodeToString(AesGcm.encrypt(d, AAD_KCV, KCV_TEXT)),
                ts = if (k.ts == 0L) System.currentTimeMillis() else k.ts
            )
        )
    }

    /** True if [remote] was created with the same DEK as the one currently unlocked. */
    fun matchesCurrentDek(remote: Keyring): Boolean {
        val d = dek ?: return false
        if (remote.kcv.isEmpty()) return false
        return try {
            AesGcm.decrypt(d, AAD_KCV, dec.decode(remote.kcv)); true
        } catch (e: Exception) { false }
    }

    fun isKeyringDirty() = prefs.getBoolean("keyring_dirty", false)
    fun setKeyringDirty(v: Boolean) { prefs.edit().putBoolean("keyring_dirty", v).apply() }
}