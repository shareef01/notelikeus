package com.aus.notelikeus.di

import android.app.Application
import android.content.Context
import androidx.work.WorkManager
import org.koin.dsl.module

val appModule = module {
    single<Context> { get<Application>().applicationContext }
    single { WorkManager.getInstance(get()) }
}
