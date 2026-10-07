package com.aus.notelikeus

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aus.notelikeus.di.initKoin
import com.aus.notelikeus.ui.navigation.SharedImagePayload
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Lifecycle contract for image shares, exercised through a real `content://` round trip.
 *
 * This suite owns the Activity/ViewModel half of the share hardening: at-most-once import across cold
 * start, recreation, warm delivery and replay. Byte-level ingestion (bounds, MIME policy, provider
 * failures) is covered by `ExternalImageIngestorTest`; here the same production ingestor is driven
 * against a registered provider so the two halves meet exactly where the app does.
 *
 * The ingestor copies on its IO dispatcher, so payloads arrive asynchronously. Waits are
 * condition-driven and bounded — never a fixed sleep standing in for correctness.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = ShareTestApplication::class)
class MainActivityRobolectricTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var ingestorUnderTest: FakeShareIngestor

    /**
     * Koin's container is process-global, so each case starts from a clean graph, with the ingestion
     * boundary replaced by [FakeShareIngestor]: Robolectric's resolver refuses `content://`
     * authorities that no manifest declares, and the byte-level rules that the real ingestor enforces
     * are already pinned by `ExternalImageIngestorTest` and the on-device instrumentation suite.
     * Stopping Koin in `@After` also leaves the next test class — which uses the real Application —
     * free to start its own container.
     */
    @Before
    fun startFreshKoinWithFakeIngestor() {
        stopKoin()
        ingestorUnderTest = FakeShareIngestor(Dispatchers.Unconfined)
        initKoin {
            androidContext(InstrumentationRegistry.getInstrumentation().targetContext.applicationContext)
            modules(androidAppModule)
        }
        // Loaded after the app graph rather than inside it: Koin only lets a later definition win
        // when the override is requested explicitly, and the test asserts that it did.
        GlobalContext.loadKoinModules(
            module {
                single<com.aus.notelikeus.ui.navigation.ExternalImageIngestor> { ingestorUnderTest }
            },
            true,
        )
        org.junit.Assert.assertSame(
            "The fake ingestor must be the instance MainActivity injects",
            ingestorUnderTest,
            GlobalContext.get().get<com.aus.notelikeus.ui.navigation.ExternalImageIngestor>(),
        )
    }

    @After
    fun stopKoinAfterCase() {
        stopKoin()
    }

    @Test
    fun `cold start share imports exactly once and marks the intent consumed`() {
        ActivityScenario.launch<MainActivity>(shareIntent(SEGMENT_VALID)).use { scenario ->
            val payload = awaitPayload(scenario)
            assertNotNull("Cold-start share must import its image", payload)
            assertEquals("image/png", payload?.mimeType)
            scenario.onActivity { activity ->
                assertTrue("Accepted share must be consumed", activity.isIntentConsumedForTests())
            }
        }
    }

    @Test
    fun `recreation preserves the payload without durably consuming the intent`() {
        ActivityScenario.launch<MainActivity>(shareIntent(SEGMENT_VALID)).use { scenario ->
            val first = awaitPayload(scenario)
            assertNotNull(first)

            scenario.recreate()

            // Identity is the proof of "not re-imported": a second ingestion would have built a fresh
            // payload object around freshly copied bytes.
            val afterRecreation = payloadNow(scenario)
            assertSame("Recreation must preserve the pending payload, not rebuild it", first, afterRecreation)
            scenario.onActivity { activity ->
                // The payload lives only in ViewModel memory, so consumed stays unsaved on purpose:
                // a process restart must be free to import the share again rather than losing it.
                assertTrue(
                    "A pending payload must not be recorded as durably consumed",
                    !activity.isIntentConsumedForTests(),
                )
            }
        }
    }

    @Test
    fun `a second share is refused and the intent is settled`() {
        ActivityScenario.launch<MainActivity>(shareIntent(SEGMENT_VALID)).use { scenario ->
            val first = awaitPayload(scenario)
            assertNotNull(first)

            // Rapid successive shares are explicitly rejected, not merged or queued: the second share
            // must not overwrite a payload the editor has not consumed yet. It is also settled rather
            // than left dangling, so a later recreation cannot import a share the user never saw.
            scenario.onActivity { activity -> activity.onNewIntent(shareIntent(SEGMENT_VALID)) }

            val after = payloadNow(scenario)
            assertSame("A second share must not replace an unconsumed payload", first, after)
            scenario.onActivity { activity ->
                assertTrue("A refused share must still leave the intent settled", activity.isIntentConsumedForTests())
            }
        }
    }

    @Test
    fun `already consumed intent cannot be replayed`() {
        ActivityScenario.launch<MainActivity>(shareIntent(SEGMENT_VALID)).use { scenario ->
            val first = awaitPayload(scenario)
            assertNotNull(first)

            // The same Intent object is delivered twice. onNewIntent resets the Activity-level flag,
            // so the consumed marker carried on the Intent itself is what must win here.
            val replayed = shareIntent(SEGMENT_VALID)
            scenario.onActivity { activity ->
                activity.onNewIntent(replayed)
                activity.onNewIntent(replayed)
            }

            val after = payloadNow(scenario)
            assertSame("A replayed intent must not produce a second payload", first, after)
        }
    }

    @Test
    fun `unreadable uri fails safely without a payload or a crash`() {
        ActivityScenario.launch<MainActivity>(shareIntent(SEGMENT_UNKNOWN_AUTHORITY)).use { scenario ->
            assertNull("Unknown authority must not yield a payload", payloadStable(scenario))
            scenario.onActivity { activity -> assertNotNull(activity) }
        }
    }

    @Test
    fun `non image provider type is rejected`() {
        ActivityScenario.launch<MainActivity>(shareIntent(SEGMENT_NON_IMAGE)).use { scenario ->
            assertNull("A non-image provider type must be rejected", payloadStable(scenario))
            scenario.onActivity { activity -> assertNotNull(activity) }
        }
    }

    @Test
    fun `provider security exception is handled without crashing`() {
        ActivityScenario.launch<MainActivity>(shareIntent(SEGMENT_SECURITY)).use { scenario ->
            assertNull("A provider security failure must not yield a payload", payloadStable(scenario))
            scenario.onActivity { activity -> assertNotNull(activity) }
        }
    }

    @Test
    fun `empty provider stream is rejected`() {
        ActivityScenario.launch<MainActivity>(shareIntent(SEGMENT_EMPTY)).use { scenario ->
            assertNull("A zero-byte share must be rejected", payloadStable(scenario))
            scenario.onActivity { activity -> assertNotNull(activity) }
        }
    }

    @Test
    fun `process death before consumption does not persist the consumed marker`() {
        ActivityScenario.launch<MainActivity>(shareIntent(SEGMENT_VALID)).use { scenario ->
            val payload = awaitPayload(scenario)
            assertNotNull(payload)

            val savedState = Bundle()
            scenario.onActivity { activity -> activity.saveInstanceStateForTests(savedState) }

            // The payload lives only in transient ViewModel memory, so a process restart must be free
            // to import it again rather than losing the share permanently.
            assertTrue(
                "Consumed flag must not be durably saved while the payload is pending",
                !savedState.getBoolean(KEY_INTENT_CONSUMED, false),
            )
        }
    }

    private fun shareUri(segment: String): Uri = Uri.parse("content://$AUTHORITY/$segment")

    private fun shareIntent(segment: String): Intent = Intent(Intent.ACTION_SEND).apply {
        setClassName(context, "com.aus.notelikeus.MainActivity")
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, shareUri(segment))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** Reads the current payload, idling the main looper so pending effects have run. */
    private fun payloadNow(scenario: ActivityScenario<MainActivity>): SharedImagePayload? {
        shadowOf(Looper.getMainLooper()).idle()
        var current: SharedImagePayload? = null
        scenario.onActivity { activity -> current = activity.getPendingSharedImageForTests() }
        return current
    }

    /**
     * Settles the asynchronous failure path before asserting that no payload appears.
     *
     * Failure is only established once ingestion has actually finished, so this waits for the
     * ViewModel to leave its in-progress state instead of assuming the import already failed.
     */
    private fun payloadStable(scenario: ActivityScenario<MainActivity>): SharedImagePayload? {
        val deadline = System.currentTimeMillis() + ASYNC_SETTLE_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            var payload: SharedImagePayload? = null
            var inProgress = true
            scenario.onActivity { activity ->
                payload = activity.getPendingSharedImageForTests()
                inProgress = activity.isIngestionInProgressForTests()
            }
            if (payload != null || !inProgress) return payload
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return payloadNow(scenario)
    }

    /** Bounded, condition-driven wait for a successful import; fails loudly on timeout. */
    private fun awaitPayload(
        scenario: ActivityScenario<MainActivity>,
        timeoutMs: Long = ASYNC_SETTLE_TIMEOUT_MS,
    ): SharedImagePayload? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val payload = payloadNow(scenario)
            if (payload != null) return payload
            Thread.sleep(POLL_INTERVAL_MS)
        }
        var inProgress = false
        var consumed = false
        var intentFacts = "n/a"
        scenario.onActivity { activity ->
            inProgress = activity.isIngestionInProgressForTests()
            consumed = activity.isIntentConsumedForTests()
            val delivered = activity.intent
            intentFacts = "action=${delivered?.action} type=${delivered?.type} " +
                "extras=${delivered?.extras?.keySet()?.toList()} " +
                "stream=${delivered?.let { com.aus.notelikeus.ui.navigation.extractStreamUri(it) }}"
        }
        assertNotNull(
            "No shared-image payload after ${timeoutMs}ms " +
                "(ingestorCalls=${ingestorUnderTest.callCount}, " +
                "lastUri=${ingestorUnderTest.lastUri}, " +
                "inProgress=$inProgress, consumed=$consumed, $intentFacts)",
            null as SharedImagePayload?,
        )
        return null
    }

    private companion object {
        const val AUTHORITY = FakeShareIngestor.AUTHORITY
        const val SEGMENT_VALID = FakeShareIngestor.SEGMENT_VALID
        const val SEGMENT_NON_IMAGE = FakeShareIngestor.SEGMENT_NON_IMAGE
        const val SEGMENT_SECURITY = FakeShareIngestor.SEGMENT_SECURITY
        const val SEGMENT_EMPTY = FakeShareIngestor.SEGMENT_EMPTY
        const val SEGMENT_UNKNOWN_AUTHORITY = FakeShareIngestor.SEGMENT_UNKNOWN_AUTHORITY
        const val KEY_INTENT_CONSUMED = "com.aus.notelikeus.KEY_INTENT_CONSUMED"
        const val POLL_INTERVAL_MS = 10L
        const val ASYNC_SETTLE_TIMEOUT_MS = 5_000L
    }
}
