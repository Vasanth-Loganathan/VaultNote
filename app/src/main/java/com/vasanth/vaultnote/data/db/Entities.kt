package com.vasanth.vaultnote.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

object NoteType {
    const val NOTE = "note"
    const val CHECKLIST = "checklist"
    const val BOARD = "board"
}

@Entity(
    tableName = "notes",
    indices = [Index("updatedAt"), Index("deleted", "archived"), Index("boardId")]
)
data class NoteEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val type: String = NoteType.NOTE,
    val title: String = "",
    val body: String = "",
    val itemsJson: String = "[]",
    val color: String = "default",
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val locked: Boolean = false,
    val reminderAt: Long? = null,
    val repeat: String? = null,          // null, "daily", "weekly"
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val deleted: Boolean = false,        // true = in Trash
    val deletedAt: Long? = null,
    val dirty: Boolean = true,           // needs upload to Drive
    val syncedAt: Long? = null,
    val driveFileId: String? = null,
    val remoteModifiedTime: Long? = null,
    val boardId: String? = null,         // set on cards: the board (a note of type "board") they live in
    val columnId: String? = null,
    @ColumnInfo(defaultValue = "0") val boardPos: Long = 0,
    val attachmentsJson: String = "[]"
)
@Entity(tableName = "tags")
data class TagEntity(@PrimaryKey val name: String)

@Entity(
    tableName = "note_tag",
    primaryKeys = ["noteId", "tagName"],
    foreignKeys = [
        ForeignKey(NoteEntity::class, ["id"], ["noteId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(TagEntity::class, ["name"], ["tagName"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("tagName")]
)
data class NoteTagCrossRef(val noteId: String, val tagName: String)

/** Full-text search table. We maintain it manually from the repository. */
@Fts4(notIndexed = ["noteId"])
@Entity(tableName = "notes_fts")
data class NoteFts(
    @PrimaryKey @ColumnInfo(name = "rowid") val rowId: Int = 0,
    val noteId: String,
    val title: String,
    val body: String
)

@Entity(
    tableName = "links",
    primaryKeys = ["fromId", "toId"],
    indices = [Index("toId")]
)
data class LinkEntity(val fromId: String, val toId: String)

@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val key: String,
    val value: String
)