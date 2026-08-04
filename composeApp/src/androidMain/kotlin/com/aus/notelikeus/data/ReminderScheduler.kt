package com.aus.notelikeus.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.aus.notelikeus.domain.platform.ReminderManager
import com.aus.notelikeus.data.remote.ReminderReceiver
import com.aus.notelikeus.data.remote.ReminderScheduleResult

class ReminderScheduler(
    private val context: Context
) : ReminderManager {
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun reminderIntent(noteId: Long): Intent =
        Intent(context, ReminderReceiver::class.java).apply {
            data = Uri.parse("notelikeus://reminder/$noteId")
            putExtra("noteId", noteId)
        }

    override fun scheduleReminder(noteId: Long, timestamp: Long) {
        if (timestamp <= System.currentTimeMillis()) return

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            noteId.hashCode(),
            reminderIntent(noteId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, timestamp, pendingIntent)
        } else {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, timestamp, pendingIntent)
        }
    }

    fun canScheduleExactAlarms(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
    }

    override fun cancelReminder(noteId: Long) {
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
