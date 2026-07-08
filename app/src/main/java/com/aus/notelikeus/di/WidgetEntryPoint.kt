package com.aus.notelikeus.di

import com.aus.notelikeus.data.local.dao.NoteDao
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
  fun noteDao(): NoteDao
}
