package com.aus.notelikeus.data.remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SupabaseBackendSelectionTest {

    @Test
    fun firstNonBlankSkipsEmptyBuildConfigAndPrefersRuntimeEnv() {
        assertNull(firstNonBlank(null, "", "  "))
        assertEquals("supabase", firstNonBlank("", "  ", "supabase"))
        assertEquals("from-env", firstNonBlank("from-env", "from-buildconfig"))
    }

    @Test
    fun localUrlDetection() {
        assertTrue(isLocalSupabaseUrl("http://127.0.0.1:54321"))
        assertTrue(isLocalSupabaseUrl("http://localhost:54321"))
        assertTrue(isLocalSupabaseUrl(""))
        assertFalse(isLocalSupabaseUrl("https://abcd.supabase.co"))
    }

    @Test
    fun requireConfiguredSupabaseUrlRejectsCleartextLoopback() {
        val error = runCatching { requireConfiguredSupabaseUrl("http://127.0.0.1:54321") }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertTrue(error.message!!.contains("cleartext"))
    }

    @Test
    fun requireConfiguredSupabaseUrlRejectsEmpty() {
        val error = runCatching { requireConfiguredSupabaseUrl("") }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertTrue(error.message!!.contains("not set"))
    }

    @Test
    fun requireConfiguredSupabaseUrlAcceptsHostedHttps() {
        requireConfiguredSupabaseUrl("https://abcd.supabase.co")
    }

    @Test
    fun debugFallsBackToLocalSupabaseWhenConfigIsEmpty() {
        assertEquals(DEFAULT_LOCAL_SUPABASE_URL, resolveSupabaseUrl(null, allowLocalFallback = true))
        assertEquals(DEFAULT_LOCAL_SUPABASE_URL, resolveSupabaseUrl("", allowLocalFallback = true))
        assertEquals(DEFAULT_LOCAL_SUPABASE_ANON_KEY, resolveSupabaseAnonKey(null, allowLocalFallback = true))
        assertEquals(DEFAULT_LOCAL_SUPABASE_ANON_KEY, resolveSupabaseAnonKey("  ", allowLocalFallback = true))
    }

    @Test
    fun releaseAndAndroidWithoutHostedConfigFailClosed() {
        assertEquals("", resolveSupabaseUrl(null, allowLocalFallback = false))
        assertEquals("", resolveSupabaseUrl("", allowLocalFallback = false))
        assertEquals("", resolveSupabaseUrl("http://127.0.0.1:54321", allowLocalFallback = false))
        assertEquals("", resolveSupabaseUrl("http://localhost:54321", allowLocalFallback = false))
        assertEquals("", resolveSupabaseAnonKey(null, allowLocalFallback = false))
        assertEquals("", resolveSupabaseAnonKey("", allowLocalFallback = false))
    }

    @Test
    fun hostedConfigWinsInReleaseAndDebug() {
        val hosted = "https://abcd.supabase.co"
        val anon = "eyJhbGciOiJIUzI1NiJ9.payload.signature"
        assertEquals(hosted, resolveSupabaseUrl(hosted, allowLocalFallback = false))
        assertEquals(hosted, resolveSupabaseUrl(hosted, allowLocalFallback = true))
        assertEquals(anon, resolveSupabaseAnonKey(anon, allowLocalFallback = false))
        assertEquals(anon, resolveSupabaseAnonKey(anon, allowLocalFallback = true))
    }
}
