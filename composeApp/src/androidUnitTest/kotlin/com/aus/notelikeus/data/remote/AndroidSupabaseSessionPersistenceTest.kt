package com.aus.notelikeus.data.remote

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import com.aus.notelikeus.data.local.PassphraseFileCodec
import com.aus.notelikeus.data.local.PassphraseKeyStore

@RunWith(RobolectricTestRunner::class)
class AndroidSupabaseSessionPersistenceTest {

    private lateinit var context: Context
    private val keyStore = SoftwareKeyStore()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        File(context.filesDir, AndroidSupabaseSessionPersistence.SESSION_FILE).delete()
        File(context.filesDir, "${AndroidSupabaseSessionPersistence.SESSION_FILE}.tmp").delete()
        context.deleteSharedPreferences(AndroidSupabaseSessionPersistence.PREFS_NAME)
        context.deleteSharedPreferences(AndroidSupabaseSessionPersistence.PREFS_NAME_FALLBACK)
    }

    @Test
    fun saveThenLoadRoundTrips() {
        val persistence = AndroidSupabaseSessionPersistence(context, keyStore)
        persistence.save(SAMPLE)

        val loaded = persistence.load()

        assertEquals(SAMPLE, loaded)
        assertTrue(File(context.filesDir, AndroidSupabaseSessionPersistence.SESSION_FILE).exists())
    }

    @Test
    fun aSecondSaveReplacesTheFile() {
        val persistence = AndroidSupabaseSessionPersistence(context, keyStore)
        persistence.save(SAMPLE)
        val updated = SAMPLE.copy(accessToken = "access-2", expiresAtEpochMs = 99L)
        persistence.save(updated)

        assertEquals(updated, persistence.load())
    }

    @Test
    fun clearRemovesTheFile() {
        val persistence = AndroidSupabaseSessionPersistence(context, keyStore)
        persistence.save(SAMPLE)
        persistence.clear()

        assertNull(persistence.load())
        assertFalse(File(context.filesDir, AndroidSupabaseSessionPersistence.SESSION_FILE).exists())
    }

    @Test
    fun anUndecryptableFileIsTreatedAsSignedOut() {
        val otherKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        File(context.filesDir, AndroidSupabaseSessionPersistence.SESSION_FILE).writeBytes(
            PassphraseFileCodec.encrypt(otherKey, """{"accessToken":"x","userId":"y"}"""),
        )

        assertNull(AndroidSupabaseSessionPersistence(context, keyStore).load())
        assertTrue(
            "corrupt file must be kept, not wiped",
            File(context.filesDir, AndroidSupabaseSessionPersistence.SESSION_FILE).exists(),
        )
    }

    @Test
    fun aPlaintextFallbackSessionIsMigratedIntoTheKeystoreFile() {
        context.getSharedPreferences(
            AndroidSupabaseSessionPersistence.PREFS_NAME_FALLBACK,
            Context.MODE_PRIVATE,
        ).edit()
            .putString(AndroidSupabaseSessionPersistence.KEY_ACCESS, SAMPLE.accessToken)
            .putString(AndroidSupabaseSessionPersistence.KEY_REFRESH, SAMPLE.refreshToken)
            .putString(AndroidSupabaseSessionPersistence.KEY_USER_ID, SAMPLE.userId)
            .putString(AndroidSupabaseSessionPersistence.KEY_EMAIL, SAMPLE.email)
            .putLong(AndroidSupabaseSessionPersistence.KEY_EXPIRES, SAMPLE.expiresAtEpochMs)
            .commit()

        val persistence = AndroidSupabaseSessionPersistence(context, keyStore)
        val loaded = persistence.load()

        assertEquals(SAMPLE, loaded)
        assertTrue(File(context.filesDir, AndroidSupabaseSessionPersistence.SESSION_FILE).exists())
        val leftover = context.getSharedPreferences(
            AndroidSupabaseSessionPersistence.PREFS_NAME_FALLBACK,
            Context.MODE_PRIVATE,
        )
        assertTrue(leftover.all.isEmpty())
    }

    private class SoftwareKeyStore : PassphraseKeyStore {
        private val key: SecretKey =
            KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

        override fun getOrCreateKey(): SecretKey = key
        override fun deleteKey(): Boolean = true
    }

    private companion object {
        val SAMPLE = SupabaseAuthSession(
            accessToken = "access",
            refreshToken = "refresh",
            userId = "user-1",
            email = "a@b.test",
            expiresAtEpochMs = 1_700_000_000_000L,
        )
    }
}
