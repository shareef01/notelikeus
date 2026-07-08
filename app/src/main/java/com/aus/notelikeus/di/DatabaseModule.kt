package com.aus.notelikeus.di

import android.content.Context
import com.aus.notelikeus.data.local.DatabaseKeyManager
import com.aus.notelikeus.data.local.NotelikeusDatabase
import com.aus.notelikeus.data.local.PlaintextDatabaseMigrator
import com.aus.notelikeus.data.local.DatabaseMigrations
import com.aus.notelikeus.data.local.dao.LabelDao
import com.aus.notelikeus.data.local.dao.NoteDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.zetetic.database.sqlcipher.SupportFactory
import javax.inject.Singleton
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideNotelikeusDatabase(
        @ApplicationContext context: Context,
        keyManager: DatabaseKeyManager
    ): NotelikeusDatabase {
        val passphrase = keyManager.getPassphrase()
        PlaintextDatabaseMigrator.migrateToEncryptedIfNeeded(
            context,
            NotelikeusDatabase.DATABASE_NAME,
            passphrase
        )

        return androidx.room.Room.databaseBuilder(
            context,
            NotelikeusDatabase::class.java,
            NotelikeusDatabase.DATABASE_NAME
        )
        .openHelperFactory(SupportFactory(passphrase))
        .addMigrations(*DatabaseMigrations.ALL)
        .build()
    }
    @Provides
    @Singleton
    fun provideNoteDao(db: NotelikeusDatabase): NoteDao {
        return db.noteDao
    }

    @Provides
    @Singleton
    fun provideLabelDao(db: NotelikeusDatabase): LabelDao {
        return db.labelDao
    }
}
