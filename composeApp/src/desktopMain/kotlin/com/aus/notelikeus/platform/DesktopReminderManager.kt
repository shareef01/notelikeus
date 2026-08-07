package com.aus.notelikeus.platform

import com.aus.notelikeus.data.local.dao.NoteDao
import com.aus.notelikeus.data.mapper.toNote
import com.aus.notelikeus.domain.platform.ReminderManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Timer
import java.util.TimerTask

/**
 * Desktop reminders, backed by a single [Timer].
 *
 * A Timer only lives as long as the process, so the scheduled set has to be rebuilt from the
 * database on every launch — see [restoreScheduledReminders]. Reminders still cannot fire while the
 * app is closed (there is no OS-level scheduler behind this the way Android has AlarmManager); the
 * catch-up pass is what keeps a missed reminder from silently sitting in the DB as "active"
 * forever.
 */
class DesktopReminderManager(
    private val noteDao: NoteDao
) : ReminderManager {

    private val timer = Timer("notelikeus-reminders", /* isDaemon = */ true)
    private val scheduledTasks = mutableMapOf<Long, TimerTask>()
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Set by the desktop UI so reminders surface through the window's existing tray icon.
     *
     * This used to build a throwaway AWT `TrayIcon` per notification from
     * `createImage("")` — a blank icon, added to the system tray and removed 10s later, leaking one
     * per overlapping reminder.
     */
    @Volatile
    var notify: ((title: String, message: String) -> Unit)? = null

    override fun scheduleReminder(noteId: Long, timestamp: Long) {
        cancelReminder(noteId)
        val delay = timestamp - System.currentTimeMillis()
        if (delay <= 0) return

        val task = object : TimerTask() {
            override fun run() {
                synchronized(lock) { scheduledTasks.remove(noteId) }
                scope.launch { fireReminder(noteId) }
            }
        }
        synchronized(lock) { scheduledTasks[noteId] = task }
        timer.schedule(task, delay)
    }

    override fun cancelReminder(noteId: Long) {
        synchronized(lock) { scheduledTasks.remove(noteId) }?.cancel()
    }

    /**
     * Rebuilds the timer schedule from stored notes, and fires a catch-up notification for any
     * reminder that came due while the app was closed (clearing it afterwards so the note's
     * reminder state reflects reality). Mirrors Android's `ReminderBootReceiver`.
     *
     * Call once at startup. Runs off the caller's thread.
     */
    fun restoreScheduledReminders() {
        scope.launch {
            val now = System.currentTimeMillis()

            noteDao.getNotesWithActiveReminders(now).forEach { entity ->
                val note = entity.toNote()
                val noteId = note.id ?: return@forEach
                val timestamp = note.reminderTimestamp ?: return@forEach
                scheduleReminder(noteId, timestamp)
            }

            noteDao.getNotesWithMissedReminders(now).forEach { entity ->
                val noteId = entity.toNote().id ?: return@forEach
                fireReminder(noteId)
                noteDao.clearReminderTimestamp(noteId)
            }
        }
    }

    private suspend fun fireReminder(noteId: Long) {
        val note = noteDao.getNoteById(noteId)?.toNote()
        val title = note?.title?.takeIf { it.isNotBlank() } ?: DEFAULT_TITLE
        val body = note?.content?.takeIf { it.isNotBlank() } ?: DEFAULT_BODY
        notify?.invoke(title, body.take(MAX_BODY_CHARS))
    }

    private companion object {
        const val DEFAULT_TITLE = "Reminder"
        const val DEFAULT_BODY = "You have a note reminder"
        const val MAX_BODY_CHARS = 100
    }
}
