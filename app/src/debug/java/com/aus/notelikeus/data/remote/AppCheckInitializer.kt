package com.aus.notelikeus.data.remote

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory

/**
 * Debug App Check — register the token printed in Logcat under Firebase Console → App Check.
 */
object AppCheckInitializer {
    fun install(context: Context) {
        FirebaseApp.initializeApp(context)
        val appCheck = FirebaseAppCheck.getInstance()
        appCheck.installAppCheckProviderFactory(DebugAppCheckProviderFactory.getInstance())
        appCheck.setTokenAutoRefreshEnabled(true)
    }
}
