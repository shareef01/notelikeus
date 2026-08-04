package com.aus.notelikeus.data.local

import androidx.room.RoomDatabase

expect fun getDatabaseBuilder(): RoomDatabase.Builder<NotelikeusDatabase>
