package com.vasanth.vaultnote.ui.list

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vasanth.vaultnote.data.NoteRepository
import com.vasanth.vaultnote.data.db.NoteEntity
import com.vasanth.vaultnote.data.BoardOps
import com.vasanth.vaultnote.data.db.NoteDao
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

    fun setQuery(q: String) { query.value = q }
    fun selectTag(tag: String?) { selectedTag.value = tag }

    fun setPinned(id: String, pinned: Boolean) = viewModelScope.launch { repo.setPinned(id, pinned) }
    fun setArchived(id: String, archived: Boolean) = viewModelScope.launch { repo.setArchived(id, archived) }
    fun moveToTrash(id: String) = viewModelScope.launch { noteDao.getById(id)?.let { boards.trash(it) } }
    fun restore(id: String) = viewModelScope.launch { noteDao.getById(id)?.let { boards.restore(it) } }
    fun deleteForever(id: String) = viewModelScope.launch { noteDao.getById(id)?.let { boards.deleteForever(it) } }
    fun createBoard(title: String, onCreated: (String) -> Unit) =
        viewModelScope.launch { onCreated(boards.createBoard(title)) }
    fun emptyTrash() = viewModelScope.launch { repo.emptyTrash() }
}