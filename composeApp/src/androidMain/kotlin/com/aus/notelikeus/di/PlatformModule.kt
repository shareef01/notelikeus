package com.aus.notelikeus.di

import com.aus.notelikeus.data.local.NOTELIKEUS_DATABASE_VERSION
import com.aus.notelikeus.domain.diagnostics.DiagnosticsCollector
import com.aus.notelikeus.domain.repository.NoteRepository
import com.aus.notelikeus.data.ReminderScheduler
import com.aus.notelikeus.data.local.DatabaseKeyManager
import com.aus.notelikeus.data.local.DatabaseMigrations
import com.aus.notelikeus.data.local.NotelikeusDatabase
import com.aus.notelikeus.data.local.PlaintextDatabaseMigrator
import com.aus.notelikeus.data.local.getDatabaseBuilder
import com.aus.notelikeus.data.local.settingsDataStore
import com.aus.notelikeus.domain.platform.PlatformWidgetManager
import com.aus.notelikeus.domain.platform.ReminderManager
import com.aus.notelikeus.domain.platform.SyncCoordinator
import com.aus.notelikeus.platform.AndroidWidgetManager
import com.aus.notelikeus.platform.ForegroundActivityTracker
import com.aus.notelikeus.data.backup.NoteBackupExporter
import com.aus.notelikeus.data.backup.NoteBackupImporter
import com.aus.notelikeus.data.remote.SharedPrefsNoteSyncStateStore
import com.aus.notelikeus.data.remote.AndroidSupabaseRpcClient
import com.aus.notelikeus.data.remote.AndroidSupabaseSessionPersistence
import com.aus.notelikeus.data.remote.BackendConfig
import com.aus.notelikeus.data.remote.CloudSessionManager
import com.aus.notelikeus.data.remote.RemoteIdentityProvider
import com.aus.notelikeus.data.remote.SupabaseAccessTokenProvider
import com.aus.notelikeus.data.remote.SupabaseAuthApi
import com.aus.notelikeus.data.remote.SupabaseNoteTransport
import com.aus.notelikeus.data.remote.SupabaseSessionAccessTokenProvider
import com.aus.notelikeus.data.remote.SupabaseSessionManager
import com.aus.notelikeus.data.remote.SupabaseSessionStore
import com.aus.notelikeus.data.attachments.AndroidAttachmentBytesProtector
import com.aus.notelikeus.data.attachments.AndroidAttachmentLocalStorage
import com.aus.notelikeus.data.attachments.AttachmentAtRestMigrator
import com.aus.notelikeus.data.attachments.AttachmentBytesProtector
import com.aus.notelikeus.data.attachments.AttachmentLocalStorage
import com.aus.notelikeus.data.attachments.AttachmentStagingStore
import com.aus.notelikeus.data.attachments.AttachmentSyncService
import com.aus.notelikeus.data.attachments.FileAttachmentStagingStore
import java.io.File
import okio.Path.Companion.toPath
import com.aus.notelikeus.data.remote.AttachmentBlobTransport
import com.aus.notelikeus.data.remote.NoopAttachmentBlobTransport
import com.aus.notelikeus.data.remote.R2AttachmentBlobTransport
import com.aus.notelikeus.data.remote.SupabaseAttachmentMetadata
import com.aus.notelikeus.data.migration.AccountUidBridge
import com.aus.notelikeus.data.sync.DatasetEpochAuthority
import com.aus.notelikeus.data.sync.LocalCommitGate
import com.aus.notelikeus.data.sync.DatasetEpochStore
import com.aus.notelikeus.data.sync.LocalAccountIsolator
import com.aus.notelikeus.data.sync.NoteSyncEngine
import com.aus.notelikeus.data.sync.NoteSyncStateStore
import com.aus.notelikeus.data.remote.DatasetScopedCloudRevisionState
import com.aus.notelikeus.data.sync.IdentityBoundNoteTransport
import com.aus.notelikeus.data.remote.CloudNoteSyncCoordinator
import com.aus.notelikeus.data.remote.PendingCloudSyncStore
import com.aus.notelikeus.data.remote.AndroidGoogleSignInHelper
import com.aus.notelikeus.platform.AndroidSyncManager
import com.aus.notelikeus.domain.repository.SyncManager
import com.aus.notelikeus.ui.auth.GoogleSignInHelper
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.koin.core.qualifier.named
import org.koin.dsl.module
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection

