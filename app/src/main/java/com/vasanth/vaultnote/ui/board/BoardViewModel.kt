package com.vasanth.vaultnote.ui.board

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vasanth.vaultnote.data.BoardColumn
import com.vasanth.vaultnote.data.BoardJson
import com.vasanth.vaultnote.data.BoardOps
import com.vasanth.vaultnote.data.db.NoteDao
import com.vasanth.vaultnote.data.db.NoteEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.util.Collections
import javax.inject.Inject

data class ColumnUi(val column: BoardColumn, val cards: List<NoteEntity>)
data class BoardUi(val board: NoteEntity, val columns: List<ColumnUi>)

@HiltViewModel
class BoardViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    noteDao: NoteDao,
    private val ops: BoardOps
) : ViewModel() {

    val boardId: String = checkNotNull(savedStateHandle["boardId"])
    private var current: BoardUi? = null

    /** null = the board no longer exists or is in the trash. */
    val ui: Flow<BoardUi?> = combine(noteDao.observeById(boardId), noteDao.observeCards(boardId)) { b, cards ->
        if (b == null || b.deleted) null
        else BoardUi(
            b,
            BoardJson.decode(b.itemsJson).map { c -> ColumnUi(c, cards.filter { it.columnId == c.id }) }
        )
    }.onEach { current = it }

    private fun cols() = current?.columns?.map { it.column } ?: emptyList()
    private fun endPos(columnId: String): Long =
        (current?.columns?.firstOrNull { it.column.id == columnId }?.cards?.maxOfOrNull { it.boardPos } ?: -1L) + 1

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
        if (remaining.isEmpty()) return
        viewModelScope.launch {
            var pos = if (moveTo != null) endPos(moveTo) else 0L
            for (c in col.cards) {
                ops.saveCard(
                    if (moveTo != null) c.copy(columnId = moveTo, boardPos = pos++)
                    else c.copy(boardId = null, columnId = null, boardPos = 0)
                )
            }
            ops.saveBoard(ui.board, remaining)
        }
    }

    // ----- cards -----
    fun addCard(columnId: String, title: String) {
        val pos = endPos(columnId)
        viewModelScope.launch { ops.addCard(boardId, columnId, title, pos) }
    }

    fun moveCard(card: NoteEntity, toColumn: String) {
        val pos = endPos(toColumn)
        viewModelScope.launch { ops.saveCard(card.copy(columnId = toColumn, boardPos = pos)) }
    }

    fun reorder(columnId: String, orderedIds: List<String>) {
        val cards = current?.columns?.firstOrNull { it.column.id == columnId }?.cards ?: return
        val byId = cards.associateBy { it.id }
        viewModelScope.launch {
            orderedIds.forEachIndexed { i, id ->
                val c = byId[id]
                if (c != null && c.boardPos != i.toLong()) ops.saveCard(c.copy(boardPos = i.toLong()))
            }
        }
    }

    fun trashCard(card: NoteEntity) { viewModelScope.launch { ops.trash(card) } }
    fun restoreCard(card: NoteEntity) { viewModelScope.launch { ops.restore(card) } }
}