package com.aus.notelikeus.data.local

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase

fun getDatabaseBuilder(context: Context): RoomDatabase.Builder<NotelikeusDatabase> {
    val dbFile = context.getDatabasePath(NotelikeusDatabase.DATABASE_NAME)
    return Room.databaseBuilder<NotelikeusDatabase>(
        context = context,
        name = dbFile.absolutePath
    )
}
