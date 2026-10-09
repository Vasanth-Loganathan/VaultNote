package com.vasanth.vaultnote.ui.list

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vasanth.vaultnote.data.BoardOps
import com.vasanth.vaultnote.data.NoteRepository
import com.vasanth.vaultnote.data.db.NoteDao
import com.vasanth.vaultnote.data.db.NoteEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

object ListMode {
    const val ACTIVE = "active"
    const val ARCHIVE = "archive"
    const val TRASH = "trash"
}

@HiltViewModel
class NotesListViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repo: NoteRepository,
    private val noteDao: NoteDao,
    private val boards: BoardOps
) : ViewModel() {

    val mode: String = savedStateHandle["mode"] ?: ListMode.ACTIVE

    private val query = MutableStateFlow("")
    val selectedTag = MutableStateFlow<String?>(null)
    val currentQuery: String get() = query.value

    /** Ids of the notes selected in multi-select mode. */
    val selected = MutableStateFlow<Set<String>>(emptySet())

    val tags: StateFlow<List<String>> = repo.observeAllTags()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val items: StateFlow<List<ListItem>> = combine(query, selectedTag) { q, t -> q to t }
        .flatMapLatest { (q, t) ->
            when {
                mode == ListMode.TRASH -> repo.observeTrash()
                mode == ListMode.ARCHIVE -> repo.observeArchived()
                q.isNotBlank() -> repo.search(q)
                t != null -> repo.observeByTag(t)
                else -> repo.observeActive()
            }
        }
        .map { notes -> buildItems(notes) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        if (mode == ListMode.ACTIVE) viewModelScope.launch { repo.purgeExpiredTrash() }
    }

    private fun buildItems(notes: List<NoteEntity>): List<ListItem> {
        if (mode != ListMode.ACTIVE || query.value.isNotBlank()) return notes.map { ListItem.Row(it) }
        val pinned = notes.filter { it.pinned }
        val others = notes.filter { !it.pinned }
        if (pinned.isEmpty()) return others.map { ListItem.Row(it) }
        return buildList {
            add(ListItem.Header("Pinned"))
            pinned.forEach { add(ListItem.Row(it)) }
            if (others.isNotEmpty()) {
                add(ListItem.Header("Others"))
                others.forEach { add(ListItem.Row(it)) }
            }
        }
    }

    fun setQuery(q: String) {
        if (q != query.value) { query.value = q; clearSelection() }
    }

    fun selectTag(tag: String?) {
        if (tag != selectedTag.value) { selectedTag.value = tag; clearSelection() }
    }

    // ---------- selection ----------
    fun toggleSelected(id: String) {
        selected.value = if (id in selected.value) selected.value - id else selected.value + id
    }

    fun selectAll(ids: Collection<String>) { selected.value = ids.toSet() }

    fun clearSelection() { if (selected.value.isNotEmpty()) selected.value = emptySet() }

    /** Drops selected ids that are no longer in the list. */
    fun retainSelection(valid: Set<String>) {
        val cur = selected.value
        if (cur.isEmpty()) return
        val keep = cur.intersect(valid)
        if (keep != cur) selected.value = keep
    }

    private fun takeSelection(): List<String> {
        val ids = selected.value.toList()
        selected.value = emptySet()
        return ids
    }

    fun pinSelected(pin: Boolean) {
        val ids = takeSelection()
        viewModelScope.launch { ids.forEach { repo.setPinned(it, pin) } }
    }

    fun archiveSelected(archive: Boolean): List<String> {
        val ids = takeSelection()
        setArchivedIds(ids, archive)
        return ids
    }

    fun setArchivedIds(ids: List<String>, archive: Boolean) {
        viewModelScope.launch { ids.forEach { repo.setArchived(it, archive) } }
    }

    fun colorSelected(color: String) {
        val ids = takeSelection()
        viewModelScope.launch { ids.forEach { repo.setColor(it, color) } }
    }

    fun trashSelected(): List<String> {
        val ids = takeSelection()
        viewModelScope.launch { ids.forEach { id -> noteDao.getById(id)?.let { boards.trash(it) } } }
        return ids
    }

    fun restoreIds(ids: List<String>) {
        viewModelScope.launch { ids.forEach { id -> noteDao.getById(id)?.let { boards.restore(it) } } }
    }

    fun restoreSelected() = restoreIds(takeSelection())

    fun deleteSelectedForever() {
        val ids = takeSelection()
        viewModelScope.launch { ids.forEach { id -> noteDao.getById(id)?.let { boards.deleteForever(it) } } }
    }

    // ---------- single note (used by the Trash tap menu) ----------
    fun setPinned(id: String, pinned: Boolean) = viewModelScope.launch { repo.setPinned(id, pinned) }
    fun setArchived(id: String, archived: Boolean) = viewModelScope.launch { repo.setArchived(id, archived) }
    fun moveToTrash(id: String) = viewModelScope.launch { noteDao.getById(id)?.let { boards.trash(it) } }
    fun restore(id: String) = viewModelScope.launch { noteDao.getById(id)?.let { boards.restore(it) } }
    fun deleteForever(id: String) = viewModelScope.launch { noteDao.getById(id)?.let { boards.deleteForever(it) } }
    fun emptyTrash() = viewModelScope.launch { repo.emptyTrash() }
    fun createBoard(title: String, onCreated: (String) -> Unit) =
        viewModelScope.launch { onCreated(boards.createBoard(title)) }
}