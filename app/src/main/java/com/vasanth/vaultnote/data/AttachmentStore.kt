package com.vasanth.vaultnote.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.LruCache
import com.vasanth.vaultnote.crypto.AesGcm
import com.vasanth.vaultnote.crypto.KeyringManager
import com.vasanth.vaultnote.data.db.NoteDao
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.UUID
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

class AttachmentException(message: String) : Exception(message)

/** Encrypted image files in filesDir/att/<id>.att  (AES-GCM, key = vault DEK, AAD = attachment id). */
@Singleton
class AttachmentStore @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val keyring: KeyringManager,
    private val noteDao: NoteDao
) {
    private val dir: File by lazy { File(ctx.filesDir, "att").also { it.mkdirs() } }

    private val _arrived = MutableSharedFlow<String>(extraBufferCapacity = 16)
    /** Emits an attachment id when its file was downloaded from Drive. */
    val arrived: SharedFlow<String> = _arrived.asSharedFlow()

    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private class Jpeg(val bytes: ByteArray, val w: Int, val h: Int)

    // ---------- files ----------
    private fun file(id: String) = File(dir, "$id.att")

    fun exists(id: String) = file(id).exists()

    fun readBlob(id: String): ByteArray? = file(id).takeIf { it.exists() }?.readBytes()

    private fun writeAtomic(f: File, bytes: ByteArray) {
        val tmp = File(dir, f.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(f)) { f.writeBytes(bytes); tmp.delete() }
    }

    /** Stores an encrypted blob downloaded from Drive. Returns false if it cannot be decrypted. */
    fun saveDownloaded(id: String, blob: ByteArray): Boolean {
        val dek = keyring.currentDek() ?: return false
        if (runCatching { AesGcm.decrypt(dek, id.toByteArray(), blob) }.isFailure) return false
        writeAtomic(file(id), blob)
        _arrived.tryEmit(id)
        return true
    }

    // ---------- import ----------
    suspend fun import(uri: Uri): Attachment = withContext(Dispatchers.IO) {
        val jpeg = compress(uri)
        val dek = waitForDek() ?: throw AttachmentException("The vault is locked. Try again after unlocking")
        val id = UUID.randomUUID().toString()
        val blob = AesGcm.encrypt(dek, id.toByteArray(), jpeg.bytes)
        writeAtomic(file(id), blob)
        Attachment(id, jpeg.w, jpeg.h, blob.size)
    }

    private suspend fun waitForDek(): SecretKey? = withTimeoutOrNull(120_000) {
        while (keyring.currentDek() == null) delay(300)
        keyring.currentDek()
    }

    private fun compress(uri: Uri): Jpeg {
        try {
            val cr = ctx.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val opened = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds); true } ?: false
            if (!opened) throw AttachmentException("Could not open the image")
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw AttachmentException("Not a supported image")

            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_DIM) sample *= 2
            val decoded = cr.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: throw AttachmentException("Could not read the image")

            val rotation = cr.openInputStream(uri)?.use { exifRotation(it) } ?: 0
            val scale = min(1f, MAX_DIM.toFloat() / max(decoded.width, decoded.height))
            val m = Matrix().apply { postScale(scale, scale); postRotate(rotation.toFloat()) }
            val scaled = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)
            val flat = if (scaled.hasAlpha()) {
                Bitmap.createBitmap(scaled.width, scaled.height, Bitmap.Config.ARGB_8888).also {
                    Canvas(it).apply { drawColor(Color.WHITE); drawBitmap(scaled, 0f, 0f, null) }
                }
            } else scaled

            var q = 70
            var bytes = ByteArray(0)
            while (true) {
                val bos = ByteArrayOutputStream()
                flat.compress(Bitmap.CompressFormat.JPEG, q, bos)
                bytes = bos.toByteArray()
                if (bytes.size <= MAX_BYTES || q <= 30) break
                q -= 10
            }
            if (bytes.size > MAX_BYTES) throw AttachmentException("Image is still over 1 MB after compression")
            return Jpeg(bytes, flat.width, flat.height)
        } catch (e: OutOfMemoryError) {
            throw AttachmentException("Image is too large to process")
        }
    }

    private fun exifRotation(input: InputStream): Int = runCatching {
        when (ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }.getOrDefault(0)

    // ---------- display ----------
    /** Decrypted bitmap whose longest side is about maxDim (null if locked, missing or damaged). */
    suspend fun load(id: String, maxDim: Int): Bitmap? = withContext(Dispatchers.IO) {
        val dek = keyring.currentDek() ?: run { cache.evictAll(); return@withContext null }
        val key = "$id:$maxDim"
        cache.get(key)?.let { return@withContext it }
        val f = file(id)
        if (!f.exists()) return@withContext null
        val plain = try {
            AesGcm.decrypt(dek, id.toByteArray(), f.readBytes())
        } catch (e: Exception) {
            return@withContext null
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(plain, 0, plain.size, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(
            plain, 0, plain.size, BitmapFactory.Options().apply { inSampleSize = sample }
        )
        bmp?.also { cache.put(key, it) }
    }

    // ---------- cleanup ----------
    suspend fun referencedIds(): Set<String> =
        noteDao.getWithAttachments()
            .flatMap { AttachmentJson.decode(it.attachmentsJson) }
            .map { it.id }
            .toSet()

    /** Deletes local files no note references (only files older than 10 minutes). */
    suspend fun gc() {
        val refs = referencedIds()
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            dir.listFiles()?.forEach { f ->
                if (now - f.lastModified() < MIN_AGE_MS) return@forEach
                val unreferenced = f.name.endsWith(".att") && f.name.removeSuffix(".att") !in refs
                if (unreferenced || f.name.endsWith(".tmp")) f.delete()
            }
        }
    }

    companion object {
        private const val MAX_DIM = 1280
        private const val MAX_BYTES = 1_000_000
        private const val MIN_AGE_MS = 10 * 60_000L
    }
}