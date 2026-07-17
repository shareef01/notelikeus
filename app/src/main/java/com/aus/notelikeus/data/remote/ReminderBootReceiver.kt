package com.aus.notelikeus.data.remote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.aus.notelikeus.domain.repository.NoteRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class ReminderBootReceiver : BroadcastReceiver() {

    @Inject lateinit var repository: NoteRepository
    @Inject lateinit var reminderScheduler: ReminderScheduler

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val now = System.currentTimeMillis()
                repository.getNotesWithActiveReminders(now).forEach { note ->
                    val noteId = note.id ?: return@forEach
                    if (note.isLocked) return@forEach
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
                    if (!note.isLocked) {
                        ReminderReceiver.showReminderNotification(context, noteId)
                    }
                    repository.clearReminderTimestamp(noteId)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
