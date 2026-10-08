package com.vasanth.vaultnote.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {
    @Upsert suspend fun upsert(note: NoteEntity)

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun getById(id: String): NoteEntity?

    @Query("SELECT * FROM notes WHERE id = :id")
    fun observeById(id: String): Flow<NoteEntity?>

    @Query("SELECT * FROM notes WHERE deleted = 0 AND archived = 0 ORDER BY pinned DESC, updatedAt DESC")
    fun observeActive(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE deleted = 0 AND archived = 1 ORDER BY updatedAt DESC")
    fun observeArchived(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE deleted = 1 ORDER BY deletedAt DESC")
    fun observeTrash(): Flow<List<NoteEntity>>

    @Query(
        """SELECT n.* FROM notes n INNER JOIN note_tag t ON t.noteId = n.id
           WHERE t.tagName = :tag AND n.deleted = 0 AND n.archived = 0
           ORDER BY n.pinned DESC, n.updatedAt DESC"""
    )
    fun observeByTag(tag: String): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE deleted = 0 AND reminderAt IS NOT NULL")
    suspend fun getWithReminders(): List<NoteEntity>

    @Query(
        """SELECT * FROM notes WHERE deleted = 0
           AND id IN (SELECT noteId FROM notes_fts WHERE notes_fts MATCH :ftsQuery)
           ORDER BY pinned DESC, updatedAt DESC"""
    )
    fun search(ftsQuery: String): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE deleted = 0 AND reminderAt IS NOT NULL ORDER BY reminderAt ASC")
    fun observeWithReminders(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE title = :title COLLATE NOCASE AND deleted = 0 LIMIT 1")
    suspend fun findByTitle(title: String): NoteEntity?

    @Query("SELECT * FROM notes WHERE dirty = 1")
    suspend fun getDirty(): List<NoteEntity>

    @Query("SELECT id FROM notes WHERE deleted = 1 AND deletedAt < :before")
    suspend fun getExpiredTrashIds(before: Long): List<String>

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun deleteForever(id: String)

    // ---- full-text index maintenance ----
    @Query("DELETE FROM notes_fts WHERE noteId = :id")
    suspend fun deleteFts(id: String)

    @Query("INSERT INTO notes_fts(noteId, title, body) VALUES(:id, :title, :body)")
    suspend fun insertFts(id: String, title: String, body: String)
}

@Dao
interface TagDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTag(tag: TagEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRef(ref: NoteTagCrossRef)

    @Query("DELETE FROM note_tag WHERE noteId = :noteId")
    suspend fun clearRefs(noteId: String)

    @Query("SELECT name FROM tags ORDER BY name")
    fun observeAllTags(): Flow<List<String>>

    @Query("SELECT tagName FROM note_tag WHERE noteId = :noteId ORDER BY tagName")
    suspend fun tagsForNote(noteId: String): List<String>

    @Query("DELETE FROM tags WHERE name NOT IN (SELECT DISTINCT tagName FROM note_tag)")
    suspend fun deleteUnusedTags()
}

@Dao
interface LinkDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(link: LinkEntity)

    @Query("DELETE FROM links WHERE fromId = :fromId")
    suspend fun clearFrom(fromId: String)

    @Query(
        """SELECT n.* FROM notes n INNER JOIN links l ON l.fromId = n.id
           WHERE l.toId = :noteId AND n.deleted = 0"""
    )
    fun observeBacklinks(noteId: String): Flow<List<NoteEntity>>
}

@Dao
interface SyncStateDao {
    @Upsert suspend fun put(state: SyncStateEntity)

    @Query("SELECT value FROM sync_state WHERE `key` = :key")
    suspend fun get(key: String): String?
}