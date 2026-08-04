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
import com.aus.notelikeus.platform.AndroidWidgetManager
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.koin.dsl.module
import java.io.File

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

        getDatabaseBuilder(context)
            .openHelperFactory(SupportOpenHelperFactory(passphrase))
            .addMigrations(*DatabaseMigrations.ALL)
            .build()
    }
    
    single { get<NotelikeusDatabase>().noteDao }
    single { get<NotelikeusDatabase>().labelDao }
    
    single<ReminderManager> { ReminderScheduler(get()) }
    single<PlatformWidgetManager> { AndroidWidgetManager(get()) }
    
    single { DatabaseKeyManager(get()) }
}
