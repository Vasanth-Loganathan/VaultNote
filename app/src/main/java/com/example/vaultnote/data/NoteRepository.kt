package com.vasanth.vaultnote.data

import androidx.room.withTransaction
import com.vasanth.vaultnote.data.db.LinkDao
import com.vasanth.vaultnote.data.db.NoteDao
import com.vasanth.vaultnote.data.db.NoteEntity
import com.vasanth.vaultnote.data.db.NoteTagCrossRef
import com.vasanth.vaultnote.data.db.TagDao
import com.vasanth.vaultnote.data.db.TagEntity
import com.vasanth.vaultnote.data.db.VaultDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NoteRepository @Inject constructor(
    private val db: VaultDatabase,
    private val noteDao: NoteDao,
    private val tagDao: TagDao,
    private val linkDao: LinkDao
) {
    // ---------- observe ----------
    fun observeActive(): Flow<List<NoteEntity>> = noteDao.observeActive()
    fun observeArchived(): Flow<List<NoteEntity>> = noteDao.observeArchived()
    fun observeTrash(): Flow<List<NoteEntity>> = noteDao.observeTrash()
    fun observeByTag(tag: String): Flow<List<NoteEntity>> = noteDao.observeByTag(tag)
    fun observeAllTags(): Flow<List<String>> = tagDao.observeAllTags()
    fun observeNote(id: String): Flow<NoteEntity?> = noteDao.observeById(id)
    fun observeBacklinks(id: String): Flow<List<NoteEntity>> = linkDao.observeBacklinks(id)

    fun search(raw: String): Flow<List<NoteEntity>> {
        val q = toFtsQuery(raw)
        return if (q.isEmpty()) flowOf(emptyList()) else noteDao.search(q)
    }

    suspend fun getNote(id: String): NoteEntity? = noteDao.getById(id)
    suspend fun getTags(noteId: String): List<String> = tagDao.tagsForNote(noteId)

    // ---------- save ----------
    /** Saves a note + its tags, refreshes the search index, marks it dirty for sync. */
    suspend fun saveNote(note: NoteEntity, tags: List<String>): NoteEntity =
        db.withTransaction {
            val saved = note.copy(updatedAt = System.currentTimeMillis(), dirty = true)
            noteDao.upsert(saved)

            noteDao.deleteFts(saved.id)
            noteDao.insertFts(saved.id, saved.title, saved.body)

            tagDao.clearRefs(saved.id)
            tags.map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
                .distinct()
                .forEach {
                    tagDao.insertTag(TagEntity(it))
                    tagDao.insertRef(NoteTagCrossRef(saved.id, it))
                }
            tagDao.deleteUnusedTags()
            saved
        }

    // ---------- quick actions ----------
    suspend fun setPinned(id: String, pinned: Boolean) = modify(id) { it.copy(pinned = pinned) }
    suspend fun setArchived(id: String, archived: Boolean) = modify(id) { it.copy(archived = archived) }
    suspend fun setColor(id: String, color: String) = modify(id) { it.copy(color = color) }

    suspend fun moveToTrash(id: String) = modify(id) {
        it.copy(deleted = true, deletedAt = System.currentTimeMillis(), pinned = false)
    }

    suspend fun restore(id: String) = modify(id) { it.copy(deleted = false, deletedAt = null) }

    private suspend fun modify(id: String, change: (NoteEntity) -> NoteEntity) {
        val current = noteDao.getById(id) ?: return
        noteDao.upsert(
            change(current).copy(updatedAt = System.currentTimeMillis(), dirty = true)
        )
    }

    // ---------- permanent delete ----------
    suspend fun deleteForever(id: String) = db.withTransaction {
        noteDao.deleteFts(id)
        linkDao.clearFrom(id)
        noteDao.deleteForever(id)
        tagDao.deleteUnusedTags()
    }

    suspend fun emptyTrash() {
        // read the current trash once and delete each
        val ids = noteDao.getExpiredTrashIds(Long.MAX_VALUE)
        ids.forEach { deleteForever(it) }
    }

    /** Called periodically: deletes notes that were in Trash for more than 30 days. */
    suspend fun purgeExpiredTrash(days: Long = 30) {
        val limit = System.currentTimeMillis() - days * 24 * 60 * 60 * 1000
        noteDao.getExpiredTrashIds(limit).forEach { deleteForever(it) }
    }

    // ---------- helpers ----------
    /** "hello wor" -> "hello* wor*" (prefix search, all words must match). */
    private fun toFtsQuery(raw: String): String =
        raw.trim().split(Regex("\\s+"))
            .map { it.replace(Regex("[^\\p{L}\\p{N}]"), "") }
            .filter { it.isNotEmpty() }
            .joinToString(" ") { "$it*" }
}