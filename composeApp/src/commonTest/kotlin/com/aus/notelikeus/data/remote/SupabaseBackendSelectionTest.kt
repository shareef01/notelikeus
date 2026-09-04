package com.aus.notelikeus.data.remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SupabaseBackendSelectionTest {

    @Test
    fun debugAllowsLocalhostSupabase() {
        assertTrue(
            isSupabaseRemoteSelected(
                isDebug = true,
                remoteBackendEnv = "supabase",
                allowProductionEnv = null,
                supabaseUrl = "http://127.0.0.1:54321",
            ),
        )
    }

    @Test
    fun firebaseRemainsDefault() {
        assertFalse(
            isSupabaseRemoteSelected(
                isDebug = true,
                remoteBackendEnv = null,
                allowProductionEnv = null,
                supabaseUrl = "https://abcd.supabase.co",
            ),
        )
    }

    @Test
    fun releaseRequiresAllowFlagAndHostedUrl() {
        assertFalse(
            isSupabaseRemoteSelected(
                isDebug = false,
                remoteBackendEnv = "supabase",
                allowProductionEnv = null,
                supabaseUrl = "https://abcd.supabase.co",
            ),
        )
        assertFalse(
            isSupabaseRemoteSelected(
                isDebug = false,
                remoteBackendEnv = "supabase",
                allowProductionEnv = "true",
                supabaseUrl = "http://127.0.0.1:54321",
            ),
        )
        assertTrue(
            isSupabaseRemoteSelected(
                isDebug = false,
                remoteBackendEnv = "supabase",
                allowProductionEnv = "true",
                supabaseUrl = "https://abcd.supabase.co",
            ),
        )
    }

    @Test
    fun firstNonBlankSkipsEmptyBuildConfigAndPrefersRuntimeEnv() {
        assertNull(firstNonBlank(null, "", "  "))
        assertEquals("supabase", firstNonBlank("", "  ", "supabase"))
        assertEquals("from-env", firstNonBlank("from-env", "from-buildconfig"))
    }
}
