package com.aus.notelikeus

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import android.content.Context

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MainActivityRobolectricTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun `recreation after ingestion but before editor consumes payload preserves image exactly once`() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            setClassName(context, "com.aus.notelikeus.MainActivity")
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, Uri.parse("content://fake/uri"))
        }

        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                assertNotNull(activity)
                // Robolectric runs coroutines synchronously, so payload is populated
                assertNotNull(activity.getPendingSharedImageForTests())
            }
            
            // Recreate before NavGraph consumes it
            scenario.recreate()
            
            scenario.onActivity { recreatedActivity ->
                // Consumed flag is true
                assertTrue(recreatedActivity.isIntentConsumedForTests())
                // Payload must survive exactly once
                val payload = recreatedActivity.getPendingSharedImageForTests()
                assertNotNull(payload)
                assertEquals("image/png", payload?.mimeType)
            }
        }
    }

    @Test
    fun `process death before editor consumes payload does not persist consumed marker`() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            setClassName(context, "com.aus.notelikeus.MainActivity")
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, Uri.parse("content://fake/uri"))
        }

        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            var savedState = Bundle()
            scenario.onActivity { activity ->
                assertNotNull(activity.getPendingSharedImageForTests())
                activity.onSaveInstanceState(savedState)
            }
            // process death simulation: consumed flag should not be saved as true if payload is pending
            assertFalse("Expected consumed flag to not be durably saved if payload is pending", savedState.getBoolean("com.aus.notelikeus.KEY_INTENT_CONSUMED", false))
        }
    }
}
