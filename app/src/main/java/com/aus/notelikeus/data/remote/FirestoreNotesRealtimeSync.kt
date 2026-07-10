package com.aus.notelikeus.data.remote

import android.util.Log
import com.aus.notelikeus.domain.repository.SettingsRepository
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "FirestoreNotesRealtime"

@Singleton
class FirestoreNotesRealtimeSync @Inject constructor(
    private val firebaseNoteSync: FirebaseNoteSync,
    private val firebaseSessionManager: FirebaseSessionManager,
    private val settingsRepository: SettingsRepository,
    private val firestore: FirebaseFirestore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var registration: ListenerRegistration? = null
    private var activeUserId: String? = null
    private val knownCloudIds = Collections.synchronizedSet(mutableSetOf<String>())
    private var onChanges: (() -> Unit)? = null

    fun start(userId: String, onChanges: () -> Unit) {
        if (activeUserId == userId && registration != null) {
            this.onChanges = onChanges
            return
        }
        stop()
        activeUserId = userId
        this.onChanges = onChanges

        registration = firestore.collection("users")
            .document(userId)
            .collection("notes")
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Realtime listener failed", error)
                    return@addSnapshotListener
                }
                if (snapshot == null) return@addSnapshotListener

                scope.launch {
                    if (!settingsRepository.isCloudAutoSyncEnabled.first()) return@launch
                    if (!firebaseSessionManager.getCurrentAccount().isGoogleAccount) return@launch

                    val changes = firebaseNoteSync.applyRealtimeSnapshot(
                        documents = snapshot.documents,
                        knownCloudIds = knownCloudIds
                    )
                    if (changes > 0) {
                        onChanges?.invoke()
                    }
                }
            }
    }

    fun stop() {
        registration?.remove()
        registration = null
        activeUserId = null
        knownCloudIds.clear()
        onChanges = null
    }
}
