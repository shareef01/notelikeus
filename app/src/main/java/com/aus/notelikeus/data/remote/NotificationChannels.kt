package com.aus.notelikeus.data.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.app.NotificationManager.IMPORTANCE_HIGH
import com.aus.notelikeus.R

object NotificationChannels {
    const val REMINDERS_ID = "note_reminders"

    fun createReminderChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(REMINDERS_ID) != null) return
        val channel = NotificationChannel(
            REMINDERS_ID,
            context.getString(R.string.reminder_channel_name),
            IMPORTANCE_HIGH
        ).apply {
            lockscreenVisibility = Notification.VISIBILITY_SECRET
        }
        manager.createNotificationChannel(channel)
    }
}
