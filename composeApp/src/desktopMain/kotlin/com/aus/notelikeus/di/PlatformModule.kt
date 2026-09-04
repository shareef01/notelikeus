package com.aus.notelikeus.di

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.aus.notelikeus.data.backup.AttachmentBackupReader
import com.aus.notelikeus.data.backup.NoteBackupExporter
import com.aus.notelikeus.data.backup.NoteBackupImporter
import com.aus.notelikeus.data.local.DatabaseMigrations
import com.aus.notelikeus.data.local.NotelikeusDatabase
import com.aus.notelikeus.data.local.SETTINGS_DATASTORE_FILENAME
import com.aus.notelikeus.data.local.createDataStore
import com.aus.notelikeus.data.local.getDatabaseBuilder
import com.aus.notelikeus.data.remote.BackendConfig
import com.aus.notelikeus.util.readLocalProperty
import com.aus.notelikeus.data.remote.CloudSessionManager
import com.aus.notelikeus.data.remote.DesktopFirestoreTransport
import com.aus.notelikeus.data.remote.DesktopSupabaseRpcClient
import com.aus.notelikeus.data.remote.RemoteBackend
import com.aus.notelikeus.data.remote.SupabaseAccessTokenProvider
import com.aus.notelikeus.data.remote.SupabaseAuthApi
import com.aus.notelikeus.data.remote.SupabaseNoteTransport
import com.aus.notelikeus.data.remote.SupabaseSessionAccessTokenProvider
import com.aus.notelikeus.data.remote.SupabaseSessionManager
import com.aus.notelikeus.data.remote.SupabaseSessionStore
import com.aus.notelikeus.data.attachments.AttachmentLocalStorage
import com.aus.notelikeus.data.attachments.AttachmentSyncService
import com.aus.notelikeus.data.attachments.DesktopAttachmentLocalStorage
import com.aus.notelikeus.data.remote.AttachmentBlobTransport
import com.aus.notelikeus.data.remote.NoopAttachmentBlobTransport
import com.aus.notelikeus.data.remote.R2AttachmentBlobTransport
import com.aus.notelikeus.data.remote.SupabaseAttachmentMetadata
import com.aus.notelikeus.platform.DesktopSessionManager
import com.aus.notelikeus.data.sync.CloudNoteTransport
import com.aus.notelikeus.data.migration.AccountUidBridge
import com.aus.notelikeus.data.migration.FirebaseSupabaseAccountLinker
import com.aus.notelikeus.data.sync.LocalAccountIsolator
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
import com.aus.notelikeus.util.SidebarCollapsedStore
import com.aus.notelikeus.util.WindowMetricsStore
import org.koin.dsl.module
import java.io.File
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection


