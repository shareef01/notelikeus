package com.aus.notelikeus.ui.navigation

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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import com.aus.notelikeus.ui.editor.EditorScreen
import com.aus.notelikeus.ui.editor.EditorViewModel
import com.aus.notelikeus.ui.labels.LabelsScreen
import com.aus.notelikeus.ui.labels.LabelsViewModel
import com.aus.notelikeus.ui.main.MainScreen
import com.aus.notelikeus.ui.main.MainViewModel

@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }
val LocalAnimatedVisibilityScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

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

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun NavGraph(
    navController: NavHostController,
    mainViewModel: MainViewModel,
    windowSizeClass: WindowSizeClass,
    isAppLockEnabled: Boolean = false,
    onRequestAppUnlock: (onSuccess: () -> Unit) -> Unit = {},
    onAppLockEnabled: () -> Unit = {}
) {
    SharedTransitionLayout {
        CompositionLocalProvider(LocalSharedTransitionScope provides this) {
            NavHost(
                navController = navController,
                startDestination = Screen.Main.route
            ) {
                composable(
                    route = Screen.Main.route,
                    enterTransition = { fadeIn() },
                    exitTransition = { fadeOut() }
                ) {
                    CompositionLocalProvider(LocalAnimatedVisibilityScope provides this) {
                        MainScreen(
                            viewModel = mainViewModel,
                            onNoteClick = { noteId ->
                                val initialColor =
                                    if (noteId == null) mainViewModel.state.value.selectedColor else null
                                navController.navigate(Screen.Editor.createRoute(noteId, initialColor))
                            },
                            onEditLabels = {
                                navController.navigate(Screen.Labels.route)
                            },
                            windowSizeClass = windowSizeClass,
                            isAppLockEnabled = isAppLockEnabled,
                            onRequestAppUnlock = onRequestAppUnlock,
                            onAppLockEnabled = onAppLockEnabled
                        )
                    }
                }
                composable(
                    route = Screen.Labels.route,
                    enterTransition = { slideInHorizontally { it / 4 } + fadeIn() },
                    exitTransition = { fadeOut() },
                    popEnterTransition = { fadeIn() },
                    popExitTransition = { slideOutHorizontally { it / 4 } + fadeOut() }
                ) {
                    val viewModel: LabelsViewModel = hiltViewModel()
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
                    deepLinks = listOf(
                        navDeepLink {
                            uriPattern = "notelikeus://editor/{noteId}"
                        }
                    ),
                    enterTransition = { slideInHorizontally { it / 4 } + fadeIn() },
                    exitTransition = { fadeOut() },
                    popEnterTransition = { fadeIn() },
                    popExitTransition = { slideOutHorizontally { it / 4 } + fadeOut() }
                ) {
                    CompositionLocalProvider(LocalAnimatedVisibilityScope provides this) {
                        val viewModel: EditorViewModel = hiltViewModel()
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
        }
    }
}
