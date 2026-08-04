package com.aus.notelikeus.di

import com.aus.notelikeus.data.repository.NoteRepositoryImpl
import com.aus.notelikeus.data.repository.SettingsRepositoryImpl
import com.aus.notelikeus.domain.repository.NoteRepository
import com.aus.notelikeus.domain.repository.SettingsRepository
import org.koin.dsl.module

val repositoryModule = module {
    single<NoteRepository> { NoteRepositoryImpl(get(), get(), get(), get(), get()) }
    single<SettingsRepository> { SettingsRepositoryImpl(get(), get()) }
}
