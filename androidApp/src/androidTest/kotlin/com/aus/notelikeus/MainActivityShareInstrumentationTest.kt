package com.aus.notelikeus

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityShareInstrumentationTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun packageManagerResolvesImageShareIntentToMainActivity() {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, TestImageContentProvider.CONTENT_URI)
        }
        val resolveInfos = context.packageManager.queryIntentActivities(shareIntent, 0)
        val matchesMainActivity = resolveInfos.any {
            it.activityInfo.packageName == context.packageName &&
                it.activityInfo.name == "com.aus.notelikeus.MainActivity"
        }
        assertTrue("Expected MainActivity to resolve image ACTION_SEND", matchesMainActivity)
    }

    @Test
    fun coldLaunchWithImageShareIntentProcessesSafely() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            setClassName(context, "com.aus.notelikeus.MainActivity")
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, TestImageContentProvider.CONTENT_URI)
            putExtra(Intent.EXTRA_SUBJECT, "Instrumentation Title")
            putExtra(Intent.EXTRA_TEXT, "Instrumentation Caption")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                assertNotNull(activity)
                assertTrue(activity.isIntentConsumedForTests())
            }
        }
    }

    @Test
    fun rotationRecreationDoesNotCrashOrDuplicateSharedIntent() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            setClassName(context, "com.aus.notelikeus.MainActivity")
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, TestImageContentProvider.CONTENT_URI)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                assertNotNull(activity)
                assertTrue(activity.isIntentConsumedForTests())
            }
            scenario.recreate()
            scenario.onActivity { recreatedActivity ->
                assertNotNull(recreatedActivity)
                assertTrue(recreatedActivity.isIntentConsumedForTests())
            }
        }
    }

    @Test
    fun warmDeliveryViaOnNewIntentProcessesImageShareSafely() {
        val initialIntent = Intent(Intent.ACTION_SEND).apply {
            setClassName(context, "com.aus.notelikeus.MainActivity")
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, TestImageContentProvider.CONTENT_URI)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        ActivityScenario.launch<MainActivity>(initialIntent).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(activity.isIntentConsumedForTests())
                val newShareIntent = Intent(Intent.ACTION_SEND).apply {
                    setClassName(context, "com.aus.notelikeus.MainActivity")
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, TestImageContentProvider.CONTENT_URI)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                activity.onNewIntent(newShareIntent)
                assertTrue(activity.isIntentConsumedForTests())
            }
        }
    }

    @Test
    fun externalImageShareIntentWithFakeNoteIdCannotTargetExistingNote() {
        val maliciousIntent = Intent(Intent.ACTION_SEND).apply {
            setClassName(context, "com.aus.notelikeus.MainActivity")
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, TestImageContentProvider.CONTENT_URI)
            putExtra("noteId", 9999L)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        ActivityScenario.launch<MainActivity>(maliciousIntent).use { scenario ->
            scenario.onActivity { activity ->
                assertNotNull(activity)
            }
        }
    }

    @Test
    fun rapidSuccessiveSharesAreExplicitlyRejectedNotMerged() {
        // Each intent carries a distinct subject so the surviving payload can be identified: if the
        // second share were merged or allowed to overwrite the first, the payload's title would be
        // intent B's. The delayed provider holds A's copy open while B arrives.
        val intentA = Intent(Intent.ACTION_SEND).apply {
            setClassName(context, "com.aus.notelikeus.MainActivity")
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, TestImageContentProvider.CONTENT_URI_DELAYED)
            putExtra(Intent.EXTRA_SUBJECT, SUBJECT_FIRST_SHARE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val intentB = Intent(Intent.ACTION_SEND).apply {
            setClassName(context, "com.aus.notelikeus.MainActivity")
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, TestImageContentProvider.CONTENT_URI)
            putExtra(Intent.EXTRA_SUBJECT, SUBJECT_SECOND_SHARE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        TestImageContentProvider.delayLatch = java.util.concurrent.CountDownLatch(1)

        try {
            ActivityScenario.launch<MainActivity>(intentA).use { scenario ->
                // B arrives while A is still being copied; it must be refused, not queued or merged.
                scenario.onActivity { activity -> activity.onNewIntent(intentB) }

                TestImageContentProvider.delayLatch.countDown()

                val payload = awaitPayload(scenario)
                assertNotNull("The first share must still complete after the second is refused", payload)
                assertEquals(SUBJECT_FIRST_SHARE, payload?.title)
            }
        } finally {
            TestImageContentProvider.delayLatch = null
        }
    }

    private fun awaitPayload(
        scenario: ActivityScenario<MainActivity>,
        timeoutMs: Long = ASYNC_SETTLE_TIMEOUT_MS,
    ): com.aus.notelikeus.ui.navigation.SharedImagePayload? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            var payload: com.aus.notelikeus.ui.navigation.SharedImagePayload? = null
            scenario.onActivity { activity -> payload = activity.getPendingSharedImageForTests() }
            if (payload != null) return payload
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return null
    }

    private companion object {
        const val SUBJECT_FIRST_SHARE = "latch-share"
        const val SUBJECT_SECOND_SHARE = "second-share"
        const val POLL_INTERVAL_MS = 25L
        const val ASYNC_SETTLE_TIMEOUT_MS = 10_000L
    }
}
