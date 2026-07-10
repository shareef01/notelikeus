package com.aus.notelikeus

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.aus.notelikeus.data.local.LegacyAttachmentCleanup
import com.aus.notelikeus.data.remote.NotificationChannels
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class NotelikeusApp : Application(), Configuration.Provider {

    @Inject
    lateinit var legacyAttachmentCleanup: LegacyAttachmentCleanup

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

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


