package com.aus.notelikeus

import android.app.Application
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.aus.notelikeus.data.remote.AppCheckInitializer
import com.aus.notelikeus.data.remote.NotificationChannels
import com.aus.notelikeus.data.remote.ReconciliationSyncWorker
import com.aus.notelikeus.ui.navigation.InternalNavigationToken
import com.aus.notelikeus.di.initKoin
import org.koin.android.ext.koin.androidContext
import java.util.concurrent.TimeUnit

class NotelikeusApp : Application(), Configuration.Provider {

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .build()

    override fun onCreate() {
        super.onCreate()
        initKoin {
            androidContext(this@NotelikeusApp)
        }
        
        InternalNavigationToken.init(this)
        AppCheckInitializer.install(this)
        NotificationChannels.createReminderChannel(this)
        scheduleReconciliationSync()
        try {
            System.loadLibrary("sqlcipher")
        } catch (_: UnsatisfiedLinkError) {
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
