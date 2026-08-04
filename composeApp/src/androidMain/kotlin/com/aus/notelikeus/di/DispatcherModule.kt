package com.aus.notelikeus.di

import org.koin.dsl.module
import org.koin.core.qualifier.named
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher

val dispatcherModule = module {
    single<CoroutineDispatcher>(named("DefaultDispatcher")) { Dispatchers.Default }
}
