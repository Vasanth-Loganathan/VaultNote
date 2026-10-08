package com.vasanth.vaultnote.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vasanth.vaultnote.data.NoteRepository
import com.vasanth.vaultnote.data.db.NoteEntity
import com.vasanth.vaultnote.ui.list.ListItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

@HiltViewModel
class TodayViewModel @Inject constructor(
    private val repo: NoteRepository
) : ViewModel() {

    val items: StateFlow<List<ListItem>> = repo.observeWithReminders()
        .map { build(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private fun build(notes: List<NoteEntity>): List<ListItem> {
        val now = System.currentTimeMillis()
        val endOfToday = LocalDate.now().plusDays(1)
            .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

        val overdue = notes.filter { (it.reminderAt ?: 0L) < now }
        val today = notes.filter { (it.reminderAt ?: 0L) in now until endOfToday }
        val upcoming = notes.filter { (it.reminderAt ?: 0L) >= endOfToday }

        return buildList {
            fun section(title: String, list: List<NoteEntity>) {
                if (list.isEmpty()) return
                add(ListItem.Header(title))
                list.forEach { add(ListItem.Row(it)) }
            }
            section("Overdue", overdue)
            section("Today", today)
            section("Upcoming", upcoming)
        }
    }

    fun done(id: String) = viewModelScope.launch { repo.completeReminder(id) }
}