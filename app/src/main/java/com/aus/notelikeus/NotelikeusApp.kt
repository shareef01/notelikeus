package com.aus.notelikeus

import android.app.Application
import com.aus.notelikeus.data.local.LegacyAttachmentCleanup
import com.aus.notelikeus.data.remote.NotificationChannels
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class NotelikeusApp : Application() {

    @Inject
    lateinit var legacyAttachmentCleanup: LegacyAttachmentCleanup

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.createReminderChannel(this)
        legacyAttachmentCleanup.scheduleIfNeeded()
        try {
            System.loadLibrary("sqlcipher")
        } catch (_: UnsatisfiedLinkError) {
            // DatabaseModule also loads the native library before opening the DB.
        }
    }
}


