package com.aus.notelikeus.di

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.aus.notelikeus.data.backup.NoteBackupExporter
import com.aus.notelikeus.data.backup.NoteBackupImporter
import com.aus.notelikeus.data.local.DatabaseMigrations
import com.aus.notelikeus.data.local.NotelikeusDatabase
import com.aus.notelikeus.data.local.SETTINGS_DATASTORE_FILENAME
import com.aus.notelikeus.data.local.createDataStore
import com.aus.notelikeus.data.local.getDatabaseBuilder
import com.aus.notelikeus.data.remote.DesktopFirestoreTransport
import com.aus.notelikeus.data.sync.CloudNoteTransport
import com.aus.notelikeus.data.sync.NoteSyncEngine
import com.aus.notelikeus.data.sync.NoteSyncStateStore
import com.aus.notelikeus.di.DesktopNoteSyncStateStore
import com.aus.notelikeus.domain.platform.PlatformWidgetManager
import com.aus.notelikeus.domain.platform.ReminderManager
import com.aus.notelikeus.domain.platform.SyncCoordinator
import com.aus.notelikeus.domain.repository.NoteRepository
import com.aus.notelikeus.domain.repository.SyncManager
import com.aus.notelikeus.platform.DesktopGoogleSignInHelper
import com.aus.notelikeus.platform.DesktopReminderManager
import com.aus.notelikeus.platform.DesktopSyncCoordinator
import com.aus.notelikeus.platform.DesktopSyncManager
import com.aus.notelikeus.platform.DesktopTokenStore
import com.aus.notelikeus.platform.DesktopWidgetManager
import com.aus.notelikeus.ui.auth.GoogleSignInHelper
import com.aus.notelikeus.util.DesktopPathProvider
import org.koin.dsl.module
import java.io.File

actual val platformModule = module {
    single {
        createDataStore {
            File(DesktopPathProvider.getDataDirectory(), SETTINGS_DATASTORE_FILENAME).absolutePath
        }
    }

    single<NotelikeusDatabase> {
        getDatabaseBuilder()
            .setDriver(BundledSQLiteDriver())
            .addMigrations(*DatabaseMigrations.ALL)
            .build()
    }

    single { get<NotelikeusDatabase>().noteDao }
    single { get<NotelikeusDatabase>().labelDao }

    // Exposed by concrete type too, so main.kt can wire the tray notifier and kick off
    // restoreScheduledReminders() at startup.
    single { DesktopReminderManager(get()) }
    single<ReminderManager> { get<DesktopReminderManager>() }
    single<PlatformWidgetManager> { DesktopWidgetManager() }
    single<SyncCoordinator> { DesktopSyncCoordinator(get()) }

    single { NoteBackupExporter(get<NoteRepository>(), "Notelikeus", "1.0.0") }
    single { NoteBackupImporter(get<NoteRepository>()) }

    // Cloud sync — real implementations
    single {
        DesktopTokenStore(DesktopPathProvider.getDataDirectory())
    }

    single<CloudNoteTransport> {
        val tokenStore = get<DesktopTokenStore>()
        DesktopFirestoreTransport(
            firebaseProject = "notelikeus",
            idTokenProvider = { tokenStore.idToken() }
        )
    }

    single<NoteSyncStateStore> {
        // DataStore-backed sync state store for desktop
        DesktopNoteSyncStateStore(get())
    }

    single {
        val tokenStore = get<DesktopTokenStore>()
        NoteSyncEngine(
            transport = get<CloudNoteTransport>(),
            noteDao = get(),
            labelDao = get(),
            syncStateStore = get<NoteSyncStateStore>(),
            uidProvider = {
                val uid = tokenStore.uid()
                if (uid != null) Result.success(uid)
                else Result.failure(IllegalStateException("Not signed in"))
            },
            platform = "desktop"
        )
    }

    single<SyncManager> {
        DesktopSyncManager(get<NoteSyncEngine>(), get<DesktopTokenStore>())
    }

    single<GoogleSignInHelper> {
        // OAuth 2.0 client ID for desktop — this is the same web client ID used by Android.
        // For production, a dedicated Desktop OAuth client should be created in Google Cloud Console
        // with http://127.0.0.1 added as an authorised redirect URI.
        // WARNING: This is a live OAuth client secret. Do not commit this to a public repo.
        // For production, use a proper secrets management solution (env vars, vault, etc.).
        // PKCE alone is sufficient per RFC 8252, but Google requires the secret for
        // non-Android desktop clients created through the Cloud Console.
        DesktopGoogleSignInHelper(
            oauthClientId = "404285880902-9d7de03t81j8lpp4jd0vqicu5mme2jc2.apps.googleusercontent.com",
            oauthClientSecret = "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",
            firebaseApiKey = "AIzaSyBDF6ff82bZ-nSI5sW4MhtGiHomifciAQo"
        )
    }
}
