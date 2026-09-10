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
import com.aus.notelikeus.data.remote.SupabaseAccessTokenProvider
import com.aus.notelikeus.data.remote.SupabaseAuthApi
import com.aus.notelikeus.data.remote.SupabaseNoteTransport
import com.aus.notelikeus.data.remote.SupabaseSessionAccessTokenProvider
import com.aus.notelikeus.data.remote.SupabaseSessionManager
import com.aus.notelikeus.data.remote.SupabaseSessionStore
import com.aus.notelikeus.data.attachments.AndroidAttachmentLocalStorage
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
import com.aus.notelikeus.data.sync.LocalAccountIsolator
import com.aus.notelikeus.data.sync.NoteSyncEngine
import com.aus.notelikeus.data.sync.NoteSyncStateStore
import com.aus.notelikeus.data.sync.CloudNoteTransport
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
    single { SharedPrefsNoteSyncStateStore(get()) }
    single<NoteSyncStateStore> { get<SharedPrefsNoteSyncStateStore>() }
    single { androidx.work.WorkManager.getInstance(get<android.content.Context>()) }

    single { SupabaseSessionStore(AndroidSupabaseSessionPersistence(get())) }
    single { SupabaseAuthApi(BackendConfig.supabaseUrl, BackendConfig.supabaseAnonKey) }
    single { SupabaseSessionManager(get(), get()) }
    single<SupabaseAccessTokenProvider> { SupabaseSessionAccessTokenProvider(get(), get()) }
    single<CloudSessionManager> { get<SupabaseSessionManager>() }
    single<AttachmentLocalStorage> { AndroidAttachmentLocalStorage(get()) }
    single<AttachmentBlobTransport> {
        if (BackendConfig.attachmentsWorkerUrl.isNotEmpty()) {
            val rpcClient = AndroidSupabaseRpcClient(
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
    single<AttachmentStagingStore> {
        FileAttachmentStagingStore(
            root = File(get<android.content.Context>().filesDir, "pending-attachments")
                .absolutePath.toPath(),
            ioDispatcher = get(),
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
    single<CloudNoteTransport> {
        SupabaseNoteTransport(
            AndroidSupabaseRpcClient(
                supabaseUrl = BackendConfig.supabaseUrl,
                anonKey = BackendConfig.supabaseAnonKey,
                accessTokenProvider = get<SupabaseAccessTokenProvider>(),
            ),
        )
    }
    single { AccountUidBridge(get()) }
    single {
        val sessionManager = get<CloudSessionManager>()
        val database = get<NotelikeusDatabase>()
        NoteSyncEngine(
            transport = get<CloudNoteTransport>(),
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
        )
    }
    
    // Sync
    single { PendingCloudSyncStore(get()) }
    single<SyncCoordinator> { CloudNoteSyncCoordinator(get(), get(), get(), get(), get()) }
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
            clearStagedAttachmentCache = { get<AttachmentSyncService>().clearStagingCache() },
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

    single<SyncManager> { AndroidSyncManager(get(), get(), get()) }

    single<GoogleSignInHelper> {
        AndroidGoogleSignInHelper(
            context = get(),
            webClientId = get(named("webClientId")),
            activityProvider = { ForegroundActivityTracker.current() }
        )
    }
}
