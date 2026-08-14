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
            // Compose Navigation on Desktop/JVM targets does not populate the Bundle via
            // SavedStateHandle, so standard argument accessors are unavailable. The argument
            // values are present in toString() output, so we parse them with a regex as a
            // stable workaround. If this ever breaks (e.g. after a Navigation library update),
            // the editor opens as a blank new note rather than the one that was tapped.
            val arguments = backStackEntry.arguments?.toString()
            fun argument(name: String): String? =
                arguments?.let { Regex("$name=(-?\\d+)").find(it)?.groupValues?.get(1) }

            val noteId = argument("noteId")?.toLongOrNull()?.takeIf { it != -1L }
            // Parsed alongside noteId: reading it from SavedStateHandle alone meant a new note
            // always opened in the theme default colour on desktop.
            val initialColor = argument("initialColor")?.toIntOrNull()?.takeIf { it != Int.MIN_VALUE }

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
