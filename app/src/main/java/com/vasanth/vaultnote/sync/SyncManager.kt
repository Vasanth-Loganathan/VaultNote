package com.vasanth.vaultnote.sync

import android.content.Context
import android.os.SystemClock
import com.vasanth.vaultnote.crypto.AesGcm
import com.vasanth.vaultnote.crypto.KeyringManager
import com.vasanth.vaultnote.data.NoteRepository
import com.vasanth.vaultnote.data.db.NoteDao
import com.vasanth.vaultnote.data.db.NoteEntity
import com.vasanth.vaultnote.data.db.SyncStateDao
import com.vasanth.vaultnote.data.db.SyncStateEntity
import com.vasanth.vaultnote.data.db.TagDao
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton

enum class SyncOutcome { DONE, SKIPPED, RETRY }

data class SyncStatus(val syncing: Boolean = false, val error: String? = null, val lastSyncAt: Long = 0L)

sealed interface EnableResult {
    object Ok : EnableResult
    object VaultMismatch : EnableResult
    data class Error(val message: String) : EnableResult
}

sealed interface RestoreResult {
    object Found : RestoreResult
    object NotFound : RestoreResult
    data class Error(val message: String) : RestoreResult
}

data class StorageInfo(val appBytes: Long, val fileCount: Int, val accountUsed: Long, val accountLimit: Long?)

