package com.vasanth.vaultnote.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.vasanth.vaultnote.data.NoteRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ReminderEntryPoint {
    fun repository(): NoteRepository
    fun scheduler(): ReminderScheduler
}

class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val ep = EntryPointAccessors.fromApplication(app, ReminderEntryPoint::class.java)
        val pending = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    ReminderScheduler.ACTION_FIRE -> {
                        val id = intent.getStringExtra(ReminderScheduler.EXTRA_NOTE_ID)
                        if (id != null) fire(app, ep, id)
                    }
                    Intent.ACTION_BOOT_COMPLETED,
                    Intent.ACTION_MY_PACKAGE_REPLACED -> ep.repository().rescheduleAll()
                }
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun fire(ctx: Context, ep: ReminderEntryPoint, noteId: String) {
        val repo = ep.repository()
        val note = repo.getNote(noteId) ?: return
        if (note.deleted) return
        val at = note.reminderAt ?: return

        Notifier.showReminder(ctx, note)

        // Repeating reminder: move to the next occurrence and schedule it.
        if (note.repeat != null) {
            val next = ep.scheduler().nextTrigger(at, note.repeat, maxOf(System.currentTimeMillis(), at))
            repo.advanceReminder(noteId, next)
        }
    }
}