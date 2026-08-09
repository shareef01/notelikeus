package com.aus.notelikeus.data.remote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.aus.notelikeus.domain.repository.NoteRepository
import com.aus.notelikeus.data.ReminderScheduler
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ReminderBootReceiver : BroadcastReceiver(), KoinComponent {

    private val repository: NoteRepository by inject()
    private val reminderScheduler: ReminderScheduler by inject()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val now = System.currentTimeMillis()
                repository.getNotesWithActiveReminders(now).forEach { note ->
                    val noteId = note.id ?: return@forEach
                    val timestamp = note.reminderTimestamp ?: return@forEach
                    reminderScheduler.scheduleReminder(
                        noteId = noteId,
                        timestamp = timestamp
                    )
                }
                // Reminders due while the device was off never fire and, left alone, keep
                // showing as "active" forever since nothing else clears them. Fire a catch-up
                // notification for each and clear the timestamp so the note's reminder state
                // reflects reality again.
                repository.getNotesWithMissedReminders(now).forEach { note ->
                    val noteId = note.id ?: return@forEach
                    ReminderReceiver.showReminderNotification(context, noteId)
                    repository.clearReminderTimestamp(noteId)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
