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

/**
 * Production cutover, as a release APK sees it. A device process has no environment, so every
 * value here arrives via BuildConfig — which is exactly why the allow flag must be gated on the
 * build environment rather than on `local.properties`.
 */
class SupabaseCutoverSelectionTest {

    private val hosted = "https://abcdefghijklmnop.supabase.co"

    /** An ordinary release: BuildConfig fields are all empty, so nothing selects Supabase. */
    @Test
    fun ordinaryReleaseStaysOnFirebase() {
        assertFalse(
            isSupabaseRemoteSelected(
                isDebug = false,
                remoteBackendEnv = firstNonBlank(null, ""),
                allowProductionEnv = firstNonBlank(null, ""),
                supabaseUrl = "",
            ),
        )
    }

    /** Backend named but no allow flag — the case a leaked staging local.properties would create. */
    @Test
    fun releaseWithBackendButNoAllowFlagStaysOnFirebase() {
        assertFalse(
            isSupabaseRemoteSelected(
                isDebug = false,
                remoteBackendEnv = firstNonBlank(null, "supabase"),
                allowProductionEnv = firstNonBlank(null, ""),
                supabaseUrl = hosted,
            ),
        )
    }

    /** Allow flag present but no backend named: still Firebase. */
    @Test
    fun releaseWithAllowFlagButNoBackendStaysOnFirebase() {
        assertFalse(
            isSupabaseRemoteSelected(
                isDebug = false,
                remoteBackendEnv = firstNonBlank(null, ""),
                allowProductionEnv = firstNonBlank(null, "true"),
                supabaseUrl = hosted,
            ),
        )
    }

    /** A localhost URL can never be a production target, even in a cutover build. */
    @Test
    fun cutoverAgainstLocalhostIsRefused() {
        assertFalse(
            isSupabaseRemoteSelected(
                isDebug = false,
                remoteBackendEnv = firstNonBlank(null, "supabase"),
                allowProductionEnv = firstNonBlank(null, "true"),
                supabaseUrl = "http://127.0.0.1:54321",
            ),
        )
    }

    /** All three together — a deliberate cutover build — is the only combination that flips. */
    @Test
    fun deliberateCutoverBuildSelectsSupabase() {
        assertTrue(
            isSupabaseRemoteSelected(
                isDebug = false,
                remoteBackendEnv = firstNonBlank(null, "supabase"),
                allowProductionEnv = firstNonBlank(null, "true"),
                supabaseUrl = hosted,
            ),
        )
    }

    /** "1" is accepted alongside "true", matching the web selector's isTruthyFlag. */
    @Test
    fun numericAllowFlagAlsoWorks() {
        assertTrue(
            isSupabaseRemoteSelected(
                isDebug = false,
                remoteBackendEnv = firstNonBlank(null, "supabase"),
                allowProductionEnv = firstNonBlank(null, "1"),
                supabaseUrl = hosted,
            ),
        )
    }
}
