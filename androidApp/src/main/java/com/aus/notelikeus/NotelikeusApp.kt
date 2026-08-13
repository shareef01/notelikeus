package com.aus.notelikeus

import android.app.Application
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.appfunctions.service.AppFunctionConfiguration
import androidx.work.WorkManager
import com.aus.notelikeus.appfunctions.NoteAppFunctions
import com.aus.notelikeus.data.remote.NotificationChannels
import com.aus.notelikeus.data.remote.ReconciliationSyncWorker
import com.aus.notelikeus.ui.navigation.InternalNavigationToken
import com.aus.notelikeus.di.initKoin
import org.koin.android.ext.koin.androidContext
import org.koin.core.qualifier.named
import org.koin.dsl.module
import java.util.concurrent.TimeUnit

val androidAppModule = module {
    single(named("webClientId")) { 
        androidContext().getString(R.string.default_web_client_id) 
    }
}

class NotelikeusApp : Application(), Configuration.Provider, AppFunctionConfiguration.Provider {

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .build()

    /**
     * Tells the AppFunctions runtime how to construct [NoteAppFunctions].
     *
     * Without this the whole feature was inert: the KSP-generated bindings had no way to
     * instantiate the enclosing class, so the two dependencies, the compiler and
     * NoteAppFunctions itself were all along for the ride with nothing invoking them.
     */
    override val appFunctionConfiguration: AppFunctionConfiguration
        get() = AppFunctionConfiguration.Builder()
            .addEnclosingClassFactory(NoteAppFunctions::class.java) { NoteAppFunctions() }
            .build()

    override fun onCreate() {
        super.onCreate()
        initKoin {
            androidContext(this@NotelikeusApp)
            modules(androidAppModule)
        }
        
        InternalNavigationToken.init(this)
        // App Check is not installed here yet. The web client sends tokens (see web/src/lib/
        // firebase.ts); Android does not, so enabling enforcement in the Firebase console today
        // would lock out this app while the web app kept working. Wiring it needs the Play
        // Integrity provider for release plus the debug provider for debug builds, and a device
        // to verify against — see audit finding 12.
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
