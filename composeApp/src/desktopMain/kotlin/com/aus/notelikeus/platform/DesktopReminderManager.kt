package com.aus.notelikeus.platform

import com.aus.notelikeus.data.local.dao.NoteDao
import com.aus.notelikeus.data.mapper.toNote
import com.aus.notelikeus.domain.platform.ReminderDelivery
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
 * database on every launch — see [restoreScheduledReminders]. Reminders still cannot fire once the
 * app has exited (there is no OS-level scheduler behind this the way Android has AlarmManager).
 * That is why closing the main window keeps the app in the tray while [hasScheduledReminders] is
 * true, and why [reminderDelivery] tells the editor to say so. The catch-up pass is what keeps a
 * reminder missed after an explicit Exit from silently sitting in the DB as "active" forever.
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

    override fun reminderDelivery(): ReminderDelivery = ReminderDelivery.WhileRunning

    /**
     * Whether a reminder is still waiting to fire in this process.
     *
     * The window's close button reads this: while it is true, closing the window leaves the app
     * running in the tray instead of exiting, because exiting would silently drop the reminder.
     */
    fun hasScheduledReminders(): Boolean = synchronized(lock) { scheduledTasks.isNotEmpty() }

    /**
     * Rebuilds the timer schedule from stored notes, and fires a catch-up notification for any
     * reminder that came due while the app was closed (clearing it afterwards so the note's
     * reminder state reflects reality). Mirrors Android's `ReminderBootReceiver`.
     *
     * [clearReminder] is how a caught-up reminder is cleared. It defaults to a local-only clear, but
     * the app passes the repository's, which also uploads: a local-only clear is overwritten by the
     * cloud copy on the next pull and the reminder fires again at every launch.
     *
     * Call once at startup. Runs off the caller's thread.
     */
    fun restoreScheduledReminders(
        clearReminder: suspend (Long) -> Unit = { noteDao.clearReminderTimestamp(it, System.currentTimeMillis()) },
    ) {
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
                clearReminder(noteId)
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
