package com.aus.notelikeus.di

import android.content.Context
import com.aus.notelikeus.data.local.DatabaseKeyManager
import com.aus.notelikeus.data.local.NotelikeusDatabase
import com.aus.notelikeus.data.local.PlaintextDatabaseMigrator
import com.aus.notelikeus.data.local.DatabaseMigrations
import com.aus.notelikeus.data.local.dao.LabelDao
import com.aus.notelikeus.data.local.dao.NoteDao
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.koin.dsl.module

val databaseModule = module {
    single<NotelikeusDatabase> {
        val context = get<android.content.Context>()
        val keyManager = get<DatabaseKeyManager>()
        System.loadLibrary("sqlcipher")
        val passphrase = keyManager.getPassphrase()
        PlaintextDatabaseMigrator.migrateToEncryptedIfNeeded(
            context,
            NotelikeusDatabase.DATABASE_NAME,
            passphrase
        )

        androidx.room.Room.databaseBuilder(
            context,
            NotelikeusDatabase::class.java,
            NotelikeusDatabase.DATABASE_NAME
        )
        .openHelperFactory(SupportOpenHelperFactory(passphrase))
        .addMigrations(*DatabaseMigrations.ALL)
        .build()
    }

    single { get<NotelikeusDatabase>().noteDao }
    single { get<NotelikeusDatabase>().labelDao }
}
