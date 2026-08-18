package com.aus.notelikeus.ui.main

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Two effects keyed on the same pending event, which is the shape App.kt and MainScreen form
 * together: App.kt consumes the event synchronously, MainScreen shows a snackbar and only clears
 * it once `showSnackbar` returns.
 *
 * The hazard is that clearing the event flips the other effect's key, and a `LaunchedEffect` is
 * cancelled when its key changes -- taking the suspended `showSnackbar` with it. That is why
 * App.kt now only consumes the event while the sign-in gate is the thing on screen.
 *
 * This exercises the Compose semantics the fix depends on rather than App.kt itself, which needs
 * the whole DI graph to compose. The snackbar is Indefinite so the only thing that can remove it
 * is the cancellation under test.
 */
@OptIn(ExperimentalTestApi::class)
class PendingEventConsumptionTest {

    @Composable
    private fun Harness(competingEffectConsumes: Boolean, host: SnackbarHostState) {
        var event by remember { mutableStateOf<String?>("Sync failed") }

        // App.kt's effect: clears the event with no suspension point first.
        LaunchedEffect(event, competingEffectConsumes) {
            if (competingEffectConsumes && event != null) {
                event = null
            }
        }

        // MainScreen's effect: suspends for as long as the snackbar is up, clears afterwards.
        LaunchedEffect(event) {
            val current = event ?: return@LaunchedEffect
            host.showSnackbar(current, duration = SnackbarDuration.Indefinite)
            event = null
        }

        MaterialTheme {
            Scaffold(snackbarHost = { SnackbarHost(host) }) { }
        }
    }

    @Test
    fun `a competing effect that consumes the event cancels the snackbar`() = runComposeUiTest {
        val host = SnackbarHostState()
        setContent { Harness(competingEffectConsumes = true, host = host) }
        waitForIdle()

        // This is the bug: the message never survives long enough to be read.
        assertNull(
            host.currentSnackbarData,
            "the snackbar survived a competing effect clearing the event"
        )
    }

    @Test
    fun `leaving the event alone lets the snackbar stay up`() = runComposeUiTest {
        val host = SnackbarHostState()
        setContent { Harness(competingEffectConsumes = false, host = host) }
        waitForIdle()

        assertNotNull(
            host.currentSnackbarData,
            "the snackbar was not shown even with nothing competing for the event"
        )
    }
}
