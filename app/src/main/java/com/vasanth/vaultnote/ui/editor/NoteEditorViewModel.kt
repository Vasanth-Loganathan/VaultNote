package com.vasanth.vaultnote.ui.editor

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vasanth.vaultnote.data.CheckItem
import com.vasanth.vaultnote.data.ChecklistJson
import com.vasanth.vaultnote.data.NoteRepository
import com.vasanth.vaultnote.data.db.NoteEntity
import com.vasanth.vaultnote.data.db.NoteType
import com.vasanth.vaultnote.security.AppLockManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import android.net.Uri
import com.vasanth.vaultnote.data.Attachment
import com.vasanth.vaultnote.data.AttachmentException
import com.vasanth.vaultnote.data.AttachmentJson
import com.vasanth.vaultnote.data.AttachmentStore
import com.vasanth.vaultnote.data.MAX_ATTACHMENTS
import kotlinx.coroutines.flow.MutableSharedFlow

@HiltViewModel
class NoteEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repo: NoteRepository,
    private val appLock: AppLockManager,
    private val store: AttachmentStore
) : ViewModel() {

    private val argId: String? = savedStateHandle["noteId"]
    private val argType: String = savedStateHandle["type"] ?: NoteType.NOTE

    private val argTitle: String? = savedStateHandle["title"]
    val isNew = argId == null
    val noteId: String = argId ?: UUID.randomUUID().toString()

    /** The note being edited (always the latest in-memory version). */
    val state = MutableStateFlow(NoteEntity(id = noteId, type = argType, title = argTitle ?: ""))
    val tags = MutableStateFlow<List<String>>(emptyList())
    val loaded = MutableStateFlow(false)
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    val attachments: List<Attachment> get() = AttachmentJson.decode(state.value.attachmentsJson)

    /** Notes that contain a [[link]] to this note. */
    val backlinks: Flow<List<NoteEntity>> = repo.observeBacklinks(noteId)

    private var existsInDb = false
    private var saveJob: Job? = null
    private var savedState: NoteEntity? = null
    private var savedTags: List<String> = emptyList()

    init {
        if (argId == null) {
            loaded.value = true
        } else {
            viewModelScope.launch {
                repo.getNote(argId)?.let {
                    state.value = it
                    tags.value = repo.getTags(argId)
                    existsInDb = true
                    savedState = it
                    savedTags = tags.value
                }
                loaded.value = true
            }
        }
    }

    // ---------- locked notes ----------
    private var authEpoch = -1

    /** True if the note is locked and the user has not authenticated since the last app lock. */
    fun needsAuth(): Boolean = state.value.locked && authEpoch != appLock.lockEpoch

    fun markAuthenticated() { authEpoch = appLock.lockEpoch }

    fun toggleLocked() {
        val nowLocked = !state.value.locked
        state.update { it.copy(locked = nowLocked) }
        if (nowLocked) markAuthenticated()
        scheduleSave()
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

    fun setReminder(at: Long?, repeat: String?) {
        state.update { it.copy(reminderAt = at, repeat = if (at == null) null else repeat) }
        scheduleSave()
    }

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

    // ---------- images ----------
    fun addImages(uris: List<Uri>) {
        val room = MAX_ATTACHMENTS - attachments.size
        if (room <= 0) { messages.tryEmit("Maximum $MAX_ATTACHMENTS images per note"); return }
        val take = uris.take(room)
        if (take.size < uris.size) messages.tryEmit("Only $room more image(s) fit (max $MAX_ATTACHMENTS)")
        viewModelScope.launch {
            val added = mutableListOf<Attachment>()
            for (u in take) {
                try {
                    added += store.import(u)
                } catch (e: AttachmentException) {
                    messages.tryEmit(e.message ?: "Could not add the image")
                }
            }
            if (added.isNotEmpty()) {
                state.update {
                    it.copy(attachmentsJson = AttachmentJson.encode(AttachmentJson.decode(it.attachmentsJson) + added))
                }
                saveJob?.cancel()
                saveNow()
            }
        }
    }

    fun removeAttachment(id: String) {
        state.update {
            it.copy(attachmentsJson = AttachmentJson.encode(AttachmentJson.decode(it.attachmentsJson).filter { a -> a.id != id }))
        }
        scheduleSave()
    }

    suspend fun findNoteByTitle(title: String): NoteEntity? = repo.findByTitle(title)

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
        val isEmpty = n.title.isBlank() && n.body.isBlank() && checklistEmpty &&
                tags.value.isEmpty() && n.reminderAt == null && n.attachmentsJson == "[]"
        if (isEmpty && !existsInDb) return
        if (existsInDb && n == savedState && tags.value == savedTags) return   // nothing changed: do not touch the note

        withContext(NonCancellable) {
            val toSave = if (n.type == NoteType.CHECKLIST) {
                val clean = ChecklistJson.decode(n.itemsJson).filter { it.text.isNotBlank() }
                n.copy(itemsJson = ChecklistJson.encode(clean))
            } else n
            repo.saveNote(toSave, tags.value)
            existsInDb = true
            savedState = n
            savedTags = tags.value
        }
    }
}