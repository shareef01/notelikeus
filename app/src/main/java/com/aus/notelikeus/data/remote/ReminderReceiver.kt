package com.aus.notelikeus.data.remote

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.aus.notelikeus.MainActivity
import com.aus.notelikeus.R
import com.aus.notelikeus.ui.navigation.markInternalNavigation

class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val noteId = intent.getLongExtra("noteId", -1L)
        showReminderNotification(context, noteId)
    }

    companion object {
        /** Shared with [ReminderBootReceiver]. Never includes note title/body. */
        fun showReminderNotification(context: Context, noteId: Long) {
            NotificationChannels.createReminderChannel(context)
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channelId = NotificationChannels.REMINDERS_ID

            val activityIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                markInternalNavigation()
                if (noteId != -1L) {
                    putExtra("noteId", noteId)
                }
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                noteId.hashCode(),
                activityIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.reminder_default_title))
                .setContentText(context.getString(R.string.reminder_default_body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()

            notificationManager.notify(noteId.hashCode(), notification)
        }
    }
}
