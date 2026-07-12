package com.aus.notelikeus.ui.main

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FilterAltOff
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.*
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.aus.notelikeus.data.backup.NoteBackupExporter
import com.aus.notelikeus.data.backup.BackupExportResult
import com.aus.notelikeus.data.backup.BackupImportResult
import com.aus.notelikeus.data.backup.NoteBackupImporter
import android.net.Uri
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.aus.notelikeus.MainActivity
import com.aus.notelikeus.R
import com.aus.notelikeus.di.GoogleSignInEntryPoint
import com.aus.notelikeus.ui.auth.AuthMode
import com.aus.notelikeus.ui.auth.AuthScreen
import com.aus.notelikeus.ui.components.GoogleSignInButton
import com.aus.notelikeus.ui.components.NoteStaggeredGrid
import com.aus.notelikeus.ui.components.NotesEmptyState
import com.aus.notelikeus.ui.components.NotesLoadingGrid
import com.aus.notelikeus.ui.components.OfflineBanner
import com.aus.notelikeus.domain.model.NoteSortOrder
import com.aus.notelikeus.ui.main.components.BackupImportDialog
import com.aus.notelikeus.ui.main.components.DrawerNavLabel
import com.aus.notelikeus.ui.main.components.MainTopAppBar
import com.aus.notelikeus.ui.main.components.ProfileSheet
import com.aus.notelikeus.ui.main.components.TrashBanner
import com.aus.notelikeus.ui.theme.BrandMarkIcon
import com.aus.notelikeus.ui.editor.EditorScreen
import com.aus.notelikeus.ui.editor.EditorViewModel
import androidx.hilt.navigation.compose.hiltViewModel
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@OptIn(androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onNoteClick: (Long?) -> Unit,
    onEditLabels: () -> Unit,
    windowSizeClass: WindowSizeClass,
    isAppLockEnabled: Boolean = false,
    onRequestAppUnlock: (onSuccess: () -> Unit) -> Unit = {},
    onAppLockEnabled: () -> Unit = {}
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
    var pendingBackupImport by remember {
        mutableStateOf<Triple<Uri, String, NoteBackupImporter.ImportResult>?>(null)
    }
    var showAuthScreen by remember { mutableStateOf(false) }
    var isGoogleSignInPending by remember { mutableStateOf(false) }
    var authMode by remember { mutableStateOf(AuthMode.SIGN_IN) }

    fun openAuth(mode: AuthMode = AuthMode.SIGN_IN) {
        authMode = mode
        showAuthScreen = true
        showProfileSheet = false
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
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
        isGoogleSignInPending = false
        googleSignInHelper.parseIdToken(result.data)
            .onSuccess { viewModel.signInWithGoogleIdToken(it) }
            .onFailure { error ->
                val message = googleSignInHelper.diagnose(error)
                if (error is com.google.android.gms.common.api.ApiException &&
                    (error.statusCode == com.google.android.gms.common.api.CommonStatusCodes.CANCELED || error.statusCode == 12501)
                ) {
                    return@rememberLauncherForActivityResult
                }
                scope.launch {
                    snackbarHostState.showSnackbar(message)
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
                        appContext.getString(R.string.export_success)
                    )
                    BackupExportResult.WriteFailed,
                    is BackupExportResult.Error -> snackbarHostState.showSnackbar(
                        appContext.getString(R.string.export_failed)
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
                val preview = viewModel.previewBackupImport(context.contentResolver, it)
                if (preview != null) {
                    val fileName = it.lastPathSegment ?: "backup.json"
                    pendingBackupImport = Triple(it, fileName, preview)
                } else {
                    snackbarHostState.showSnackbar(appContext.getString(R.string.import_failed))
                }
            }
        }
    }

    fun formatImportSuccessMessage(result: NoteBackupImporter.ImportResult): String {
        return when {
            result.notesImported > 0 && result.labelsCreated > 0 ->
                appContext.getString(R.string.import_success_notes_labels, result.notesImported, result.labelsCreated)
            result.notesImported > 0 ->
                appContext.getString(R.string.import_success_notes_only, result.notesImported)
            result.labelsCreated > 0 ->
                appContext.getString(R.string.import_success_labels_only, result.labelsCreated)
            else -> appContext.getString(R.string.import_success_empty)
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

    LaunchedEffect(state.pendingCloudSyncEvent) {
        when (val event = state.pendingCloudSyncEvent) {
            is CloudSyncEvent.Uploaded -> {
                snackbarHostState.showSnackbar(
                    appContext.getString(R.string.cloud_sync_success, event.noteCount)
                )
            }
            is CloudSyncEvent.Downloaded -> {
                snackbarHostState.showSnackbar(
                    appContext.getString(R.string.cloud_download_success, event.noteCount)
                )
            }
            is CloudSyncEvent.Failure -> {
                snackbarHostState.showSnackbar(
                    event.message.ifBlank { appContext.getString(R.string.cloud_sync_failed) }
                )
            }
            CloudSyncEvent.SignedIn -> {
                snackbarHostState.showSnackbar(appContext.getString(R.string.cloud_sign_in_success))
            }
            is CloudSyncEvent.SignedOut -> {
                snackbarHostState.showSnackbar(
                    appContext.getString(
                        if (event.cloudDataDeleted) {
                            R.string.cloud_sign_out_deleted_success
                        } else {
                            R.string.cloud_sign_out_success
                        }
                    )
                )
            }
            CloudSyncEvent.SignInRequired -> {
                snackbarHostState.showSnackbar(appContext.getString(R.string.cloud_sign_in_required))
            }
            null -> Unit
        }
        if (state.pendingCloudSyncEvent != null) {
            viewModel.clearPendingCloudSyncEvent()
        }
    }

    LaunchedEffect(state.cloudAccount.isGoogleAccount, showAuthScreen) {
        if (showAuthScreen && state.cloudAccount.isGoogleAccount) {
            isGoogleSignInPending = false
            if (authMode == AuthMode.SIGN_UP) {
                snackbarHostState.showSnackbar(appContext.getString(R.string.auth_account_created))
            }
            showAuthScreen = false
        }
    }

    val isExpanded = windowSizeClass.widthSizeClass == WindowWidthSizeClass.Expanded
    val showNavRail = windowSizeClass.widthSizeClass != WindowWidthSizeClass.Compact
    val navigator = rememberListDetailPaneScaffoldNavigator<Long?>()

    val drawerContent = @Composable {
        ModalDrawerSheet(
            drawerContainerColor = MaterialTheme.colorScheme.surface,
            drawerTonalElevation = 0.dp,
            modifier = if (isExpanded) Modifier.width(300.dp) else Modifier
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 24.dp)
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
                    scope.launch { if (!isExpanded) drawerState.close() }
                },
                icon = { Icon(Icons.Default.Description, contentDescription = stringResource(R.string.nav_notes)) },
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
                    scope.launch { if (!isExpanded) drawerState.close() }
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
                    scope.launch { if (!isExpanded) drawerState.close() }
                },
                icon = { Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.nav_trash)) },
                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                colors = NavigationDrawerItemDefaults.colors(
                    unselectedContainerColor = Color.Transparent,
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                )
            )

            NavigationDrawerItem(
                label = { Text(stringResource(R.string.nav_edit_labels), fontWeight = FontWeight.Medium) },
                selected = false,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                    onEditLabels()
                    scope.launch { if (!isExpanded) drawerState.close() }
                },
                icon = { @Suppress("DEPRECATION") Icon(Icons.Default.Label, contentDescription = stringResource(R.string.nav_edit_labels)) },
                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                colors = NavigationDrawerItemDefaults.colors(unselectedContainerColor = Color.Transparent)
            )

            Spacer(modifier = Modifier.weight(1f))

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            if (state.cloudAccount.isGoogleAccount) {
                val accountEmail = state.cloudAccount.email
                if (!accountEmail.isNullOrBlank()) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(
                        text = accountEmail,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    TextButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                            showCloudSignOutConfirm = true
                            scope.launch { if (!isExpanded) drawerState.close() }
                        },
                    ) {
                        Text(stringResource(R.string.cloud_sign_out))
                    }
                }
                }
            } else {
                GoogleSignInButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                        openAuth(AuthMode.SIGN_IN)
                        scope.launch { if (!isExpanded) drawerState.close() }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            NavigationDrawerItem(
                label = { Text(stringResource(R.string.nav_settings), fontWeight = FontWeight.Medium) },
                selected = false,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                    showProfileSheet = true
                    scope.launch { if (!isExpanded) drawerState.close() }
                },
                icon = { Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.nav_settings)) },
                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                colors = NavigationDrawerItemDefaults.colors(unselectedContainerColor = Color.Transparent)
            )
            Spacer(modifier = Modifier.height(16.dp).navigationBarsPadding())
        }
    }

    if (isExpanded) {
        PermanentNavigationDrawer(
            drawerContent = { 
                PermanentDrawerSheet(
                    drawerContainerColor = MaterialTheme.colorScheme.surface,
                    drawerTonalElevation = 0.dp,
                    modifier = Modifier.width(300.dp)
                ) {
                    drawerContent()
                }
            }
        ) {
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
                                    if (showNavRail) {
                                        navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, noteId)
                                    } else {
                                        onNoteClick(noteId)
                                    }
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
                            onOpenSignIn = { openAuth(AuthMode.SIGN_IN) },
                        )
                    }
                },
                detailPane = {
                    AnimatedPane {
                        val destination = navigator.currentDestination
                        if (destination != null && destination.contentKey != null) {
                            val noteId = destination.contentKey
                            // Elite Detail Integration
                            // This ensures the editor is visible side-by-side with the list on tablets
                            // We provide a dedicated back handler that navigates back in the scaffold
                            key(noteId) {
                                val editorViewModel: EditorViewModel = hiltViewModel()
                                EditorScreen(
                                    viewModel = editorViewModel,
                                    onBack = {
                                        scope.launch {
                                            navigator.navigateBack()
                                        }
                                    },
                                    onStageUndo = { note, action, message ->
                                        viewModel.stageEditorUndo(note, action, message)
                                    }
                                )
                            }
                        } else {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Description,
                                        contentDescription = null,
                                        modifier = Modifier.size(64.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f)
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(
                                        text = stringResource(R.string.select_note_to_view),
                                        style = MaterialTheme.typography.bodyLarge.copy(
                                            fontWeight = FontWeight.Medium
                                        ),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                        textAlign = TextAlign.Center
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = stringResource(R.string.select_note_to_view_subtitle),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }
                }
            )
        }
    } else {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = drawerContent
        ) {
            MainScaffold(
                state = state,
                viewModel = viewModel,
                onNoteClick = { noteId ->
                    if (showNavRail) {
                        scope.launch {
                            navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, noteId)
                        }
                    } else {
                        onNoteClick(noteId)
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
                onOpenSignIn = { openAuth(AuthMode.SIGN_IN) },
            )
        }
    }

    // Dialogs
    if (showAuthScreen) {
        AuthScreen(
            initialMode = authMode,
            onDismiss = {
                isGoogleSignInPending = false
                showAuthScreen = false
            },
            onGoogleSignIn = {
                isGoogleSignInPending = true
                googleSignInLauncher.launch(googleSignInHelper.getSignInIntent())
            },
            isGoogleSignInPending = isGoogleSignInPending,
        )
    }

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
            onGoogleSignInClick = { openAuth(AuthMode.SIGN_IN) },
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
        val trashCount = state.trashedNoteCount
        AlertDialog(
            onDismissRequest = { showEmptyTrashConfirm = false },
            shape = MaterialTheme.shapes.large,
            title = { Text(stringResource(R.string.empty_trash_title)) },
            text = { Text(stringResource(R.string.empty_trash_message, trashCount)) },
            confirmButton = {
                TextButton(onClick = {
                    showEmptyTrashConfirm = false
                    viewModel.emptyTrash()
                    scope.launch {
                        showUndoSnackbar(appContext.getString(R.string.empty_trash_deleted, trashCount))
                    }
                }) {
                    Text(
                        stringResource(R.string.empty_trash),
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showEmptyTrashConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    pendingBackupImport?.let { (uri, fileName, preview) ->
        BackupImportDialog(
            fileName = fileName,
            notesImported = preview.notesImported,
            labelsCreated = preview.labelsCreated,
            onDismiss = { pendingBackupImport = null },
            onConfirm = {
                val importUri = uri
                pendingBackupImport = null
                scope.launch {
                    val message = when (val result = viewModel.importBackup(context.contentResolver, importUri)) {
                        is BackupImportResult.Success -> formatImportSuccessMessage(
                            NoteBackupImporter.ImportResult(
                                notesImported = result.notesImported,
                                labelsCreated = result.labelsCreated,
                            )
                        )
                        is BackupImportResult.InvalidFormat -> appContext.getString(
                            R.string.import_invalid_format,
                            result.message
                        )
                        BackupImportResult.ReadFailed,
                        is BackupImportResult.Error -> appContext.getString(R.string.import_failed)
                    }
                    snackbarHostState.showSnackbar(message)
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
                        appContext.getString(R.string.note_deleted)
                    } else {
                        appContext.getString(R.string.note_trashed)
                    }
                    scope.launch { showUndoSnackbar(message) }
                }) {
                    Text(
                        stringResource(R.string.action_delete),
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
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
    onOpenSignIn: () -> Unit = {},
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val noteArchivedMessage = stringResource(R.string.note_archived)
    val noteDeletedMessage = stringResource(R.string.note_deleted)
    val noteTrashedMessage = stringResource(R.string.note_trashed)
    val showFab = state.currentFilter == NoteFilter.ACTIVE && state.selectedNotes.isEmpty()
    val selectedNoteModels by remember(state.notes, state.selectedNotes) {
        derivedStateOf { state.notes.filter { it.id in state.selectedNotes } }
    }
    val selectionAllPinned by remember(selectedNoteModels) {
        derivedStateOf { selectedNoteModels.isNotEmpty() && selectedNoteModels.all { it.isPinned } }
    }
    val visibleNoteIds by remember(state.filteredNotes) {
        derivedStateOf { state.filteredNotes.mapNotNull { it.id }.toSet() }
    }
    val allFilteredSelected by remember(visibleNoteIds, state.selectedNotes) {
        derivedStateOf {
            visibleNoteIds.isNotEmpty() && visibleNoteIds.all { it in state.selectedNotes }
        }
    }
    val allowReorder by remember(state) {
        derivedStateOf {
            state.currentFilter == NoteFilter.ACTIVE &&
                state.sortOrder == NoteSortOrder.MANUAL &&
                state.searchQuery.isEmpty() &&
                state.selectedColor == null &&
                state.selectedLabelId == null &&
                state.selectedNotes.isEmpty() &&
                state.viewMode.columns == 1
        }
    }
    val hasActiveFilters by remember(state.searchQuery, state.selectedColor, state.selectedLabelId) {
        derivedStateOf {
            state.searchQuery.isNotEmpty() ||
                state.selectedColor != null ||
                state.selectedLabelId != null
        }
    }

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
                    onShowDeleteConfirm(true)
                },
                onArchiveSelected = {
                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                    viewModel.archiveSelectedNotes()
                    showUndoSnackbar(noteArchivedMessage)
                },
                onRestoreSelected = {
                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                    val count = state.selectedNotes.size
                    viewModel.restoreSelectedNotes()
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            appContext.getString(R.string.notes_restored_count, count)
                        )
                    }
                },
                selectionAllPinned = selectionAllPinned,
                onPinSelected = {
                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                    val pin = !selectionAllPinned
                    viewModel.setSelectedNotesPinned(pin)
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            appContext.getString(if (pin) R.string.notes_pinned else R.string.notes_unpinned)
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
                selectedColor = state.selectedColor,
                onColorSelect = viewModel::selectColorFilter,
                allLabels = state.allLabels,
                selectedLabelId = state.selectedLabelId,
                onLabelSelect = viewModel::selectLabelFilter,
                sortOrder = state.sortOrder,
                onSortOrderChange = viewModel::setSortOrder,
                recentSearches = state.recentSearches,
                onRecentSearchClick = {
                    viewModel.onSearchQueryChange(it)
                    viewModel.addRecentSearch(it)
                },
                onClearRecentSearches = viewModel::clearRecentSearches,
                hasActiveFilters = hasActiveFilters,
                onClearFilters = viewModel::clearFilters,
                listScrolled = listScrolled
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
                        defaultElevation = 6.dp,
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

        Column(modifier = Modifier.fillMaxSize()) {
            OfflineBanner()

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(paddingValues)
            ) {
        if (state.isNotesLoading) {
            NotesLoadingGrid(
                columns = state.viewMode.columns,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = 16.dp,
                    start = 16.dp,
                    end = 16.dp,
                    bottom = gridBottomPadding
                )
            )
        } else if (filteredNotes.isEmpty()) {
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
                    subtitle = stringResource(R.string.empty_archived_subtitle)
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
                showSignInActions = showCreate && !state.cloudAccount.isGoogleAccount,
                onSignInClick = onOpenSignIn,
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
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
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
                            viewModel.toggleNoteSelection(note.id!!)
                        } else {
                            onNoteClick(note.id)
                        }
                    },
                    onNoteLongClick = { note ->
                        viewModel.toggleNoteSelection(note.id!!)
                    },
                    onSwipeToArchive = { note ->
                        haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                        viewModel.archiveNote(note)
                        showUndoSnackbar(noteArchivedMessage)
                    },
                    onSwipeToTrash = { note ->
                        haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                        viewModel.trashNote(note)
                        val message = if (state.currentFilter == NoteFilter.TRASHED) {
                            noteDeletedMessage
                        } else {
                            noteTrashedMessage
                        }
                        showUndoSnackbar(message)
                    },
                    onLabelClick = { labelId ->
                        viewModel.selectLabelFilter(labelId)
                    },
                    onMoveNote = viewModel::previewMoveNote,
                    onReorderComplete = viewModel::commitNoteOrder,
                    columns = state.viewMode.columns,
                    compact = state.viewMode.compact || state.viewMode.columns > 1,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(
                        top = 16.dp,
                        start = 16.dp,
                        end = 16.dp,
                        bottom = gridBottomPadding
                    )
                )
            }
        }
            }
        }
    }
}
