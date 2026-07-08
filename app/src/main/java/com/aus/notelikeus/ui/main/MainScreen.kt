package com.aus.notelikeus.ui.main

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aus.notelikeus.MainActivity
import com.aus.notelikeus.ui.components.NoteStaggeredGrid
import com.aus.notelikeus.ui.main.components.MainTopAppBar
import com.aus.notelikeus.ui.main.components.ProfileSheet
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onNoteClick: (Long?, Boolean) -> Unit
) {
    val state by viewModel.state.collectAsState()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showProfileSheet by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current

    val topBarHeight = 64.dp
    val topBarHeightPx = with(LocalDensity.current) { topBarHeight.roundToPx().toFloat() }
    val topBarOffsetHeightPx = remember { mutableStateOf(0f) }

    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val delta = available.y
                val newOffset = topBarOffsetHeightPx.value + delta
                topBarOffsetHeightPx.value = newOffset.coerceIn(-topBarHeightPx, 0f)
                return Offset.Zero
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            // ... (drawer content remains same)
            ModalDrawerSheet(
                drawerContainerColor = MaterialTheme.colorScheme.surface,
                drawerTonalElevation = 0.dp
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 28.dp, vertical = 32.dp)
                ) {
                    Column {
                        Text(
                            "Notelikeus",
                            style = MaterialTheme.typography.displaySmall.copy(
                                fontWeight = FontWeight.Black,
                                letterSpacing = (-1).sp
                            ),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "Capture everything.",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                NavigationDrawerItem(
                    label = { Text("Notes", fontWeight = FontWeight.Medium) },
                    selected = state.currentFilter == NoteFilter.ACTIVE,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.setFilter(NoteFilter.ACTIVE)
                        scope.launch { drawerState.close() }
                    },
                    icon = { Icon(Icons.Default.Lightbulb, contentDescription = null) },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                    colors = NavigationDrawerItemDefaults.colors(
                        unselectedContainerColor = Color.Transparent,
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                    )
                )
                NavigationDrawerItem(
                    label = { Text("Archive", fontWeight = FontWeight.Medium) },
                    selected = state.currentFilter == NoteFilter.ARCHIVED,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.setFilter(NoteFilter.ARCHIVED)
                        scope.launch { drawerState.close() }
                    },
                    icon = { Icon(Icons.Default.Archive, contentDescription = null) },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                    colors = NavigationDrawerItemDefaults.colors(
                        unselectedContainerColor = Color.Transparent,
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                    )
                )
                NavigationDrawerItem(
                    label = { Text("Trash", fontWeight = FontWeight.Medium) },
                    selected = state.currentFilter == NoteFilter.TRASHED,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.setFilter(NoteFilter.TRASHED)
                        scope.launch { drawerState.close() }
                    },
                    icon = { Icon(Icons.Default.Delete, contentDescription = null) },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                    colors = NavigationDrawerItemDefaults.colors(
                        unselectedContainerColor = Color.Transparent,
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                    )
                )

                Spacer(modifier = Modifier.weight(1f))
                
                HorizontalDivider(modifier = Modifier.padding(horizontal = 28.dp))
                
                NavigationDrawerItem(
                    label = { Text("Settings", fontWeight = FontWeight.Medium) },
                    selected = false,
                    onClick = { 
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        showProfileSheet = true
                        scope.launch { drawerState.close() }
                    },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                    colors = NavigationDrawerItemDefaults.colors(unselectedContainerColor = Color.Transparent)
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(nestedScrollConnection)
        ) {
            Scaffold(
                topBar = {
                    MainTopAppBar(
                        searchQuery = state.searchQuery,
                        onSearchQueryChange = viewModel::onSearchQueryChange,
                        isListView = state.isListView,
                        onToggleLayout = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.toggleLayout()
                        },
                        selectedCount = state.selectedNotes.size,
                        onClearSelection = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.clearSelection()
                        },
                        onDeleteSelected = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.deleteSelectedNotes()
                        },
                        onArchiveSelected = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.archiveSelectedNotes()
                        },
                        onRestoreSelected = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.restoreSelectedNotes()
                        },
                        currentFilter = state.currentFilter,
                        onMenuClick = { 
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            scope.launch { drawerState.open() } 
                        },
                        onProfileClick = { 
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            showProfileSheet = true 
                        },
                        selectedColor = state.selectedColor,
                        onColorSelect = viewModel::selectColorFilter,
                        allLabels = state.allLabels,
                        selectedLabelId = state.selectedLabelId,
                        onLabelSelect = viewModel::selectLabelFilter,
                        modifier = Modifier
                            .offset { IntOffset(x = 0, y = topBarOffsetHeightPx.value.roundToInt()) }
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
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onNoteClick(null, false) 
                            },
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            shape = MaterialTheme.shapes.large
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Add note")
                        }
                    }
                }
            ) { paddingValues ->
                // Adjust content padding because TopAppBar is manually offset
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = paddingValues.calculateBottomPadding())
                ) {
                    val filteredNotes = state.filteredNotes

                    if (filteredNotes.isEmpty()) {
                        // ...
                        val (icon, message) = when {
                            state.searchQuery.isNotEmpty() -> Icons.Default.Search to "No matching notes"
                            state.currentFilter == NoteFilter.ARCHIVED -> Icons.Default.Archive to "No archived notes"
                            state.currentFilter == NoteFilter.TRASHED -> Icons.Default.Delete to "No notes in trash"
                            else -> Icons.Default.Lightbulb to "Notes you add appear here"
                        }
                        EmptyState(icon = icon, message = message)
                    } else {
                        NoteStaggeredGrid(
                            notes = filteredNotes,
                            selectedNotes = state.selectedNotes,
                            searchQuery = state.searchQuery,
                            onNoteClick = { note ->
                                if (state.selectedNotes.isNotEmpty()) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    viewModel.toggleNoteSelection(note.id!!)
                                } else if (note.isLocked) {
                                    (context as MainActivity).showBiometricPrompt(
                                        title = "Locked Note",
                                        onSuccess = { onNoteClick(note.id, true) },
                                        onError = { /* Handle error */ }
                                    )
                                } else {
                                    onNoteClick(note.id, false)
                                }
                            },
                            onNoteLongClick = { note ->
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.toggleNoteSelection(note.id!!)
                            },
                            onSwipeToArchive = { note ->
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.archiveNote(note)
                            },
                            onSwipeToTrash = { note ->
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.trashNote(note)
                            },
                            onMoveNote = viewModel::onMoveNote,
                            columns = if (state.isListView) 1 else 2,
                            contentPadding = PaddingValues(
                                top = 130.dp, // Allowance for top bar + filter row
                                start = 16.dp,
                                end = 16.dp,
                                bottom = 16.dp
                            )
                        )
                    }
                }
            }
        }
    }

    if (showProfileSheet) {
        ProfileSheet(
            onDismiss = { showProfileSheet = false },
            noteCount = state.notes.size,
            isTrueDarkMode = state.isTrueDarkMode,
            onTrueDarkModeChange = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                viewModel.setTrueDarkMode(it)
            }
        )
    }
}

@Composable
fun EmptyState(icon: ImageVector, message: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(120.dp),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}
