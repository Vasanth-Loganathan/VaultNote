package com.vasanth.vaultnote.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.vasanth.vaultnote.data.db.NoteEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /** Makes the alarm match the note: schedules it, or cancels it if there is nothing to fire. */
    fun sync(note: NoteEntity) {
        val at = note.reminderAt
        if (note.deleted || at == null) {
            cancel(note.id); return
        }
        val trigger = nextTrigger(at, note.repeat, System.currentTimeMillis())
        if (trigger == null) cancel(note.id) else schedule(note.id, trigger)
    }

    fun cancel(noteId: String) = alarmManager.cancel(pendingIntent(noteId))

    private fun schedule(noteId: String, at: Long) {
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pendingIntent(noteId))
    }

    private fun pendingIntent(noteId: String): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_FIRE
            data = Uri.parse("vaultnote://reminder/$noteId")
            putExtra(EXTRA_NOTE_ID, noteId)
        }
        return PendingIntent.getBroadcast(
            context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * First fire time strictly after [after].
     * One-time reminders return null once they are in the past.
     */
    fun nextTrigger(reminderAt: Long, repeat: String?, after: Long): Long? {
        if (reminderAt > after) return reminderAt
        val stepDays = when (repeat) {
            "daily" -> 1L
            "weekly" -> 7L
            else -> return null
        }
        val zone = ZoneId.systemDefault()
        val start = Instant.ofEpochMilli(reminderAt).atZone(zone)
        val now = Instant.ofEpochMilli(after).atZone(zone)
        val jump = ChronoUnit.DAYS.between(start, now) / stepDays * stepDays
        var candidate = start.plusDays(jump)
        while (candidate.toInstant().toEpochMilli() <= after) candidate = candidate.plusDays(stepDays)
        return candidate.toInstant().toEpochMilli()
    }

    companion object {
        const val ACTION_FIRE = "com.vasanth.vaultnote.REMINDER_FIRE"
        const val EXTRA_NOTE_ID = "note_id"
    }
}