import com.aus.notelikeus.data.remote.AndroidPendingCloudWipeIntentStore
import com.aus.notelikeus.data.sync.CloudWipeCoordinator
actual val platformModule = module {
    // Same singleton Glance uses (`Context.settingsDataStore`). A second
    // PreferenceDataStoreFactory on that file crashes App Functions / the widget with
    // "multiple DataStores active for the same file".
    single { get<android.content.Context>().settingsDataStore }

    single<NotelikeusDatabase> {
        val context = get<android.content.Context>()
        val keyManager = get<DatabaseKeyManager>()
        val passphrase = keyManager.getPassphrase()
        
        PlaintextDatabaseMigrator.migrateToEncryptedIfNeeded(
            context,
            NotelikeusDatabase.DATABASE_NAME,
            passphrase
        )

        getDatabaseBuilder()
            .openHelperFactory(SupportOpenHelperFactory(passphrase))
            .addMigrations(*DatabaseMigrations.ALL)
            .build()
    }
    
    single { get<NotelikeusDatabase>().noteDao }
    single { get<NotelikeusDatabase>().labelDao }
    
    single<ReminderManager> { ReminderScheduler(get()) }
    single<PlatformWidgetManager> { AndroidWidgetManager(get()) }
    
    single { DatabaseKeyManager(get()) }
    
    single { 
        val context = get<android.content.Context>()
        NoteBackupExporter(
            repository = get<NoteRepository>(),
            appName = context.getString(com.aus.notelikeus.shared.R.string.app_name),
            appVersion = com.aus.notelikeus.util.AppConfig.versionName
        )
    }
    single { NoteBackupImporter(get<NoteRepository>()) }
    single<com.aus.notelikeus.data.backup.BackupBundleOperations> {
        com.aus.notelikeus.data.backup.bundle.BackupBundleTransfer(
            repository = get(),
            exporter = get(),
            importer = get(),
            staging = get(),
            localStorage = get(),
            ownerIdProvider = { get<CloudSessionManager>().getCurrentAccount().userId },
            appName = get<android.content.Context>().getString(com.aus.notelikeus.shared.R.string.app_name),
            appVersion = com.aus.notelikeus.util.AppConfig.versionName,
        )
    }
    single { SharedPrefsNoteSyncStateStore(get()) }
    single<NoteSyncStateStore> { get<SharedPrefsNoteSyncStateStore>() }
    single { androidx.work.WorkManager.getInstance(get<android.content.Context>()) }
    single<com.aus.notelikeus.ui.navigation.ExternalImageIngestor> {
        com.aus.notelikeus.ui.navigation.DefaultExternalImageIngestor(get<android.content.Context>().contentResolver)
    }

    single { SupabaseSessionStore(AndroidSupabaseSessionPersistence(get())) }
    single { SupabaseAuthApi(BackendConfig.supabaseUrl, BackendConfig.supabaseAnonKey) }
    single { SupabaseSessionManager(get(), get()) }
    single<SupabaseAccessTokenProvider> { SupabaseSessionAccessTokenProvider(get(), get()) }
    single<CloudSessionManager> { get<SupabaseSessionManager>() }
    single<AttachmentBytesProtector> { AndroidAttachmentBytesProtector() }
    single<AttachmentLocalStorage> {
        val context = get<android.content.Context>()
        val protector = get<AttachmentBytesProtector>()
        val attachmentsDir = File(context.filesDir, "attachments")
        val stagingRoot = File(context.filesDir, "pending-attachments")
        AttachmentAtRestMigrator.migrateAttachmentsRoot(attachmentsDir, protector)
        AttachmentAtRestMigrator.migratePendingStagingRoot(stagingRoot, protector)
        AndroidAttachmentLocalStorage(context, protector)
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
            root = File(get<android.content.Context>().filesDir, "pending-attachments")
                .absolutePath.toPath(),
            ioDispatcher = get(),
            protector = get(),
        )
    }
    single {
        AttachmentSyncService(
            blobTransport = get(),
            metadata = SupabaseAttachmentMetadata(
                AndroidSupabaseRpcClient(
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
            AndroidSupabaseRpcClient(
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
    // persists it before any destructive call and clears it only on authoritative completion. The
    // store is platform-backed on purpose: it has to survive sign-out, process death and the local
    // account isolation that clears everything else.

    // F-8: the durable record of an accepted destructive wipe request, plus the coordinator that
    // persists it before any destructive call and clears it only on authoritative completion. The
    // store is platform-backed on purpose: it has to survive sign-out, process death and the local
    // account isolation that clears everything else.
    single<com.aus.notelikeus.data.sync.PendingCloudWipeIntentStore> {
        AndroidPendingCloudWipeIntentStore(get())
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
            syncStateStore = get<SharedPrefsNoteSyncStateStore>(),
            uidProvider = { sessionManager.ensureSignedIn() },
            runInTransaction = { block ->
                database.useWriterConnection { transactor ->
                    transactor.immediateTransaction { block() }
                }
            },
            attachmentSync = get(),
            // The session-backed, generation-coherent provider: the engine captures its operation
            // token once at the start and every local commit carries it.
            localCommitTokenProvider = get(),
            // The scheduled-work authority: the *same* instance the coordinator stamps queued
            // commands from, so a command's epoch and the epoch an engine entry validates cannot
            // be two different values.
            datasetEpochAuthority = get(),
        )
    }

    // Sync
    single { PendingCloudSyncStore(get()) }
    single<DatasetEpochStore> { get<PendingCloudSyncStore>() }
    single { DatasetEpochAuthority(get()).also { authority ->
        // The incomplete-isolation quarantine: while the device is between datasets, no
        // account-owned local commit may proceed either — see LocalCommitGate's invariant note.
        LocalCommitGate.installDatasetAuthorityQuarantine { authority.isIsolationIncomplete() }
    } }
    single<SyncCoordinator> { CloudNoteSyncCoordinator(get(), get(), get(), get()) }
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
            storageKind = "Room + SQLCipher",
            encryptedAtRest = true,
        )
    }

    single<SyncManager> { AndroidSyncManager(get(), get(), get(), get()) }

    single<GoogleSignInHelper> {
        AndroidGoogleSignInHelper(
            context = get(),
            webClientId = get(named("webClientId")),
            activityProvider = { ForegroundActivityTracker.current() }
        )
    }
}
