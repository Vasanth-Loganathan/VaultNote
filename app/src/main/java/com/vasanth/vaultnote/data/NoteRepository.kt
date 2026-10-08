package com.vasanth.vaultnote.data

import androidx.room.withTransaction
import com.vasanth.vaultnote.data.db.*
import com.vasanth.vaultnote.reminder.ReminderScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NoteRepository @Inject constructor(
    private val db: VaultDatabase,
    private val noteDao: NoteDao,
    private val tagDao: TagDao,
    private val linkDao: LinkDao,
    private val scheduler: ReminderScheduler
) {
    // ---------- observe ----------
    fun observeActive(): Flow<List<NoteEntity>> = noteDao.observeActive()
    fun observeArchived(): Flow<List<NoteEntity>> = noteDao.observeArchived()
    fun observeTrash(): Flow<List<NoteEntity>> = noteDao.observeTrash()
    fun observeByTag(tag: String): Flow<List<NoteEntity>> = noteDao.observeByTag(tag)
    fun observeAllTags(): Flow<List<String>> = tagDao.observeAllTags()
    fun observeNote(id: String): Flow<NoteEntity?> = noteDao.observeById(id)
    fun observeBacklinks(id: String): Flow<List<NoteEntity>> = linkDao.observeBacklinks(id)
    fun observeWithReminders(): Flow<List<NoteEntity>> = noteDao.observeWithReminders()

    fun search(raw: String): Flow<List<NoteEntity>> {
        val q = toFtsQuery(raw)
        return if (q.isEmpty()) flowOf(emptyList()) else noteDao.search(q)
    }

    suspend fun getNote(id: String): NoteEntity? = noteDao.getById(id)
    suspend fun getTags(noteId: String): List<String> = tagDao.tagsForNote(noteId)

    // ---------- save ----------
    /** Saves note + tags, refreshes search index and [[links]], marks dirty, updates the alarm. */
    suspend fun saveNote(note: NoteEntity, tags: List<String>): NoteEntity {
        val saved = db.withTransaction {
            val s = note.copy(updatedAt = System.currentTimeMillis(), dirty = true)
            noteDao.upsert(s)

            val indexBody = if (s.type == NoteType.CHECKLIST)
                ChecklistJson.decode(s.itemsJson).joinToString(" ") { it.text }
            else s.body
            noteDao.deleteFts(s.id)
            if (!s.locked) noteDao.insertFts(s.id, s.title, indexBody)   // locked notes are not searchable

            tagDao.clearRefs(s.id)
            tags.map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
                .distinct()
                .forEach {
                    tagDao.insertTag(TagEntity(it))
                    tagDao.insertRef(NoteTagCrossRef(s.id, it))
                }
            tagDao.deleteUnusedTags()

            // rebuild outgoing [[links]]
            linkDao.clearFrom(s.id)
            extractLinkTitles(s).forEach { title ->
                val target = noteDao.findByTitle(title)
                if (target != null && target.id != s.id) linkDao.insert(LinkEntity(s.id, target.id))
            }
            s
        }
        scheduler.sync(saved)
        return saved
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
        val updated = change(current).copy(updatedAt = System.currentTimeMillis(), dirty = true)
        noteDao.upsert(updated)
        scheduler.sync(updated)
    }

    // ---------- reminders ----------
    /** Tick on the Today screen: clears a one-time reminder, or jumps a repeating one forward. */
    suspend fun completeReminder(id: String) {
        val n = noteDao.getById(id) ?: return
        val at = n.reminderAt ?: return
        val next = if (n.repeat != null)
            scheduler.nextTrigger(at, n.repeat, System.currentTimeMillis()) else null
        val updated = n.copy(
            reminderAt = next,
            repeat = if (next == null) null else n.repeat,
            updatedAt = System.currentTimeMillis(),
            dirty = true
        )
        noteDao.upsert(updated)
        scheduler.sync(updated)
    }

    /** Used after a repeating reminder fires. Does not change updatedAt. */
    suspend fun advanceReminder(id: String, next: Long?) {
        val n = noteDao.getById(id) ?: return
        val updated = n.copy(reminderAt = next, repeat = if (next == null) null else n.repeat)
        noteDao.upsert(updated)
        scheduler.sync(updated)
    }

    /** After reboot / app update. */
    suspend fun rescheduleAll() {
        val now = System.currentTimeMillis()
        noteDao.getWithReminders().forEach { n ->
            val at = n.reminderAt ?: return@forEach
            val next = scheduler.nextTrigger(at, n.repeat, now)
            val current = if (next != null && next != at) {
                n.copy(reminderAt = next).also { noteDao.upsert(it) }
            } else n
            scheduler.sync(current)
        }
    }

    // ---------- permanent delete ----------
    suspend fun deleteForever(id: String) {
        db.withTransaction {
            noteDao.deleteFts(id)
            linkDao.clearFrom(id)
            noteDao.deleteForever(id)
            tagDao.deleteUnusedTags()
        }
        scheduler.cancel(id)
    }

    suspend fun emptyTrash() {
        noteDao.getExpiredTrashIds(Long.MAX_VALUE).forEach { deleteForever(it) }
    }

    /** Deletes notes that were in Trash for more than [days] days. */
    suspend fun purgeExpiredTrash(days: Long = 30) {
        val limit = System.currentTimeMillis() - days * 24 * 60 * 60 * 1000
        noteDao.getExpiredTrashIds(limit).forEach { deleteForever(it) }
    }

    suspend fun findByTitle(title: String): NoteEntity? = noteDao.findByTitle(title.trim())

    // ---------- helpers ----------
    private val linkRegex = Regex("\\[\\[([^\\[\\]]+)\\]\\]")

    private fun extractLinkTitles(note: NoteEntity): List<String> {
        val text = if (note.type == NoteType.CHECKLIST)
            ChecklistJson.decode(note.itemsJson).joinToString("\n") { it.text }
        else note.body
        return linkRegex.findAll(text)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
    }

    /** "hello wor" -> "hello* wor*" (prefix search, all words must match). */
    private fun toFtsQuery(raw: String): String =
        raw.trim().split(Regex("\\s+"))
            .map { it.replace(Regex("[^\\p{L}\\p{N}]"), "") }
            .filter { it.isNotEmpty() }
            .joinToString(" ") { "$it*" }
}