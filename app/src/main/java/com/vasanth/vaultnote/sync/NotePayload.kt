package com.vasanth.vaultnote.sync

import com.vasanth.vaultnote.data.BoardColumn
import com.vasanth.vaultnote.data.BoardJson
import com.vasanth.vaultnote.data.CheckItem
import com.vasanth.vaultnote.data.ChecklistJson
import com.vasanth.vaultnote.data.db.NoteEntity
import com.vasanth.vaultnote.data.db.NoteType
import kotlinx.serialization.Serializable

@Serializable
data class NotePayload(
    val v: Int = 1,
    val type: String = "note",
    val title: String = "",
    val body: String = "",
    val items: List<CheckItem> = emptyList(),
    val tags: List<String> = emptyList(),
    val color: String = "default",
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val locked: Boolean = false,
    val reminderAt: Long? = null,
    val repeat: String? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val trashed: Boolean = false,
    val trashedAt: Long? = null,
    // boards (type "board" has columns; cards carry boardId, columnId, boardPos)
    val columns: List<BoardColumn> = emptyList(),
    val boardId: String? = null,
    val columnId: String? = null,
    val boardPos: Long = 0
) {
    fun toEntity(id: String, fileId: String, remoteTime: Long) = NoteEntity(
        id = id, type = type, title = title, body = body,
        itemsJson = if (type == NoteType.BOARD) BoardJson.encode(columns) else ChecklistJson.encode(items),
        color = color, pinned = pinned, archived = archived, locked = locked,
        reminderAt = reminderAt, repeat = repeat,
        createdAt = createdAt, updatedAt = updatedAt,
        deleted = trashed, deletedAt = trashedAt,
        dirty = false, syncedAt = System.currentTimeMillis(),
        driveFileId = fileId, remoteModifiedTime = remoteTime,
        boardId = boardId, columnId = columnId, boardPos = boardPos
    )

    companion object {
        fun from(n: NoteEntity, tags: List<String>): NotePayload {
            val isBoard = n.type == NoteType.BOARD
            return NotePayload(
                type = n.type, title = n.title, body = n.body,
                items = if (isBoard) emptyList() else ChecklistJson.decode(n.itemsJson),
                tags = tags, color = n.color, pinned = n.pinned, archived = n.archived, locked = n.locked,
                reminderAt = n.reminderAt, repeat = n.repeat,
                createdAt = n.createdAt, updatedAt = n.updatedAt,
                trashed = n.deleted, trashedAt = n.deletedAt,
                columns = if (isBoard) BoardJson.decode(n.itemsJson) else emptyList(),
                boardId = n.boardId, columnId = n.columnId, boardPos = n.boardPos
            )
        }
    }
}