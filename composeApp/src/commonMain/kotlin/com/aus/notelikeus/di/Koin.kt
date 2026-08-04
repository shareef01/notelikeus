package com.aus.notelikeus.di

import com.aus.notelikeus.data.repository.NoteRepositoryImpl
import com.aus.notelikeus.data.repository.SettingsRepositoryImpl
import com.aus.notelikeus.domain.repository.NoteRepository
import com.aus.notelikeus.domain.repository.SettingsRepository
import com.aus.notelikeus.ui.main.MainViewModel
import com.aus.notelikeus.ui.editor.EditorViewModel
import com.aus.notelikeus.ui.labels.LabelsViewModel
import com.aus.notelikeus.data.backup.NoteBackupExporter
import com.aus.notelikeus.data.backup.NoteBackupImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.dsl.KoinAppDeclaration
import org.koin.dsl.module
import org.koin.core.module.dsl.viewModel

val sharedModule = module {
    single<NoteRepository> { NoteRepositoryImpl(get(), get(), get(), get(), get()) }
    single<SettingsRepository> { SettingsRepositoryImpl(get(), get()) }
    
    single { NoteBackupExporter(get()) }
    single { NoteBackupImporter(get()) }
    
    single { Dispatchers.IO }
    
    viewModel { MainViewModel(get(), get(), get(), get(), get(), get()) }
    viewModel { EditorViewModel(get(), get(), get(), get(), get()) } // TODO: Update EditorViewModel
    viewModel { LabelsViewModel(get()) }
}

expect val platformModule: Module

fun initKoin(appDeclaration: KoinAppDeclaration = {}) =
    startKoin {
        appDeclaration()
        modules(sharedModule, platformModule)
    }
