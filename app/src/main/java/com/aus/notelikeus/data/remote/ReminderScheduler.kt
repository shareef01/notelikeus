package com.aus.notelikeus.data.remote

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /**
     * Distinct per-note Intent. The request code is a 32-bit hash of the 64-bit note id, which is
     * injective only below 2^32 — web-originated ids (Date.now()*1000+rand) sit far above that and
     * can collide. PendingIntent equality ignores extras and compares filterEquals (component,
     * action, data, …), so without a distinct [Uri] two colliding ids would share one alarm and
     * scheduling/cancelling one reminder could clobber another. Schedule and cancel must build the
     * identical Intent for cancellation to match.
     */
    private fun reminderIntent(noteId: Long): Intent =
        Intent(context, ReminderReceiver::class.java).apply {
            data = Uri.parse("notelikeus://reminder/$noteId")
            putExtra("noteId", noteId)
        }

    /** Schedules a reminder that only carries [noteId] — never note title/body (lock-screen safe). */
    fun scheduleReminder(noteId: Long, timestamp: Long): ReminderScheduleResult {
        if (timestamp <= System.currentTimeMillis()) return ReminderScheduleResult.PAST_TIME

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            noteId.hashCode(),
            reminderIntent(noteId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, timestamp, pendingIntent)
            return ReminderScheduleResult.SCHEDULED_INEXACT
        } else {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, timestamp, pendingIntent)
        }
        return ReminderScheduleResult.SCHEDULED_EXACT
    }

    fun canScheduleExactAlarms(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
    }

    fun cancelReminder(noteId: Long) {
        // Must filterEquals the Intent used to schedule (same component + data), or FLAG_NO_CREATE
        // returns null and the alarm is never cancelled.
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            noteId.hashCode(),
            reminderIntent(noteId),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        pendingIntent?.let {
            alarmManager.cancel(it)
            it.cancel()
        }
    }
}
