package com.aus.notelikeus.data.local

import android.content.Context
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * The key-replacement path, which shipped reasoned-about but unverified.
 *
 * An invalidated AndroidKeyStore key still *exists* — it only fails at `Cipher.init` — so
 * `getOrCreateKey` keeps handing back the same dead alias and every attempt fails identically.
 * Left alone that is permanent: the passphrase falls back to the legacy `EncryptedSharedPreferences`
 * and stays there for good, which is the deprecated store `DatabaseKeyManager` exists to replace.
 *
 * None of that could be tested before, because a real key cannot be invalidated from a test — it
 * takes a lock-screen credential reset or a device-to-device transfer. [PassphraseKeyStore] is the
 * seam that makes it reachable: a key that genuinely fails to encrypt is just an AES key of the
 * wrong length, and `Cipher.init` rejects it exactly the way an invalidated one does.
 */
@RunWith(RobolectricTestRunner::class)
class DatabaseKeyManagerRecoveryTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        File(context.filesDir, DatabaseKeyManager.PASSPHRASE_FILE).delete()
    }

    /**
     * A key that exists and cannot encrypt — the shape of an invalidated Keystore key.
     *
     * 7 bytes is not a legal AES key length, so `Cipher.init` throws `InvalidKeyException`, which is
     * what `encryptUnderKeystoreKey` catches. [deleteKey] swaps in a real 256-bit key, standing in
     * for the fresh alias the real Keystore generates once the dead one is removed.
     */
    private class FakeKeyStore(startBroken: Boolean = true) : PassphraseKeyStore {
        var deleteCount = 0
            private set
        var deleteSucceeds = true
        private var current: SecretKey =
            if (startBroken) SecretKeySpec(ByteArray(7), "AES") else freshKey()

        override fun getOrCreateKey(): SecretKey = current

        override fun deleteKey(): Boolean {
            deleteCount++
            if (!deleteSucceeds) return false
            current = freshKey()
            return true
        }

        companion object {
            fun freshKey(): SecretKey =
                KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        }
    }

    private fun passphraseFile() = File(context.filesDir, DatabaseKeyManager.PASSPHRASE_FILE)

    @Test
    fun `a key that cannot encrypt is replaced, and the passphrase is persisted under the new one`() {
        val keyStore = FakeKeyStore()

        val passphrase = DatabaseKeyManager(context, keyStore).getPassphrase()

        assertEquals("passphrase should be 256 bits", 32, passphrase.size)
        assertEquals("the dead key should be replaced exactly once", 1, keyStore.deleteCount)
        assertTrue("nothing was published", passphraseFile().exists())

        // The real property: it did not merely return a passphrase, it persisted one the
        // replacement key can read back. Without the retry this file would not exist at all and
        // every launch would regenerate a different passphrase — which is how an openable database
        // becomes a quarantined one.
        val readBack = DatabaseKeyManager(context, keyStore).getPassphrase()
        assertArrayEquals("the persisted passphrase did not survive", passphrase, readBack)
        assertEquals("a healthy key was deleted on the second run", 1, keyStore.deleteCount)
    }

    @Test
    fun `a healthy key is never deleted`() {
        // Deletion must be driven by encryption failing and by nothing else, so the ordinary path
        // must not touch the key at all.
        //
        // Note what this does *not* cover, because it would be easy to read it as more than it is.
        // The bug this fix introduced on its first attempt deleted a healthy key when *publishing*
        // failed — a rename losing a race — and that variant cannot be reached here: publish always
        // succeeds against Robolectric's temp filesDir, so `deleteCount` stays 0 either way and this
        // test passes against the buggy version too. It is a guard against deletion becoming
        // unconditional, not against that specific race. Forcing a rename failure was attempted and
        // abandoned: a directory planted at the target path is moved aside by
        // preserveUnreadablePassphraseFile before publish is ever reached.
        val keyStore = FakeKeyStore(startBroken = false)

        val passphrase = DatabaseKeyManager(context, keyStore).getPassphrase()

        assertEquals(32, passphrase.size)
        assertEquals("a healthy key must never be deleted", 0, keyStore.deleteCount)
        assertTrue(passphraseFile().exists())

        val readBack = DatabaseKeyManager(context, keyStore).getPassphrase()
        assertArrayEquals(passphrase, readBack)
        assertEquals(0, keyStore.deleteCount)
    }

    @Test
    fun `a key that cannot be deleted is not retried into a loop`() {
        // deleteKey failing means the alias is stuck, so a retry would resolve to the same unusable
        // key. The caller still has to hand back a working passphrase for this session rather than
        // throw — the database has to open.
        val keyStore = FakeKeyStore().apply { deleteSucceeds = false }

        val passphrase = DatabaseKeyManager(context, keyStore).getPassphrase()

        assertEquals(32, passphrase.size)
        assertEquals("deletion should be attempted once, not retried", 1, keyStore.deleteCount)
    }
}
