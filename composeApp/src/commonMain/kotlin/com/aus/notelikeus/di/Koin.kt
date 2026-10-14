package com.aus.notelikeus.di

import com.aus.notelikeus.data.sync.NoteSyncStateStore
import com.aus.notelikeus.data.sync.SessionLocalCommitTokenProvider
import com.aus.notelikeus.domain.repository.LocalCommitTokenProvider
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
    single<LocalCommitTokenProvider> { SessionLocalCommitTokenProvider(get()) }
    single<NoteRepository> {
        val syncState = get<NoteSyncStateStore>()
        NoteRepositoryImpl(get(), get(), get(), get(), get(), get(), get()).apply {
            recordDeletedDigest = syncState::setDeletedDigest
        }
    }
    single<SettingsRepository> { SettingsRepositoryImpl(get(), get()) }
    
    single { Dispatchers.IO }
    
    // Named, not positional: `diagnostics` and `bundleOperations` have defaults, so a parameter
    // added ahead of them shifts every positional get() along and the last one silently falls
    // back to its default instead of failing. That is how .nlkbak import/export became a no-op.
    viewModel {
        MainViewModel(
            repository = get(),
            localCommitTokenProvider = get(),
            settingsRepository = get(),
            backupExporter = get(),
            backupImporter = get(),
            syncManager = get(),
            defaultDispatcher = get(),
            diagnostics = get(),
            bundleOperations = get(),
        )
    }
    viewModel { params -> 
        EditorViewModel(get(), get(), params.get(), get(), syncManager = getOrNull(), localCommitTokenProvider = get())
    }
    factory(named("windowEditor")) {
        EditorViewModel(get(), get(), SavedStateHandle(), get(), syncManager = getOrNull(), localCommitTokenProvider = get())
    }
    viewModel { LabelsViewModel(get(), get()) }
}

expect val platformModule: Module

fun initKoin(appDeclaration: KoinAppDeclaration = {}) =
    startKoin {
        appDeclaration()
        modules(sharedModule, platformModule)
    }
