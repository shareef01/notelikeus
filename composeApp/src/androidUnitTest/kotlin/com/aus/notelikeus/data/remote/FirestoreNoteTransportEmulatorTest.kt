package com.aus.notelikeus.data.remote

import com.aus.notelikeus.domain.model.Note
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Exercises [FirestoreNoteTransport] against a real Firestore server (the local emulator).
 *
 * This covers the one thing unit tests over fakes cannot: the wire behaviour of the batched
 * `serverUpdatedAt` readback. That readback issues `whereIn(FieldPath.documentId(), …)` queries in
 * chunks of 30 — a Firestore-imposed limit — and the failure it guards against is a commit whose
 * timestamps come back partial, which downstream reads as "these notes are locally newer" and
 * re-uploads them on every sync forever.
 *
 * **Skipped unless the emulator is running**, via [assumeTrue], so a developer without one is not
 * blocked. Firebase CI runs them for real — see the "Android Firestore transport" step, which is
 * what stops a skip from quietly hiding a failure. They did exactly that until then: written,
 * never actually executed, and failing against the real rules the whole time.
 *
 * Note the config. This client has no auth token, so the production rules reject every write
 * (`isOwner` requires `request.auth`); the emulator must therefore run *without* rules, which is
 * what firebase.transport-test.json is for. That is not a gap — this test is about the transport's
 * wire behaviour, and the rules themselves are covered by tests/firestore.rules.test.mjs and by
 * web's notesSync.emulator.test.ts, both against the real firestore.rules with an authed context.
 *
 * ```
 * firebase emulators:exec --only firestore --config firebase.transport-test.json \
 *   "./gradlew :composeApp:testDebugUnitTest --tests '*FirestoreNoteTransportEmulatorTest*'"
 * ```
 *
 * The FirebaseApp below is built from hardcoded dummy options with a project id that does not
 * exist, deliberately: even if the emulator wiring were wrong, there is no credential and no real
 * project for these writes to reach. Nothing here can touch the production `notelikeus` data.
 */
@RunWith(RobolectricTestRunner::class)
class FirestoreNoteTransportEmulatorTest {

    private lateinit var firestore: FirebaseFirestore
    private lateinit var transport: FirestoreNoteTransport
    private lateinit var app: FirebaseApp

    private fun emulatorReachable(): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(EMULATOR_HOST, EMULATOR_PORT), 750) }
        true
    } catch (_: Exception) {
        false
    }

    @Before
    fun setUp() {
        assumeTrue("Firestore emulator not running on $EMULATOR_HOST:$EMULATOR_PORT", emulatorReachable())

        val context = RuntimeEnvironment.getApplication()
        val options = FirebaseOptions.Builder()
            .setProjectId(FAKE_PROJECT)
            .setApplicationId("1:000000000000:android:c4test")
            .setApiKey("not-a-real-key")
            .build()
        app = runCatching { FirebaseApp.getInstance(APP_NAME) }
            .getOrElse { FirebaseApp.initializeApp(context, options, APP_NAME) }

        firestore = FirebaseFirestore.getInstance(app).apply {
            useEmulator(EMULATOR_HOST, EMULATOR_PORT)
            firestoreSettings = FirebaseFirestoreSettings.Builder()
                .setPersistenceEnabled(false)
                .build()
        }
        transport = FirestoreNoteTransport(firestore)
    }

    @After
    fun tearDown() {
        if (this::firestore.isInitialized) {
            runCatching { runBlocking { firestore.terminate() } }
        }
    }

    private fun note(id: Long) = Note(
        id = id,
        title = "Note $id",
        content = "Body $id",
        timestamp = 1_700_000_000_000L + id,
        color = 0
    )

    /**
     * The boundary that matters: 70 notes is three `whereIn` chunks (30 + 30 + 10), so an
     * off-by-one in the chunk size or a dropped chunk shows up as missing timestamps rather than
     * passing by luck on a single-chunk batch.
     */
    @Test
    fun `putNotes returns a server timestamp for every note across whereIn chunks`() {
        val uid = "uid-${System.nanoTime()}"
        val notes = (1L..70L).map(::note)

        val resolved = runBlocking { transport.putNotes(uid, notes) }

        assertEquals("one entry per note written", 70, resolved.size)
        for (noteId in 1L..70L) {
            assertTrue("note $noteId missing from the result map", resolved.containsKey(noteId))
            assertNotNull("note $noteId came back without a server timestamp", resolved[noteId])
        }
        // Server-assigned, so it must look like a real clock rather than a client default.
        val timestamps = resolved.values.filterNotNull()
        assertTrue("timestamps should be plausible epoch millis", timestamps.all { it > 1_600_000_000_000L })
    }

    /** The mapping must be per-note, not one timestamp smeared across the batch. */
    @Test
    fun `each note maps to the timestamp of its own document`() {
        val uid = "uid-${System.nanoTime()}"
        val resolved = runBlocking { transport.putNotes(uid, (1L..35L).map(::note)) }

        val fetched = runBlocking { transport.fetchNotes(uid) }.associateBy { it.noteId }
        assertEquals(35, fetched.size)
        for ((noteId, timestamp) in resolved) {
            assertEquals(
                "readback timestamp disagrees with the stored document for note $noteId",
                fetched.getValue(noteId).serverUpdatedAt,
                timestamp
            )
        }
    }

    /** A round trip through the transport must preserve the note payload, not just its timestamp. */
    @Test
    fun `notes survive a put and fetch round trip`() {
        val uid = "uid-${System.nanoTime()}"
        runBlocking { transport.putNotes(uid, listOf(note(7L))) }

        val fetched = runBlocking { transport.fetchNotes(uid) }
        assertEquals(1, fetched.size)
        val record = fetched.single()
        assertEquals(7L, record.noteId)
        assertEquals("Note 7", record.title)
        assertEquals("Body 7", record.content)
        assertEquals(1_700_000_000_007L, record.timestamp)
    }

    private companion object {
        const val EMULATOR_HOST = "127.0.0.1"
        const val EMULATOR_PORT = 8080

        /** No such project exists; these writes have nowhere real to land. */
        const val FAKE_PROJECT = "notelikeus-c4-test"
        const val APP_NAME = "c4-emulator-test"
    }
}
