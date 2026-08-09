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
import com.aus.notelikeus.util.AppConfig
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

    single { NoteBackupExporter(get<NoteRepository>(), "Notelikeus", AppConfig.versionName) }
    single { NoteBackupImporter(get<NoteRepository>()) }

    // Cloud sync — real implementations
    single {
        DesktopTokenStore(
            dataDir = DesktopPathProvider.getDataDirectory(),
            firebaseApiKey = DesktopOAuthConfig.FIREBASE_API_KEY
        )
    }

    single<CloudNoteTransport> {
        val tokenStore = get<DesktopTokenStore>()
        DesktopFirestoreTransport(
            firebaseProject = "notelikeus",
            // validIdToken(), not idToken(): refreshes a token that is about to expire instead of
            // letting every request 401 an hour after sign-in.
            idTokenProvider = { tokenStore.validIdToken() }
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
        DesktopGoogleSignInHelper(
            oauthClientId = DesktopOAuthConfig.CLIENT_ID,
            oauthClientSecret = DesktopOAuthConfig.clientSecret(),
            firebaseApiKey = DesktopOAuthConfig.FIREBASE_API_KEY,
            tokenStore = get()
        )
    }
}

/**
 * Google/Firebase client configuration for the desktop build.
 *
 * The client ID and Firebase API key identify the project and are public by design — they ship in
 * every client and are visible in `google-services.json` and the web bundle.
 *
 * The OAuth client secret is different. Google classes installed-app secrets as non-confidential
 * (RFC 8252: PKCE is what actually secures this flow, and it is enabled above), but a secret
 * pasted into a source file lands in git history and stays there, so it comes from the
 * environment instead. Set `NOTELIKEUS_OAUTH_CLIENT_SECRET` for a build that needs sign-in; the
 * Cloud Console requires it for desktop-type clients.
 */
private object DesktopOAuthConfig {
    const val CLIENT_ID =
        "404285880902-9d7de03t81j8lpp4jd0vqicu5mme2jc2.apps.googleusercontent.com"
    const val FIREBASE_API_KEY = "AIzaSyBDF6ff82bZ-nSI5sW4MhtGiHomifciAQo"

    private const val SECRET_ENV_VAR = "NOTELIKEUS_OAUTH_CLIENT_SECRET"

    fun clientSecret(): String = System.getenv(SECRET_ENV_VAR).orEmpty()
}
