package com.aus.notelikeus.ui.navigation

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.savedstate.read
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The editor's route arguments have to survive the trip through Compose Navigation.
 *
 * `NavGraph` used to recover them by regex over `arguments.toString()`, on the grounds that the JVM
 * target does not populate `SavedStateHandle`. F1 recorded the discomfort: a navigation-library bump
 * could change that string with no compile error, and the failure lands on the user as *"tapping a
 * note opened a blank new note"*.
 *
 * Writing this test is what showed the workaround had **already stopped working** on desktop --
 * `arguments.toString()` is now `androidx.savedstate.SavedState@36cf295c`, an identity string with
 * no values in it. It went unnoticed only because desktop stopped using this route when the editor
 * moved into its own OS window, leaving Android -- where `Bundle.toString()` still happens to
 * include the contents -- as the only platform relying on it.
 *
 * The reads are typed now. These drive a **real** `NavHost` with the real argument types, so they
 * fail if the keys ever stop resolving. A test against a hand-written fixture could not do that:
 * the fixture would encode an assumption about the format and keep passing through exactly the
 * change it exists to detect.
 */
@OptIn(ExperimentalTestApi::class)
class RouteArgumentParsingTest {

    /**
     * A host for the NavController, which needs a lifecycle and a ViewModel store the desktop test
     * environment does not provide on its own.
     */
    private class TestNavHost : LifecycleOwner, ViewModelStoreOwner {
        // createUnsafe skips the main-thread assertion the normal constructor makes. This host is
        // built before the composition exists and is only ever touched by this test.
        private val registry = LifecycleRegistry.createUnsafe(this).apply {
            currentState = Lifecycle.State.RESUMED
        }
        override val lifecycle: Lifecycle get() = registry
        override val viewModelStore = ViewModelStore()
    }

    private data class Args(val noteId: Long?, val initialColor: Int?)

    /** Navigates to the editor route and reads its arguments exactly as NavGraph does. */
    private fun navigateAndRead(noteId: Long, initialColor: Int): Args {
        var read = Args(null, null)
        val host = TestNavHost()
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(
                    LocalLifecycleOwner provides host,
                    LocalViewModelStoreOwner provides host
                ) {
                    MaterialTheme {
                        val navController = rememberNavController()
                        NavHost(navController = navController, startDestination = "home") {
                            composable("home") { Text("home") }
                            composable(
                                route = "editor?noteId={noteId}&initialColor={initialColor}",
                                arguments = listOf(
                                    navArgument("noteId") {
                                        type = NavType.LongType
                                        defaultValue = -1L
                                    },
                                    navArgument("initialColor") {
                                        type = NavType.IntType
                                        defaultValue = Int.MIN_VALUE
                                    }
                                )
                            ) { backStackEntry ->
                                val arguments = backStackEntry.arguments
                                read = Args(
                                    noteId = arguments?.read {
                                        if (contains("noteId")) getLong("noteId") else null
                                    },
                                    initialColor = arguments?.read {
                                        if (contains("initialColor")) getInt("initialColor")
                                        else null
                                    }
                                )
                                Text("editor")
                            }
                        }
                        navController.navigate("editor?noteId=$noteId&initialColor=$initialColor")
                    }
                }
            }
            waitForIdle()
        }
        return read
    }

    @Test
    fun `both route arguments survive the round trip through Navigation`() {
        val green = 0xFF2E5A32.toInt()

        val args = navigateAndRead(noteId = 42L, initialColor = green)

        assertEquals(42L, args.noteId, "noteId did not survive navigation")
        assertEquals(green, args.initialColor, "initialColor did not survive navigation")
    }

    /**
     * A new note passes the sentinels, and both have to come back as themselves -- NavGraph maps
     * them to null afterwards, so losing the sign here would open an existing note by accident.
     */
    @Test
    fun `the sentinel values survive too`() {
        val args = navigateAndRead(noteId = -1L, initialColor = Int.MIN_VALUE)

        assertEquals(-1L, args.noteId)
        assertEquals(Int.MIN_VALUE, args.initialColor)
    }

    /** Reading a key that was never set has to be absent, not a silent zero. */
    @Test
    fun `an unset key reads as absent rather than as zero`() {
        val host = TestNavHost()
        var missing: Long? = -99L
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(
                    LocalLifecycleOwner provides host,
                    LocalViewModelStoreOwner provides host
                ) {
                    MaterialTheme {
                        val navController = rememberNavController()
                        NavHost(navController = navController, startDestination = "plain") {
                            composable("plain") { backStackEntry ->
                                missing = backStackEntry.arguments?.read {
                                    if (contains("noteId")) getLong("noteId") else null
                                }
                                Text("plain")
                            }
                        }
                    }
                }
            }
            waitForIdle()
        }

        assertNull(missing, "an absent argument must not read as 0")
    }
}
