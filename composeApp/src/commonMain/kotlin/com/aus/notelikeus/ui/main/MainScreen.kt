package com.aus.notelikeus.ui.main

    import androidx.compose.animation.core.animateDpAsState
    import androidx.compose.foundation.background
    import androidx.compose.foundation.layout.*
    import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
    import androidx.compose.material3.*
    import androidx.compose.material3.adaptive.layout.AnimatedPane
    import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
    import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
    import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
    import androidx.compose.material3.windowsizeclass.WindowSizeClass
    import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
    import androidx.compose.runtime.*
    import androidx.compose.ui.Alignment
    import androidx.compose.ui.Modifier
    import androidx.compose.ui.draw.alpha
    import androidx.compose.ui.graphics.Brush
    import androidx.compose.ui.graphics.Color
    import androidx.compose.ui.hapticfeedback.HapticFeedbackType
    import androidx.compose.ui.input.key.*
    import androidx.compose.ui.platform.LocalHapticFeedback
    import androidx.compose.ui.text.font.FontWeight
    import androidx.compose.ui.text.style.TextAlign
    import androidx.compose.ui.unit.dp
    import androidx.compose.ui.unit.sp
    import androidx.lifecycle.compose.collectAsStateWithLifecycle
    import com.aus.notelikeus.ui.editor.EditorScreen
    import com.aus.notelikeus.ui.editor.EditorViewModel
    import com.aus.notelikeus.ui.main.components.ProfileSheet
    import com.aus.notelikeus.ui.theme.BrandMarkIcon
    import com.aus.notelikeus.util.AppConfig
    import kotlinx.coroutines.launch
    import notelikeus.composeapp.generated.resources.Res
    import notelikeus.composeapp.generated.resources.*
    import org.jetbrains.compose.resources.getString
    import org.jetbrains.compose.resources.stringResource
    import org.koin.compose.viewmodel.koinViewModel
    import org.koin.core.annotation.KoinExperimentalAPI

/**
 * Detail-pane key standing in for "compose a new note".
 *
 * The two-pane scaffold treats a null content key as "nothing selected", so a new note — which the
 * editor represents as a null id — cannot be passed through directly. Matches the -1L the Editor
 * nav route already uses for the same purpose.
 */
private const val NewNoteContentKey = -1L

