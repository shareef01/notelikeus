package com.aus.notelikeus.ui.navigation

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.aus.notelikeus.ui.editor.EditorScreen
import com.aus.notelikeus.ui.editor.EditorViewModel
import com.aus.notelikeus.ui.main.MainScreen
import com.aus.notelikeus.ui.main.MainViewModel

@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }
val LocalAnimatedVisibilityScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

sealed class Screen(val route: String) {
    object Main : Screen("main")
    object Editor : Screen("editor/{noteId}?skipLockCheck={skipLockCheck}") {
        fun createRoute(noteId: Long?, skipLockCheck: Boolean = false) =
            "editor/${noteId ?: -1L}?skipLockCheck=$skipLockCheck"
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun NavGraph(
    navController: NavHostController,
    mainViewModel: MainViewModel
) {
    SharedTransitionLayout {
        CompositionLocalProvider(LocalSharedTransitionScope provides this) {
            NavHost(
                navController = navController,
                startDestination = Screen.Main.route
            ) {
                composable(Screen.Main.route) {
                    CompositionLocalProvider(LocalAnimatedVisibilityScope provides this) {
                        MainScreen(
                            viewModel = mainViewModel,
                            onNoteClick = { noteId, skipLockCheck ->
                                navController.navigate(Screen.Editor.createRoute(noteId, skipLockCheck))
                            }
                        )
                    }
                }
                composable(
                    route = Screen.Editor.route,
                    arguments = listOf(
                        navArgument("noteId") {
                            type = NavType.LongType
                            defaultValue = -1L
                        },
                        navArgument("skipLockCheck") {
                            type = NavType.BoolType
                            defaultValue = false
                        }
                    )
                ) {
                    CompositionLocalProvider(LocalAnimatedVisibilityScope provides this) {
                        val viewModel: EditorViewModel = hiltViewModel()
                        EditorScreen(
                            viewModel = viewModel,
                            onBack = {
                                navController.popBackStack()
                            }
                        )
                    }
                }
            }
        }
    }
}
