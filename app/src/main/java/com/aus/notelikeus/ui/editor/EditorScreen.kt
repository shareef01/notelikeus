package com.aus.notelikeus.ui.editor

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.aus.notelikeus.MainActivity
import com.aus.notelikeus.ui.editor.components.ChecklistUI
import com.aus.notelikeus.ui.editor.components.EditorBottomBar
import com.aus.notelikeus.ui.editor.components.EditorBottomSheet
import com.aus.notelikeus.ui.editor.components.ImageHeader
import com.aus.notelikeus.ui.editor.components.RichTextToolbar
import com.aus.notelikeus.ui.navigation.LocalAnimatedVisibilityScope
import com.aus.notelikeus.ui.navigation.LocalSharedTransitionScope
import com.aus.notelikeus.ui.theme.getContentColor

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun EditorScreen(
    viewModel: EditorViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val noteColor = Color(state.color)
    val contentColor = noteColor.getContentColor()
    var showBottomSheet by remember { mutableStateOf(false) }
    var showDateTimePicker by remember { mutableStateOf(false) }
    val datePickerState = rememberDatePickerState()

    val sharedTransitionScope = LocalSharedTransitionScope.current
    val animatedVisibilityScope = LocalAnimatedVisibilityScope.current

    val imageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { viewModel.addAttachment(it.toString()) }
    }

    val sharedModifier = if (sharedTransitionScope != null && animatedVisibilityScope != null) {
        with(sharedTransitionScope) {
            Modifier.sharedElement(
                rememberSharedContentState(key = "note-${state.id}"),
                animatedVisibilityScope = animatedVisibilityScope
            )
        }
    } else {
        Modifier
    }

    val showLockOverlay = state.isNoteLoaded && state.isLocked && !state.isAccessGranted

    if (showLockOverlay) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = noteColor
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = contentColor.copy(alpha = 0.6f)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "This note is locked",
                    style = MaterialTheme.typography.titleLarge,
                    color = contentColor
                )
                Spacer(modifier = Modifier.height(24.dp))
                FilledTonalButton(
                    onClick = {
                        (context as MainActivity).showBiometricPrompt(
                            title = "Locked Note",
                            onSuccess = { viewModel.grantAccess() },
                            onError = { onBack() }
                        )
                    }
                ) {
                    Text("Unlock")
                }
                TextButton(onClick = onBack) {
                    Text("Go back")
                }
            }
        }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = { 
                        viewModel.saveNote()
                        onBack() 
                    }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = contentColor
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showDateTimePicker = true }) {
                        Icon(
                            if (state.reminderTimestamp != null) Icons.Default.NotificationsActive else Icons.Outlined.Notifications,
                            contentDescription = "Set reminder",
                            tint = contentColor
                        )
                    }
                    IconButton(onClick = viewModel::togglePin) {
                        Icon(
                            if (state.isPinned) Icons.Default.PushPin else Icons.Outlined.PushPin,
                            contentDescription = "Pin note",
                            tint = contentColor
                        )
                    }
                    IconButton(onClick = viewModel::toggleArchive) {
                        Icon(
                            if (state.isArchived) Icons.Default.Unarchive else Icons.Default.Archive,
                            contentDescription = "Archive note",
                            tint = contentColor
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = noteColor
                ),
                windowInsets = WindowInsets.statusBars
            )
        },
        bottomBar = {
            EditorBottomBar(
                timestamp = state.timestamp,
                onAddAttachment = {
                    imageLauncher.launch("image/*")
                },
                onMoreClick = { showBottomSheet = true },
                modifier = Modifier.background(noteColor)
            )
        },
        containerColor = noteColor,
        modifier = Modifier.fillMaxSize().then(sharedModifier)
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize()) {
            if (showBottomSheet) {
                EditorBottomSheet(
                    selectedColor = state.color,
                    onColorSelect = viewModel::onColorChange,
                    allLabels = state.allLabels,
                    selectedLabels = state.labels,
                    onLabelToggle = viewModel::toggleLabel,
                    onCreateLabel = viewModel::createLabel,
                    isLocked = state.isLocked,
                    onLockToggle = viewModel::toggleLock,
                    onDeleteNote = {
                        viewModel.toggleTrash()
                        onBack()
                    },
                    onDismiss = { showBottomSheet = false }
                )
            }

            if (showDateTimePicker) {
                DatePickerDialog(
                    onDismissRequest = { showDateTimePicker = false },
                    confirmButton = {
                        TextButton(onClick = {
                            val date = datePickerState.selectedDateMillis ?: System.currentTimeMillis()
                            viewModel.setReminder(date)
                            showDateTimePicker = false
                        }) { Text("OK") }
                    }
                ) {
                    DatePicker(state = datePickerState)
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                state.attachments.firstOrNull()?.let {
                    ImageHeader(uri = it.uri)
                }

                BasicTextField(
                    value = state.title,
                    onValueChange = viewModel::onTitleChange,
                    textStyle = MaterialTheme.typography.headlineMedium.copy(
                        color = contentColor
                    ),
                    cursorBrush = SolidColor(contentColor),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { innerTextField ->
                        if (state.title.isEmpty()) {
                            Text(
                                text = "Title",
                                style = MaterialTheme.typography.headlineMedium,
                                color = contentColor.copy(alpha = 0.5f)
                            )
                        }
                        innerTextField()
                    }
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                if (state.checklist.isNotEmpty()) {
                    ChecklistUI(
                        items = state.checklist,
                        onUpdate = viewModel::updateChecklistItem,
                        onAdd = viewModel::addChecklistItem,
                        onRemove = viewModel::removeChecklistItem,
                        contentColor = contentColor
                    )
                } else {
                    BasicTextField(
                        value = state.contentValue,
                        onValueChange = viewModel::onContentValueChange,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = contentColor
                        ),
                        cursorBrush = SolidColor(contentColor),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
                        decorationBox = { innerTextField ->
                            if (state.content.isEmpty()) {
                                Text(
                                    text = "Note",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = contentColor.copy(alpha = 0.5f)
                                )
                            }
                            innerTextField()
                        }
                    )
                }
            }

            // Floating Rich Text Toolbar
            AnimatedVisibility(
                visible = state.contentValue.selection.length > 0,
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = paddingValues.calculateBottomPadding() + 16.dp)
            ) {
                RichTextToolbar(
                    onBoldClick = viewModel::applyBoldToSelection,
                    onItalicClick = viewModel::applyItalicToSelection,
                    onListClick = viewModel::applyBulletListToSelection,
                    onChecklistClick = {
                        if (state.checklist.isEmpty()) viewModel.addChecklistItem()
                    },
                    contentColor = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
