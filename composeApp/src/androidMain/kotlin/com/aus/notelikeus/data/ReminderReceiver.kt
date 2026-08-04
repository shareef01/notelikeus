package com.aus.notelikeus.data.remote

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
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
        /** Notifications are disambiguated by a per-note string tag, so the id can be constant. */
        private const val NOTE_REMINDER_NOTIFICATION_ID = 1

        /** Shared with [ReminderBootReceiver]. Never includes note title/body. */
        fun showReminderNotification(context: Context, noteId: Long) {
            NotificationChannels.createReminderChannel(context)
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channelId = NotificationChannels.REMINDERS_ID

            val activityIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                // Distinct data per note so this tap-to-open PendingIntent doesn't collide with
                // another note's (request code is a 32-bit hash of a 64-bit id — see
                // ReminderScheduler), which would otherwise open the wrong note.
                if (noteId != -1L) {
                    data = Uri.parse("notelikeus://note/$noteId")
                    putExtra("noteId", noteId)
                }
                markInternalNavigation()
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

            // Tag by note id: notify(int) alone would collide for ids whose hashCode collides,
            // showing two reminders as one. A string tag keys each note's notification uniquely.
            notificationManager.notify(noteId.toString(), NOTE_REMINDER_NOTIFICATION_ID, notification)
        }
    }
}
