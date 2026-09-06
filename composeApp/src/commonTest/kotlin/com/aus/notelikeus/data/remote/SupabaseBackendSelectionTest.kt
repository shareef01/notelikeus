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
    fun requireConfiguredSupabaseUrlAcceptsHostedHttps() {
        requireConfiguredSupabaseUrl("https://abcd.supabase.co")
    }
}
