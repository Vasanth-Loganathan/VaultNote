package com.vasanth.vaultnote.ui.board

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vasanth.vaultnote.data.BoardColumn
import com.vasanth.vaultnote.data.BoardJson
import com.vasanth.vaultnote.data.BoardOps
import com.vasanth.vaultnote.data.db.NoteDao
import com.vasanth.vaultnote.data.db.NoteEntity
import com.vasanth.vaultnote.data.db.TagDao
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.util.Collections
import javax.inject.Inject

data class CardUi(val note: NoteEntity, val tags: List<String>)
data class ColumnUi(val column: BoardColumn, val cards: List<CardUi>)
data class BoardUi(val board: NoteEntity, val columns: List<ColumnUi>)

@HiltViewModel
class BoardViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    noteDao: NoteDao,
    tagDao: TagDao,
    private val ops: BoardOps
) : ViewModel() {

    val boardId: String = checkNotNull(savedStateHandle["boardId"])
    private var current: BoardUi? = null

    /** null = the board no longer exists or is in the trash. */
    val ui: Flow<BoardUi?> = combine(
        noteDao.observeById(boardId), noteDao.observeCards(boardId), tagDao.observeBoardTags(boardId)
    ) { b, cards, tagRows ->
        if (b == null || b.deleted) {
            null
        } else {
            val tagMap = tagRows.groupBy({ it.noteId }, { it.tagName })
            val cols = BoardJson.decode(b.itemsJson)
            val known = cols.map { it.id }.toSet()
            val orphans = cards.filter { it.columnId !in known }      // column removed elsewhere: show in the first column
            BoardUi(
                b,
                cols.mapIndexed { i, c ->
                    val mine = cards.filter { it.columnId == c.id } + if (i == 0) orphans else emptyList()
                    ColumnUi(c, mine.map { CardUi(it, tagMap[it.id].orEmpty()) })
                }
            )
        }
    }.onEach { current = it }

    private fun cols() = current?.columns?.map { it.column } ?: emptyList()
    private fun endPos(columnId: String): Long =
        (current?.columns?.firstOrNull { it.column.id == columnId }?.cards?.maxOfOrNull { it.note.boardPos } ?: -1L) + 1

    // ----- board -----
    fun renameBoard(title: String) {
        val b = current?.board ?: return
        viewModelScope.launch { ops.saveBoard(b.copy(title = title), cols()) }
    }

    fun setColor(color: String) {
        val b = current?.board ?: return
        viewModelScope.launch { ops.saveBoard(b.copy(color = color), cols()) }
    }

    // ----- columns -----
    fun addColumn(name: String) {
        val b = current?.board ?: return
        viewModelScope.launch { ops.saveBoard(b, cols() + BoardColumn(name = name)) }
    }

    fun renameColumn(id: String, name: String) {
        val b = current?.board ?: return
        viewModelScope.launch {
            ops.saveBoard(b, cols().map { if (it.id == id) it.copy(name = name) else it })
        }
    }

    fun moveColumn(id: String, delta: Int) {
        val b = current?.board ?: return
        val list = cols().toMutableList()
        val i = list.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j !in list.indices) return
        Collections.swap(list, i, j)
        viewModelScope.launch { ops.saveBoard(b, list) }
    }

    /** moveTo = another column id, or null to turn the cards into normal notes. */
    fun deleteColumn(id: String, moveTo: String?) {
        val ui = current ?: return
        val col = ui.columns.firstOrNull { it.column.id == id } ?: return
        val remaining = ui.columns.map { it.column }.filter { it.id != id }
        viewModelScope.launch {
            var pos = if (moveTo != null) endPos(moveTo) else 0L
            for (c in col.cards) {
                val n = c.note
                ops.saveCard(
                    if (moveTo != null) n.copy(columnId = moveTo, boardPos = pos++)
                    else n.copy(boardId = null, columnId = null, boardPos = 0)
                )
            }
            ops.saveBoard(ui.board, remaining)
        }
    }

    // ----- cards -----
    fun addCard(columnId: String, title: String, type: String) {
        val pos = endPos(columnId)
        viewModelScope.launch { ops.addCard(boardId, columnId, title, pos, type) }
    }

    /** From the card menu: to the end of another column. */
    fun moveCard(card: NoteEntity, toColumn: String) {
        val pos = endPos(toColumn)
        viewModelScope.launch { ops.saveCard(card.copy(columnId = toColumn, boardPos = pos)) }
    }

    /**
     * Drag and drop. index = insertion position in the destination column's current list
     * (the dragged card may still be in it when dropped in its own column).
     */
    fun dropCard(cardId: String, toColumn: String, index: Int) {
        val ui = current ?: return
        val card = ui.columns.flatMap { it.cards }.map { it.note }.firstOrNull { it.id == cardId } ?: return
        val dest = ui.columns.firstOrNull { it.column.id == toColumn }
            ?.cards?.map { it.note }?.toMutableList() ?: return

        var target = index.coerceIn(0, dest.size)
        val old = dest.indexOfFirst { it.id == cardId }
        if (old >= 0) {
            dest.removeAt(old)
            if (old < target) target--
        }
        dest.add(target.coerceIn(0, dest.size), card)

        viewModelScope.launch {
            dest.forEachIndexed { i, n ->
                if (n.boardPos != i.toLong() || n.columnId != toColumn) {
                    ops.saveCard(n.copy(columnId = toColumn, boardPos = i.toLong()))
                }
            }
        }
    }

    fun setColumnColor(id: String, color: String) {
        val b = current?.board ?: return
        viewModelScope.launch {
            ops.saveBoard(b, cols().map { if (it.id == id) it.copy(color = color) else it })
        }
    }

    suspend fun boardChoices() = ops.boardChoices()

    fun moveToBoard(card: NoteEntity, boardId: String, columnId: String) {
        viewModelScope.launch { ops.moveToBoard(listOf(card.id), boardId, columnId) }
    }

    fun moveOut(card: NoteEntity) { viewModelScope.launch { ops.moveOut(card) } }

    /** Undo of "Move out of board": restores the card exactly as it was. */
    fun putBack(card: NoteEntity) { viewModelScope.launch { ops.saveCard(card) } }

    fun trashCard(card: NoteEntity) { viewModelScope.launch { ops.trash(card) } }
    fun restoreCard(card: NoteEntity) { viewModelScope.launch { ops.restore(card) } }
}