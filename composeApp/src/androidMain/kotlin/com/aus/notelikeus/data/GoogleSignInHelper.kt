package com.aus.notelikeus.data.remote

import android.app.Activity
import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import com.aus.notelikeus.ui.auth.GoogleSignInHelper
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

/**
 * Google sign-in via Credential Manager.
 *
 * Replaces the legacy `GoogleSignIn`/`GoogleSignInClient` API, which Google has deprecated in favour
 * of Credential Manager. The old flow also had to round-trip through an Intent and
 * `onActivityResult`, which is what pushed platform types (`Any?`) into the shared interface.
 *
 * @param activityProvider supplies the foreground Activity. Credential Manager needs an Activity to
 *   host its bottom sheet, but this helper is a process-scoped singleton, so the Activity has to be
 *   looked up per call rather than injected.
 */
class AndroidGoogleSignInHelper(
    private val context: Context,
    private val webClientId: String,
    private val activityProvider: () -> Activity?
) : GoogleSignInHelper {

    private val credentialManager by lazy { CredentialManager.create(context) }

    /** Credential Manager's Google provider is delivered through Play Services. */
    override fun isAvailable(): Boolean =
        GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS

    override suspend fun requestIdToken(): Result<String> {
        val activity = activityProvider()
            ?: return Result.failure(
                IllegalStateException("No foreground Activity available to host Google sign-in")
            )

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(
                GetGoogleIdOption.Builder()
                    // false so a first-time user gets the account picker instead of an empty
                    // list when nothing has been authorized for this app yet.
                    .setFilterByAuthorizedAccounts(false)
                    .setServerClientId(webClientId)
                    .setAutoSelectEnabled(false)
                    .build()
            )
            .build()

        return try {
            val credential = credentialManager.getCredential(activity, request).credential
            if (credential !is CustomCredential ||
                credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                return Result.failure(
                    IllegalStateException("Unexpected credential type: ${credential.type}")
                )
            }
            val token = GoogleIdTokenCredential.createFrom(credential.data).idToken
            if (token.isBlank()) {
                Result.failure(IllegalStateException("Google sign-in returned no ID token"))
            } else {
                Result.success(token)
            }
        } catch (error: GetCredentialException) {
            // Covers user cancellation and "no credential available" as well as real errors.
            Result.failure(error)
        } catch (error: IllegalArgumentException) {
            // createFrom() rejects a malformed credential bundle.
            Result.failure(error)
        }
    }
}
