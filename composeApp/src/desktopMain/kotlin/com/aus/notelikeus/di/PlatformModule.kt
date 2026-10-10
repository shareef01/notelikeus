package com.aus.notelikeus.di

import com.aus.notelikeus.data.local.NOTELIKEUS_DATABASE_VERSION
import com.aus.notelikeus.domain.diagnostics.DiagnosticsCollector
import com.aus.notelikeus.domain.repository.NoteRepository
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.aus.notelikeus.data.backup.NoteBackupExporter
import com.aus.notelikeus.data.backup.NoteBackupImporter
import com.aus.notelikeus.data.local.DesktopDatabaseKeyManager
import com.aus.notelikeus.data.local.DesktopPlaintextDatabaseMigrator
import com.aus.notelikeus.data.local.DesktopSqliteFlags
import com.aus.notelikeus.data.local.DatabaseMigrations
import com.aus.notelikeus.data.local.JdbcSQLiteDriver
import com.aus.notelikeus.data.local.NotelikeusDatabase
import com.aus.notelikeus.data.local.SETTINGS_DATASTORE_FILENAME
import com.aus.notelikeus.data.local.createDataStore
import com.aus.notelikeus.data.local.getDatabaseBuilder
import com.aus.notelikeus.data.remote.BackendConfig
import com.aus.notelikeus.util.readLocalProperty
import com.aus.notelikeus.data.remote.CloudSessionManager
import com.aus.notelikeus.data.remote.DesktopSupabaseRpcClient
import com.aus.notelikeus.data.remote.DesktopSupabaseSessionPersistence
import com.aus.notelikeus.data.remote.RemoteIdentityProvider
import com.aus.notelikeus.data.remote.SupabaseAccessTokenProvider
import com.aus.notelikeus.data.remote.SupabaseAuthApi
import com.aus.notelikeus.data.remote.SupabaseNoteTransport
import com.aus.notelikeus.data.remote.SupabaseSessionAccessTokenProvider
import com.aus.notelikeus.data.remote.SupabaseSessionManager
import com.aus.notelikeus.data.remote.SupabaseSessionStore
import com.aus.notelikeus.data.attachments.AttachmentBytesProtector
import com.aus.notelikeus.data.attachments.AttachmentLocalStorage
import com.aus.notelikeus.data.attachments.AttachmentStagingStore
import com.aus.notelikeus.data.attachments.AttachmentSyncService
import com.aus.notelikeus.data.attachments.AttachmentAtRestMigrator
import com.aus.notelikeus.data.attachments.DesktopAttachmentBytesProtector
import com.aus.notelikeus.data.attachments.DesktopAttachmentLocalStorage
import com.aus.notelikeus.data.attachments.FileAttachmentStagingStore
import okio.Path.Companion.toPath
import com.aus.notelikeus.data.remote.AttachmentBlobTransport
import com.aus.notelikeus.data.remote.NoopAttachmentBlobTransport
import com.aus.notelikeus.data.remote.R2AttachmentBlobTransport
import com.aus.notelikeus.data.remote.SupabaseAttachmentMetadata
import com.aus.notelikeus.data.sync.DatasetEpochAuthority
import com.aus.notelikeus.data.sync.LocalCommitGate
import com.aus.notelikeus.data.sync.DatasetEpochStore
import com.aus.notelikeus.data.remote.DatasetScopedCloudRevisionState
import com.aus.notelikeus.data.sync.IdentityBoundNoteTransport
import com.aus.notelikeus.data.migration.AccountUidBridge
import com.aus.notelikeus.data.sync.LocalAccountIsolator
import com.aus.notelikeus.data.sync.NoteSyncEngine
import com.aus.notelikeus.data.sync.NoteSyncStateStore
import com.aus.notelikeus.di.DesktopNoteSyncStateStore
import com.aus.notelikeus.domain.platform.PlatformWidgetManager
import com.aus.notelikeus.domain.platform.ReminderManager
import com.aus.notelikeus.domain.platform.SyncCoordinator
import com.aus.notelikeus.domain.repository.SyncManager
import com.aus.notelikeus.platform.DesktopGoogleSignInHelper
import com.aus.notelikeus.platform.DesktopReminderManager
import com.aus.notelikeus.platform.DesktopSyncCoordinator
import com.aus.notelikeus.platform.DesktopSyncManager
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


