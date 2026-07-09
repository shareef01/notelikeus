package com.aus.notelikeus.ui.main

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FilterAltOff
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.aus.notelikeus.data.backup.NoteBackupExporter
import com.aus.notelikeus.data.backup.BackupExportResult
import com.aus.notelikeus.data.backup.BackupImportResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.aus.notelikeus.MainActivity
import com.aus.notelikeus.R
import com.aus.notelikeus.di.GoogleSignInEntryPoint
import com.aus.notelikeus.ui.components.NoteColorSwatch
import com.aus.notelikeus.ui.components.NoteStaggeredGrid
import com.aus.notelikeus.ui.components.NotesEmptyState
import com.aus.notelikeus.ui.main.components.DrawerNavLabel
import com.aus.notelikeus.ui.main.components.MainTopAppBar
import com.aus.notelikeus.ui.main.components.ProfileSheet
import com.aus.notelikeus.ui.main.components.TrashBanner
import com.aus.notelikeus.ui.theme.BrandMarkIcon
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.launch

@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onNoteClick: (Long?) -> Unit,
    isAppLockEnabled: Boolean = false,
    onRequestAppUnlock: (onSuccess: () -> Unit) -> Unit = {},
    onAppLockEnabled: () -> Unit = {}
) {
    val state by viewModel.state.collectAsState()
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
    val snackbarHostState = remember { SnackbarHostState() }
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val undoLabel = stringResource(R.string.action_undo)
    val googleSignInHelper = remember {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            GoogleSignInEntryPoint::class.java
        ).googleSignInHelper()
    }

    val googleSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        googleSignInHelper.parseIdToken(result.data)
            .onSuccess { viewModel.signInWithGoogleIdToken(it) }
            .onFailure {
                scope.launch {
                    snackbarHostState.showSnackbar(
                        context.getString(R.string.cloud_sign_in_failed)
                    )
                }
            }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(NoteBackupExporter.BACKUP_MIME_TYPE)
    ) { uri ->
        uri?.let {
            scope.launch {
                when (val result = viewModel.exportBackup(context.contentResolver, it)) {
                    BackupExportResult.Success -> snackbarHostState.showSnackbar(
                        context.getString(R.string.export_success)
                    )
                    BackupExportResult.WriteFailed,
                    is BackupExportResult.Error -> snackbarHostState.showSnackbar(
                        context.getString(R.string.export_failed)
                    )
                }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            scope.launch {
                val message = when (val result = viewModel.importBackup(context.contentResolver, it)) {
                    is BackupImportResult.Success -> context.getString(
                        R.string.import_success,
                        result.notesImported
                    )
                    is BackupImportResult.InvalidFormat -> context.getString(
                        R.string.import_invalid_format,
                        result.message
                    )
                    BackupImportResult.ReadFailed,
                    is BackupImportResult.Error -> context.getString(R.string.import_failed)
                }
                snackbarHostState.showSnackbar(message)
            }
        }
    }

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

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = MaterialTheme.colorScheme.surface,
                drawerTonalElevation = 0.dp
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 28.dp, vertical = 24.dp)
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            BrandMarkIcon(
                                size = 36.dp,
                                backgroundColor = MaterialTheme.colorScheme.onSurface,
                                stripeColor = MaterialTheme.colorScheme.surface
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                stringResource(R.string.app_name),
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = (-0.5).sp
                                ),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Text(
                            stringResource(R.string.drawer_tagline),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.nav_notes), fontWeight = FontWeight.Medium) },
                    selected = state.currentFilter == NoteFilter.ACTIVE,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                        viewModel.setFilter(NoteFilter.ACTIVE)
                        scope.launch { drawerState.close() }
                    },
                    icon = { Icon(Icons.Default.Lightbulb, contentDescription = stringResource(R.string.nav_notes)) },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                    colors = NavigationDrawerItemDefaults.colors(
                        unselectedContainerColor = Color.Transparent,
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                    )
                )
                NavigationDrawerItem(
                    label = {
                        DrawerNavLabel(
                            text = stringResource(R.string.nav_archive),
                            count = state.archivedNoteCount
                        )
                    },
                    selected = state.currentFilter == NoteFilter.ARCHIVED,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                        viewModel.setFilter(NoteFilter.ARCHIVED)
                        scope.launch { drawerState.close() }
                    },
                    icon = { Icon(Icons.Default.Archive, contentDescription = stringResource(R.string.nav_archive)) },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                    colors = NavigationDrawerItemDefaults.colors(
                        unselectedContainerColor = Color.Transparent,
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                    )
                )
                NavigationDrawerItem(
                    label = {
                        DrawerNavLabel(
                            text = stringResource(R.string.nav_trash),
                            count = state.trashedNoteCount
                        )
                    },
                    selected = state.currentFilter == NoteFilter.TRASHED,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                        viewModel.setFilter(NoteFilter.TRASHED)
                        scope.launch { drawerState.close() }
                    },
                    icon = { Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.nav_trash)) },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                    colors = NavigationDrawerItemDefaults.colors(
                        unselectedContainerColor = Color.Transparent,
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                    )
                )

                Spacer(modifier = Modifier.weight(1f))

                HorizontalDivider(modifier = Modifier.padding(horizontal = 28.dp))

                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.nav_settings), fontWeight = FontWeight.Medium) },
                    selected = false,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                        showProfileSheet = true
                        scope.launch { drawerState.close() }
                    },
                    icon = { Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.nav_settings)) },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                    colors = NavigationDrawerItemDefaults.colors(unselectedContainerColor = Color.Transparent)
                )
                Spacer(modifier = Modifier.height(16.dp).navigationBarsPadding())
            }
        }
    ) {
        val showFab = state.currentFilter == NoteFilter.ACTIVE && state.selectedNotes.isEmpty()
        val selectedNoteModels = state.notes.filter { it.id in state.selectedNotes }
        val selectionAllPinned = selectedNoteModels.isNotEmpty() && selectedNoteModels.all { it.isPinned }
        val visibleNoteIds = state.filteredNotes.mapNotNull { it.id }.toSet()
        val allFilteredSelected = visibleNoteIds.isNotEmpty() &&
            visibleNoteIds.all { it in state.selectedNotes }
        val allowReorder = state.searchQuery.isEmpty() &&
            state.selectedColor == null &&
            state.selectedLabelId == null
        Scaffold(
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
                        showDeleteConfirm = true
                    },
                    onArchiveSelected = {
                        haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                        viewModel.archiveSelectedNotes()
                        scope.launch {
                            showUndoSnackbar(context.getString(R.string.note_archived))
                        }
                    },
                    onRestoreSelected = {
                        haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                        viewModel.restoreSelectedNotes()
                        scope.launch {
                            snackbarHostState.showSnackbar(context.getString(R.string.notes_restored))
                        }
                    },
                    selectionAllPinned = selectionAllPinned,
                    onPinSelected = {
                        haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                        val pin = !selectionAllPinned
                        viewModel.setSelectedNotesPinned(pin)
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                context.getString(if (pin) R.string.notes_pinned else R.string.notes_unpinned)
                            )
                        }
                    },
                    currentFilter = state.currentFilter,
                    onMenuClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                        scope.launch { drawerState.open() }
                    },
                    onProfileClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                        showProfileSheet = true
                    },
                    selectedColor = state.selectedColor,
                    onColorSelect = viewModel::selectColorFilter,
                    allLabels = state.allLabels,
                    selectedLabelId = state.selectedLabelId,
                    onLabelSelect = viewModel::selectLabelFilter,
                    sortOrder = state.sortOrder,
                    hasActiveFilters = state.selectedColor != null || state.selectedLabelId != null,
                    onClearFilters = viewModel::clearFilters,
                    listScrolled = listScrolled
                )
            },
            floatingActionButton = {
                AnimatedVisibility(
                    visible = state.currentFilter == NoteFilter.ACTIVE && state.selectedNotes.isEmpty(),
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
                            defaultElevation = 6.dp, // Increased for premium depth
                            pressedElevation = 12.dp,
                            hoveredElevation = 8.dp,
                            focusedElevation = 8.dp
                        ),
                        shape = MaterialTheme.shapes.large
                    ) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_note))
                    }
                }
            }
        ) { paddingValues ->
            val filteredNotes = state.filteredNotes
            val gridBottomPadding = paddingValues.calculateBottomPadding() + if (showFab) 80.dp else 16.dp

            if (filteredNotes.isEmpty()) {
                val hasActiveFilters = state.selectedColor != null || state.selectedLabelId != null
                val message: String
                val subtitle: String?
                val showCreate: Boolean
                val showClear: Boolean
                val emptyIcon: ImageVector?
                when {
                    state.searchQuery.isNotEmpty() -> {
                        message = stringResource(R.string.no_matching_notes)
                        subtitle = stringResource(R.string.empty_search_subtitle)
                        showCreate = false
                        showClear = hasActiveFilters
                        emptyIcon = Icons.Outlined.SearchOff
                    }
                    hasActiveFilters -> {
                        message = stringResource(R.string.no_filter_matches)
                        subtitle = stringResource(R.string.empty_filter_subtitle)
                        showCreate = false
                        showClear = true
                        emptyIcon = Icons.Outlined.FilterAltOff
                    }
                    state.currentFilter == NoteFilter.ARCHIVED -> {
                        message = stringResource(R.string.no_archived_notes)
                        subtitle = null
                        showCreate = false
                        showClear = false
                        emptyIcon = Icons.Outlined.Archive
                    }
                    state.currentFilter == NoteFilter.TRASHED -> {
                        message = stringResource(R.string.no_trashed_notes)
                        subtitle = stringResource(R.string.empty_trash_subtitle)
                        showCreate = false
                        showClear = false
                        emptyIcon = Icons.Outlined.DeleteOutline
                    }
                    else -> {
                        message = stringResource(R.string.empty_notes_hint)
                        subtitle = stringResource(R.string.empty_notes_subtitle)
                        showCreate = state.currentFilter == NoteFilter.ACTIVE
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
                                showEmptyTrashConfirm = true
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
                                showUndoSnackbar(context.getString(R.string.note_archived))
                            }
                        },
                        onSwipeToTrash = { note ->
                            haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                            viewModel.trashNote(note)
                            val message = if (state.currentFilter == NoteFilter.TRASHED) {
                                context.getString(R.string.note_deleted)
                            } else {
                                context.getString(R.string.note_trashed)
                            }
                            scope.launch { showUndoSnackbar(message) }
                        },
                        onLabelClick = { labelId ->
                            viewModel.selectLabelFilter(labelId)
                        },
                        onMoveNote = viewModel::previewMoveNote,
                        onReorderComplete = viewModel::commitNoteOrder,
                        columns = state.viewMode.columns,
                        compact = state.viewMode.compact,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentPadding = PaddingValues(
                            top = 12.dp,
                            start = 16.dp, // Strict Screen Borders
                            end = 16.dp,   // Strict Screen Borders
                            bottom = gridBottomPadding
                        )
                    )
                }
            }
        }
    }

    if (showProfileSheet) {
        ProfileSheet(
            onDismiss = { showProfileSheet = false },
            noteCount = state.totalNoteCount,
            viewMode = state.viewMode,
            sortOrder = state.sortOrder,
            useMonochromeTheme = state.useMonochromeTheme,
            isTrueDarkMode = state.isTrueDarkMode,
            isAppLockEnabled = isAppLockEnabled,
            cloudSyncStatus = state.cloudSyncStatus,
            cloudSyncedNoteCount = state.cloudSyncedNoteCount,
            cloudAccount = state.cloudAccount,
            isCloudAutoSyncEnabled = state.isCloudAutoSyncEnabled,
            onViewModeChange = { viewModel.setViewMode(it) },
            onSortOrderChange = { viewModel.setSortOrder(it) },
            onMonochromeThemeChange = { viewModel.setUseMonochromeTheme(it) },
            onTrueDarkModeChange = { viewModel.setTrueDarkMode(it) },
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
                val fileName = "${NoteBackupExporter.BACKUP_FILE_PREFIX}_${
                    SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
                }.json"
                exportLauncher.launch(fileName)
            },
            onImportClick = {
                showProfileSheet = false
                importLauncher.launch(arrayOf(NoteBackupExporter.BACKUP_MIME_TYPE))
            },
            onCloudSyncClick = {
                viewModel.syncNotesToCloud()
            },
            onCloudRestoreClick = {
                viewModel.downloadNotesFromCloud()
            },
            onGoogleSignInClick = {
                googleSignInLauncher.launch(googleSignInHelper.getSignInIntent())
            },
            onGoogleSignOutClick = {
                showCloudSignOutConfirm = true
            },
            onCloudAutoSyncChange = { viewModel.setCloudAutoSyncEnabled(it) }
        )
    }

    LaunchedEffect(state.pendingCloudSyncEvent) {
        when (val event = state.pendingCloudSyncEvent) {
            is CloudSyncEvent.Uploaded -> {
                snackbarHostState.showSnackbar(
                    context.getString(R.string.cloud_sync_success, event.noteCount)
                )
            }
            is CloudSyncEvent.Downloaded -> {
                snackbarHostState.showSnackbar(
                    context.getString(R.string.cloud_download_success, event.noteCount)
                )
            }
            is CloudSyncEvent.Failure -> {
                snackbarHostState.showSnackbar(
                    event.message.ifBlank { context.getString(R.string.cloud_sync_failed) }
                )
            }
            CloudSyncEvent.SignedIn -> {
                snackbarHostState.showSnackbar(context.getString(R.string.cloud_sign_in_success))
            }
            is CloudSyncEvent.SignedOut -> {
                snackbarHostState.showSnackbar(
                    context.getString(
                        if (event.cloudDataDeleted) {
                            R.string.cloud_sign_out_deleted_success
                        } else {
                            R.string.cloud_sign_out_success
                        }
                    )
                )
            }
            CloudSyncEvent.SignInRequired -> {
                snackbarHostState.showSnackbar(context.getString(R.string.cloud_sign_in_required))
            }
            null -> Unit
        }
        if (state.pendingCloudSyncEvent != null) {
            viewModel.clearPendingCloudSyncEvent()
        }
    }

    if (showCloudSignOutConfirm) {
        AlertDialog(
            onDismissRequest = { showCloudSignOutConfirm = false },
            title = { Text(stringResource(R.string.cloud_sign_out_confirm_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.cloud_sign_out_confirm_message))
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
                        Text(stringResource(R.string.cloud_sign_out_delete_data))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showCloudSignOutConfirm = false
                    showProfileSheet = false
                    viewModel.signOutFromCloud(deleteCloudData = false)
                }) {
                    Text(stringResource(R.string.cloud_sign_out))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCloudSignOutConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (showEmptyTrashConfirm) {
        AlertDialog(
            onDismissRequest = { showEmptyTrashConfirm = false },
            title = { Text(stringResource(R.string.empty_trash_title)) },
            text = { Text(stringResource(R.string.empty_trash_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showEmptyTrashConfirm = false
                    viewModel.emptyTrash()
                    scope.launch {
                        showUndoSnackbar(context.getString(R.string.note_deleted))
                    }
                }) { Text(stringResource(R.string.empty_trash)) }
            },
            dismissButton = {
                TextButton(onClick = { showEmptyTrashConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (showDeleteConfirm) {
        val count = state.selectedNotes.size
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = {
                Text(
                    if (count == 1) {
                        stringResource(R.string.delete_note_title)
                    } else {
                        stringResource(R.string.delete_notes_title, count)
                    }
                )
            },
            text = {
                Text(
                    if (state.currentFilter == NoteFilter.TRASHED) {
                        stringResource(R.string.delete_permanent_message)
                    } else {
                        stringResource(R.string.delete_to_trash_message)
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    viewModel.deleteSelectedNotes()
                    val message = if (state.currentFilter == NoteFilter.TRASHED) {
                        context.getString(R.string.note_deleted)
                    } else {
                        context.getString(R.string.note_trashed)
                    }
                    scope.launch { showUndoSnackbar(message) }
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