@Singleton
class SyncManager @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val keyring: KeyringManager,
    private val repo: NoteRepository,
    private val noteDao: NoteDao,
    private val tagDao: TagDao,
    private val stateDao: SyncStateDao,
    private val prefs: SyncPrefs,
    private val scheduler: SyncScheduler
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()

    private var cachedToken: String? = null
    private var tokenTime = 0L

    private val _status = MutableStateFlow(SyncStatus(lastSyncAt = prefs.lastSyncAt))
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    val isEnabled get() = prefs.enabled
    val email get() = prefs.email

    // =====================================================================
    //  Sync now (pull, then push)
    // =====================================================================
    suspend fun syncNow(): SyncOutcome {
        if (!prefs.enabled) return SyncOutcome.SKIPPED
        val dek = keyring.currentDek() ?: return SyncOutcome.SKIPPED   // locked: no key in memory
        if (!mutex.tryLock()) return SyncOutcome.SKIPPED
        try {
            _status.update { it.copy(syncing = true, error = null) }
            val token = freshToken()
            if (token == null) {
                _status.update { it.copy(error = "Google sign-in needed. Turn sync off and on again") }
                return SyncOutcome.SKIPPED
            }
            withContext(Dispatchers.IO) { runSync(DriveApi(http, token), dek) }
            val now = System.currentTimeMillis()
            prefs.lastSyncAt = now
            prefs.driveFull = false
            _status.update { it.copy(lastSyncAt = now) }
            return SyncOutcome.DONE
        } catch (e: CancellationException) {
            throw e
        } catch (e: DriveException) {
            return handleDriveError(e)
        } catch (e: IOException) {
            _status.update { it.copy(error = "No connection. Will retry") }
            return SyncOutcome.RETRY
        } catch (e: Exception) {
            _status.update { it.copy(error = "Sync problem: ${e.message}") }
            return SyncOutcome.DONE
        } finally {
            _status.update { it.copy(syncing = false) }
            mutex.unlock()
        }
    }

    private fun handleDriveError(e: DriveException): SyncOutcome = when {
        e.code == 401 -> {
            cachedToken = null
            _status.update { it.copy(error = "Google session expired. Will retry") }
            SyncOutcome.RETRY
        }
        e.isQuota -> {
            prefs.driveFull = true
            _status.update { it.copy(error = "Google Drive is full. Notes stay on this phone and sync when there is space") }
            SyncOutcome.RETRY
        }
        e.code == 429 || e.code >= 500 -> {
            _status.update { it.copy(error = "Google is busy. Will retry") }
            SyncOutcome.RETRY
        }
        else -> {
            _status.update { it.copy(error = describe(e)) }
            SyncOutcome.DONE
        }
    }

    private fun describe(e: DriveException) = when {
        e.body.contains("accessNotConfigured") -> "Enable the Google Drive API in your Google Cloud project"
        e.isQuota -> "Google Drive is full"
        else -> "Drive error ${e.code}: ${e.body.take(120)}"
    }

    private suspend fun runSync(api: DriveApi, dek: SecretKey) {
        uploadKeyringIfDirty(api)
        pull(api, dek)
        push(api, dek)
    }

    // =====================================================================
    //  Pull
    // =====================================================================
    private suspend fun pull(api: DriveApi, dek: SecretKey) {
        val saved = stateDao.get(KEY_TOKEN)
        if (saved != null) {
            try {
                followChanges(api, dek, saved)
                return
            } catch (e: DriveException) {
                if (e.code != 400 && e.code != 404 && e.code != 410) throw e
                stateDao.delete(KEY_TOKEN)      // token no longer valid: do a full resync
            }
        }
        // First sync: take the token BEFORE listing so nothing that changes meanwhile is missed.
        val start = api.startPageToken()
        for (f in api.listFiles()) processFile(api, dek, f)
        stateDao.put(SyncStateEntity(KEY_TOKEN, start))
    }

    private suspend fun followChanges(api: DriveApi, dek: SecretKey, startToken: String) {
        var page: String? = startToken
        while (page != null) {
            val res = api.changes(page)
            for (c in res.changes) {
                val f = c.file
                if (c.removed || f == null) c.fileId?.let { removeLocal(it) }
                else processFile(api, dek, f)
            }
            val next = res.nextPageToken ?: res.newStartPageToken
            if (next != null) stateDao.put(SyncStateEntity(KEY_TOKEN, next))
            page = res.nextPageToken
        }
    }

    private suspend fun processFile(api: DriveApi, dek: SecretKey, f: DriveFile) {
        val name = f.name ?: return
        val remoteTime = parseTime(f.modifiedTime)

        if (name == KEYRING_NAME) { adoptRemoteKeyring(api, f); return }
        if (!name.endsWith(NOTE_SUFFIX)) return
        val noteId = name.removeSuffix(NOTE_SUFFIX)

        val local = noteDao.getById(noteId)
        // Our own upload (or an unchanged file) echoing back through the change feed
        if (local != null && local.driveFileId == f.id && local.remoteModifiedTime == remoteTime) return

        val blob = api.download(f.id)                    // network errors propagate and retry later
        val payload = try { decode(blob, dek, noteId) } catch (e: Exception) { return }  // corrupt: skip
        val remote = payload.toEntity(noteId, f.id, remoteTime)

        when {
            local == null -> repo.applyRemote(remote, payload.tags)
            local.dirty -> resolveConflict(local, remote, payload.tags, f.id, remoteTime)
            remote.updatedAt > local.updatedAt -> repo.applyRemote(remote, payload.tags)
            else -> noteDao.setDriveInfo(local.id, f.id, remoteTime)
        }
    }

    /** Both sides changed. The newer version stays, the older one becomes a conflict copy. */
    private suspend fun resolveConflict(
        local: NoteEntity, remote: NoteEntity, remoteTags: List<String>, fileId: String, remoteTime: Long
    ) {
        when {
            remote.updatedAt == local.updatedAt ->
                noteDao.setDriveInfo(local.id, fileId, remoteTime)       // same edit, just re-link

            remote.updatedAt > local.updatedAt -> {
                saveCopy(local, tagDao.tagsForNote(local.id))
                repo.applyRemote(remote, remoteTags)
            }

            else -> {
                saveCopy(remote, remoteTags)
                noteDao.setDriveInfo(local.id, fileId, remoteTime)       // local stays dirty and overwrites
            }
        }
    }

    private suspend fun saveCopy(src: NoteEntity, tags: List<String>) {
        repo.saveNote(
            NoteEntity(
                id = UUID.randomUUID().toString(),
                type = src.type,
                title = src.title.ifBlank { "Untitled" } + " (conflict copy)",
                body = src.body,
                itemsJson = src.itemsJson,
                color = src.color,
                archived = src.archived,
                locked = src.locked
            ),
            tags
        )
    }

    /** The file disappeared from Drive (deleted on another device). */
    private suspend fun removeLocal(fileId: String) {
        val n = noteDao.getByDriveFileId(fileId) ?: return
        if (n.dirty) noteDao.clearDriveInfo(n.id)              // has unsynced edits: keep it, upload again
        else repo.deleteForever(n.id, recordTombstone = false)
    }

    /** A passphrase change made on another device (same DEK, newer timestamp). */
    private fun adoptRemoteKeyring(api: DriveApi, f: DriveFile) {
        val raw = String(api.download(f.id))
        val remote = keyring.parseKeyring(raw) ?: return
        val local = keyring.localKeyring() ?: return
        if (remote.ts > local.ts && keyring.matchesCurrentDek(remote)) keyring.installKeyring(raw)
    }

    // =====================================================================
    //  Push
    // =====================================================================
    private suspend fun push(api: DriveApi, dek: SecretKey) {
        // 1. deletions made while offline
        for (key in stateDao.keysWithPrefix("del:")) {
            api.delete(key.removePrefix("del:"))
            stateDao.delete(key)
        }
        // 2. changed notes
        for (n in noteDao.getDirty()) pushNote(api, dek, n)
    }

    private suspend fun pushNote(api: DriveApi, dek: SecretKey, n: NoteEntity) {
        val tags = tagDao.tagsForNote(n.id)
        val raw = json.encodeToString(NotePayload.from(n, tags)).toByteArray()
        if (raw.size > MAX_PAYLOAD_BYTES) return                 // too large: stays local only
        val blob = AesGcm.encrypt(dek, n.id.toByteArray(), gzip(raw))
        val name = n.id + NOTE_SUFFIX

        val existing = n.driveFileId
        val meta = if (existing == null) {
            api.create(name, blob, n.updatedAt)
        } else {
            try {
                api.update(existing, blob, n.updatedAt).also {
                    try { api.pruneRevisions(existing) } catch (_: Exception) { }
                }
            } catch (e: DriveException) {
                if (e.code == 404) api.create(name, blob, n.updatedAt) else throw e
            }
        }
        noteDao.setDriveInfo(n.id, meta.id, parseTime(meta.modifiedTime))
        noteDao.markClean(n.id, n.updatedAt, System.currentTimeMillis())   // only if not edited meanwhile
    }

    private fun uploadKeyringIfDirty(api: DriveApi) {
        if (!keyring.isKeyringDirty()) return
        val bytes = keyring.rawKeyring()?.toByteArray() ?: return
        val remote = api.listFiles(KEYRING_NAME).firstOrNull()
        if (remote == null) api.create(KEYRING_NAME, bytes, null) else api.update(remote.id, bytes, null)
        keyring.setKeyringDirty(false)
    }

    // =====================================================================
    //  Turn on / restore / off / delete
    // =====================================================================
    suspend fun enable(token: String, replaceExisting: Boolean = false): EnableResult =
        withContext(Dispatchers.IO) {
            try {
                if (keyring.currentDek() == null) return@withContext EnableResult.Error("Unlock the app first")
                keyring.ensureKcv()
                val localRaw = keyring.rawKeyring() ?: return@withContext EnableResult.Error("Keyring missing")
                val api = DriveApi(http, token)
                val me = api.about().user?.emailAddress
                val remoteFile = api.listFiles(KEYRING_NAME).firstOrNull()

                if (replaceExisting) {
                    api.listFiles().forEach { api.delete(it.id) }
                    noteDao.clearAllDriveInfo()
                    api.create(KEYRING_NAME, localRaw.toByteArray(), null)
                } else if (remoteFile == null) {
                    api.create(KEYRING_NAME, localRaw.toByteArray(), null)
                } else {
                    val remoteRaw = String(api.download(remoteFile.id))
                    val remote = keyring.parseKeyring(remoteRaw)
                    if (remote == null || !keyring.matchesCurrentDek(remote)) {
                        return@withContext EnableResult.VaultMismatch   // a different vault lives in this account
                    }
                    val local = keyring.localKeyring()
                    if (local != null && remote.ts > local.ts) keyring.installKeyring(remoteRaw)
                    else api.update(remoteFile.id, localRaw.toByteArray(), null)
                }

                noteDao.markUnsyncedDirty()
                stateDao.delete(KEY_TOKEN)
                keyring.setKeyringDirty(false)
                prefs.email = me
                prefs.enabled = true
                cachedToken = token
                tokenTime = SystemClock.elapsedRealtime()
                scheduler.startPeriodic()
                EnableResult.Ok
            } catch (e: CancellationException) {
                throw e
            } catch (e: DriveException) {
                EnableResult.Error(describe(e))
            } catch (e: IOException) {
                EnableResult.Error("No connection")
            } catch (e: Exception) {
                EnableResult.Error(e.message ?: "Unknown error")
            }
        }

    /** New device: fetch keyring.bin so the user can unlock with passphrase or recovery key. */
    suspend fun restoreKeyring(token: String): RestoreResult = withContext(Dispatchers.IO) {
        try {
            val api = DriveApi(http, token)
            val f = api.listFiles(KEYRING_NAME).firstOrNull() ?: return@withContext RestoreResult.NotFound
            val raw = String(api.download(f.id))
            if (!keyring.installKeyring(raw)) return@withContext RestoreResult.Error("The saved vault is damaged")
            prefs.email = api.about().user?.emailAddress
            prefs.enabled = true
            stateDao.delete(KEY_TOKEN)
            cachedToken = token
            tokenTime = SystemClock.elapsedRealtime()
            scheduler.startPeriodic()
            RestoreResult.Found
        } catch (e: CancellationException) {
            throw e
        } catch (e: DriveException) {
            RestoreResult.Error(describe(e))
        } catch (e: IOException) {
            RestoreResult.Error("No connection")
        } catch (e: Exception) {
            RestoreResult.Error(e.message ?: "Unknown error")
        }
    }

    suspend fun disable() {
        prefs.enabled = false
        prefs.email = null
        cachedToken = null
        scheduler.cancelAll()
        stateDao.delete(KEY_TOKEN)
        stateDao.keysWithPrefix("del:").forEach { stateDao.delete(it) }
        _status.value = SyncStatus()
    }

    /**
     * Signs out of Google. If deleteCloud is true, every Drive file is deleted first
     * (throws on failure, and then nothing changes). Local notes are always kept here.
     */
    suspend fun signOut(deleteCloud: Boolean) {
        val account = prefs.email
        if (deleteCloud) deleteAllCloudData() else disable()
        withContext(Dispatchers.IO) { noteDao.clearAllDriveInfo() }   // notes upload fresh to the next account
        cachedToken = null
        DriveAuth.revoke(ctx, account)
    }

    /** Deletes every file this app stored in Drive, then turns sync off. Notes stay on this phone. */
    suspend fun deleteAllCloudData(): Int {
        val count = withContext(Dispatchers.IO) {
            val token = freshToken() ?: throw IllegalStateException("Google sign-in needed")
            val api = DriveApi(http, token)
            val files = api.listFiles()
            files.forEach { api.delete(it.id) }
            noteDao.clearAllDriveInfo()
            files.size
        }
        disable()
        return count
    }

    suspend fun storageInfo(): StorageInfo? = withContext(Dispatchers.IO) {
        try {
            val token = freshToken() ?: return@withContext null
            val api = DriveApi(http, token)
            val files = api.listFiles()
            val about = api.about()
            StorageInfo(
                appBytes = files.sumOf { it.size?.toLongOrNull() ?: 0L },
                fileCount = files.size,
                accountUsed = about.storageQuota?.usage?.toLongOrNull() ?: 0L,
                accountLimit = about.storageQuota?.limit?.toLongOrNull()
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    // =====================================================================
    //  helpers
    // =====================================================================
    suspend fun freshToken(): String? {
        val cached = cachedToken
        if (cached != null && SystemClock.elapsedRealtime() - tokenTime < 45 * 60_000L) return cached
        val t = DriveAuth.silentToken(ctx)
        cachedToken = t
        tokenTime = SystemClock.elapsedRealtime()
        return t
    }

    private fun parseTime(s: String?): Long =
        s?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L

    private fun decode(blob: ByteArray, dek: SecretKey, noteId: String): NotePayload {
        val plain = AesGcm.decrypt(dek, noteId.toByteArray(), blob)     // AAD = note id
        return json.decodeFromString<NotePayload>(String(gunzip(plain)))
    }

    private fun gzip(raw: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(raw) }
        return out.toByteArray()
    }

    private fun gunzip(data: ByteArray): ByteArray =
        GZIPInputStream(ByteArrayInputStream(data)).use { it.readBytes() }

    companion object {
        private const val KEY_TOKEN = "changesPageToken"
        private const val KEYRING_NAME = "keyring.bin"
        private const val NOTE_SUFFIX = ".vn"
        private const val MAX_PAYLOAD_BYTES = 400_000
    }
}