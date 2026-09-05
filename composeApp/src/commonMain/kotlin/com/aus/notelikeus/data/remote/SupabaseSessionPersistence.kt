package com.aus.notelikeus.data.remote

interface SupabaseSessionPersistence {
    fun load(): SupabaseAuthSession?
    fun save(session: SupabaseAuthSession)
    fun clear()
}

class InMemorySupabaseSessionPersistence : SupabaseSessionPersistence {
    override fun load(): SupabaseAuthSession? = null
    override fun save(session: SupabaseAuthSession) = Unit
    override fun clear() = Unit
}
