package com.aus.notelikeus.data

import android.app.AlarmManager
import android.content.Context
import com.aus.notelikeus.data.remote.ReminderReceiver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Reminder scheduling had no tests, yet a missed alarm is a silent user-facing failure. These
 * run against Robolectric's AlarmManager shadow: the exact time scheduled, the past-timestamp
 * no-op guard, and cancellation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReminderSchedulerTest {

    private lateinit var context: Context
    private lateinit var alarmManager: AlarmManager
    private lateinit var scheduler: ReminderScheduler

    @Before
    fun setup() {
        context = org.robolectric.RuntimeEnvironment.getApplication()
        alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        scheduler = ReminderScheduler(context)
    }

    @Test
    fun `scheduling a future reminder registers the exact alarm`() {
        val triggerAt = System.currentTimeMillis() + 60_000L
        scheduler.scheduleReminder(noteId = 42L, timestamp = triggerAt)

        val alarm = shadowOf(alarmManager).nextScheduledAlarm
        assertNotNull(alarm)
        assertEquals(triggerAt, alarm!!.triggerAtTime)
    }

    @Test
    fun `scheduling a past timestamp is a no-op`() {
        // Pending reminders can legitimately hold timestamps that have since passed (note
        // edited to a reminder in the past, clock skew): scheduling those must not fire
        // immediately-at-boot style catch-up alarms.
        scheduler.scheduleReminder(noteId = 7L, timestamp = System.currentTimeMillis() - 1_000L)
        assertNull(shadowOf(alarmManager).nextScheduledAlarm)
    }

    @Test
    fun `cancelling removes the scheduled alarm`() {
        val triggerAt = System.currentTimeMillis() + 60_000L
        scheduler.scheduleReminder(noteId = 42L, timestamp = triggerAt)
        assertNotNull(shadowOf(alarmManager).nextScheduledAlarm)

        scheduler.cancelReminder(noteId = 42L)
        assertNull(shadowOf(alarmManager).nextScheduledAlarm)
    }
}
