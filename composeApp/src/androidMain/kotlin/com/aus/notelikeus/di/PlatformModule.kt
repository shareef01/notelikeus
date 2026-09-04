package com.aus.notelikeus.di

import com.aus.notelikeus.data.ReminderScheduler
import com.aus.notelikeus.data.local.DatabaseKeyManager
import com.aus.notelikeus.data.local.DatabaseMigrations
import com.aus.notelikeus.data.local.NotelikeusDatabase
import com.aus.notelikeus.data.local.PlaintextDatabaseMigrator
import com.aus.notelikeus.data.local.getDatabaseBuilder
import com.aus.notelikeus.data.local.createDataStore
import com.aus.notelikeus.data.local.SETTINGS_DATASTORE_FILENAME
import com.aus.notelikeus.domain.platform.PlatformWidgetManager
import com.aus.notelikeus.domain.platform.ReminderManager
import com.aus.notelikeus.domain.platform.SyncCoordinator
import com.aus.notelikeus.platform.AndroidWidgetManager
import com.aus.notelikeus.platform.ForegroundActivityTracker
import com.aus.notelikeus.data.backup.NoteBackupExporter
import com.aus.notelikeus.data.backup.NoteBackupImporter
import com.aus.notelikeus.domain.repository.NoteRepository
import com.aus.notelikeus.data.remote.SharedPrefsNoteSyncStateStore
import com.aus.notelikeus.data.remote.FirebaseSessionManager
import com.aus.notelikeus.data.remote.AndroidSupabaseRpcClient
import com.aus.notelikeus.data.remote.BackendConfig
import com.aus.notelikeus.data.remote.CloudSessionManager
import com.aus.notelikeus.data.remote.FirestoreNoteTransport
import com.aus.notelikeus.data.remote.RemoteBackend
import com.aus.notelikeus.data.remote.SupabaseAccessTokenProvider
import com.aus.notelikeus.data.remote.SupabaseAuthApi
import com.aus.notelikeus.data.remote.SupabaseNoteTransport
import com.aus.notelikeus.data.remote.SupabaseSessionAccessTokenProvider
import com.aus.notelikeus.data.remote.SupabaseSessionManager
import com.aus.notelikeus.data.remote.SupabaseSessionStore
import com.aus.notelikeus.data.attachments.AndroidAttachmentLocalStorage
import com.aus.notelikeus.data.attachments.AttachmentLocalStorage
import com.aus.notelikeus.data.attachments.AttachmentSyncService
import com.aus.notelikeus.data.remote.AttachmentBlobTransport
import com.aus.notelikeus.data.remote.NoopAttachmentBlobTransport
import com.aus.notelikeus.data.remote.R2AttachmentBlobTransport
import com.aus.notelikeus.data.remote.SupabaseAttachmentMetadata
import com.aus.notelikeus.data.migration.AccountUidBridge
import com.aus.notelikeus.data.migration.FirebaseSupabaseAccountLinker
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
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.koin.core.qualifier.named
import org.koin.dsl.module
import java.io.File
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection

actual val platformModule = module {
    single {
        val context = get<android.content.Context>()
        createDataStore {
            File(context.filesDir, "datastore/$SETTINGS_DATASTORE_FILENAME").absolutePath
        }
    }

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

    // Firebase Core
    single { FirebaseAuth.getInstance() }
    single { FirebaseFirestore.getInstance() }
    single { FirebaseSessionManager(get(), get()) }
    single { SupabaseSessionStore() }
    single { SupabaseAuthApi(BackendConfig.supabaseUrl, BackendConfig.supabaseAnonKey) }
    single { SupabaseSessionManager(get(), get()) }
    single<SupabaseAccessTokenProvider> { SupabaseSessionAccessTokenProvider(get(), get()) }
    single<CloudSessionManager> {
        if (BackendConfig.remoteBackend == RemoteBackend.SUPABASE) {
            get<SupabaseSessionManager>()
        } else {
            get<FirebaseSessionManager>()
        }
    }
    single<AttachmentLocalStorage> { AndroidAttachmentLocalStorage(get()) }
    single<AttachmentBlobTransport> {
        if (
            BackendConfig.remoteBackend == RemoteBackend.SUPABASE &&
            BackendConfig.attachmentsWorkerUrl.isNotEmpty()
        ) {
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
    single {
        val metadata = if (BackendConfig.remoteBackend == RemoteBackend.SUPABASE) {
            SupabaseAttachmentMetadata(
                AndroidSupabaseRpcClient(
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
                AndroidSupabaseRpcClient(
                    supabaseUrl = BackendConfig.supabaseUrl,
                    anonKey = BackendConfig.supabaseAnonKey,
                    accessTokenProvider = get<SupabaseAccessTokenProvider>(),
                ),
            )
        } else {
            FirestoreNoteTransport(get())
        }
    }
    single { AccountUidBridge(get()) }
    single {
        FirebaseSupabaseAccountLinker(
            remoteBackend = BackendConfig.remoteBackend,
            accountUidBridge = get(),
            syncStateStore = get<NoteSyncStateStore>(),
            supabaseRpc = if (BackendConfig.remoteBackend == RemoteBackend.SUPABASE) {
                AndroidSupabaseRpcClient(
                    supabaseUrl = BackendConfig.supabaseUrl,
                    anonKey = BackendConfig.supabaseAnonKey,
                    accessTokenProvider = get<SupabaseAccessTokenProvider>(),
                )
            } else {
                null
            },
            // Android can see the live Firebase session directly, so a breadcrumb-sourced uid can
            // be corroborated rather than refused.
            firebaseSession = { com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid },
        )
    }
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
    single { LocalAccountIsolator(get(), get(), get()) }
    single<SyncManager> { AndroidSyncManager(get(), get(), get(), get()) }

    single<GoogleSignInHelper> {
        AndroidGoogleSignInHelper(
            context = get(),
            webClientId = get(named("webClientId")),
            activityProvider = { ForegroundActivityTracker.current() }
        )
    }
}
