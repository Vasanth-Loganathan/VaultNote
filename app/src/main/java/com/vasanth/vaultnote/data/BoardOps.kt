package com.vasanth.vaultnote.data

import com.vasanth.vaultnote.data.db.NoteDao
import com.vasanth.vaultnote.data.db.NoteEntity
import com.vasanth.vaultnote.data.db.NoteType
import com.vasanth.vaultnote.data.db.TagDao
import javax.inject.Inject
import javax.inject.Singleton

/** Board-aware actions. Trash, restore and delete cascade to a board's cards. */
@Singleton
class BoardOps @Inject constructor(
    private val repo: NoteRepository,
    private val noteDao: NoteDao,
    private val tagDao: TagDao
) {
    suspend fun createBoard(title: String): String {
        val b = NoteEntity(
            type = NoteType.BOARD, title = title,
            itemsJson = BoardJson.encode(emptyList())
        )
        repo.saveNote(b, emptyList())
        return b.id
    }

    suspend fun saveBoard(board: NoteEntity, columns: List<BoardColumn>) =
        repo.saveNote(board.copy(itemsJson = BoardJson.encode(columns)), tagDao.tagsForNote(board.id))

    suspend fun saveCard(card: NoteEntity) = repo.saveNote(card, tagDao.tagsForNote(card.id))

    suspend fun addCard(boardId: String, columnId: String, title: String, pos: Long, type: String) =
        repo.saveNote(
            NoteEntity(type = type, title = title, boardId = boardId, columnId = columnId, boardPos = pos),
            emptyList()
        )

    suspend fun trash(note: NoteEntity) {
        repo.moveToTrash(note.id)
        if (note.type == NoteType.BOARD)
            noteDao.getCardsOfBoard(note.id).filter { !it.deleted }.forEach { repo.moveToTrash(it.id) }
    }

    suspend fun restore(note: NoteEntity) {
        repo.restore(note.id)
        if (note.type == NoteType.BOARD)
            noteDao.getCardsOfBoard(note.id).filter { it.deleted }.forEach { repo.restore(it.id) }
    }

    suspend fun deleteForever(note: NoteEntity) {
        if (note.type == NoteType.BOARD)
            noteDao.getCardsOfBoard(note.id).forEach { repo.deleteForever(it.id) }
        repo.deleteForever(note.id)
    }
}