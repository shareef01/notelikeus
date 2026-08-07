@file:JvmName("DatabaseBuilderDesktop")
package com.aus.notelikeus.data.local

import androidx.room.Room
import androidx.room.RoomDatabase
import com.aus.notelikeus.util.DesktopPathProvider
import java.io.File

actual fun getDatabaseBuilder(): RoomDatabase.Builder<NotelikeusDatabase> {
    val dbFile = File(DesktopPathProvider.getDataDirectory(), NotelikeusDatabase.DATABASE_NAME)
    return Room.databaseBuilder<NotelikeusDatabase>(
        name = dbFile.absolutePath,
    )
}
