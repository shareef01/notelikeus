package com.aus.notelikeus

import android.app.Application
import android.util.Log
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.appfunctions.service.AppFunctionConfiguration
import androidx.work.WorkManager
import androidx.compose.runtime.mutableStateOf
import com.aus.notelikeus.appfunctions.NoteAppFunctions
import com.aus.notelikeus.data.local.NotelikeusDatabase
import com.aus.notelikeus.data.local.warmUp
import com.aus.notelikeus.data.remote.NotificationChannels
import com.aus.notelikeus.data.remote.ReconciliationSyncWorker
import com.aus.notelikeus.ui.navigation.InternalNavigationToken
import com.aus.notelikeus.di.initKoin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.core.qualifier.named
import org.koin.dsl.module
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

val androidAppModule = module {
    single(named("webClientId")) { 
        androidContext().getString(R.string.default_web_client_id) 
    }
}

/**
 * Flips once the encrypted database has finished opening on the background startup thread.
 * [MainActivity] holds the first composition back on this, so nothing that resolves through
 * Koin to a DAO can pull the database open onto the main thread.
 *
 * A [MutableStateFlow] rather than `mutableStateOf`, and the distinction is a crash rather than a
 * style preference. This is a process-lived `object`, so it initialises on whichever thread first
 * touches it — and the first toucher on a cold start is [markReady], running on the background
 * startup dispatcher. A snapshot state created on that thread, after composition has already taken
 * its snapshot, is read back with:
 *
 * ```
 * IllegalStateException: Reading a state that was created after the snapshot was taken
 *     or in a snapshot that has not yet been applied
 * ```
 *
 * Observed on first launch after install, where the database open is slowest and the two are most
 * likely to interleave. A flow has no snapshot identity, so the failure mode is not merely
 * unlikely here — it cannot occur. The Compose-side state is created by `collectAsState` inside
 * composition, on the main thread, which is where snapshot state is meant to be created.
 *
 * See docs/FINDINGS.md F9.
 */
object AppStartup {
    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    fun markReady() {
        _isReady.value = true
    }
}

class NotelikeusApp : Application(), Configuration.Provider, AppFunctionConfiguration.Provider {

    // Process-lived by design, matching CloudNoteSyncCoordinator's scope: it does one job at
    // startup and outlives any screen.
    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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

        // Loaded before Koin, so nothing can reach the database before the native library exists.
        // Room opens the encrypted database through SQLCipher's JNI; without it there is no usable
        // database at all, and PlaintextDatabaseMigrator will later fail with UnsatisfiedLinkError
        // — an Error, not an Exception, so that class's own `catch (_: Exception)` guards do not
        // contain it. Swallowing this silently does not prevent that crash, it only removes the
        // one log line that would explain it.
        try {
            System.loadLibrary("sqlcipher")
        } catch (error: UnsatisfiedLinkError) {
            Log.e(
                "NotelikeusApp",
                "SQLCipher native library failed to load; the encrypted database cannot be opened",
                error
            )
        }

        val koinApp = initKoin {
            androidContext(this@NotelikeusApp)
            modules(androidAppModule)
        }

        // Creating the database singleton runs the key-manager decrypt (Keystore + file IO)
        // and, on the first launch after the encryption rollout, a full sqlcipher_export
        // re-encryption. Room defers the actual open and its migrations to first use, which
        // used to be the first composition — koinViewModel() -> repository -> DAO — on the
        // main thread. Resolving it and checking out the writer connection here moves all of
        // that to the background; MainActivity gates the UI on AppStartup until it finishes.
        // If creation throws, markReady() still runs: the UI's own resolution then retries and
        // surfaces the same failure it would have before, rather than hanging on the gate.
        startupScope.launch {
            try {
                koinApp.koin.get<NotelikeusDatabase>().warmUp()
            } finally {
                AppStartup.markReady()
            }
        }

        InternalNavigationToken.init(this)
        // App Check is not installed here yet. The web client sends tokens (see web/src/lib/
        // firebase.ts); Android does not, so enabling enforcement in the Firebase console today
        // would lock out this app while the web app kept working. Wiring it needs the Play
        // Integrity provider for release plus the debug provider for debug builds, and a device
        // to verify against — see audit finding 12.
        NotificationChannels.createReminderChannel(this)
        scheduleReconciliationSync()
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
