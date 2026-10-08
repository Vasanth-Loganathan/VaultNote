package com.vasanth.vaultnote.ui.editor

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vasanth.vaultnote.data.CheckItem
import com.vasanth.vaultnote.data.ChecklistJson
import com.vasanth.vaultnote.data.NoteRepository
import com.vasanth.vaultnote.data.db.NoteEntity
import com.vasanth.vaultnote.data.db.NoteType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class NoteEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repo: NoteRepository
) : ViewModel() {

    private val argId: String? = savedStateHandle["noteId"]
    private val argType: String = savedStateHandle["type"] ?: NoteType.NOTE

    val isNew = argId == null

    /** The note being edited (always the latest in-memory version). */
    val state = MutableStateFlow(NoteEntity(type = argType))
    val tags = MutableStateFlow<List<String>>(emptyList())
    val loaded = MutableStateFlow(false)

    private var existsInDb = false
    private var saveJob: Job? = null

    init {
        if (argId == null) {
            loaded.value = true
        } else {
            viewModelScope.launch {
                repo.getNote(argId)?.let {
                    state.value = it
                    tags.value = repo.getTags(argId)
                    existsInDb = true
                }
                loaded.value = true
            }
        }
    }

    // ---------- edits ----------
    fun updateContent(title: String, body: String, items: List<CheckItem>?) {
        state.update {
            it.copy(
                title = title,
                body = body,
                itemsJson = items?.let(ChecklistJson::encode) ?: it.itemsJson
            )
        }
        scheduleSave()
    }

    fun togglePinned() { state.update { it.copy(pinned = !it.pinned) }; scheduleSave() }
    fun toggleArchived() { state.update { it.copy(archived = !it.archived) }; scheduleSave() }
    fun setColor(color: String) { state.update { it.copy(color = color) }; scheduleSave() }

    fun addTag(raw: String) {
        val t = raw.trim().lowercase()
        if (t.isEmpty() || t in tags.value) return
        tags.value = tags.value + t
        scheduleSave()
    }

    fun removeTag(tag: String) {
        tags.value = tags.value - tag
        scheduleSave()
    }

    fun deleteNote() {
        saveJob?.cancel()
        state.update {
            it.copy(deleted = true, deletedAt = System.currentTimeMillis(), pinned = false)
        }
        viewModelScope.launch { saveNow() }
    }

    // ---------- saving ----------
    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(800)          // auto-save after the user pauses typing
            saveNow()
        }
    }

    /** Called when the screen pauses so nothing is lost. */
    fun flush() {
        saveJob?.cancel()
        viewModelScope.launch { saveNow() }
    }

    private suspend fun saveNow() {
        val n = state.value
        val checklistEmpty = ChecklistJson.decode(n.itemsJson).all { it.text.isBlank() }
        val isEmpty = n.title.isBlank() && n.body.isBlank() && checklistEmpty && tags.value.isEmpty()
        if (isEmpty && !existsInDb) return

        withContext(NonCancellable) {
            val toSave = if (n.type == NoteType.CHECKLIST) {
                val clean = ChecklistJson.decode(n.itemsJson).filter { it.text.isNotBlank() }
                n.copy(itemsJson = ChecklistJson.encode(clean))
            } else n
            repo.saveNote(toSave, tags.value)
            existsInDb = true
        }
    }
}