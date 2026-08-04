package com.aus.notelikeus.data.local

import androidx.room.Room
import androidx.room.RoomDatabase
import java.io.File

fun getDatabaseBuilder(): RoomDatabase.Builder<NotelikeusDatabase> {
    val dbFile = File(System.getProperty("java.io.tmpdir"), NotelikeusDatabase.DATABASE_NAME)
    return Room.databaseBuilder<NotelikeusDatabase>(
        name = dbFile.absolutePath,
    )
}
