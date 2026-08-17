package com.aus.notelikeus.data.remote

import com.aus.notelikeus.util.AppConfig
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers the account-mapping and error-diagnosis logic that used to have no tests: which
 * providers make an account "cloud sync eligible", the debug-only email/password gate, and the
 * user-facing messages diagnose() produces for the Firebase errors the app actually sees.
 * Network sign-ins are exercised against the real emulator in FirestoreNoteTransportEmulatorTest
 * and the web sync suite; everything here is local logic. Robolectric because the Firebase
 * exception constructors and android.util.Log need real Android framework classes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FirebaseSessionManagerTest {

    private lateinit var auth: FirebaseAuth
    private lateinit var firestore: FirebaseFirestore
    private lateinit var manager: FirebaseSessionManager

    @Before
    fun setup() {
        auth = mockk()
        firestore = mockk(relaxed = true)
        manager = FirebaseSessionManager(auth, firestore)
    }

    @After
    fun tearDown() {
        unmockkObject(AppConfig)
    }

    private fun user(
        uid: String,
        email: String?,
        isAnonymous: Boolean,
        providers: List<String>
    ): FirebaseUser {
        val user = mockk<FirebaseUser>()
        every { user.uid } returns uid
        every { user.email } returns email
        every { user.isAnonymous } returns isAnonymous
        every { user.providerData } returns providers.map { providerId ->
            mockk<com.google.firebase.auth.UserInfo>().also {
                every { it.providerId } returns providerId
            }
        }
        return user
    }

    @Test
    fun `no current user maps to anonymous signed-out account`() {
        every { auth.currentUser } returns null
        val account = manager.getCurrentAccount()
        assertFalse(account.isGoogleAccount)
        assertTrue(account.isAnonymous)
        assertEquals(null, account.userId)
    }

    @Test
    fun `google provider makes account cloud sync eligible`() {
        every { auth.currentUser } returns user(
            uid = "u1",
            email = "a@example.com",
            isAnonymous = false,
            providers = listOf(GoogleAuthProvider.PROVIDER_ID)
        )
        val account = manager.getCurrentAccount()
        assertTrue(account.isGoogleAccount)
        assertFalse(account.isAnonymous)
        assertEquals("u1", account.userId)
    }

    @Test
    fun `email provider is also cloud sync eligible`() {
        every { auth.currentUser } returns user(
            uid = "u2",
            email = "b@example.com",
            isAnonymous = false,
            providers = listOf(EmailAuthProvider.PROVIDER_ID)
        )
        assertTrue(manager.getCurrentAccount().isGoogleAccount)
    }

    @Test
    fun `phone-only user is neither anonymous nor cloud eligible`() {
        every { auth.currentUser } returns user(
            uid = "u3",
            email = null,
            isAnonymous = false,
            providers = listOf("phone")
        )
        val account = manager.getCurrentAccount()
        assertFalse(account.isGoogleAccount)
        // isAnonymous && !hasCloudProvider: a real (non-anonymous) phone user stays false.
        assertFalse(account.isAnonymous)
    }

    @Test
    fun `ensureGoogleSignedIn fails when not signed in`() = runTest {
        every { auth.currentUser } returns null
        val result = manager.ensureGoogleSignedIn()
        assertTrue(result.isFailure)
        assertEquals(
            "Sign in with Google to use cloud sync.",
            manager.diagnose(result.exceptionOrNull()!!)
        )
    }

    @Test
    fun `email sign-in is rejected outside debug builds`() = runTest {
        mockkObject(AppConfig)
        every { AppConfig.isDebug } returns false
        val result = manager.signInWithEmailPassword("a@example.com", "pw", createAccount = true)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun `diagnose explains disabled sign-in providers`() {
        val error = FirebaseAuthException("ERROR_OPERATION_NOT_ALLOWED", "nope")
        assertEquals(
            "Sign-in disabled. Firebase Console → Authentication → enable Google (and Email/Password for test login).",
            manager.diagnose(error)
        )
    }

    @Test
    fun `diagnose explains credential in use`() {
        val error = FirebaseAuthException("ERROR_CREDENTIAL_ALREADY_IN_USE", "nope")
        assertEquals(
            "This Google account is already linked to another user.",
            manager.diagnose(error)
        )
    }

    @Test
    fun `diagnose explains firestore permission denial`() {
        val error = FirebaseFirestoreException(
            "denied",
            FirebaseFirestoreException.Code.PERMISSION_DENIED
        )
        assertEquals(
            "Firestore permission denied. Publish rules from firestore.rules and sign in.",
            manager.diagnose(error)
        )
    }

    @Test
    fun `diagnose explains missing firestore database`() {
        val error = FirebaseFirestoreException(
            "down",
            FirebaseFirestoreException.Code.UNAVAILABLE
        )
        assertEquals(
            "Firestore unavailable. Firebase Console → Firestore Database → Create database.",
            manager.diagnose(error)
        )
    }

    @Test
    fun `diagnose falls back to code and message detail`() {
        val error = FirebaseAuthException("ERROR_INVALID_CREDENTIAL", "bad token")
        assertEquals(
            "Firebase error — ERROR_INVALID_CREDENTIAL: bad token",
            manager.diagnose(error)
        )
    }
}
