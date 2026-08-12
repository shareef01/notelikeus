package com.aus.notelikeus.ui.main

    import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FilterAltOff
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.Settings
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.domain.model.NoteSortOrder
import com.aus.notelikeus.domain.model.NoteViewMode
import com.aus.notelikeus.ui.components.NoteStaggeredGrid
import com.aus.notelikeus.ui.components.NotesEmptyState
import com.aus.notelikeus.ui.editor.EditorScreen
import com.aus.notelikeus.ui.editor.EditorViewModel
import com.aus.notelikeus.ui.main.components.*
import com.aus.notelikeus.ui.theme.*
import com.aus.notelikeus.util.AppConfig
import kotlinx.coroutines.CoroutineScope
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
    var sidebarManuallyCollapsed by remember { mutableStateOf(initialSidebarCollapsed) }
    val effectiveSidebarCollapsed = sidebarManuallyCollapsed && isExpanded
    val navigator = rememberListDetailPaneScaffoldNavigator<Long?>()

    val drawerInner: @Composable (collapsed: Boolean) -> Unit = { collapsed ->
        Column(modifier = Modifier.fillMaxHeight()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(
                        horizontal = if (collapsed) 8.dp else 20.dp,
                        vertical = if (collapsed) 16.dp else 20.dp
                    )
            ) {
                if (collapsed) {
                    BrandMarkIcon(
                        size = 28.dp,
                        backgroundColor = MaterialTheme.colorScheme.onSurface,
                        stripeColor = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.align(Alignment.Center)
                    )
                } else {
                    Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BrandMarkIcon(
                            size = 36.dp,
                            backgroundColor = MaterialTheme.colorScheme.onSurface,
                            stripeColor = MaterialTheme.colorScheme.surface
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                stringResource(Res.string.app_name),
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = (-0.3).sp,
                                    fontSize = 15.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                stringResource(Res.string.drawer_tagline_short),
                                style = ChromeLabelStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(if (collapsed) 2.dp else 4.dp)
            ) {
                SideDrawerNavItem(
                    label = stringResource(Res.string.nav_notes),
                    icon = Icons.Outlined.Lightbulb,
                    selectedIcon = Icons.Filled.Lightbulb,
                    selected = state.currentFilter == NoteFilter.ACTIVE,
                    count = state.totalNoteCount,
                    identityColor = Color(0xFF38BDF8), // sky-400
                    collapsed = collapsed,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                        viewModel.setFilter(NoteFilter.ACTIVE)
                        scope.launch { if (!isExpanded) drawerState.close() }
                    }
                )
                SideDrawerNavItem(
                    label = stringResource(Res.string.nav_archive),
                    icon = Icons.Outlined.Archive,
                    selectedIcon = Icons.Filled.Archive,
                    selected = state.currentFilter == NoteFilter.ARCHIVED,
                    count = state.archivedNoteCount,
                    identityColor = Color(0xFFFBBF24), // amber-400
                    collapsed = collapsed,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                        viewModel.setFilter(NoteFilter.ARCHIVED)
                        scope.launch { if (!isExpanded) drawerState.close() }
                    }
                )
                SideDrawerNavItem(
                    label = stringResource(Res.string.nav_trash),
                    icon = Icons.Outlined.Delete,
                    selectedIcon = Icons.Filled.Delete,
                    selected = state.currentFilter == NoteFilter.TRASHED,
                    count = state.trashedNoteCount,
                    identityColor = Color(0xFFFB7185), // rose-400
                    collapsed = collapsed,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                        viewModel.setFilter(NoteFilter.TRASHED)
                        scope.launch { if (!isExpanded) drawerState.close() }
                    }
                )

                Spacer(modifier = Modifier.height(20.dp))
                if (!collapsed) {
                SideDrawerSectionLabel(text = stringResource(Res.string.nav_section_manage))
                }

                if (!collapsed) {
                SideDrawerNavItem(
                    label = stringResource(Res.string.nav_edit_labels),
                    icon = Icons.AutoMirrored.Outlined.Label,
                    selectedIcon = Icons.AutoMirrored.Filled.Label,
                    selected = false,
                    identityColor = Color(0xFFA78BFA), // violet-400
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                        onEditLabels()
                        scope.launch { if (!isExpanded) drawerState.close() }
                    }
                )
                SideDrawerNavItem(
                    label = stringResource(Res.string.nav_settings),
                    icon = Icons.Outlined.Settings,
                    selectedIcon = Icons.Filled.Settings,
                    selected = showProfileSheet,
                    identityColor = Color(0xFF2DD4BF), // teal-400
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                        showProfileSheet = true
                        scope.launch { if (!isExpanded) drawerState.close() }
                    }
                )
                }
            }

            // Collapse toggle — only in permanent (expanded) mode
            if (isExpanded) {
                Spacer(modifier = Modifier.height(4.dp))
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = if (collapsed) 8.dp else 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = Chrome.Divider)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = if (collapsed) 8.dp else 16.dp)
                        .height(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                            val next = !sidebarManuallyCollapsed
                            sidebarManuallyCollapsed = next
                            onSidebarCollapsedChange(next)
                        }
                        .padding(horizontal = if (collapsed) 0.dp else 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = if (collapsed) Arrangement.Center else Arrangement.Start
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowLeft,
                        contentDescription = if (collapsed) "Expand sidebar" else "Collapse sidebar",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(18.dp)
                            .rotate(if (collapsed) 180f else 0f)
                    )
                    if (!collapsed) {
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Collapse",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (!collapsed) {
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = Chrome.Divider)
            )
            Spacer(modifier = Modifier.height(12.dp))
            val email = state.cloudAccount.email
            if (state.cloudAccount.isGoogleAccount && !email.isNullOrBlank()) {
                SideDrawerAccountRow(email = email)
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .height(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(SignOutRoseContainer)
                        .border(
                            width = 1.dp,
                            color = SignOutRose.copy(alpha = 0.25f),
                            shape = RoundedCornerShape(12.dp)
                        )
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                            showCloudSignOutConfirm = true
                            scope.launch { if (!isExpanded) drawerState.close() }
                        }
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Logout,
                        contentDescription = stringResource(Res.string.cloud_sign_out),
                        tint = SignOutRose,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(Res.string.cloud_sign_out),
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = (-0.15).sp
                        ),
                        color = SignOutRose
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
            }
            Spacer(modifier = Modifier.height(8.dp).navigationBarsPadding())
        }
    }

    val drawerContent = @Composable {
        ModalDrawerSheet(
            drawerContainerColor = MaterialTheme.colorScheme.surface,
            drawerTonalElevation = 0.dp,
            modifier = if (isExpanded) Modifier.width(260.dp) else Modifier.widthIn(max = 300.dp)
        ) {
            drawerInner(false)
        }
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
                        drawerInner(effectiveSidebarCollapsed)
                    }
                }
            ) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    ListDetailPaneScaffold(
                        directive = navigator.scaffoldDirective,
                        value = navigator.scaffoldValue,
                        listPane = {
                            AnimatedPane {
                                MainScaffold(
                                    state = state,
                                    viewModel = viewModel,
                                    onNoteClick = { noteId ->
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
                                    },
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
                                                    androidx.compose.ui.graphics.Brush.horizontalGradient(
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

    // Dialogs
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
                viewModel.downloadNotesFromCloud()
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

    if (showCloudSignOutConfirm) {
        AlertDialog(
            onDismissRequest = { showCloudSignOutConfirm = false },
            shape = MaterialTheme.shapes.large,
            title = { Text(stringResource(Res.string.cloud_sign_out_confirm_title)) },
            text = {
                Column {
                    Text(stringResource(Res.string.cloud_sign_out_confirm_message))
                    Spacer(modifier = Modifier.height(12.dp))
                    TextButton(
                        onClick = {
                            showCloudSignOutConfirm = false
                            showProfileSheet = false
                            viewModel.signOutFromCloud(deleteCloudData = true)
                        },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text(
                            stringResource(Res.string.cloud_sign_out_delete_data),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showCloudSignOutConfirm = false
                    showProfileSheet = false
                    viewModel.signOutFromCloud(deleteCloudData = false)
                }) {
                    Text(stringResource(Res.string.cloud_sign_out))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCloudSignOutConfirm = false }) {
                    Text(stringResource(Res.string.action_cancel))
                }
            }
        )
    }

    if (showEmptyTrashConfirm) {
        AlertDialog(
            onDismissRequest = { showEmptyTrashConfirm = false },
            shape = MaterialTheme.shapes.large,
            title = { Text(stringResource(Res.string.empty_trash_title)) },
            text = { Text(stringResource(Res.string.empty_trash_message)) },
            confirmButton = {
                TextButton(onClick = {
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
                }) {
                    Text(
                        stringResource(Res.string.empty_trash),
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showEmptyTrashConfirm = false }) {
                    Text(stringResource(Res.string.action_cancel))
                }
            }
        )
    }

    if (showDeleteConfirm) {
        val count = state.selectedNotes.size
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            shape = MaterialTheme.shapes.large,
            title = {
                Text(
                    if (count == 1) {
                        stringResource(Res.string.delete_note_title)
                    } else {
                        stringResource(Res.string.delete_notes_title, count)
                    }
                )
            },
            text = {
                Text(
                    if (state.currentFilter == NoteFilter.TRASHED) {
                        stringResource(Res.string.delete_permanent_message)
                    } else {
                        stringResource(Res.string.delete_to_trash_message)
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
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
                }) {
                    Text(
                        stringResource(Res.string.action_delete),
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(Res.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun MainScaffold(
    state: MainState,
    viewModel: MainViewModel,
    onNoteClick: (Long?) -> Unit,
    gridState: LazyStaggeredGridState,
    snackbarHostState: SnackbarHostState,
    showProfileSheet: Boolean,
    onShowProfileSheet: (Boolean) -> Unit,
    onShowDeleteConfirm: (Boolean) -> Unit,
    onShowEmptyTrashConfirm: (Boolean) -> Unit,
    onShowDrawer: () -> Unit,
    listScrolled: Boolean,
    haptic: androidx.compose.ui.hapticfeedback.HapticFeedback,
    scope: CoroutineScope,
    showUndoSnackbar: (String) -> Unit,
    searchFocusRequester: androidx.compose.ui.focus.FocusRequester,
    isExpanded: Boolean
) {
    val showFab = state.currentFilter == NoteFilter.ACTIVE && state.selectedNotes.isEmpty()
    val selectedNoteModels = remember(state.notes, state.selectedNotes) {
        state.notes.filter { it.id in state.selectedNotes }
    }
    val selectionAllPinned = remember(selectedNoteModels) {
        selectedNoteModels.isNotEmpty() && selectedNoteModels.all { it.isPinned }
    }
    val visibleNoteIds = remember(state.filteredNotes) {
        state.filteredNotes.mapNotNull { it.id }.toSet()
    }
    val allFilteredSelected = remember(visibleNoteIds, state.selectedNotes) {
        visibleNoteIds.isNotEmpty() && visibleNoteIds.all { it in state.selectedNotes }
    }
    val allowReorder = remember(state.searchQuery, state.selectedColor, state.selectedLabelId) {
        state.searchQuery.isEmpty() && state.selectedColor == null && state.selectedLabelId == null
    }

    Scaffold(
        containerColor = Color.Transparent, // Parent handles background
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.navigationBarsPadding()
            ) { data ->
                Snackbar(
                    snackbarData = data,
                    shape = MaterialTheme.shapes.medium,
                    containerColor = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    actionColor = MaterialTheme.colorScheme.inversePrimary,
                    modifier = Modifier.padding(
                        start = 16.dp,
                        end = 16.dp,
                        bottom = if (showFab) 88.dp else 16.dp
                    )
                )
            }
        },
        topBar = {
            MainTopAppBar(
                searchQuery = state.searchQuery,
                onSearchQueryChange = viewModel::onSearchQueryChange,
                viewMode = state.viewMode,
                onViewModeChange = viewModel::setViewMode,
                selectedCount = state.selectedNotes.size,
                allFilteredSelected = allFilteredSelected,
                onToggleSelectAll = {
                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                    viewModel.toggleSelectAll()
                },
                onClearSelection = {
                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                    viewModel.clearSelection()
                },
                onDeleteSelected = {
                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                    onShowDeleteConfirm(true)
                },
                onArchiveSelected = {
                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                    viewModel.archiveSelectedNotes()
                    scope.launch {
                        showUndoSnackbar(getString(Res.string.note_archived))
                    }
                },
                onRestoreSelected = {
                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                    viewModel.restoreSelectedNotes()
                    scope.launch {
                        snackbarHostState.showSnackbar(getString(Res.string.notes_restored))
                    }
                },
                selectionAllPinned = selectionAllPinned,
                onPinSelected = {
                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                    val pin = !selectionAllPinned
                    viewModel.setSelectedNotesPinned(pin)
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            getString(if (pin) Res.string.notes_pinned else Res.string.notes_unpinned)
                        )
                    }
                },
                currentFilter = state.currentFilter,
                onMenuClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                    onShowDrawer()
                },
                onProfileClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                    onShowProfileSheet(true)
                },
                accountEmail = state.cloudAccount.email,
                selectedColor = state.selectedColor,
                onColorSelect = viewModel::selectColorFilter,
                allLabels = state.allLabels,
                selectedLabelId = state.selectedLabelId,
                onLabelSelect = viewModel::selectLabelFilter,
                sortOrder = state.sortOrder,
                onSortOrderCycle = {
                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                    val next = state.sortOrder.next()
                    viewModel.setSortOrder(next)
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            getString(
                                Res.string.sorted_by,
                                getString(sortOrderLabelRes(next))
                            )
                        )
                    }
                },
                recentSearches = state.recentSearches,
                onRecentSearchClick = {
                    viewModel.onSearchQueryChange(it)
                    viewModel.addRecentSearch(it)
                },
                onClearRecentSearches = viewModel::clearRecentSearches,
                hasActiveFilters = state.selectedColor != null || state.selectedLabelId != null,
                onClearFilters = viewModel::clearFilters,
                listScrolled = listScrolled,
                searchFocusRequester = searchFocusRequester,
                showMenuIcon = !isExpanded
            )
        },
        floatingActionButton = {
            AnimatedVisibility(
                visible = showFab,
                enter = scaleIn() + fadeIn(),
                exit = scaleOut() + fadeOut()
            ) {
                FloatingActionButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                        onNoteClick(null)
                    },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    elevation = FloatingActionButtonDefaults.elevation(
                        defaultElevation = 2.dp,
                        pressedElevation = 4.dp,
                        hoveredElevation = 3.dp,
                        focusedElevation = 3.dp
                    ),
                    shape = MaterialTheme.shapes.large
                ) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(Res.string.add_note))
                }
            }
        }
    ) { paddingValues ->
        val filteredNotes = state.filteredNotes
        val gridBottomPadding = paddingValues.calculateBottomPadding() + if (showFab) 80.dp else 16.dp

        if (state.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (filteredNotes.isEmpty()) {
            val hasActiveFilters = state.selectedColor != null || state.selectedLabelId != null
            val message: String
            val subtitle: String?
            val showCreate: Boolean
            val showClear: Boolean
            val emptyIcon: ImageVector?
            when {
                state.searchQuery.isNotEmpty() -> {
                    message = stringResource(Res.string.no_matching_notes)
                    subtitle = stringResource(Res.string.empty_search_subtitle)
                    showCreate = false
                    showClear = true
                    emptyIcon = Icons.Outlined.SearchOff
                }
                hasActiveFilters -> {
                    message = stringResource(Res.string.no_filter_matches)
                    subtitle = stringResource(Res.string.empty_filter_subtitle)
                    showCreate = false
                    showClear = true
                    emptyIcon = Icons.Outlined.FilterAltOff
                }
                state.currentFilter == NoteFilter.ARCHIVED -> {
                    message = stringResource(Res.string.no_archived_notes)
                    subtitle = null
                    showCreate = false
                    showClear = false
                    emptyIcon = Icons.Outlined.Archive
                }
                state.currentFilter == NoteFilter.TRASHED -> {
                    message = stringResource(Res.string.no_trashed_notes)
                    subtitle = stringResource(Res.string.empty_trash_subtitle)
                    showCreate = false
                    showClear = false
                    emptyIcon = Icons.Outlined.DeleteOutline
                }
                else -> {
                    message = stringResource(Res.string.empty_notes_hint)
                    subtitle = stringResource(Res.string.empty_notes_subtitle)
                    // FAB already provides create — avoid a second CTA on empty.
                    showCreate = false
                    showClear = false
                    emptyIcon = null
                }
            }
            NotesEmptyState(
                message = message,
                subtitle = subtitle,
                icon = emptyIcon,
                showCreateButton = showCreate,
                showClearFilters = showClear,
                recentSearches = state.recentSearches,
                onRecentSearchClick = {
                    viewModel.onSearchQueryChange(it)
                    viewModel.addRecentSearch(it)
                },
                onCreateClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                    onNoteClick(null)
                },
                onClearFilters = {
                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                    viewModel.clearFilters()
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                if (state.currentFilter == NoteFilter.TRASHED && state.selectedNotes.isEmpty()) {
                    TrashBanner(
                        onEmptyTrash = {
                            haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                            onShowEmptyTrashConfirm(true)
                        }
                    )
                }
                NoteStaggeredGrid(
                    notes = filteredNotes,
                    selectedNotes = state.selectedNotes,
                    searchQuery = state.searchQuery,
                    listRevision = state.listRevision,
                    gridState = gridState,
                    enableArchiveSwipe = state.currentFilter == NoteFilter.ACTIVE,
                    enableSwipe = state.selectedNotes.isEmpty(),
                    allowReorder = allowReorder,
                    onNoteClick = { note ->
                        if (state.selectedNotes.isNotEmpty()) {
                            haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                            viewModel.toggleNoteSelection(note.id!!)
                        } else {
                            haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                            onNoteClick(note.id)
                        }
                    },
                    onNoteLongClick = { note ->
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.toggleNoteSelection(note.id!!)
                    },
                    onSwipeToArchive = { note ->
                        haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                        viewModel.archiveNote(note)
                        scope.launch {
                            showUndoSnackbar(getString(Res.string.note_archived))
                        }
                    },
                    onSwipeToTrash = { note ->
                        haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                        viewModel.trashNote(note)
                        scope.launch {
                            val message = if (state.currentFilter == NoteFilter.TRASHED) {
                                getString(Res.string.note_deleted)
                            } else {
                                getString(Res.string.note_trashed)
                            }
                            showUndoSnackbar(message)
                        }
                    },
                    onLabelClick = { labelId ->
                        viewModel.selectLabelFilter(labelId)
                    },
                    onMoveNote = viewModel::previewMoveNote,
                    onReorderComplete = viewModel::commitNoteOrder,
                    // In two-pane mode the list shares the window with the editor, so the wider
                    // grid choices leave cards too narrow to read. Cap the list pane at 2.
                    columns = if (isExpanded && state.viewMode.columns > 2) 2 else state.viewMode.columns,
                    compact = state.viewMode.compact,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(
                        top = 12.dp,
                        start = 12.dp,
                        end = 12.dp,
                        bottom = gridBottomPadding
                    )
                )
            }
        }
    }
}