/** F-8: the file that remembers an accepted destructive cloud-wipe request. Deliberately outside
 *  the settings DataStore, which local account isolation clears. */

import com.aus.notelikeus.data.remote.DesktopPendingCloudWipeIntentStore
import com.aus.notelikeus.data.sync.CloudWipeCoordinator
import com.aus.notelikeus.ui.components.clearAttachmentThumbnailCache
// F-8: the file that remembers an accepted destructive cloud-wipe request. Deliberately outside
// the settings DataStore, which local account isolation clears.
private const val PENDING_WIPE_FILENAME = "pending_cloud_wipe.txt"

actual val platformModule = module {
    single {
        createDataStore {
            File(DesktopPathProvider.getDataDirectory(), SETTINGS_DATASTORE_FILENAME).absolutePath
        }
    }

    single { WindowMetricsStore(get()) }
    single { SidebarCollapsedStore(get()) }

    // Slice 1: DPAPI-sealed passphrase.
    // Slice 2–3: optional encrypted JDBC path behind DesktopSqliteFlags (default off).
    single {
        DesktopDatabaseKeyManager(
            keyDir = File(System.getProperty("user.home"), ".notelikeus"),
        )
    }

    single<NotelikeusDatabase> {
        val useEncrypted = DesktopSqliteFlags.useJdbcSqlite()
        val driver = if (useEncrypted) {
            val passphrase = get<DesktopDatabaseKeyManager>().getPassphrase()
            val dbFile = File(
                DesktopPathProvider.getDataDirectory(),
                NotelikeusDatabase.DATABASE_NAME,
            )
            DesktopPlaintextDatabaseMigrator.migrateToEncryptedIfNeeded(dbFile, passphrase)
            JdbcSQLiteDriver(passphrase)
        } else {
            BundledSQLiteDriver()
        }
        getDatabaseBuilder()
            .setDriver(driver)
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
    // Bound by contract as well as by type: DatasetEpochAuthority takes the store, and two
    // registrations of it would be two datasets.
    single { DesktopPendingSyncStore(get()) }
    single<DatasetEpochStore> { get<DesktopPendingSyncStore>() }
    single { DatasetEpochAuthority(get()).also { authority ->
        // The incomplete-isolation quarantine: while the device is between datasets, no
        // account-owned local commit may proceed either — see LocalCommitGate's invariant note.
        LocalCommitGate.installDatasetAuthorityQuarantine { authority.isIsolationIncomplete() }
    } }
    single<SyncCoordinator> {
        DesktopSyncCoordinator(
            syncEngine = get(),
            epochAuthority = get(),
            // Stamped onto each queued command when it is queued, never read when it runs.
            ownerUidProvider = { get<CloudSessionManager>().getCurrentAccount().userId },
        )
    }

    single { NoteBackupExporter(get<NoteRepository>(), "Notelikeus", AppConfig.versionName) }
    single { NoteBackupImporter(get<NoteRepository>()) }
    single<com.aus.notelikeus.data.backup.BackupBundleOperations> {
        com.aus.notelikeus.data.backup.bundle.BackupBundleTransfer(
            repository = get(),
            exporter = get(),
            importer = get(),
            staging = get(),
            localStorage = get(),
            ownerIdProvider = { get<CloudSessionManager>().getCurrentAccount().userId },
            appVersion = AppConfig.versionName,
        )
    }

    // Cloud sync
    single {
        SupabaseSessionStore(
            DesktopSupabaseSessionPersistence(DesktopPathProvider.getDataDirectory()),
        )
    }
    single { SupabaseAuthApi(BackendConfig.supabaseUrl, BackendConfig.supabaseAnonKey) }
    single { SupabaseSessionManager(get(), get()) }
    single<SupabaseAccessTokenProvider> { SupabaseSessionAccessTokenProvider(get(), get()) }
    single<CloudSessionManager> { get<SupabaseSessionManager>() }

    single<AttachmentBytesProtector> {
        DesktopAttachmentBytesProtector(
            keyDir = File(System.getProperty("user.home"), ".notelikeus"),
        )
    }
    single<AttachmentLocalStorage> {
        val home = File(System.getProperty("user.home"), ".notelikeus")
        val protector = get<AttachmentBytesProtector>()
        AttachmentAtRestMigrator.migrateAttachmentsRoot(File(home, "attachments"), protector)
        AttachmentAtRestMigrator.migratePendingStagingRoot(File(home, "pending-attachments"), protector)
        DesktopAttachmentLocalStorage(protector = protector, homeDir = File(System.getProperty("user.home")))
    }
    single<AttachmentBlobTransport> {
        if (BackendConfig.attachmentsWorkerUrl.isNotEmpty()) {
            R2AttachmentBlobTransport(
                workerBaseUrl = BackendConfig.attachmentsWorkerUrl,
                accessTokenProvider = get(),
                ownerIdProvider = { get<SupabaseSessionManager>().ensureSignedIn().getOrThrow() },
            )
        } else {
            NoopAttachmentBlobTransport()
        }
    }
    single<AttachmentStagingStore> {
        FileAttachmentStagingStore(
            root = File(System.getProperty("user.home"), ".notelikeus/pending-attachments")
                .absolutePath.toPath(),
            ioDispatcher = get(),
            protector = get(),
        )
    }
    single {
        AttachmentSyncService(
            blobTransport = get(),
            metadata = SupabaseAttachmentMetadata(
                DesktopSupabaseRpcClient(
                    supabaseUrl = BackendConfig.supabaseUrl,
                    anonKey = BackendConfig.supabaseAnonKey,
                    accessTokenProvider = get<SupabaseAccessTokenProvider>(),
                ),
            ),
            localStorage = get(),
            noteDao = get(),
            staging = get(),
            ownerIdProvider = { get<SupabaseSessionManager>().getCurrentAccount().userId },
        )
    }

    // The engine's transport is registered under the *identity-bound* contract, not
    // `CloudNoteTransport`: the two are separate types so that no wiring can hand the engine a
    // transport whose protected calls would resolve their bearer from the live session.
    // The transport is registered by its own type so that the two interfaces it implements resolve to
    // this one instance: clearing dataset-scoped revision state on a second instance would leave the
    // engine's map untouched, which is the whole defect this registration avoids.
    single {
        SupabaseNoteTransport(
            DesktopSupabaseRpcClient(
                supabaseUrl = BackendConfig.supabaseUrl,
                anonKey = BackendConfig.supabaseAnonKey,
                accessTokenProvider = get<SupabaseAccessTokenProvider>(),
            ),
        )
    }
    single<IdentityBoundNoteTransport> { get<SupabaseNoteTransport>() }
    single<DatasetScopedCloudRevisionState> { get<SupabaseNoteTransport>() }

    /**
     * Supplies the immutable `OperationRemoteIdentity` every protected note operation executes under.
     *
     * Reuses the same live session the rest of the graph uses — one credential source, read once per
     * logical operation and then never again — and reads the owner either side of the credential
     * acquisition so a refresh that lands after a sign-in cannot produce `Identity(A, tokenB)`.
     */
    single<RemoteIdentityProvider> {
        RemoteIdentityProvider(
            accessTokenProvider = get<SupabaseAccessTokenProvider>(),
            sessionOwnerId = { get<SupabaseSessionManager>().getCurrentAccount().userId },
            // Read inside the identity capture's held gate section: the operation's dataset
            // binding is decided by the same token validation that authorises it.
            revisionEpochProvider = { get<DatasetScopedCloudRevisionState>().currentRevisionEpoch() },
        )
    }

    single<NoteSyncStateStore> {
        DesktopNoteSyncStateStore(get())
    }

    // persists it before any destructive call and clears it only on authoritative completion. The
    // store is platform-backed on purpose: it has to survive sign-out, process death and the local
    // account isolation that clears everything else.

    // F-8: the durable record of an accepted destructive wipe request, plus the coordinator that
    // persists it before any destructive call and clears it only on authoritative completion. The
    // store is platform-backed on purpose: it has to survive sign-out, process death and the local
    // account isolation that clears everything else.
    single<com.aus.notelikeus.data.sync.PendingCloudWipeIntentStore> {
        DesktopPendingCloudWipeIntentStore(File(DesktopPathProvider.getDataDirectory(), PENDING_WIPE_FILENAME))
    }
    single { CloudWipeCoordinator(get()) }

    single { AccountUidBridge(get()) }

    single {
        val sessionManager = get<CloudSessionManager>()
        val database = get<NotelikeusDatabase>()
        NoteSyncEngine(
            transport = get<IdentityBoundNoteTransport>(),
            // Mandatory, non-nullable, and the same provider the rest of the graph captures identity
            // with: the engine cannot be constructed without a way to bind protected calls to the
            // account their operation started as.
            remoteIdentityProvider = get(),
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
            // The session-backed, generation-coherent provider: the engine captures its operation
            // token once at the start and every local commit carries it.
            localCommitTokenProvider = get(),
            // The scheduled-work authority: the same instance the coordinator stamps queued
            // commands from, so a command's epoch and the epoch an engine entry validates cannot
            // be two different values.
            datasetEpochAuthority = get(),
        )
    }

    single {
        LocalAccountIsolator(
            get(),
            get(),
            get(),
            // Resolved lazily: AttachmentSyncService is built from the sync graph this
            // isolator belongs to, so taking it as a constructor argument would cycle.
            adoptGuestStagedAttachments = { uid ->
                get<AttachmentSyncService>().adoptGuestStagedAttachments(uid)
            },
            // One hook, both halves of the dataset's in-memory state: staged bytes and learned cloud
            // revisions. They have to be cleared together and at the boundary, not at sign-out.
            clearDatasetScopedInMemoryState = {
                get<AttachmentSyncService>().clearStagingCache()
                get<DatasetScopedCloudRevisionState>().clearDatasetScopedState()
                // Decoded copies of the departing library's pictures, for the same reason as the staged bytes.
                clearAttachmentThumbnailCache()
            },
        )
    }
    /**
     * Diagnostics read the same stores sync does, and nothing else. Registered per platform
     * because only the platform knows what its storage is and whether it is encrypted — the two
     * facts a user troubleshooting "where are my notes" most needs stated plainly.
     */
    single {
        DiagnosticsCollector(
            loadNotes = { get<NoteRepository>().getAllNotesForBackup() },
            syncStateStore = get(),
            staging = get(),
            ownerIdProvider = { get<CloudSessionManager>().getCurrentAccount().userId },
            isSignedIn = { get<CloudSessionManager>().getCurrentAccount().userId != null },
            databaseSchemaVersion = NOTELIKEUS_DATABASE_VERSION,
            storageKind = "Room",
            encryptedAtRest = DesktopSqliteFlags.useJdbcSqlite(),
        )
    }

    single<SyncManager> {
        DesktopSyncManager(
            get<NoteSyncEngine>(),
            get<CloudSessionManager>(),
            get<LocalAccountIsolator>(),
            get<CloudWipeCoordinator>(),
        )
    }

    single<GoogleSignInHelper> {
        DesktopGoogleSignInHelper(
            oauthClientId = DesktopOAuthConfig.CLIENT_ID,
            oauthClientSecret = DesktopOAuthConfig.clientSecret(),
            supabaseAuthApi = get(),
            supabaseSessionStore = get(),
        )
    }
}

/**
 * Google OAuth client configuration for the desktop build.
 *
 * The client ID identifies the project and is public by design.
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
 *  3. [DesktopSecrets], generated at build time from the same two sources.
 *
 * Empty means desktop sign-in is simply not configured.
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

    private const val SECRET_ENV_VAR = "NOTELIKEUS_OAUTH_CLIENT_SECRET"
    private const val SECRET_PROPERTY = "notelikeus.oauthClientSecret"

    fun clientSecret(): String {
        System.getenv(SECRET_ENV_VAR)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        readLocalProperty(SECRET_PROPERTY)?.let { return it }
        return DesktopSecrets.OAUTH_CLIENT_SECRET
    }
}
