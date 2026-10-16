package com.aus.notelikeus.data.remote

import android.app.Application
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Android's Turnstile path (D30): the provider opens [CaptchaActivity] and waits for its result. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AndroidCaptchaTokenProviderTest {

    private val app: Application = RuntimeEnvironment.getApplication()
    private val page = "https://notes.example/captcha.html"

    @Test
    fun noPageUrlMeansNoCaptchaAndNoScreen() = runTest {
        assertNull(AndroidCaptchaTokenProvider(app, "").obtainToken())
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test
    fun itOpensThePageInAppModeAndClosingTheScreenCancelsTheCheck() = runTest {
        val token = async { runCatching { AndroidCaptchaTokenProvider(app, page).obtainToken() } }
        runCurrent()

        val intent = shadowOf(app).nextStartedActivity
        assertEquals(CaptchaActivity::class.java.name, intent.component?.className)
        val url = intent.getStringExtra(CaptchaActivity.EXTRA_URL)!!
        val state = CaptchaActivity.pending!!.state
        assertEquals("$page?return=app&state=$state", url)

        // The user backs out of the check.
        Robolectric.buildActivity(CaptchaActivity::class.java, intent).setup().apply {
            get().finish()
            destroy()
        }
        runCurrent()

        val failure = token.await().exceptionOrNull()
        assertTrue(failure is CaptchaUnavailableException)
        assertEquals(CAPTCHA_CANCELLED_MESSAGE, failure?.message)
        assertNull(CaptchaActivity.pending)
    }

    @Test
    fun aScreenOpenedWithoutAPendingCheckClosesItself() {
        CaptchaActivity.pending = null
        val activity = Robolectric.buildActivity(CaptchaActivity::class.java).setup().get()
        assertTrue(activity.isFinishing)
    }
}
