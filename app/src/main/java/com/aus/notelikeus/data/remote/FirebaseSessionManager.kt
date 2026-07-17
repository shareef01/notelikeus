package com.aus.notelikeus.data.remote

import android.util.Log
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "FirebaseSession"

data class FirebaseAccount(
    val userId: String?,
    val email: String?,
    /** True for Google or email/password (test) accounts — i.e. cloud sync eligible. */
    val isGoogleAccount: Boolean,
    val isAnonymous: Boolean
)

@Singleton
class FirebaseSessionManager @Inject constructor(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore
) {
    fun getCurrentAccount(): FirebaseAccount = auth.currentUser.toAccount()

    suspend fun signInWithGoogle(idToken: String): Result<Unit> {
        return try {
            val credential = GoogleAuthProvider.getCredential(idToken, null)
            auth.signInWithCredential(credential).await()
            Result.success(Unit)
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    suspend fun signInWithEmailPassword(
        email: String,
        password: String,
        createAccount: Boolean
    ): Result<Unit> {
        return try {
            if (createAccount) {
                auth.createUserWithEmailAndPassword(email.trim(), password).await()
            } else {
                auth.signInWithEmailAndPassword(email.trim(), password).await()
            }
            Result.success(Unit)
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    suspend fun signOut(): Result<Unit> {
        return try {
            auth.signOut()
            Result.success(Unit)
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    suspend fun ensureGoogleSignedIn(): Result<String> {
        val account = getCurrentAccount()
        if (!account.isGoogleAccount) {
            return Result.failure(IllegalStateException("Google sign-in required"))
        }
        return Result.success(account.userId ?: error("Sign-in returned no user"))
    }

    suspend fun verifyConnection(): Result<Unit> {
        return try {
            val uid = ensureGoogleSignedIn().getOrThrow()
            firestore.collection("users")
                .document(uid)
                .collection("_meta")
                .document("connection")
                .set(
                    mapOf(
                        "connectedAt" to System.currentTimeMillis(),
                        "platform" to "android"
                    ),
                    SetOptions.merge()
                )
                .await()
            Result.success(Unit)
        } catch (error: Throwable) {
            Log.e(TAG, diagnose(error), error)
            Result.failure(error)
        }
    }

    fun diagnose(error: Throwable): String {
        val code = when (error) {
            is FirebaseAuthException -> error.errorCode
            is FirebaseFirestoreException -> error.code.name
            else -> null
        }
        val detail = listOfNotNull(code, error.message?.takeIf { it.isNotBlank() })
            .joinToString(": ")
        return when {
            error is IllegalStateException && error.message == "Google sign-in required" ->
                "Sign in with Google to use cloud sync."
            error is FirebaseAuthException && (
                error.errorCode == "ERROR_OPERATION_NOT_ALLOWED" ||
                    detail.contains("ADMIN_ONLY_OPERATION", ignoreCase = true)
                ) -> {
                "Sign-in disabled. Firebase Console → Authentication → enable Google (and Email/Password for test login)."
            }
            error is FirebaseAuthException && error.errorCode == "ERROR_CREDENTIAL_ALREADY_IN_USE" ->
                "This Google account is already linked to another user."
            error is FirebaseFirestoreException && error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED ->
                "Firestore permission denied. Publish rules from firestore.rules and sign in."
            error is FirebaseFirestoreException && error.code == FirebaseFirestoreException.Code.UNAVAILABLE ->
                "Firestore unavailable. Firebase Console → Firestore Database → Create database."
            detail.isNotBlank() -> "Firebase error — $detail"
            else -> "Firebase error — ${error.javaClass.simpleName}"
        }
    }

    private fun FirebaseUser?.toAccount(): FirebaseAccount {
        if (this == null) {
            return FirebaseAccount(
                userId = null,
                email = null,
                isGoogleAccount = false,
                isAnonymous = true
            )
        }
        val hasCloudProvider = providerData.any { provider ->
            provider.providerId == GoogleAuthProvider.PROVIDER_ID ||
                provider.providerId == EmailAuthProvider.PROVIDER_ID
        }
        return FirebaseAccount(
            userId = uid,
            email = email,
            isGoogleAccount = hasCloudProvider,
            isAnonymous = isAnonymous && !hasCloudProvider
        )
    }
}
