package com.aus.notelikeus.data.remote

import android.content.Context
import android.content.Intent
import com.aus.notelikeus.R
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GoogleSignInHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val googleSignInClient: GoogleSignInClient by lazy {
        val webClientId = context.getString(R.string.default_web_client_id)
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(webClientId)
            .requestEmail()
            .build()
        GoogleSignIn.getClient(context, options)
    }

    /** True if Google Play Services is present and usable; false means sign-in can't work at all. */
    fun isPlayServicesAvailable(): Boolean {
        val availability = GoogleApiAvailability.getInstance()
        return availability.isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    }

    fun getSignInIntent(): Intent = googleSignInClient.signInIntent

    fun parseIdToken(data: Intent?): Result<String> {
        return try {
            val account = GoogleSignIn.getSignedInAccountFromIntent(data)
                .getResult(ApiException::class.java)
            val token = account.idToken
            if (token.isNullOrBlank()) {
                Result.failure(IllegalStateException("Google sign-in returned no ID token"))
            } else {
                Result.success(token)
            }
        } catch (error: ApiException) {
            Result.failure(error)
        }
    }

    suspend fun signOutFromGoogle() {
        googleSignInClient.signOut().await()
    }

    fun diagnose(error: Throwable): String {
        if (error is ApiException) {
            return when (error.statusCode) {
                CommonStatusCodes.CANCELED,
                12501 -> "Google sign-in was canceled."
                CommonStatusCodes.DEVELOPER_ERROR,
                10 -> {
                    "Google sign-in configuration error. Add this device's SHA-1 in " +
                        "Firebase Console → Project settings → Your apps → Add fingerprint, " +
                        "then re-download google-services.json. Run: ./gradlew :app:signingReport"
                }
                CommonStatusCodes.NETWORK_ERROR -> "Network error during Google sign-in."
                else -> "Google sign-in failed (code ${error.statusCode})."
            }
        }
        return error.message?.takeIf { it.isNotBlank() }
            ?: "Google sign-in failed."
    }
}
