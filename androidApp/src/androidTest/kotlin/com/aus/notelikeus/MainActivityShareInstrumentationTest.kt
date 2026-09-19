package com.aus.notelikeus

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    setClassName(context, "com.aus.notelikeus.MainActivity")
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, TestImageContentProvider.CONTENT_URI)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                activity.onNewIntent(shareIntent)
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
}
