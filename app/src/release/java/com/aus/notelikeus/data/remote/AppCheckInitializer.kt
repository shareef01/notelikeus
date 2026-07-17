package com.aus.notelikeus.data.remote

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory

/**
 * Release App Check via Play Integrity — register the app SHA-256 in Firebase Console → App Check.
 */
object AppCheckInitializer {
    fun install(context: Context) {
        FirebaseApp.initializeApp(context)
        val appCheck = FirebaseAppCheck.getInstance()
        appCheck.installAppCheckProviderFactory(
            PlayIntegrityAppCheckProviderFactory.getInstance()
        )
        appCheck.setTokenAutoRefreshEnabled(true)
    }
}
