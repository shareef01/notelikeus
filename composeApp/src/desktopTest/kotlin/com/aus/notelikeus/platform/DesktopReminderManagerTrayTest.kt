package com.aus.notelikeus.platform

import com.aus.notelikeus.data.sync.FakeNoteDao
import com.aus.notelikeus.domain.platform.ReminderDelivery
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * NL-01: the main window's close button keeps the app in the tray only while a reminder is
 * waiting, and the editor's confirmation must not promise more than an in-process timer delivers.
 */
class DesktopReminderManagerTrayTest {

    private val manager = DesktopReminderManager(FakeNoteDao())

    @Test
    fun deliveryIsReportedAsWhileRunning() {
        assertEquals(ReminderDelivery.WhileRunning, manager.reminderDelivery())
    }

    @Test
    fun nothingScheduledLetsTheWindowCloseExit() {
        assertFalse(manager.hasScheduledReminders())
    }

    @Test
    fun aFutureReminderKeepsTheAppRunningUntilCancelled() {
        manager.scheduleReminder(noteId = 7L, timestamp = System.currentTimeMillis() + 3_600_000L)
        assertTrue(manager.hasScheduledReminders())

        manager.cancelReminder(7L)
        assertFalse(manager.hasScheduledReminders())
    }

    @Test
    fun aReminderInThePastIsNotCountedAsWaiting() {
        manager.scheduleReminder(noteId = 8L, timestamp = System.currentTimeMillis() - 1_000L)
        assertFalse(manager.hasScheduledReminders())
    }

    @Test
    fun aFiredReminderRequestsATrayNotificationAndClearsTheSchedule() {
        val notifications = LinkedBlockingQueue<Pair<String, String>>()
        manager.notify = { title, message -> notifications.add(title to message) }
        manager.scheduleReminder(noteId = 9L, timestamp = System.currentTimeMillis() + 100L)

        assertEquals("Reminder" to "You have a note reminder", notifications.poll(5, TimeUnit.SECONDS))
        assertFalse(manager.hasScheduledReminders())
    }
}
