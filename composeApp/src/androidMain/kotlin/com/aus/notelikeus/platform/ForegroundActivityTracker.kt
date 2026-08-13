package com.aus.notelikeus.platform

import android.app.Activity
import java.lang.ref.WeakReference

/**
 * Tracks the foreground Activity so process-scoped singletons can reach it.
 *
 * Needed because Credential Manager requires an Activity to host its sign-in UI, while
 * [com.aus.notelikeus.data.remote.AndroidGoogleSignInHelper] is a Koin singleton created with the
 * application context.
 *
 * The reference is weak and cleared on [unregister] so a destroyed Activity can't be leaked or
 * handed out.
 */
object ForegroundActivityTracker {

    private var current: WeakReference<Activity>? = null

    fun register(activity: Activity) {
        current = WeakReference(activity)
    }

    /** Only clears when [activity] is still the tracked one, so overlapping lifecycles are safe. */
    fun unregister(activity: Activity) {
        if (current?.get() === activity) {
            current = null
        }
    }

    fun current(): Activity? = current?.get()?.takeUnless { it.isFinishing || it.isDestroyed }
}
