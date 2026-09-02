package com.aus.notelikeus.data.remote

fun interface SupabaseAccessTokenProvider {
    suspend fun accessToken(): String?
}
