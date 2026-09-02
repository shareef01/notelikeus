package com.aus.notelikeus.data.remote

class DevSupabaseAccessTokenProvider : SupabaseAccessTokenProvider {
    override suspend fun accessToken(): String? =
        System.getenv("NOTELIKEUS_SUPABASE_ACCESS_TOKEN")?.trim()?.takeIf { it.isNotEmpty() }
}