actual val platformModule = module {
    single {
        createDataStore {
            File(DesktopPathProvider.getDataDirectory(), SETTINGS_DATASTORE_FILENAME).absolutePath
        }
    }

    single { WindowMetricsStore(get()) }
    single { SidebarCollapsedStore(get()) }

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
    // Bound to the interface, not the concrete class: DesktopSyncCoordinator asks for
    // PendingSyncStore, and registering only the implementation type leaves that unresolvable.
    single<PendingSyncStore> { DesktopPendingSyncStore(get()) }
    single<SyncCoordinator> { DesktopSyncCoordinator(get(), get()) }

    single {
        NoteBackupExporter(
            repository = get<NoteRepository>(),
            appName = "Notelikeus",
            appVersion = AppConfig.versionName,
            attachmentReader = AttachmentBackupReader { attachment ->
                get<AttachmentSyncService>().readAttachmentBytes(attachment)
            },
        )
    }
    single { NoteBackupImporter(get<NoteRepository>(), get<AttachmentLocalStorage>()) }

    // Cloud sync — real implementations
    single {
        DesktopTokenStore(
            dataDir = DesktopPathProvider.getDataDirectory(),
            firebaseApiKey = DesktopOAuthConfig.FIREBASE_API_KEY
        )
    }

    single { SupabaseSessionStore() }
    single { SupabaseAuthApi(BackendConfig.supabaseUrl, BackendConfig.supabaseAnonKey) }
    single { SupabaseSessionManager(get(), get()) }
    single<SupabaseAccessTokenProvider> { SupabaseSessionAccessTokenProvider(get(), get()) }
    single<CloudSessionManager> { DesktopSessionManager(get(), get()) }

    single<AttachmentLocalStorage> { DesktopAttachmentLocalStorage() }
    single<AttachmentBlobTransport> {
        if (
            BackendConfig.remoteBackend == RemoteBackend.SUPABASE &&
            BackendConfig.attachmentsWorkerUrl.isNotEmpty()
        ) {
            val rpcClient = DesktopSupabaseRpcClient(
                supabaseUrl = BackendConfig.supabaseUrl,
                anonKey = BackendConfig.supabaseAnonKey,
                accessTokenProvider = get<SupabaseAccessTokenProvider>(),
            )
            R2AttachmentBlobTransport(
                workerBaseUrl = BackendConfig.attachmentsWorkerUrl,
                accessTokenProvider = get(),
                metadata = SupabaseAttachmentMetadata(rpcClient),
                ownerIdProvider = { get<SupabaseSessionManager>().ensureSignedIn().getOrThrow() },
            )
        } else {
            NoopAttachmentBlobTransport()
        }
    }
    single {
        val metadata = if (BackendConfig.remoteBackend == RemoteBackend.SUPABASE) {
            SupabaseAttachmentMetadata(
                DesktopSupabaseRpcClient(
                    supabaseUrl = BackendConfig.supabaseUrl,
                    anonKey = BackendConfig.supabaseAnonKey,
                    accessTokenProvider = get<SupabaseAccessTokenProvider>(),
                ),
            )
        } else {
            null
        }
        AttachmentSyncService(
            blobTransport = get(),
            metadata = metadata,
            localStorage = get(),
            noteDao = get(),
        )
    }

    single<CloudNoteTransport> {
        if (BackendConfig.remoteBackend == RemoteBackend.SUPABASE) {
            SupabaseNoteTransport(
                DesktopSupabaseRpcClient(
                    supabaseUrl = BackendConfig.supabaseUrl,
                    anonKey = BackendConfig.supabaseAnonKey,
                    accessTokenProvider = get<SupabaseAccessTokenProvider>(),
                ),
            )
        } else {
            val tokenStore = get<DesktopTokenStore>()
            DesktopFirestoreTransport(
                firebaseProject = "notelikeus",
                idTokenProvider = { tokenStore.validIdToken() },
            )
        }
    }

    single<NoteSyncStateStore> {
        // DataStore-backed sync state store for desktop
        DesktopNoteSyncStateStore(get())
    }

    single { AccountUidBridge(get()) }
    single {
        FirebaseSupabaseAccountLinker(
            remoteBackend = BackendConfig.remoteBackend,
            accountUidBridge = get(),
            syncStateStore = get<NoteSyncStateStore>(),
            supabaseRpc = if (BackendConfig.remoteBackend == RemoteBackend.SUPABASE) {
                DesktopSupabaseRpcClient(
                    supabaseUrl = BackendConfig.supabaseUrl,
                    anonKey = BackendConfig.supabaseAnonKey,
                    accessTokenProvider = get<SupabaseAccessTokenProvider>(),
                )
            } else {
                null
            },
        )
    }

    single {
        val sessionManager = get<CloudSessionManager>()
        val database = get<NotelikeusDatabase>()
        NoteSyncEngine(
            transport = get<CloudNoteTransport>(),
            noteDao = get(),
            labelDao = get(),
            syncStateStore = get<NoteSyncStateStore>(),
            uidProvider = { sessionManager.ensureSignedIn() },
            platform = "desktop",
            runInTransaction = { block ->
                database.useWriterConnection { transactor ->
                    transactor.immediateTransaction { block() }
                }
            },
            attachmentSync = get(),
        )
    }

    single { LocalAccountIsolator(get(), get(), get()) }
    single<SyncManager> {
        DesktopSyncManager(
            get<NoteSyncEngine>(),
            get<CloudSessionManager>(),
            get<LocalAccountIsolator>(),
            get<FirebaseSupabaseAccountLinker>(),
        )
    }

    single<GoogleSignInHelper> {
        DesktopGoogleSignInHelper(
            oauthClientId = DesktopOAuthConfig.CLIENT_ID,
            oauthClientSecret = DesktopOAuthConfig.clientSecret(),
            firebaseApiKey = DesktopOAuthConfig.FIREBASE_API_KEY,
            tokenStore = get(),
            supabaseAuthApi = get(),
            supabaseSessionStore = get(),
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
 * pasted into a source file lands in git history and stays there. So it is resolved at runtime,
 * in order:
 *
 *  1. the `NOTELIKEUS_OAUTH_CLIENT_SECRET` environment variable, for CI and one-off runs;
 *  2. `notelikeus.oauthClientSecret` in the repo's gitignored `local.properties`, which is the
 *     ergonomic path for day-to-day development — same pattern the project already uses for
 *     `signing.properties`.
 *  3. [DesktopSecrets], generated at build time from the same two sources. An installed app has
 *     neither an env var nor a checked-out `local.properties`, so without this a packaged build
 *     could never sign in. Runtime sources come first so a build can still be overridden without
 *     recompiling.
 *
 * Empty means desktop sign-in is simply not configured, and
 * [com.aus.notelikeus.platform.DesktopGoogleSignInHelper] says so up front rather than letting
 * Google answer with an opaque `client_secret is missing` 400 after the user has already picked
 * an account.
 */
private object DesktopOAuthConfig {
    /**
     * A dedicated **Desktop app** client, not the web client Android uses — those are
     * `…-hgpicaqc…` (Android native) and `…-cpiu3nj2…` (Android ID tokens and web GIS), and
     * neither ever sees a client secret. Only this client has one, so its secret can be rotated
     * without touching the other platforms.
     *
     * Desktop-app clients are what RFC 8252 expects for the loopback flow: Google accepts a
     * `http://127.0.0.1:<port>` redirect on whatever ephemeral port the local server binds, which
     * is why no fixed port is registered.
     */
    const val CLIENT_ID =
        "404285880902-o8gn7j5v211m7rvldo19v0eb93im8b7e.apps.googleusercontent.com"
    const val FIREBASE_API_KEY = "AIzaSyBDF6ff82bZ-nSI5sW4MhtGiHomifciAQo"

    private const val SECRET_ENV_VAR = "NOTELIKEUS_OAUTH_CLIENT_SECRET"
    private const val SECRET_PROPERTY = "notelikeus.oauthClientSecret"

    fun clientSecret(): String {
        System.getenv(SECRET_ENV_VAR)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        readLocalProperty(SECRET_PROPERTY)?.let { return it }
        return DesktopSecrets.OAUTH_CLIENT_SECRET
    }
}
