package com.aus.notelikeus.di

import androidx.lifecycle.SavedStateHandle
import com.aus.notelikeus.data.repository.NoteRepositoryImpl
import com.aus.notelikeus.data.repository.SettingsRepositoryImpl
import com.aus.notelikeus.domain.repository.NoteRepository
import com.aus.notelikeus.domain.repository.SettingsRepository
import com.aus.notelikeus.ui.main.MainViewModel
import com.aus.notelikeus.ui.editor.EditorViewModel
import com.aus.notelikeus.ui.labels.LabelsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.dsl.KoinAppDeclaration
import org.koin.dsl.module
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named

val sharedModule = module {
    single<NoteRepository> { NoteRepositoryImpl(get(), get(), get(), get(), get(), get(), get()) }
    single<SettingsRepository> { SettingsRepositoryImpl(get(), get()) }
    
    single { Dispatchers.IO }
    
    viewModel { MainViewModel(get(), get(), get(), get(), get(), get()) }
    viewModel { params -> 
        EditorViewModel(get(), get(), get(), params.get()) 
    }
    // Standalone note windows on desktop cannot use koinViewModel (no SavedStateRegistryOwner
    // exists there), so they resolve the editor through this plain factory with a fresh handle.
    factory(named("windowEditor")) {
        EditorViewModel(get(), get(), get(), SavedStateHandle())
    }
    viewModel { LabelsViewModel(get()) }
}

expect val platformModule: Module

fun initKoin(appDeclaration: KoinAppDeclaration = {}) =
    startKoin {
        appDeclaration()
        modules(sharedModule, platformModule)
    }