@OptIn(androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi::class, KoinExperimentalAPI::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onNoteClick: (Long?) -> Unit,
    onEditLabels: () -> Unit,
    windowSizeClass: WindowSizeClass,
    initialSidebarCollapsed: Boolean = false,
    onSidebarCollapsedChange: (Boolean) -> Unit = {},
    isAppLockEnabled: Boolean = false,
    onRequestAppUnlock: (onSuccess: () -> Unit) -> Unit = {},
    onAppLockEnabled: () -> Unit = {},
    onExportBackup: () -> Unit = {},
    onImportBackup: () -> Unit = {},
    onGoogleSignIn: () -> Unit = {}
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val gridState = rememberLazyStaggeredGridState()
    val listScrolled by remember {
        derivedStateOf {
            gridState.firstVisibleItemIndex > 0 || gridState.firstVisibleItemScrollOffset > 0
        }
    }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showProfileSheet by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showEmptyTrashConfirm by remember { mutableStateOf(false) }
    var showCloudSignOutConfirm by remember { mutableStateOf(false) }
    var showCloudRestoreConfirm by remember { mutableStateOf(false) }
    var profileSignInError by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val haptic = LocalHapticFeedback.current
    val undoLabel = stringResource(Res.string.action_undo)
    val searchFocusRequester = remember { androidx.compose.ui.focus.FocusRequester() }

    suspend fun showUndoSnackbar(message: String) {
        val result = snackbarHostState.showSnackbar(
            message = message,
            actionLabel = undoLabel,
            duration = SnackbarDuration.Short
        )
        if (result == SnackbarResult.ActionPerformed) {
            viewModel.undoLastAction()
        }
    }

    LaunchedEffect(state.pendingUndoMessage) {
        state.pendingUndoMessage?.let { message ->
            showUndoSnackbar(message)
            viewModel.clearPendingUndoMessage()
        }
    }

    LaunchedEffect(state.pendingActionFailure) {
        val failure = state.pendingActionFailure ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(
            getString(
                when (failure) {
                    NoteActionFailure.UPDATE -> Res.string.note_update_failed
                    NoteActionFailure.DELETE -> Res.string.note_delete_failed
                    NoteActionFailure.UNDO -> Res.string.note_undo_failed
                    NoteActionFailure.REORDER -> Res.string.note_order_save_failed
                }
            )
        )
        viewModel.clearPendingActionFailure()
    }

    LaunchedEffect(state.pendingCloudSyncEvent) {
        when (val event = state.pendingCloudSyncEvent) {
            is CloudSyncEvent.Uploaded -> {
                snackbarHostState.showSnackbar(
                    getString(Res.string.cloud_sync_success, event.noteCount)
                )
            }
            is CloudSyncEvent.Downloaded -> {
                snackbarHostState.showSnackbar(
                    getString(Res.string.cloud_download_success, event.noteCount)
                )
            }
            is CloudSyncEvent.Failure -> {
                snackbarHostState.showSnackbar(
                    event.message.ifBlank { getString(Res.string.cloud_sync_failed) }
                )
                profileSignInError = event.message
            }
            CloudSyncEvent.SignedIn -> {
                snackbarHostState.showSnackbar(getString(Res.string.cloud_sign_in_success))
            }
            is CloudSyncEvent.SignedOut -> {
                snackbarHostState.showSnackbar(
                    getString(
                        if (event.cloudDataDeleted) {
                            Res.string.cloud_sign_out_deleted_success
                        } else {
                            Res.string.cloud_sign_out_success
                        }
                    )
                )
            }
            CloudSyncEvent.SignInRequired -> {
                snackbarHostState.showSnackbar(getString(Res.string.cloud_sign_in_required))
            }
            null -> Unit
        }
        if (state.pendingCloudSyncEvent != null) {
            viewModel.clearPendingCloudSyncEvent()
        }
    }

    // A Medium-width desktop window is a resized app window, not a phone, so it still gets the
    // two-pane layout. On Android, Medium is a large phone or a folded foldable and must not.
    val isExpanded = windowSizeClass.widthSizeClass == WindowWidthSizeClass.Expanded ||
            (windowSizeClass.widthSizeClass == WindowWidthSizeClass.Medium && AppConfig.isDesktop)
    var sidebarManuallyCollapsed by remember(initialSidebarCollapsed) {
        mutableStateOf(initialSidebarCollapsed)
    }
    val effectiveSidebarCollapsed = sidebarManuallyCollapsed && isExpanded
    val navigator = rememberListDetailPaneScaffoldNavigator<Long?>()

    // Drawer navigation: every entry point performs its haptic and closes the modal drawer
    // (compact layouts only) before acting.
    val selectFilter: (NoteFilter) -> Unit = { filter ->
        haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
        viewModel.setFilter(filter)
        scope.launch { if (!isExpanded) drawerState.close() }
    }
    val editLabels: () -> Unit = {
        haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
        onEditLabels()
        scope.launch { if (!isExpanded) drawerState.close() }
    }
    val openSettings: () -> Unit = {
        haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
        showProfileSheet = true
        scope.launch { if (!isExpanded) drawerState.close() }
    }
    val requestCloudSignOut: () -> Unit = {
        haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
        showCloudSignOutConfirm = true
        scope.launch { if (!isExpanded) drawerState.close() }
    }

    val drawerContent = @Composable {
        ModalDrawerSheet(
            drawerContainerColor = MaterialTheme.colorScheme.surface,
            drawerTonalElevation = 0.dp,
            modifier = if (isExpanded) Modifier.width(260.dp) else Modifier.widthIn(max = 300.dp)
        ) {
            MainDrawerContent(
                state = state,
                collapsed = false,
                isExpanded = isExpanded,
                settingsSelected = showProfileSheet,
                onFilterSelect = selectFilter,
                onEditLabels = editLabels,
                onOpenSettings = openSettings,
                onCloudSignOut = requestCloudSignOut,
                onSidebarCollapsedChange = { next ->
                    sidebarManuallyCollapsed = next
                    onSidebarCollapsedChange(next)
                }
            )
        }
    }

    // Shared notes-list scaffold. Desktop shows it full-width (notes open in their own
    // OS windows, so a detail pane would just sit there empty), while Android tablets
    // pair it with the editor detail pane.
    val mainScaffold: @Composable ((Long?) -> Unit) -> Unit = { handleNoteClick ->
        MainScaffold(
            state = state,
            viewModel = viewModel,
            onNoteClick = handleNoteClick,
            gridState = gridState,
            snackbarHostState = snackbarHostState,
            showProfileSheet = showProfileSheet,
            onShowProfileSheet = { showProfileSheet = it },
            onShowDeleteConfirm = { showDeleteConfirm = it },
            onShowEmptyTrashConfirm = { showEmptyTrashConfirm = it },
            onShowDrawer = { scope.launch { drawerState.open() } },
            listScrolled = listScrolled,
            haptic = haptic,
            scope = scope,
            showUndoSnackbar = { scope.launch { showUndoSnackbar(it) } },
            searchFocusRequester = searchFocusRequester,
            isExpanded = isExpanded
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onKeyEvent {
                if (it.isCtrlPressed && it.key == Key.F) {
                    searchFocusRequester.requestFocus()
                    true
                } else if (it.isCtrlPressed && it.key == Key.N) {
                    onNoteClick(null)
                    true
                } else if (it.isCtrlPressed && it.key == Key.Comma) {
                    showProfileSheet = true
                    true
                } else if (it.key == Key.Delete && state.selectedNotes.isNotEmpty()) {
                    showDeleteConfirm = true
                    true
                } else if (it.key == Key.Escape) {
                    if (state.selectedNotes.isNotEmpty()) {
                        viewModel.clearSelection()
                        true
                    } else if (state.searchQuery.isNotEmpty()) {
                        viewModel.onSearchQueryChange("")
                        true
                    } else false
                } else false
            }
    ) {
        if (isExpanded) {
            val drawerWidth by animateDpAsState(
                targetValue = if (effectiveSidebarCollapsed) 64.dp else 260.dp,
                label = "drawerWidth"
            )
            PermanentNavigationDrawer(
                drawerContent = { 
                    PermanentDrawerSheet(
                        drawerContainerColor = MaterialTheme.colorScheme.surface,
                        drawerTonalElevation = 0.dp,
                        modifier = Modifier.width(drawerWidth)
                    ) {
                        MainDrawerContent(
                            state = state,
                            collapsed = effectiveSidebarCollapsed,
                            isExpanded = isExpanded,
                            settingsSelected = showProfileSheet,
                            onFilterSelect = selectFilter,
                            onEditLabels = editLabels,
                            onOpenSettings = openSettings,
                            onCloudSignOut = requestCloudSignOut,
                            onSidebarCollapsedChange = { next ->
                                sidebarManuallyCollapsed = next
                                onSidebarCollapsedChange(next)
                            }
                        )
                    }
                }
            ) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    if (AppConfig.isDesktop) {
                        // Desktop opens each note in its own OS window, so a detail pane
                        // would just sit there empty. Give the notes list the full window,
                        // capped at the same content width the web app uses.
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.TopCenter
                        ) {
                            Box(modifier = Modifier.fillMaxHeight().widthIn(max = 1408.dp)) {
                                mainScaffold { noteId -> onNoteClick(noteId) }
                            }
                        }
                    } else {
                        ListDetailPaneScaffold(
                            directive = navigator.scaffoldDirective,
                            value = navigator.scaffoldValue,
                            listPane = {
                                AnimatedPane {
                                    mainScaffold { noteId ->
                                        scope.launch {
                                            // A new note arrives as null, which is also the
                                            // scaffold's "nothing selected" key — passing it
                                            // straight through sent the detail pane to its empty
                                            // placeholder, so the + button appeared to do nothing.
                                            navigator.navigateTo(
                                                ListDetailPaneScaffoldRole.Detail,
                                                noteId ?: NewNoteContentKey
                                            )
                                        }
                                    }
                                }
                            },
                            detailPane = {
                            AnimatedPane {
                                val destination = navigator.currentDestination
                                if (destination != null && destination.contentKey != null) {
                                    val contentKey = destination.contentKey
                                    // Unwrap the sentinel: the editor takes null to mean "compose
                                    // a new note".
                                    val noteId = contentKey?.takeIf { it != NewNoteContentKey }
                                    Box(modifier = Modifier.fillMaxSize()) {
                                        key(contentKey) {
                                            val editorViewModel: EditorViewModel = koinViewModel()
                                            LaunchedEffect(noteId) {
                                                editorViewModel.setNoteId(noteId)
                                            }
                                            EditorScreen(
                                                viewModel = editorViewModel,
                                                onBack = {
                                                    scope.launch {
                                                        navigator.navigateBack()
                                                    }
                                                },
                                                onStageUndo = { note, action, message ->
                                                    viewModel.stageEditorUndo(note, action, message)
                                                },
                                                isExpanded = true
                                            )
                                        }

                                        // Natural separation: subtle left-side inner shadow and gradient
                                        Box(
                                            modifier = Modifier
                                                .fillMaxHeight()
                                                .width(24.dp) // Wider for a smoother fade
                                                .align(Alignment.CenterStart)
                                                .background(
                                                    Brush.horizontalGradient(
                                                        colors = listOf(
                                                            Color.Black.copy(alpha = 0.05f),
                                                            Color.Transparent
                                                        )
                                                    )
                                                )
                                        )
                                    }
                                } else {
                                    Box(
                                        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            modifier = Modifier.padding(horizontal = 40.dp)
                                        ) {
                                            BrandMarkIcon(
                                                size = 80.dp,
                                                backgroundColor = MaterialTheme.colorScheme.onSurface,
                                                stripeColor = MaterialTheme.colorScheme.surface,
                                                ringColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                                modifier = Modifier.alpha(0.08f)
                                            )
                                            Spacer(modifier = Modifier.height(24.dp))
                                            Text(
                                                text = stringResource(Res.string.select_note_to_view),
                                                style = MaterialTheme.typography.titleLarge.copy(
                                                    fontWeight = FontWeight.SemiBold,
                                                    letterSpacing = (-0.5).sp
                                                ),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                                textAlign = TextAlign.Center
                                            )
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Text(
                                                text = stringResource(Res.string.select_note_to_view_subtitle),
                                                style = MaterialTheme.typography.bodyLarge,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                                                textAlign = TextAlign.Center
                                            )
                                        }
                                    }
                                }
                            }
                            }
                        )
                    }
                }
            }
        } else {
            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = drawerContent
            ) {
                MainScaffold(
                    state = state,
                    viewModel = viewModel,
                    onNoteClick = { noteId -> onNoteClick(noteId) },
                    gridState = gridState,
                    snackbarHostState = snackbarHostState,
                    showProfileSheet = showProfileSheet,
                    onShowProfileSheet = { showProfileSheet = it },
                    onShowDeleteConfirm = { showDeleteConfirm = it },
                    onShowEmptyTrashConfirm = { showEmptyTrashConfirm = it },
                    onShowDrawer = { scope.launch { drawerState.open() } },
                    listScrolled = listScrolled,
                    haptic = haptic,
                    scope = scope,
                    showUndoSnackbar = { scope.launch { showUndoSnackbar(it) } },
                    searchFocusRequester = searchFocusRequester,
                    isExpanded = isExpanded
                )
            }
        }
    }

    // Settings sheet
    if (showProfileSheet) {
        ProfileSheet(
            onDismiss = { showProfileSheet = false },
            noteCount = state.totalNoteCount,
            viewMode = state.viewMode,
            sortOrder = state.sortOrder,
            appTheme = state.appTheme,
            isAppLockEnabled = isAppLockEnabled,
            cloudSyncStatus = state.cloudSyncStatus,
            cloudSyncedNoteCount = state.cloudSyncedNoteCount,
            cloudAccount = state.cloudAccount,
            isCloudAutoSyncEnabled = state.isCloudAutoSyncEnabled,
            signInError = profileSignInError,
            onViewModeChange = { viewModel.setViewMode(it) },
            onSortOrderChange = { viewModel.setSortOrder(it) },
            onAppThemeChange = { viewModel.setAppTheme(it) },
            onAppLockChange = { enabled ->
                if (enabled) {
                    onRequestAppUnlock {
                        viewModel.setAppLockEnabled(true)
                        onAppLockEnabled()
                    }
                } else {
                    viewModel.setAppLockEnabled(false)
                }
            },
            onExportClick = {
                showProfileSheet = false
                onExportBackup()
            },
            onImportClick = {
                showProfileSheet = false
                onImportBackup()
            },
            onCloudSyncClick = {
                viewModel.syncNotesToCloud()
            },
            onCloudRestoreClick = {
                showCloudRestoreConfirm = true
            },
            onGoogleSignInClick = {
                profileSignInError = null
                onGoogleSignIn()
            },
            onGoogleSignOutClick = {
                showCloudSignOutConfirm = true
            },
            onCloudAutoSyncChange = { viewModel.setCloudAutoSyncEnabled(it) }
        )
    }

    MainDialogs(
        showCloudSignOutConfirm = showCloudSignOutConfirm,
        showCloudRestoreConfirm = showCloudRestoreConfirm,
        showEmptyTrashConfirm = showEmptyTrashConfirm,
        showDeleteConfirm = showDeleteConfirm,
        selectedCount = state.selectedNotes.size,
        isTrashedFilter = state.currentFilter == NoteFilter.TRASHED,
        onCloudSignOutConfirmDismiss = { showCloudSignOutConfirm = false },
        onCloudSignOut = { deleteCloudData ->
            showCloudSignOutConfirm = false
            showProfileSheet = false
            viewModel.signOutFromCloud(deleteCloudData = deleteCloudData)
        },
        onCloudRestoreConfirmDismiss = { showCloudRestoreConfirm = false },
        onConfirmCloudRestore = {
            showCloudRestoreConfirm = false
            showProfileSheet = false
            viewModel.downloadNotesFromCloud()
        },
        onEmptyTrashConfirmDismiss = { showEmptyTrashConfirm = false },
        onConfirmEmptyTrash = {
            val trashedCount = state.notes.size
            showEmptyTrashConfirm = false
            viewModel.emptyTrash()
            scope.launch {
                val message = if (trashedCount == 1) {
                    getString(Res.string.note_deleted)
                } else {
                    getString(Res.string.notes_deleted_count, trashedCount)
                }
                showUndoSnackbar(message)
            }
        },
        onDeleteConfirmDismiss = { showDeleteConfirm = false },
        onConfirmDelete = {
            val count = state.selectedNotes.size
            showDeleteConfirm = false
            viewModel.deleteSelectedNotes()
            scope.launch {
                val message = if (state.currentFilter == NoteFilter.TRASHED) {
                    if (count == 1) {
                        getString(Res.string.note_deleted)
                    } else {
                        getString(Res.string.notes_deleted_count, count)
                    }
                } else {
                    if (count == 1) {
                        getString(Res.string.note_trashed)
                    } else {
                        getString(Res.string.notes_trashed_count, count)
                    }
                }
                showUndoSnackbar(message)
            }
        }
    )
}
