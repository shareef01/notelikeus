package com.aus.notelikeus

import android.os.Build
import android.view.KeyEvent
import android.window.OnBackInvokedDispatcher
import androidx.activity.OnBackPressedCallback
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The predictive-back opt-in, which nothing checked.
 *
 * `AndroidManifest.xml` sets `android:enableOnBackInvokedCallback="true"`. On API 33+ that changes
 * how a back press reaches the app: instead of the legacy key-event path, the system drives
 * `OnBackInvokedDispatcher`, and androidx Activity bridges that to `OnBackPressedDispatcher` — the
 * thing `BackHandler` is built on.
 *
 * **Why it matters here specifically.** The app has exactly one back handler:
 * `EditorScreen.kt` does `PlatformBackHandler { saveThenLeave() }`. Back out of the editor *is*
 * the save. If a back press ever stopped reaching the dispatcher on the opted-in path, the symptom
 * would not be a crash or a stuck screen — it would be edits silently not persisting, which is the
 * worst way for this to fail and the least likely to be reported clearly.
 *
 * Nothing could catch that before: the flag has no effect below API 33, no effect on the JVM, and
 * no effect on anything a unit test can observe. It is the same shape as the two packaged-runtime
 * crashes already on record — a declaration whose consequences only exist at runtime.
 *
 * The test registers **its own** callback rather than driving the editor, so it does not depend on
 * what the app happens to be showing (the sign-in gate on a fresh install, for instance). What is
 * being verified is the delivery path, and that is shared.
 */
/*
 * `@SdkSuppress` rather than `@RequiresApi` plus a manual assumption: below API 33 the flag does
 * nothing and a back press takes the legacy route, so both tests would hold whether or not
 * predictive back worked. CI runs this on API 30 *and* 36, and the runner filters them out on 30
 * rather than letting them claim a pass they did not earn.
 *
 * Lint says this directly -- `UseSdkSuppress`: "Don't use @RequiresApi from tests" -- and it was
 * right; the first version of this file used the annotation meant for production code.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class PredictiveBackTest {

    /**
     * The discriminating half: a callback registered on `OnBackInvokedDispatcher` **only** receives
     * anything when the app has opted in. Without `enableOnBackInvokedCallback` the system keeps
     * using the legacy key-event path and this never fires — so deleting the manifest attribute
     * fails this test, which a check on `OnBackPressedDispatcher` alone would not do.
     */
    @Test
    fun the_manifest_opt_in_is_live_and_the_system_drives_OnBackInvokedDispatcher() {
        val invoked = CountDownLatch(1)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.onBackInvokedDispatcher.registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                ) { invoked.countDown() }
            }

            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)

            assertTrue(
                "the system did not drive OnBackInvokedDispatcher, so the manifest opt-in is not " +
                    "in effect for the running app",
                invoked.await(5, TimeUnit.SECONDS),
            )
        }
    }

    @Test
    fun a_system_back_press_reaches_the_OnBackPressedDispatcher() {
        val reachedCallback = CountDownLatch(1)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                // Added last, so it wins over anything the app registered — this is about delivery,
                // not about which handler the app would choose.
                activity.onBackPressedDispatcher.addCallback(
                    activity,
                    object : OnBackPressedCallback(true) {
                        override fun handleOnBackPressed() = reachedCallback.countDown()
                    },
                )
            }

            // A real key event through the input system, not dispatcher.onBackPressed(). Calling
            // the dispatcher directly would bypass OnBackInvokedDispatcher entirely and prove
            // nothing about the opted-in path -- which is the only thing this test exists for.
            InstrumentationRegistry.getInstrumentation()
                .sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)

            assertTrue(
                "a back press did not reach OnBackPressedDispatcher; BackHandler in EditorScreen " +
                    "would not fire, so backing out of the editor would not save",
                reachedCallback.await(5, TimeUnit.SECONDS),
            )
        }
    }
}
