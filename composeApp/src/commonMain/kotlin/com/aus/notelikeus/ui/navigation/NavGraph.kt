package com.aus.notelikeus.ui.navigation

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.aus.notelikeus.ui.editor.EditorScreen
import com.aus.notelikeus.ui.editor.EditorViewModel
import com.aus.notelikeus.ui.labels.LabelsScreen
import com.aus.notelikeus.ui.labels.LabelsViewModel
import com.aus.notelikeus.ui.main.MainScreen
import com.aus.notelikeus.ui.main.MainViewModel
import com.aus.notelikeus.util.AppConfig
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.annotation.KoinExperimentalAPI
import androidx.savedstate.read

/*
@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }
val LocalAnimatedVisibilityScope = compositionLocalOf<AnimatedVisibilityScope?> { null }
*/

sealed class Screen(val route: String) {
    object Main : Screen("main")

    object Editor : Screen("editor/{noteId}?initialColor={initialColor}") {
        fun createRoute(noteId: Long?, initialColor: Int? = null): String {
            val color = initialColor ?: Int.MIN_VALUE
            return "editor/${noteId ?: -1L}?initialColor=$color"
        }
    }

    object Labels : Screen("labels")
}

@Composable
fun NavGraph(
    navController: NavHostController,
    mainViewModel: MainViewModel,
    windowSizeClass: WindowSizeClass,
    editorWindowLauncher: EditorWindowLauncher = remember {
        object : EditorWindowLauncher {
            override fun launch(noteId: Long?, initialColor: Int?) = Unit
        }
    },
    initialSidebarCollapsed: Boolean = false,
    onSidebarCollapsedChange: (Boolean) -> Unit = {},
    isAppLockEnabled: Boolean = false,
    onRequestAppUnlock: (onSuccess: () -> Unit) -> Unit = {},
    onAppLockEnabled: () -> Unit = {},
    onExportBackup: () -> Unit = {},
    onImportBackup: () -> Unit = {}
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Main.route
    ) {
        composable(
            route = Screen.Main.route,
            enterTransition = { fadeIn() },
            exitTransition = { fadeOut() }
        ) {
            MainScreen(
                viewModel = mainViewModel,
                initialSidebarCollapsed = initialSidebarCollapsed,
                onSidebarCollapsedChange = onSidebarCollapsedChange,
                onNoteClick = { noteId ->
                    val initialColor =
                        if (noteId == null) mainViewModel.state.value.selectedColor else null
                    if (AppConfig.isDesktop) {
                        editorWindowLauncher.launch(noteId, initialColor)
                    } else {
                        navController.navigate(Screen.Editor.createRoute(noteId, initialColor))
                    }
                },
                onEditLabels = {
                    navController.navigate(Screen.Labels.route)
                },
                windowSizeClass = windowSizeClass,
                isAppLockEnabled = isAppLockEnabled,
                onRequestAppUnlock = onRequestAppUnlock,
                onAppLockEnabled = onAppLockEnabled,
                onExportBackup = onExportBackup,
                onImportBackup = onImportBackup
            )
        }
        composable(
            route = Screen.Labels.route,
            enterTransition = { slideInHorizontally { it / 4 } + fadeIn() },
            exitTransition = { fadeOut() },
            popEnterTransition = { fadeIn() },
            popExitTransition = { slideOutHorizontally { it / 4 } + fadeOut() }
        ) {
            val viewModel: LabelsViewModel = koinViewModel()
            LabelsScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() }
            )
        }
        composable(
            route = Screen.Editor.route,
            arguments = listOf(
                navArgument("noteId") {
                    type = NavType.LongType
                    defaultValue = -1L
                },
                navArgument("initialColor") {
                    type = NavType.IntType
                    defaultValue = Int.MIN_VALUE
                }
            ),
            enterTransition = { slideInHorizontally { it / 4 } + fadeIn() },
            exitTransition = { fadeOut() },
            popEnterTransition = { fadeIn() },
            popExitTransition = { slideOutHorizontally { it / 4 } + fadeOut() }
        ) { backStackEntry ->
            // Read out of the SavedState by key. This used to regex `arguments.toString()`, on
            // the grounds that the JVM target does not populate SavedStateHandle -- true, but the
            // savedstate `read` block is the supported way to reach the values and works on every
            // target. That string is now `androidx.savedstate.SavedState@36cf295c` on desktop, an
            // identity toString with no arguments in it at all, so the workaround had already
            // stopped working there. It survived only because desktop stopped using this route
            // when the editor moved into its own OS window.
            //
            // RouteArgumentParsingTest drives a real NavHost and fails if these keys stop
            // resolving -- which is a promise a regex over an unguaranteed format could not make.
            val arguments = backStackEntry.arguments
            val noteId = arguments
                ?.read { if (contains(NOTE_ID_ARG)) getLong(NOTE_ID_ARG) else null }
                ?.takeIf { it != -1L }
            val initialColor = arguments
                ?.read { if (contains(INITIAL_COLOR_ARG)) getInt(INITIAL_COLOR_ARG) else null }
                ?.takeIf { it != Int.MIN_VALUE }

            val viewModel: EditorViewModel = koinViewModel()
            LaunchedEffect(noteId, initialColor) { viewModel.setRouteArgs(noteId, initialColor) }
            EditorScreen(
                viewModel = viewModel,
                onBack = {
                    navController.popBackStack()
                },
                onStageUndo = { note, action, message ->
                    mainViewModel.stageEditorUndo(note, action, message)
                }
            )
        }
    }
}

/** The editor route's argument keys, named once so the route and the reads cannot drift apart. */
private const val NOTE_ID_ARG = "noteId"
private const val INITIAL_COLOR_ARG = "initialColor"
