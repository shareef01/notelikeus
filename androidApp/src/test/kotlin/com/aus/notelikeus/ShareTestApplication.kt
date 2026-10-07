package com.aus.notelikeus

import android.app.Application
import com.aus.notelikeus.di.initKoin
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext

/**
 * Minimal Application for Robolectric tests that need a real Activity.
 *
 * [NotelikeusApp]'s `onCreate` loads the SQLCipher native library, warms up the encrypted database on
 * a background scope and schedules WorkManager jobs — none of which can run on the JVM, and none of
 * which the share-lifecycle tests depend on. This stands in with just the dependency graph so
 * `MainActivity`'s Koin injections resolve.
 *
 * Koin's container is process-global and survives across tests in one JVM, so the graph is created
 * only when absent; the tests themselves stop and restart it around each case for isolation.
 */
class ShareTestApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (GlobalContext.getOrNull() == null) {
            initKoin {
                androidContext(this@ShareTestApplication)
                modules(androidAppModule)
            }
        }
    }
}
