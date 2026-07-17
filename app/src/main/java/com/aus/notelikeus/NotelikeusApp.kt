package com.aus.notelikeus

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.aus.notelikeus.data.remote.AppCheckInitializer
import com.aus.notelikeus.data.local.LegacyAttachmentCleanup
import com.aus.notelikeus.data.remote.NotificationChannels
import com.aus.notelikeus.data.remote.ReconciliationSyncWorker
import dagger.hilt.android.HiltAndroidApp
import java.util.concurrent.TimeUnit
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
        AppCheckInitializer.install(this)
        NotificationChannels.createReminderChannel(this)
        legacyAttachmentCleanup.scheduleIfNeeded()
        scheduleReconciliationSync()
        try {
            System.loadLibrary("sqlcipher")
        } catch (_: UnsatisfiedLinkError) {
            // DatabaseModule also loads the native library before opening the DB.
        }
    }

    private fun scheduleReconciliationSync() {
        val request = PeriodicWorkRequestBuilder<ReconciliationSyncWorker>(12, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            ReconciliationSyncWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }
}


