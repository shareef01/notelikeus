package com.aus.notelikeus.di

import com.aus.notelikeus.data.remote.GoogleSignInHelper
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface GoogleSignInEntryPoint {
    fun googleSignInHelper(): GoogleSignInHelper
}
