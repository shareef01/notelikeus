package com.aus.notelikeus.data.remote

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.aus.notelikeus.MainActivity
import com.aus.notelikeus.R

class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val noteId = intent.getLongExtra("noteId", -1L)
        val title = intent.getStringExtra("title") ?: context.getString(R.string.reminder_default_title)
        val content = intent.getStringExtra("content") ?: ""

        NotificationChannels.createReminderChannel(context)
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = NotificationChannels.REMINDERS_ID

        val activityIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (noteId != -1L) {
                data = android.net.Uri.parse("notelikeus://editor/$noteId")
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
            .setContentTitle(title)
            .setContentText(content)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(noteId.hashCode(), notification)
    }
}
