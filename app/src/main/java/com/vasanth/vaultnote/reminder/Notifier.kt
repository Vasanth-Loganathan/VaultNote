package com.vasanth.vaultnote.reminder

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.vasanth.vaultnote.MainActivity
import com.vasanth.vaultnote.R
import com.vasanth.vaultnote.data.ChecklistJson
import com.vasanth.vaultnote.data.db.NoteEntity
import com.vasanth.vaultnote.data.db.NoteType

object Notifier {
    const val CHANNEL_ID = "reminders"
    const val EXTRA_NOTE_ID = "open_note_id"

    fun createChannel(ctx: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID, "Reminders", NotificationManager.IMPORTANCE_HIGH
        ).apply { description = "Note and checklist reminders" }
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    @SuppressLint("MissingPermission")
    fun showReminder(ctx: Context, note: NoteEntity) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val open = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_NOTE_ID, note.id)
        }
        val pi = PendingIntent.getActivity(
            ctx, note.id.hashCode(), open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Locked notes never leak their content into a notification.
        val (title, text) =
            if (note.locked) Pair("Reminder", "You have a reminder for a locked note")
            else Pair(note.title.ifBlank { "Reminder" }, preview(note))

        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()

        NotificationManagerCompat.from(ctx).notify(note.id.hashCode(), notification)
    }

    private fun preview(note: NoteEntity): String =
        if (note.type == NoteType.CHECKLIST)
            ChecklistJson.decode(note.itemsJson)
                .filter { !it.done && it.text.isNotBlank() }
                .take(5).joinToString("\n") { "• " + it.text }
        else note.body.take(200)
}